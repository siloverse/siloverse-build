import io.github.siloverse.build.addSiloversePlatform
import io.github.siloverse.build.applyKotlinSupport
import io.github.siloverse.build.configureJUnitPlatform
import io.github.siloverse.build.configureJavaConventions
import io.github.siloverse.build.configureKotlinJvm
import io.github.siloverse.build.configureMavenPublishing

// Generic JVM library conventions: works for Java-only, Kotlin-only and mixed modules.
plugins {
    `java-library`
    `maven-publish`
}

applyKotlinSupport()

configureJavaConventions()
configureKotlinJvm()
addSiloversePlatform()
configureJUnitPlatform()
configureMavenPublishing()
