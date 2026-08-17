package io.github.siloverse.build

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class ParentConventionFunctionalTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `parent brings the release guard, so a snapshot cannot be published remotely`() {
        val project = writeProject(version = "1.0.0-SNAPSHOT")
        gitInit(project)

        val result = runner(project, ":bom:publish").buildAndFail().output

        assertTrue(result.contains("snapshots go to mavenLocal only"), result)
    }

    @Test
    fun `children apply siblings by bare id and inherit the aggregator identity`() {
        val project = writeProject(version = "2.5.0-SNAPSHOT")

        val result = runner(project, ":bom:$GENERATE_POM_TASK").build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":bom:$GENERATE_POM_TASK")?.outcome)
        val pom = Files.readString(
            project.resolve("bom/build/publications/mavenJavaPlatform/pom-default.xml")
        )
        assertTrue(pom.contains("<groupId>com.example</groupId>"), pom)
        assertTrue(pom.contains("<version>2.5.0-SNAPSHOT</version>"), pom)
    }

    // --- helpers ------------------------------------------------------------

    private fun writeProject(version: String): Path {
        val project = Files.createDirectory(temporaryDirectory.resolve("acme-family"))
        Files.writeString(
            project.resolve("settings.gradle.kts"),
            """
                rootProject.name = "acme-family"
                include("bom")
            """.trimIndent()
        )
        // The aggregator: exactly one plugin plus its identity — the shape this
        // convention exists to make possible.
        Files.writeString(
            project.resolve("build.gradle.kts"),
            """
                plugins {
                    id("io.github.siloverse.parent")
                }

                group = "com.example"
                version = "$version"
            """.trimIndent() + "\n"
        )
        val bom = Files.createDirectory(project.resolve("bom"))
        Files.writeString(
            bom.resolve("build.gradle.kts"),
            """
                plugins {
                    id("io.github.siloverse.platform")
                }

                dependencies {
                    constraints {
                        "api"("org.apache.commons:commons-lang3:3.14.0")
                    }
                }
            """.trimIndent()
        )
        Files.writeString(
            project.resolve(".gitignore"),
            """
                .gradle/
                build/
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

    private fun git(project: Path, vararg args: String) {
        val process = ProcessBuilder("git", *args)
            .directory(project.toFile())
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0) { "git ${args.joinToString(" ")} failed:\n$output" }
    }

    private fun runner(project: Path, vararg arguments: String): GradleRunner =
        GradleRunner.create()
            .withProjectDir(project.toFile())
            .withPluginClasspath()
            .withGradleVersion(GRADLE_VERSION)
            .withArguments(*arguments, "--configuration-cache", "--stacktrace")

    private companion object {
        const val GRADLE_VERSION = "9.6.0"
        const val GENERATE_POM_TASK = "generatePomFileForMavenJavaPlatformPublication"
    }
}
