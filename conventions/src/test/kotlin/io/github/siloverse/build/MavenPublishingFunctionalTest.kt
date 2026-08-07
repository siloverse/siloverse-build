package io.github.siloverse.build

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class MavenPublishingFunctionalTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `maven publication uses the consumer project's final coordinates`() {
        val projectDirectory = writeConsumerProject(
            directoryName = "default-coordinates",
            pluginId = "io.github.siloverse.jvm-library",
            projectName = "the-test-project-name",
            buildConfiguration = """
                group = "com.example"
                version = "1.2.3"
            """.trimIndent()
        )

        val result = generatePom(projectDirectory)

        assertEquals(TaskOutcome.SUCCESS, result.task(":$GENERATE_POM_TASK")?.outcome)
        assertPomCoordinates(
            projectDirectory,
            groupId = "com.example",
            artifactId = "the-test-project-name",
            version = "1.2.3"
        )
    }

    @Test
    fun `kotlin library alias uses the consumer project's final coordinates`() {
        val projectDirectory = writeConsumerProject(
            directoryName = "kotlin-library-default-coordinates",
            pluginId = "io.github.siloverse.kotlin-library",
            projectName = "the-kotlin-test-project",
            buildConfiguration = """
                group = "com.example.kotlin"
                version = "2.3.4"
            """.trimIndent()
        )

        generatePom(projectDirectory)

        assertPomCoordinates(
            projectDirectory,
            groupId = "com.example.kotlin",
            artifactId = "the-kotlin-test-project",
            version = "2.3.4"
        )
    }

    @Test
    fun `explicit Maven publication coordinates are preserved`() {
        val projectDirectory = writeConsumerProject(
            directoryName = "explicit-coordinates",
            pluginId = "io.github.siloverse.jvm-library",
            projectName = "the-test-project-name",
            buildConfiguration = """
                group = "com.example"
                version = "1.2.3"

                publishing {
                    publications.named<MavenPublication>("mavenJava") {
                        groupId = "org.consumer"
                        artifactId = "consumer-artifact"
                        version = "9.8.7"
                    }
                }
            """.trimIndent(),
            imports = "import org.gradle.api.publish.maven.MavenPublication"
        )

        generatePom(projectDirectory)

        assertPomCoordinates(
            projectDirectory,
            groupId = "org.consumer",
            artifactId = "consumer-artifact",
            version = "9.8.7"
        )
    }

    private fun writeConsumerProject(
        directoryName: String,
        pluginId: String,
        projectName: String,
        buildConfiguration: String,
        imports: String = ""
    ): Path {
        val projectDirectory = Files.createDirectory(temporaryDirectory.resolve(directoryName))
        Files.writeString(
            projectDirectory.resolve("settings.gradle.kts"),
            "rootProject.name = \"$projectName\"\n"
        )
        Files.writeString(
            projectDirectory.resolve("build.gradle.kts"),
            """
                $imports

                plugins {
                    id("$pluginId")
                }

                $buildConfiguration
            """.trimIndent()
        )
        return projectDirectory
    }

    private fun generatePom(projectDirectory: Path) =
        GradleRunner.create()
            .withProjectDir(projectDirectory.toFile())
            .withPluginClasspath()
            .withGradleVersion(GRADLE_VERSION)
            .withArguments(GENERATE_POM_TASK, "--configuration-cache", "--stacktrace")
            .build()

    private fun assertPomCoordinates(
        projectDirectory: Path,
        groupId: String,
        artifactId: String,
        version: String
    ) {
        val pom = Files.readString(
            projectDirectory.resolve("build/publications/mavenJava/pom-default.xml")
        )
        assertTrue(pom.contains("<groupId>$groupId</groupId>"), pom)
        assertTrue(pom.contains("<artifactId>$artifactId</artifactId>"), pom)
        assertTrue(pom.contains("<version>$version</version>"), pom)
    }

    private companion object {
        const val GRADLE_VERSION = "9.6.0"
        const val GENERATE_POM_TASK = "generatePomFileForMavenJavaPublication"
    }
}
