import io.github.siloverse.build.addSiloversePlatform
import io.github.siloverse.build.applyKotlinSupport
import io.github.siloverse.build.configureJUnitPlatform
import io.github.siloverse.build.configureJavaConventions
import io.github.siloverse.build.configureKotlinJvm
import io.github.siloverse.build.configureMavenPublishing
import org.gradle.kotlin.dsl.`java-library`
import org.gradle.kotlin.dsl.`maven-publish`

// Generic JVM library conventions: works for Java-only, Kotlin-only and mixed modules.
plugins {
    `java-library`
    `maven-publish`
}

applyKotlinSupport()

configureJavaConventions()
configureKotlinJvm()
addSiloversePlatform("implementation")
addSiloversePlatform("annotationProcessor")
configureJUnitPlatform()
configureMavenPublishing()

