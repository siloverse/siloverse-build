package io.github.siloverse.build

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class PlatformConventionFunctionalTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `platform convention publishes the javaPlatform component with the project coordinates`() {
        val projectDirectory = Files.createDirectory(temporaryDirectory.resolve("platform-module"))
        Files.writeString(
            projectDirectory.resolve("settings.gradle.kts"),
            "rootProject.name = \"the-test-bom\"\n"
        )
        Files.writeString(
            projectDirectory.resolve("build.gradle.kts"),
            """
                plugins {
                    id("io.github.siloverse.platform")
                }

                group = "com.example"
                version = "1.2.3"

                dependencies {
                    constraints {
                        "api"("org.apache.commons:commons-lang3:3.14.0")
                    }
                }
            """.trimIndent()
        )

        val result = GradleRunner.create()
            .withProjectDir(projectDirectory.toFile())
            .withPluginClasspath()
            .withGradleVersion(GRADLE_VERSION)
            .withArguments(GENERATE_POM_TASK, "--configuration-cache", "--stacktrace")
            .build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":$GENERATE_POM_TASK")?.outcome)
        val pom = Files.readString(
            projectDirectory.resolve("build/publications/mavenJavaPlatform/pom-default.xml")
        )
        assertTrue(pom.contains("<groupId>com.example</groupId>"), pom)
        assertTrue(pom.contains("<artifactId>the-test-bom</artifactId>"), pom)
        assertTrue(pom.contains("<version>1.2.3</version>"), pom)
        assertTrue(pom.contains("<packaging>pom</packaging>"), pom)
        assertTrue(pom.contains("<artifactId>commons-lang3</artifactId>"), pom)
        assertTrue(pom.contains("<dependencyManagement>"), pom)
    }

    private companion object {
        const val GRADLE_VERSION = "9.6.0"
        const val GENERATE_POM_TASK = "generatePomFileForMavenJavaPlatformPublication"
    }
}
