package io.github.siloverse.build

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue

class OpenApiClientFunctionalTest {

    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `generateClient writes a Kotlin client, our configuration and the auto-configuration import, and removes the generator's project files`() {
        val project = writeSilo()

        val result = runner(project, ":kotlin-client:generateClient").build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":kotlin-client:generateClient")?.outcome)
        val client = project.resolve("kotlin-client")
        val api = client.resolve("src/main/kotlin/io/github/siloverse/acme/client/api/ThingsControllerApi.kt")
        assertTrue(Files.exists(api), "generated API missing")
        assertTrue(Files.readString(api).contains("fun things(filterThingName: kotlin.String? = null"), Files.readString(api))

        val configuration = client.resolve("src/main/kotlin/io/github/siloverse/acme/client/configuration/ClientConfiguration.kt")
        assertTrue(Files.exists(configuration), "ClientConfiguration missing")
        val text = Files.readString(configuration)
        assertTrue(text.contains("""@Bean(name = ["acme-silo-client"])"""), text)
        assertTrue(text.contains("clients.acme-silo.url"), text)
        assertTrue(text.contains("fun thingsController(@Qualifier(\"acme-silo-client\") client: RestClient): ThingsControllerApi"), text)

        val imports = client.resolve("src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports")
        assertEquals("io.github.siloverse.acme.client.configuration.ClientConfiguration\n", Files.readString(imports))

        assertFalse(Files.exists(client.resolve("build.gradle")), "generator's build.gradle should be removed")
        assertFalse(Files.exists(client.resolve("README.md")), "generator's README should be removed")
        assertTrue(Files.exists(client.resolve("build.gradle.kts")), "the module's own build file must survive")
    }

    @Test
    fun `the service and package can be overridden`() {
        val project = writeSilo(clientBuild = """
            plugins { id("io.github.siloverse.openapi-client") }
            openApiClient {
                service.set("things")
                basePackage.set("com.example.things")
            }
        """.trimIndent())

        runner(project, ":kotlin-client:generateClient").build()

        val configuration = project.resolve("kotlin-client/src/main/kotlin/com/example/things/configuration/ClientConfiguration.kt")
        assertTrue(Files.readString(configuration).contains("""@Bean(name = ["things-client"])"""))
    }

    // --- helpers ------------------------------------------------------------

    private fun writeSilo(clientBuild: String = "plugins { id(\"io.github.siloverse.openapi-client\") }"): Path {
        val project = Files.createDirectory(temporaryDirectory.resolve("acme-silo"))
        Files.writeString(
            project.resolve("settings.gradle.kts"),
            """
                rootProject.name = "acme-silo"
                include("kotlin-client")
            """.trimIndent(),
        )
        Files.writeString(
            project.resolve("build.gradle.kts"),
            """
                plugins { id("io.github.siloverse.parent") }
                group = "io.github.siloverse.acme-silo"
                version = "1.0.0-SNAPSHOT"
            """.trimIndent() + "\n",
        )
        Files.writeString(project.resolve("openapi-spec.yaml"), SPEC)
        val client = Files.createDirectory(project.resolve("kotlin-client"))
        Files.writeString(client.resolve("build.gradle.kts"), clientBuild + "\n")
        return project
    }

    private fun runner(project: Path, vararg arguments: String): GradleRunner =
        GradleRunner.create()
            .withProjectDir(project.toFile())
            .withPluginClasspath()
            .withGradleVersion(GRADLE_VERSION)
            .withArguments(*arguments, "--stacktrace")

    private companion object {
        const val GRADLE_VERSION = "9.6.0"

        val SPEC = """
            openapi: 3.1.0
            info:
              title: acme-silo API
              version: v1
            paths:
              /things:
                get:
                  tags:
                  - things-controller
                  operationId: things
                  parameters:
                  - name: filter.thing.name
                    in: query
                    required: false
                    schema:
                      type: string
                  responses:
                    "200":
                      description: OK
                      content:
                        application/json:
                          schema:
                            type: array
                            items:
                              ${'$'}ref: "#/components/schemas/ThingDto"
            components:
              schemas:
                ThingDto:
                  type: object
                  properties:
                    id:
                      type: string
                      format: uuid
                    name:
                      type: string
        """.trimIndent() + "\n"
    }
}
