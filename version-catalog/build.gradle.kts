plugins {
    `version-catalog`
    `maven-publish`
}

group = providers.gradleProperty("group").get()
version = providers.gradleProperty("version").get()
description = "Shared Gradle version catalog for Siloverse services."

catalog {
    versionCatalog {
        from(files("src/main/resources/gradle/libs.versions.toml"))
    }
}

publishing {
    publications {
        create<MavenPublication>("versionCatalog") {
            from(components["versionCatalog"])
            artifactId = "version-catalog"
            pom {
                name.set("version-catalog")
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
