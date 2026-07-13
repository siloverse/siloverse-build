import io.github.siloverse.build.addSiloversePlatform
import io.github.siloverse.build.configureJavaToolchain
import io.github.siloverse.build.configureJUnitPlatform
import io.github.siloverse.build.configureKotlinJvm
import io.github.siloverse.build.configureMavenPublishing
import io.github.siloverse.build.configureSpringBootPackaging

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.spring")
    id("org.springframework.boot")
    id("io.spring.dependency-management")
    application
    `maven-publish`
}

configureJavaToolchain()
configureKotlinJvm()
addSiloversePlatform()
configureJUnitPlatform()
configureSpringBootPackaging()
configureMavenPublishing()

dependencies {
    "testImplementation"("org.springframework.boot:spring-boot-starter-test")
    "testImplementation"("org.testcontainers:junit-jupiter")
}

