package io.github.siloverse.build.tasks

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import java.io.File

/**
 * The whole release, one command, run by a human from a PR branch:
 *
 *   ./gradlew <library>:release -PreleaseVersion=x.y.z
 *
 * validate (clean tree · not main · rebased on refreshed main · version moves
 * forward) → set version → build → release commit + tag → publish → next
 * -SNAPSHOT commit → push branch and tag atomically. Afterward the PR is
 * merged by a human WITH A MERGE COMMIT (never rebase: the pushed tag points
 * at the release commit, and a rebase-merge would orphan it).
 *
 * Publish runs between the two commits so artifacts only ever exist for a
 * commit that exists, with the tag already at HEAD — the same clean, tagged
 * state [ReleaseGuardTask] enforces. Failures before the push revert everything
 * local; nothing leaves the machine until the final atomic push.
 */
@DisableCachingByDefault(because = "Performs the release side effects (commits, tags, publish, push) — never cacheable.")
abstract class ReleaseTask : DefaultTask() {

    /** The library's name, `project.name` of the aggregator — tag prefix and commit wording. */
    @get:Internal
    abstract val libraryName: Property<String>

    /** Tag prefix for this library, `<name>-v`. */
    @get:Internal
    abstract val tagPrefix: Property<String>

    /** Task path shown in error messages, e.g. `:messaging:release`. */
    @get:Internal
    abstract val releaseTaskPath: Property<String>

    /** The aggregator's version, captured after the build script has set it. */
    @get:Internal
    abstract val currentVersion: Property<String>

    /** The aggregator's project directory — the publish scope. */
    @get:Internal
    abstract val libraryDirectory: DirectoryProperty

    /** The repository root — where git commands run. */
    @get:Internal
    abstract val repositoryRoot: DirectoryProperty

    /** The aggregator's build file — carries the single `version = "x.y.z"` line. */
    @get:Internal
    abstract val identityFile: RegularFileProperty

    /** The requested release version, from `-PreleaseVersion=x.y.z`. */
    @get:Internal
    abstract val requestedVersion: Property<String>

    /** Absolute path of the repository's gradlew wrapper. */
    @get:Internal
    abstract val gradlewPath: Property<String>

    @TaskAction
    fun release() {
        val repoRoot = repositoryRoot.get().asFile
        val libraryName = libraryName.get()
        val tagPrefix = tagPrefix.get()
        val identityFile = identityFile.get().asFile
        val identityFilePath = identityFile.relativeTo(repoRoot).path
        val gradlew = gradlewPath.get()

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
            "Missing release version. Usage: ./gradlew ${releaseTaskPath.get()} -PreleaseVersion=x.y.z"
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

        val current = currentVersion.get().removeSuffix("-SNAPSHOT")
        val currentParts = parts(current)
        val requestedParts = parts(version)
        check(compareValuesBy(requestedParts, currentParts, { it[0] }, { it[1] }, { it[2] }) >= 0) {
            "Refusing to release $version: the build file already says ${currentVersion.get()} — versions never move backwards. " +
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
            gradle(libraryDirectory.get().asFile, "publish")
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
