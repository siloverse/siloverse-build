# siloverse-build

Shared Gradle build tooling for Kotlin and Spring microservice repositories.

Published coordinates:

- `io.github.siloverse.build:siloverse-gradle-conventions:<version>`
- `io.github.siloverse.build:siloverse-version-catalog:<version>`
- `io.github.siloverse.build:siloverse-platform:<version>`
- Gradle plugin marker artifacts for:
  - `io.github.siloverse.kotlin-library`
  - `io.github.siloverse.kotlin-application`
  - `io.github.siloverse.spring-boot-application`

The examples use GitHub owner `siloverse`. If the repository is owned by a different user or org, pass `-Psiloverse.github.owner=<owner>` when publishing and replace `siloverse` in consumer repository URLs.

## Conventions

`io.github.siloverse.kotlin-library` applies:

- Kotlin JVM
- `java-library`
- `maven-publish`
- Java toolchain 21
- sources and javadoc jars
- JUnit Platform
- the shared `siloverse-platform`
- GitHub Packages publishing defaults

`io.github.siloverse.kotlin-application` applies `io.github.siloverse.kotlin-library` plus the Gradle `application` plugin.

`io.github.siloverse.spring-boot-application` applies:

- Kotlin JVM
- Kotlin Spring
- Spring Boot
- Spring dependency-management
- `application`
- `maven-publish`
- Java toolchain 21
- JUnit Platform
- Spring Boot test and Testcontainers JUnit support
- the shared `siloverse-platform`

## Local Verification

```bash
./gradlew build
./gradlew publishToMavenLocal
```

Verify plugin markers after publishing locally:

```bash
find ~/.m2/repository -path '*io.github.siloverse.kotlin-library.gradle.plugin*' -print
find ~/.m2/repository -path '*io.github.siloverse.spring-boot-application.gradle.plugin*' -print
```

## Publish To GitHub Packages

`gradle.properties` contains the single shared project version:

```properties
version=0.0.1-SNAPSHOT
```

Publish manually:

```bash
GITHUB_ACTOR=<github-user> GITHUB_TOKEN=<token> ./gradlew publish -Psiloverse.github.owner=siloverse
```

The token needs permission to write GitHub Packages for `siloverse/siloverse-build`. The included GitHub Actions workflow publishes on tag push using `secrets.GITHUB_TOKEN`.

## Consumer Settings

`settings.gradle.kts`:

```kotlin
pluginManagement {
    repositories {
        maven {
            url = uri("https://maven.pkg.github.com/siloverse/siloverse-build")
            credentials {
                username = providers.gradleProperty("gpr.user").orElse(System.getenv("GITHUB_ACTOR")).orNull
                password = providers.gradleProperty("gpr.key").orElse(System.getenv("GITHUB_TOKEN")).orNull
            }
        }
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven {
            url = uri("https://maven.pkg.github.com/siloverse/siloverse-build")
            credentials {
                username = providers.gradleProperty("gpr.user").orElse(System.getenv("GITHUB_ACTOR")).orNull
                password = providers.gradleProperty("gpr.key").orElse(System.getenv("GITHUB_TOKEN")).orNull
            }
        }
        mavenCentral()
    }

    versionCatalogs {
        create("libs") {
            from("io.github.siloverse.build:siloverse-version-catalog:<version>")
        }
    }
}
```

For local testing before GitHub Packages publish, add `mavenLocal()` before the GitHub Packages repository in both repository blocks.

## Consumer Build Files

Kotlin library:

```kotlin
plugins {
    id("io.github.siloverse.kotlin-library") version "<version>"
}

group = "io.github.siloverse"
version = "0.1.0"
```

Spring Boot application:

```kotlin
plugins {
    id("io.github.siloverse.spring-boot-application") version "<version>"
}

group = "io.github.siloverse"
version = "0.1.0"

dependencies {
    implementation(libs.spring.boot.starter.web)
    implementation(libs.jackson.module.kotlin)
    testImplementation(libs.testcontainers.postgresql)
}
```

## Platform/BOM

The convention plugins add the platform automatically. A project can also consume it explicitly:

```kotlin
dependencies {
    implementation(platform("io.github.siloverse.build:siloverse-platform:<version>"))
    testImplementation(platform("io.github.siloverse.build:siloverse-platform:<version>"))
}
```

Use `platform`, not `enforcedPlatform`, for normal services. That keeps the platform as a shared recommendation while still allowing reviewed exceptions.

## Temporary Version Overrides

Prefer a narrow constraint with a reason and a removal target:

```kotlin
dependencies {
    constraints {
        implementation("com.fasterxml.jackson.module:jackson-module-kotlin") {
            version {
                strictly("2.22.1")
            }
            because("Temporary service-specific exception; remove after platform catches up.")
        }
    }
}
```

Avoid changing the shared catalog or platform for one service unless the exception should become standard across all Siloverse services.

