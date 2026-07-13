package io.github.siloverse.build

import org.gradle.api.Project
import org.gradle.api.artifacts.Dependency
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.tasks.testing.Test
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.get
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

object SiloverseBuild {
    const val group = "io.github.siloverse.build"
    const val javaVersion = 21

    val version: String
        get() = SiloverseBuild::class.java.`package`.implementationVersion
            ?: System.getProperty("siloverse.build.version")
            ?: "0.0.1-SNAPSHOT"

    val platformNotation: String
        get() = "$group:siloverse-platform:$version"
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

fun Project.configureKotlinJvm() {
    extensions.configure<KotlinJvmProjectExtension> {
        jvmToolchain(SiloverseBuild.javaVersion)
    }

    tasks.withType<KotlinJvmCompile>().configureEach {
        compilerOptions {
            freeCompilerArgs.add("-Xjsr305=strict")
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
                groupId = project.group.toString()
                version = project.version.toString()
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

private fun Project.githubOwner(): String =
    providers.gradleProperty("siloverse.github.owner")
        .orElse(providers.environmentVariable("GITHUB_REPOSITORY_OWNER"))
        .orElse("siloverse")
        .get()

private fun org.gradle.api.artifacts.dsl.DependencyHandler.addPlatform(
    configurationName: String
): Dependency? = add(configurationName, platform(SiloverseBuild.platformNotation))

