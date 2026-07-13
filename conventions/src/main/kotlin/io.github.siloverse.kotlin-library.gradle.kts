import io.github.siloverse.build.addSiloversePlatform
import io.github.siloverse.build.configureJavaToolchain
import io.github.siloverse.build.configureJUnitPlatform
import io.github.siloverse.build.configureKotlinJvm
import io.github.siloverse.build.configureMavenPublishing

plugins {
    id("org.jetbrains.kotlin.jvm")
    `java-library`
    `maven-publish`
}

configureJavaToolchain()
configureKotlinJvm()
addSiloversePlatform()
configureJUnitPlatform()
configureMavenPublishing()

