import io.github.siloverse.build.tasks.ReleaseGuardTask
import io.github.siloverse.build.tasks.ReleaseTask
import org.gradle.api.publish.maven.tasks.PublishToMavenRepository

// Convention for the parent of a module family that releases together — the aggregator
// project that owns the version for its published subprojects (or a standalone
// published project). The whole aggregator build file collapses to:
//
//   plugins {
//       alias(local.plugins.siloverse.parent)
//   }
//
//   group = "io.github.siloverse"
//   version = "1.0.0-SNAPSHOT"
//
// Contract with the applied project: its own build.gradle.kts sets `group` and carries
// a top-level `version = "x.y.z-SNAPSHOT"` line. That line is the single source of the
// library version — only the release task rewrites it: a bare x.y.z exists exactly on
// the release commit it tags; every other commit carries the next -SNAPSHOT.
//
// Everything else derives from the project the plugin sits on: library name =
// project.name (tag prefix "<name>-v", commit wording), identity file = the project's
// build file, publish scope = the project directory, packages link = the origin remote.
//
// Applying this plugin resolves the conventions jar onto the plugin classpath of every
// subproject, so children apply the sibling conventions by bare id, WITHOUT a version —
// the parent alias is the single place the siloverse-build version is pinned:
//
//   plugins { id("io.github.siloverse.jvm-library") }   // a published module
//   plugins { id("io.github.siloverse.platform") }      // a BOM module
//
// (A child that instead requests a sibling with a version — a catalog alias carrying
// version.ref, or id(...) version "x.y.z" — fails with "already on the classpath with
// an unknown version": Gradle only knows the version of the plugin id that was actually
// resolved, not of the siblings riding in the same jar.)
//
// The release machinery itself lives in io.github.siloverse.build.tasks and is wired
// here: releaseGuard fences every remote publish, release performs the whole release.

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

val libraryTagPrefix = "${project.name}-v"
// Named to avoid shadowing the tasks' own `releaseTaskPath` property inside their
// configuration lambdas — the bare name there resolves against the task receiver.
val releaseCommandPath = if (project == project.rootProject) ":release" else "${project.path}:release"

// No ancestor-of-main check: the release task publishes from a PR branch BEFORE the
// merge — the accepted trade is that an abandoned release PR leaves published
// artifacts and burns the version number.
val releaseGuard = tasks.register<ReleaseGuardTask>("releaseGuard") {
    val repoRoot = rootDir
    gitStatus.set(providers.exec {
        workingDir(repoRoot)
        commandLine("git", "status", "--porcelain")
    }.standardOutput.asText)
    tagsAtHead.set(providers.exec {
        workingDir(repoRoot)
        commandLine("git", "tag", "--points-at", "HEAD")
    }.standardOutput.asText)

    // Task configuration runs after project evaluation, so this sees the final version.
    currentVersion.set(project.version.toString())
    tagPrefix.set(libraryTagPrefix)
    releaseTaskPath.set(releaseCommandPath)
}

// PublishToMavenRepository covers all remote publishes and deliberately excludes
// publishToMavenLocal (a sibling task class), so snapshots stay frictionless.
allprojects {
    tasks.withType<PublishToMavenRepository>().configureEach {
        dependsOn(releaseGuard)
    }
}

tasks.register<ReleaseTask>("release") {
    description = "Releases ${project.name} from a PR branch: validate, build, commit+tag, publish, bump to next snapshot, push."

    libraryName.set(project.name)
    tagPrefix.set(libraryTagPrefix)
    releaseTaskPath.set(releaseCommandPath)
    currentVersion.set(project.version.toString())
    libraryDirectory.set(layout.projectDirectory)
    repositoryRoot.set(rootDir)
    identityFile.set(buildFile)
    requestedVersion.set(providers.gradleProperty("releaseVersion"))
    gradlewPath.set(File(rootDir, "gradlew").absolutePath)
}
