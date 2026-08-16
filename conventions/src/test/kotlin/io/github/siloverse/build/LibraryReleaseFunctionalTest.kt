package io.github.siloverse.build

import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class LibraryReleaseFunctionalTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    // --- releaseGuard -------------------------------------------------------

    @Test
    fun `guard refuses remote publish of a snapshot version`() {
        val project = writeProject(version = "1.0.0-SNAPSHOT")
        gitInit(project)

        val result = runGradleAndFail(project, ":core:publish")

        assertTrue(result.contains("snapshots go to mavenLocal only"), result)
    }

    @Test
    fun `guard refuses remote publish from a dirty working tree`() {
        val project = writeProject(version = "1.0.0")
        gitInit(project)
        git(project, "tag", "acme-lib-v1.0.0")
        Files.writeString(project.resolve("scratch.txt"), "uncommitted")

        val result = runGradleAndFail(project, ":core:publish")

        assertTrue(result.contains("working tree is dirty"), result)
    }

    @Test
    fun `guard refuses remote publish when HEAD is not tagged`() {
        val project = writeProject(version = "1.0.0")
        gitInit(project)

        val result = runGradleAndFail(project, ":core:publish")

        assertTrue(result.contains("HEAD is not tagged acme-lib-v1.0.0"), result)
    }

    @Test
    fun `guard lets a clean tagged release commit publish, with the aggregator identity spread to modules`() {
        val project = writeProject(version = "1.0.0")
        gitInit(project)
        git(project, "tag", "acme-lib-v1.0.0")

        runGradle(project, ":core:publish")

        val pom = project.resolve("remote-repo/com/example/core/1.0.0/core-1.0.0.pom")
        assertTrue(Files.exists(pom), "expected $pom to exist")
        val pomText = Files.readString(pom)
        assertTrue(pomText.contains("<groupId>com.example</groupId>"), pomText)
        assertTrue(pomText.contains("<version>1.0.0</version>"), pomText)
    }

    @Test
    fun `guard does not block snapshot publishing to mavenLocal`() {
        val project = writeProject(version = "1.0.0-SNAPSHOT")
        gitInit(project)
        Files.writeString(project.resolve("scratch.txt"), "dirty tree must not matter locally")
        val localRepo = temporaryDirectory.resolve("maven-local")

        runGradle(project, ":core:publishToMavenLocal", "-Dmaven.repo.local=$localRepo")
    }

    // --- release ------------------------------------------------------------

    @Test
    fun `release refuses to run without a release version`() {
        val project = writeProject(version = "1.0.0-SNAPSHOT")
        gitInit(project)

        val result = runGradleAndFail(project, "release")

        assertTrue(result.contains("Missing release version"), result)
    }

    @Test
    fun `release refuses a version that is not a bare semver triplet`() {
        val project = writeProject(version = "1.0.0-SNAPSHOT")
        gitInit(project)

        val result = runGradleAndFail(project, "release", "-PreleaseVersion=1.0")

        assertTrue(result.contains("must be bare x.y.z"), result)
    }

    @Test
    fun `release refuses a dirty working tree`() {
        val project = writeProject(version = "1.0.0-SNAPSHOT")
        gitInit(project)
        Files.writeString(project.resolve("scratch.txt"), "uncommitted")

        val result = runGradleAndFail(project, "release", "-PreleaseVersion=1.0.0")

        assertTrue(result.contains("working tree has pending changes"), result)
    }

    @Test
    fun `release refuses to run from main`() {
        val project = writeProject(version = "1.0.0-SNAPSHOT")
        gitInit(project)

        val result = runGradleAndFail(project, "release", "-PreleaseVersion=1.0.0")

        assertTrue(result.contains("Refusing to release from main"), result)
    }

    @Test
    fun `release refuses a version that moves backwards`() {
        val project = writeProject(version = "1.2.0-SNAPSHOT")
        gitInit(project)
        addOrigin(project)
        git(project, "switch", "-c", "release-acme-lib-1.1.9")

        val result = runGradleAndFail(project, "release", "-PreleaseVersion=1.1.9")

        assertTrue(result.contains("versions never move backwards"), result)
    }

    @Test
    fun `release refuses a version whose tag already exists`() {
        val project = writeProject(version = "1.0.0-SNAPSHOT")
        gitInit(project)
        addOrigin(project)
        git(project, "switch", "-c", "release-acme-lib-1.0.0")
        git(project, "tag", "acme-lib-v1.0.0")

        val result = runGradleAndFail(project, "release", "-PreleaseVersion=1.0.0")

        assertTrue(result.contains("tag acme-lib-v1.0.0 already exists"), result)
    }

    // --- helpers ------------------------------------------------------------

    private fun writeProject(version: String): Path {
        val project = Files.createDirectory(temporaryDirectory.resolve("acme-lib"))
        Files.writeString(
            project.resolve("settings.gradle.kts"),
            """
                rootProject.name = "acme-lib"
                include("core")
            """.trimIndent()
        )
        Files.writeString(
            project.resolve("build.gradle.kts"),
            """
                plugins {
                    id("io.github.siloverse.library-release")
                }

                group = "com.example"
                version = "$version"
            """.trimIndent() + "\n"
        )
        val core = Files.createDirectory(project.resolve("core"))
        Files.writeString(
            core.resolve("build.gradle.kts"),
            """
                import org.gradle.api.publish.maven.MavenPublication

                plugins {
                    `java-library`
                    `maven-publish`
                }

                publishing {
                    publications {
                        create<MavenPublication>("mavenJava") {
                            from(components["java"])
                        }
                    }
                    repositories {
                        maven {
                            name = "Remote"
                            url = uri(rootProject.layout.projectDirectory.dir("remote-repo"))
                        }
                    }
                }
            """.trimIndent()
        )
        Files.writeString(
            project.resolve(".gitignore"),
            """
                .gradle/
                build/
                remote-repo/
            """.trimIndent()
        )
        return project
    }

    private fun gitInit(project: Path) {
        git(project, "init", "-b", "main")
        git(project, "config", "user.name", "Test User")
        git(project, "config", "user.email", "test@example.com")
        git(project, "config", "commit.gpgsign", "false")
        git(project, "add", ".")
        git(project, "commit", "-m", "Initial commit")
    }

    private fun addOrigin(project: Path) {
        val origin = temporaryDirectory.resolve("origin.git")
        exec(temporaryDirectory, "git", "init", "--bare", "-b", "main", origin.toString())
        git(project, "remote", "add", "origin", origin.toString())
        git(project, "push", "origin", "main")
    }

    private fun git(project: Path, vararg args: String) = exec(project, "git", *args)

    private fun exec(workingDirectory: Path, vararg command: String) {
        val process = ProcessBuilder(*command)
            .directory(workingDirectory.toFile())
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0) { "${command.joinToString(" ")} failed:\n$output" }
    }

    private fun runner(project: Path, vararg arguments: String): GradleRunner =
        GradleRunner.create()
            .withProjectDir(project.toFile())
            .withPluginClasspath()
            .withGradleVersion(GRADLE_VERSION)
            .withArguments(*arguments, "--configuration-cache", "--stacktrace")

    private fun runGradle(project: Path, vararg arguments: String): String =
        runner(project, *arguments).build().output

    private fun runGradleAndFail(project: Path, vararg arguments: String): String =
        runner(project, *arguments).buildAndFail().output

    private companion object {
        const val GRADLE_VERSION = "9.6.0"
    }
}
