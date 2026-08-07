package io.github.siloverse.build

import org.gradle.api.Project
import org.gradle.api.artifacts.Dependency
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.javadoc.Javadoc
import org.gradle.api.tasks.testing.Test
import org.gradle.external.javadoc.StandardJavadocDocletOptions
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.get
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

object SiloverseBuild {
    const val group = "io.github.siloverse.gradle"
    const val javaVersion = 21

    const val kotlinJvmPluginId = "org.jetbrains.kotlin.jvm"
    const val kotlinSpringPluginId = "org.jetbrains.kotlin.plugin.spring"

    /**
     * `true` / `false` force Kotlin support on or off for a project.
     * Anything else (or absent) means auto-detection, see [Project.siloverseKotlinEnabled].
     */
    const val kotlinToggleProperty = "siloverse.kotlin"

    val version: String
        get() = SiloverseBuild::class.java.`package`.implementationVersion
            ?: System.getProperty("siloverse.build.version")
            ?: "0.0.1-SNAPSHOT"

    val platformNotation: String
        get() = "$group:platform:$version"
}

/**
 * Decides whether the Kotlin JVM toolchain should be part of this project.
 *
 * Resolution order:
 * 1. Kotlin is already applied by the consumer build file -> enabled.
 * 2. `siloverse.kotlin` is set to a boolean -> that wins.
 * 3. Auto: enabled only when the project has Kotlin sources. Java is the default, so a
 *    brand new module with no sources yet stays Java-only until a Kotlin source
 *    directory exists.
 */
fun Project.siloverseKotlinEnabled(): Boolean {
    if (pluginManager.hasPlugin(SiloverseBuild.kotlinJvmPluginId)) {
        return true
    }

    val toggle = providers.gradleProperty(SiloverseBuild.kotlinToggleProperty).orNull?.trim()?.lowercase()
    return when (toggle) {
        "true", "on", "yes", "enable", "enabled" -> true
        "false", "off", "no", "disable", "disabled" -> false
        else -> hasKotlinSources()
    }
}

/**
 * Applies the Kotlin JVM plugin when this project uses Kotlin, so that a single
 * convention plugin serves Java-only, Kotlin-only and mixed-source modules.
 */
fun Project.applyKotlinSupport(springAware: Boolean = false) {
    if (!siloverseKotlinEnabled()) {
        return
    }

    pluginManager.apply(SiloverseBuild.kotlinJvmPluginId)
    if (springAware) {
        pluginManager.apply(SiloverseBuild.kotlinSpringPluginId)
    }
}

/**
 * Java toolchain, compiler and packaging defaults. Applies to every module, including
 * Kotlin-only ones, because `java-library` and the Kotlin plugin share these settings.
 */
fun Project.configureJavaConventions() {
    configureJavaToolchain()

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        // Spring, Jackson and JUnit read real parameter names from the bytecode.
        options.compilerArgs.add("-parameters")
    }

    tasks.withType<Javadoc>().configureEach {
        (options as? StandardJavadocDocletOptions)?.addStringOption("Xdoclint:none", "-quiet")
        // A javadoc jar is required for publishing, but Kotlin-heavy modules often expose
        // nothing documentable from Java ("no public or protected classes found"), which is
        // an error, not a warning. Never fail a build over the doc jar.
        isFailOnError = false
    }
}

fun Project.configureJavaToolchain() {
    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(SiloverseBuild.javaVersion))
        }
        withSourcesJar()
        withJavadocJar()
    }
}

/**
 * No-op for Java-only projects; the configuration is attached as soon as (and only if)
 * the Kotlin JVM plugin is present, whether it came from [applyKotlinSupport] or the
 * consumer's own `plugins {}` block.
 */
fun Project.configureKotlinJvm() {
    pluginManager.withPlugin(SiloverseBuild.kotlinJvmPluginId) {
        extensions.configure<KotlinJvmProjectExtension> {
            jvmToolchain(SiloverseBuild.javaVersion)
        }

        tasks.withType<KotlinJvmCompile>().configureEach {
            compilerOptions {
                freeCompilerArgs.add("-Xjsr305=strict")
                javaParameters.set(true)
            }
        }
    }
}

fun Project.configureJUnitPlatform() {
    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
    }

    dependencies.addPlatform("testImplementation")
    dependencies.add("testImplementation", "org.junit.jupiter:junit-jupiter")
    dependencies.add("testRuntimeOnly", "org.junit.platform:junit-platform-launcher")
}

fun Project.configureMavenPublishing() {
    pluginManager.withPlugin("maven-publish") {
        extensions.configure<PublishingExtension> {
            pluginManager.withPlugin("java") {
                if (publications.findByName("mavenJava") == null) {
                    publications.register("mavenJava", MavenPublication::class.java) {
                        from(components["java"])
                    }
                }
            }

            publications.withType(MavenPublication::class.java).configureEach {
                pom {
                    name.set(project.name)
                    description.set(
                        project.description ?: "Published artifact for ${project.path}"
                    )
                }
            }

            repositories {
                maven {
                    name = "GitHubPackages"
                    val repositoryPath = providers.gradleProperty("siloverse.publish.repository")
                        .orElse(providers.environmentVariable("GITHUB_REPOSITORY"))
                        .orElse("${githubOwner()}/${rootProject.name}")
                    url = uri("https://maven.pkg.github.com/${repositoryPath.get()}")
                    credentials {
                        username = providers.gradleProperty("gpr.user")
                            .orElse(providers.environmentVariable("GITHUB_ACTOR"))
                            .orNull
                        password = providers.gradleProperty("gpr.key")
                            .orElse(providers.environmentVariable("GITHUB_TOKEN"))
                            .orNull
                    }
                }
            }
        }
    }
}

fun Project.addSiloversePlatform(configurationName: String = "implementation"): Dependency? =
    dependencies.addPlatform(configurationName)

fun Project.configureSpringBootPackaging() {
    tasks.matching { it.name == "jar" }.configureEach {
        enabled = true
    }
}

/**
 * Kotlin extras a Spring Boot service needs on top of the Java starters. Added only when
 * the module actually compiles Kotlin, so Java-only services stay free of Kotlin runtime.
 */
fun Project.configureSpringKotlinSupport() {
    pluginManager.withPlugin(SiloverseBuild.kotlinJvmPluginId) {
        dependencies.add("implementation", "com.fasterxml.jackson.module:jackson-module-kotlin")
        dependencies.add("implementation", "org.jetbrains.kotlin:kotlin-reflect")
    }
}

private fun Project.hasKotlinSources(): Boolean =
    listOf("src/main/kotlin", "src/test/kotlin")
        .any { layout.projectDirectory.dir(it).asFile.isDirectory }

private fun Project.githubOwner(): String =
    providers.gradleProperty("siloverse.github.owner")
        .orElse(providers.environmentVariable("GITHUB_REPOSITORY_OWNER"))
        .orElse("siloverse")
        .get()

private fun org.gradle.api.artifacts.dsl.DependencyHandler.addPlatform(
    configurationName: String
): Dependency? = add(configurationName, platform(SiloverseBuild.platformNotation))
