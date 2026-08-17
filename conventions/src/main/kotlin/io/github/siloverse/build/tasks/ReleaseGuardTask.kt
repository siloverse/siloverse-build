package io.github.siloverse.build.tasks

import org.gradle.api.DefaultTask
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

/**
 * Refuses remote publication unless HEAD is a clean, tagged, non-snapshot release commit.
 *
 * Every task of type `PublishToMavenRepository` in the aggregator and its subprojects is
 * made to depend on this guard by the `parent` convention. `publishToMavenLocal`
 * (a sibling task class) is deliberately not guarded, so snapshots stay frictionless.
 */
@DisableCachingByDefault(because = "A pure git-state check with no outputs — it must run on every publish.")
abstract class ReleaseGuardTask : DefaultTask() {

    /** The aggregator's version, captured after the build script has set it. */
    @get:Internal
    abstract val currentVersion: Property<String>

    /** Tag prefix for this library, `<name>-v`. */
    @get:Internal
    abstract val tagPrefix: Property<String>

    /** Task path shown in error messages, e.g. `:messaging:release`. */
    @get:Internal
    abstract val releaseTaskPath: Property<String>

    /** Output of `git status --porcelain` at the repository root. */
    @get:Internal
    abstract val gitStatus: Property<String>

    /** Output of `git tag --points-at HEAD` at the repository root. */
    @get:Internal
    abstract val tagsAtHead: Property<String>

    init {
        description = "Refuses remote publication unless HEAD is a clean, tagged release commit."
    }

    @TaskAction
    fun enforce() {
        val version = currentVersion.get()
        check(!version.endsWith("-SNAPSHOT")) {
            "Refusing remote publish: version is $version — snapshots go to mavenLocal only. " +
                    "Fix: release through the release task — ./gradlew ${releaseTaskPath.get()} -PreleaseVersion=x.y.z"
        }
        check(gitStatus.get().isBlank()) {
            "Refusing remote publish: working tree is dirty. " +
                    "Fix: commit or stash your changes — published artifacts must be reproducible from a commit."
        }
        check(tagsAtHead.get().lines().contains("${tagPrefix.get()}$version")) {
            "Refusing remote publish: HEAD is not tagged ${tagPrefix.get()}$version. " +
                    "Fix: don't publish by hand — the release task tags the release commit before it publishes."
        }
    }
}
