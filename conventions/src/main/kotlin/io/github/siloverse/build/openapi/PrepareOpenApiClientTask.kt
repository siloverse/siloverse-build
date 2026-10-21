package io.github.siloverse.build.openapi

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

/**
 * Lays out what openapi-generator needs from us: our supporting-file templates, extracted
 * from this jar to a directory (the generator reads templates from disk), and the config
 * file that names them with their destinations and carries our template variables.
 */
@DisableCachingByDefault(because = "Cheap: writes two small files from classpath resources.")
abstract class PrepareOpenApiClientTask : DefaultTask() {

    @get:Input
    abstract val service: Property<String>

    @get:Input
    abstract val basePackage: Property<String>

    @get:OutputDirectory
    abstract val templateDirectory: DirectoryProperty

    @get:OutputFile
    abstract val configFile: RegularFileProperty

    @TaskAction
    fun prepare() {
        val templates = templateDirectory.get().asFile
        templates.mkdirs()
        TEMPLATES.forEach { name ->
            val resource = javaClass.classLoader.getResourceAsStream("openapi-templates/$name")
                ?: throw IllegalStateException("template openapi-templates/$name is missing from the conventions jar")
            val target = templates.resolve(name)
            target.parentFile.mkdirs()
            resource.use { target.outputStream().use { out -> it.copyTo(out) } }
        }

        val pkg = basePackage.get()
        val configurationPackage = "$pkg.configuration"
        val configurationPath = "src/main/kotlin/${configurationPackage.replace('.', '/')}"
        configFile.get().asFile.writeText(
            """
            additionalProperties:
              configurationPackage: $configurationPackage
              serviceName: ${service.get()}
            files:
              configuration/ClientConfiguration.mustache:
                templateType: SupportingFiles
                destinationFilename: $configurationPath/ClientConfiguration.kt
              autoconfig.mustache:
                templateType: SupportingFiles
                destinationFilename: src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
            """.trimIndent() + "\n",
        )
    }

    companion object {
        val TEMPLATES = listOf("configuration/ClientConfiguration.mustache", "autoconfig.mustache")
    }
}
