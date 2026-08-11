import io.github.siloverse.build.addSiloversePlatform
import io.github.siloverse.build.applyKotlinSupport
import io.github.siloverse.build.configureJUnitPlatform
import io.github.siloverse.build.configureJavaConventions
import io.github.siloverse.build.configureKotlinJvm
import io.github.siloverse.build.configureMavenPublishing
import io.github.siloverse.build.configureSpringBootPackaging
import io.github.siloverse.build.configureSpringKotlinSupport

// Generic Spring Boot service conventions: works for Java-only, Kotlin-only and mixed modules.
plugins {
    java
    id("org.springframework.boot")
    id("io.spring.dependency-management")
    application
    `maven-publish`
}

// Also brings in the Kotlin Spring (all-open) plugin so Kotlin @Component classes stay open.
applyKotlinSupport(springAware = true)

configureJavaConventions()
configureKotlinJvm()
addSiloversePlatform("implementation")
configureJUnitPlatform()
configureSpringBootPackaging()
configureSpringKotlinSupport()
configureMavenPublishing()

dependencies {
    "testImplementation"("org.springframework.boot:spring-boot-starter-test")
    "testImplementation"("org.testcontainers:testcontainers-junit-jupiter")
}
