import org.gradle.api.publish.maven.tasks.PublishToMavenRepository

// Release machinery for a library aggregator project (a project whose subprojects are
// the published modules, or a standalone published project). Two tasks:
//
//   releaseGuard — every task of type PublishToMavenRepository in this project and its
//   subprojects depends on it; it refuses remote publication unless HEAD is a clean,
//   tagged, non-snapshot release commit.
//
//   release — the whole release, one command, run by a human from a PR branch:
//   ./gradlew <library>:release -PreleaseVersion=x.y.z
//
// Contract with the applied project: its own build.gradle.kts sets `group` and carries
// a top-level `version = "x.y.z-SNAPSHOT"` line. That line is the single source of the
// library version — only the release task rewrites it: a bare x.y.z exists exactly on
// the release commit it tags; every other commit carries the next -SNAPSHOT.
//
// Everything else derives from the project the plugin sits on: library name =
// project.name (tag prefix "<name>-v", commit wording), identity file = the project's
// build file, publish scope = the project directory, packages link = the origin remote.

val aggregator = project

// Gradle does NOT inherit these: an unset subproject version is "unspecified" and the
// default group leaks the container path (e.g. java-library.messaging). Spread them
// after the aggregator's build script has run — the plugin applies before the script
// body sets group/version. Subprojects configure later still, so they may override.
afterEvaluate {
    subprojects {
        group = aggregator.group
        version = aggregator.version
    }
}

// No ancestor-of-main check: the release task publishes from a PR branch BEFORE the
// merge — the accepted trade is that an abandoned release PR leaves published
// artifacts and burns the version number.
val releaseGuard = tasks.register("releaseGuard") {
    description = "Refuses remote publication unless HEAD is a clean, tagged release commit."

    val repoRoot = rootDir
    val gitStatus = providers.exec {
        workingDir(repoRoot)
        commandLine("git", "status", "--porcelain")
    }.standardOutput.asText

    val tagsAtHead = providers.exec {
        workingDir(repoRoot)
        commandLine("git", "tag", "--points-at", "HEAD")
    }.standardOutput.asText

    // Task configuration runs after project evaluation, so this sees the final version.
    val currentVersion = project.version.toString()
    val tagPrefix = "${project.name}-v"
    val releaseTaskPath = if (project == project.rootProject) ":release" else "${project.path}:release"

    doLast {
        check(!currentVersion.endsWith("-SNAPSHOT")) {
            "Refusing remote publish: version is $currentVersion — snapshots go to mavenLocal only. " +
                    "Fix: release through the release task — ./gradlew $releaseTaskPath -PreleaseVersion=x.y.z"
        }
        check(gitStatus.get().isBlank()) {
            "Refusing remote publish: working tree is dirty. " +
                    "Fix: commit or stash your changes — published artifacts must be reproducible from a commit."
        }
        check(tagsAtHead.get().lines().contains("$tagPrefix$currentVersion")) {
            "Refusing remote publish: HEAD is not tagged $tagPrefix$currentVersion. " +
                    "Fix: don't publish by hand — the release task tags the release commit before it publishes."
        }
    }
}

// PublishToMavenRepository covers all remote publishes and deliberately excludes
// publishToMavenLocal (a sibling task class), so snapshots stay frictionless.
allprojects {
    tasks.withType<PublishToMavenRepository>().configureEach {
        dependsOn(releaseGuard)
    }
}

// The whole release, one command, run by a human from a PR branch:
//
//   ./gradlew <library>:release -PreleaseVersion=x.y.z
//
// validate (clean tree · not main · rebased on refreshed main · version moves
// forward) → set version → build → release commit + tag → publish → next
// -SNAPSHOT commit → push branch and tag atomically. Afterward the PR is
// merged by a human WITH A MERGE COMMIT (never rebase: the pushed tag points
// at the release commit, and a rebase-merge would orphan it).
//
// Publish runs between the two commits so artifacts only ever exist for a
// commit that exists, with the tag already at HEAD — the same clean, tagged
// state releaseGuard enforces. Failures before the push revert everything
// local; nothing leaves the machine until the final atomic push.
tasks.register("release") {
    val libraryName = project.name
    description = "Releases $libraryName from a PR branch: validate, build, commit+tag, publish, bump to next snapshot, push."

    val tagPrefix = "$libraryName-v"
    val releaseTaskPath = if (project == project.rootProject) ":release" else "${project.path}:release"
    val currentVersion = project.version.toString()
    val libraryDir = projectDir
    val repoRoot = rootDir
    val identityFile = buildFile
    val identityFilePath = identityFile.relativeTo(repoRoot).path
    val requestedVersion = providers.gradleProperty("releaseVersion")
    val gradlew = File(repoRoot, "gradlew").absolutePath

    doLast {
        fun gitExitCode(vararg args: String): Int {
            val process = ProcessBuilder("git", *args).directory(repoRoot).redirectErrorStream(true).start()
            process.inputStream.readAllBytes()
            return process.waitFor()
        }

        fun git(vararg args: String): String {
            val process = ProcessBuilder("git", *args).directory(repoRoot).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText().trim()
            check(process.waitFor() == 0) { "git ${args.joinToString(" ")} failed:\n$output" }
            return output
        }

        fun gradle(workingDir: File, vararg taskNames: String) {
            val process = ProcessBuilder(gradlew, *taskNames).directory(workingDir).redirectErrorStream(true).start()
            process.inputStream.bufferedReader().forEachLine { println(it) }
            check(process.waitFor() == 0) { "${taskNames.joinToString(" ")} failed — see output above." }
        }

        fun parts(version: String): List<Int> = version.split(".").map { it.toInt() }

        val version = requestedVersion.orNull ?: error(
            "Missing release version. Usage: ./gradlew $releaseTaskPath -PreleaseVersion=x.y.z"
        )
        check(Regex("""\d+\.\d+\.\d+""").matches(version)) {
            "Release version must be bare x.y.z (got \"$version\") — the task adds -SNAPSHOT to the next version itself."
        }

        val pendingChanges = git("status", "--porcelain")
        check(pendingChanges.isBlank()) {
            "Refusing to release: working tree has pending changes:\n$pendingChanges\n" +
                    "Fix: commit or stash them — the release must be reproducible from a commit."
        }

        val branch = git("rev-parse", "--abbrev-ref", "HEAD")
        check(branch != "main") {
            "Refusing to release from main: releases ride a PR branch so the version commits go through review. " +
                    "Fix: git switch -c release-$libraryName-$version"
        }
        check(branch != "HEAD") {
            "Refusing to release from a detached HEAD: the release commits must belong to a pushable branch. " +
                    "Fix: git switch -c release-$libraryName-$version"
        }

        // Refresh the local main ref without leaving this branch, tags included
        // (the duplicate-tag check below must see releases made elsewhere).
        git("fetch", "origin", "main:main", "--tags")

        check(gitExitCode("merge-base", "--is-ancestor", "main", "HEAD") == 0) {
            "Refusing to release: this branch is not rebased on the updated main — the release would ship stale code. " +
                    "Fix: git rebase main"
        }

        val current = currentVersion.removeSuffix("-SNAPSHOT")
        val currentParts = parts(current)
        val requestedParts = parts(version)
        check(compareValuesBy(requestedParts, currentParts, { it[0] }, { it[1] }, { it[2] }) >= 0) {
            "Refusing to release $version: the build file already says $currentVersion — versions never move backwards. " +
                    "Fix: pick $current or higher."
        }

        val tag = "$tagPrefix$version"
        check(gitExitCode("rev-parse", "-q", "--verify", "refs/tags/$tag") != 0) {
            "Refusing to release $version: tag $tag already exists — released versions are immutable. " +
                    "Fix: release the next patch instead."
        }

        // The pattern source lives in this plugin, not in the file it rewrites, so the
        // self-rewrite bug that mangled the 1.0.3 in-repo machinery is structurally
        // gone — but keep the line-start anchor and replaceFirst anyway.
        val versionLinePattern = Regex("(?m)^version = \"[^\"]+\"")
        val originalContent = identityFile.readText()
        check(versionLinePattern.containsMatchIn(originalContent)) {
            "Cannot find the version line in $identityFilePath — it must contain a top-level version = \"x.y.z\" line."
        }
        fun writeVersion(newVersion: String) {
            identityFile.writeText(
                identityFile.readText().replaceFirst(versionLinePattern, "version = \"$newVersion\"")
            )
        }

        writeVersion(version)
        try {
            gradle(repoRoot, "build")
        } catch (failure: Exception) {
            identityFile.writeText(originalContent)
            throw GradleException("Build failed — version change reverted, nothing committed, nothing published.", failure)
        }

        val originUrl = git("remote", "get-url", "origin")
        val repoSlug = originUrl.removeSuffix(".git").substringAfter("github.com").trimStart(':', '/')

        git("add", identityFilePath)
        git("commit", "-m", "Release $libraryName $version")
        git("tag", "-a", tag, "-m",
            "$libraryName $version\n\nArtifacts: https://github.com/$repoSlug/packages")

        try {
            gradle(libraryDir, "publish")
        } catch (failure: Exception) {
            git("tag", "-d", tag)
            git("reset", "--hard", "HEAD~1")
            throw GradleException(
                "Publish failed — release commit and tag reverted locally, nothing was pushed. " +
                        "WARNING: if any artifact reached GitHub Packages before the failure, " +
                        "$version is burned (released versions are immutable) — release the next patch instead.",
                failure
            )
        }

        val nextVersion = requestedParts.let { (major, minor, patch) -> "$major.$minor.${patch + 1}" }
        writeVersion("$nextVersion-SNAPSHOT")
        git("add", identityFilePath)
        git("commit", "-m", "Begin $libraryName $nextVersion development")

        // Atomic: branch and tag land together or not at all.
        git("push", "--atomic", "origin", branch, "refs/tags/$tag")

        println()
        println("Released $libraryName $version — artifacts published, tag $tag pushed with branch $branch.")
        println("Next: open the PR and merge it WITH A MERGE COMMIT (a rebase-merge would orphan the tag).")
    }
}
