plugins {
    `kotlin-dsl`
    `java-gradle-plugin`
    `maven-publish`
}

group = providers.gradleProperty("group").get()
version = providers.gradleProperty("version").get()
description = "Shared Gradle convention plugins for Siloverse Kotlin and Spring services."

base {
    archivesName.set("siloverse-gradle-conventions")
}

dependencies {
    implementation(libs.kotlin.gradle.plugin)
    implementation(libs.kotlin.allopen)
    implementation(libs.spring.boot.gradle.plugin)
    implementation(libs.spring.dependency.management.gradle.plugin)

    testImplementation(gradleTestKit())
    testImplementation(libs.junit.jupiter)
}

gradlePlugin {
    website.set("https://github.com/siloverse/siloverse-build")
    vcsUrl.set("https://github.com/siloverse/siloverse-build")
}

tasks.jar {
    manifest {
        attributes(
            "Implementation-Title" to "siloverse-gradle-conventions",
            "Implementation-Version" to project.version.toString()
        )
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

publishing {
    publications.withType<MavenPublication>().configureEach {
        if (name == "pluginMaven") {
            artifactId = "siloverse-gradle-conventions"
        }
        pom {
            name.set("siloverse-gradle-conventions")
            description.set(project.description)
            url.set("https://github.com/siloverse/siloverse-build")
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

