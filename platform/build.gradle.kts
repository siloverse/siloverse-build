plugins {
    `java-platform`
    `maven-publish`
}

group = providers.gradleProperty("group").get()
version = providers.gradleProperty("version").get()
description = "Dependency platform for Siloverse Kotlin and Spring services."

javaPlatform {
    allowDependencies()
}

dependencies {
    api(platform(libs.spring.boot.dependencies))
    api(platform(libs.testcontainers.bom))
    api(platform(libs.junit.bom))
    api(platform(libs.spring.modulith.bom))

    constraints {
        api(libs.kotlin.stdlib)
        api(libs.kotlin.reflect)
        api(libs.jackson.module.kotlin)
        api(libs.kotlinx.coroutines.core)
        api(libs.kotlinx.coroutines.reactor)
        api(libs.kotlin.logging)
        api(libs.logstash.logback.encoder)
    }
}

publishing {
    publications {
        create<MavenPublication>("mavenJavaPlatform") {
            from(components["javaPlatform"])
            artifactId = "platform"
            pom {
                name.set("platform")
                description.set(project.description)
                url.set("https://github.com/siloverse/siloverse-build")
            }
        }
    }

    repositories {
        maven {
            name = "GitHubPackages"
            val owner = providers.gradleProperty("siloverse.github.owner")
                .orElse(providers.environmentVariable("GITHUB_REPOSITORY_OWNER"))
                .orElse("siloverse")
            url = uri("https://maven.pkg.github.com/${owner.get()}/siloverse-build")
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
