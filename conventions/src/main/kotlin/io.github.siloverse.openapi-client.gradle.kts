import io.github.siloverse.build.openapi.OpenApiClientExtension
import io.github.siloverse.build.openapi.PrepareOpenApiClientTask

// Convention for a silo's `kotlin-client` module: a Kotlin client generated from the silo's
// committed `openapi-spec.yaml`, with the generated sources COMMITTED next to the module's
// build file and published with the silo's own version by the silo's release task.
//
//   plugins { id("io.github.siloverse.openapi-client") }
//   openApiClient { service.set("user-silo") }      // both settings default from the root project name
//
// Generation is deliberate, never part of compile: `./gradlew :kotlin-client:generateClient`
// after the contract changed, then review and commit. The client gives a consumer one
// RestClient bean `<service>-client` (base URL from `clients.<service>.url`, built from the
// consumer's instrumented RestClient.Builder, customizable through a `Consumer<RestClient.Builder>`
// bean named `<service>-customizer`, e.g. for OAuth2) and one bean per generated `XControllerApi`.
plugins {
    id("io.github.siloverse.jvm-library")
    id("org.openapi.generator")
}

val openApiClient = extensions.create<OpenApiClientExtension>("openApiClient")
openApiClient.service.convention(rootProject.name)
openApiClient.basePackage.convention(
    "io.github.siloverse.${rootProject.name.removeSuffix("-silo").replace('-', '.')}.client",
)

val prepare = tasks.register<PrepareOpenApiClientTask>("prepareOpenApiClient") {
    service.set(openApiClient.service)
    basePackage.set(openApiClient.basePackage)
    templateDirectory.set(layout.buildDirectory.dir("openapi/templates"))
    configFile.set(layout.buildDirectory.file("openapi/config.yaml"))
}

openApiGenerate {
    generatorName.set("kotlin")
    library.set("jvm-spring-restclient")
    inputSpec.set(rootProject.layout.projectDirectory.file("openapi-spec.yaml").asFile.absolutePath)
    outputDir.set(layout.projectDirectory.asFile.absolutePath)
    templateDir.set(layout.buildDirectory.dir("openapi/templates"))
    configFile.set(layout.buildDirectory.file("openapi/config.yaml"))
    packageName.set(openApiClient.basePackage)
    apiPackage.set(openApiClient.basePackage.map { "$it.api" })
    modelPackage.set(openApiClient.basePackage.map { "$it.model" })
    additionalProperties.set(
        mapOf(
            "useSpringBoot4" to "true",
            "useJackson3" to "true",
            "serializationLibrary" to "jackson",
            "enumPropertyNaming" to "UPPERCASE",
            "enumUnknownDefaultCase" to "true",
            "omitGradlePluginVersions" to "true",
            "omitGradleWrapper" to "true",
            "useSettingsGradle" to "false",
            "sourceFolder" to "src/main/kotlin",
        ),
    )
    globalProperties.set(mapOf("modelTests" to "false", "modelDocs" to "false", "apiTests" to "false", "apiDocs" to "false"))
}

tasks.named("openApiGenerate") {
    dependsOn(prepare)
}

// The generator also writes a standalone project around the sources; this module is the project.
val removeGeneratorExtras = tasks.register<Delete>("removeOpenApiGeneratorExtras") {
    mustRunAfter("openApiGenerate")
    delete(
        layout.projectDirectory.file("README.md"),
        layout.projectDirectory.file("build.gradle"),
        layout.projectDirectory.file("settings.gradle"),
        layout.projectDirectory.file(".openapi-generator-ignore"),
        layout.projectDirectory.dir("docs"),
    )
}

tasks.register("generateClient") {
    group = "siloverse"
    description = "Regenerates the Kotlin client from the committed openapi-spec.yaml. Review and commit the result."
    dependsOn("openApiGenerate", removeGeneratorExtras)
}

dependencies {
    // What the generated sources use; versions come from the shared platform (the Boot BOM).
    "api"("org.springframework:spring-web")
    "api"("tools.jackson.module:jackson-module-kotlin")
    "implementation"("org.springframework:spring-context")
    "implementation"("org.springframework.boot:spring-boot-autoconfigure")
    "implementation"("org.jetbrains.kotlin:kotlin-reflect")
}
