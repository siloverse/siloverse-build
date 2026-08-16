# siloverse-build

Shared Gradle build tooling for JVM microservice repositories. The convention plugins are
language neutral: one plugin serves Java-only, Kotlin-only and mixed Java + Kotlin modules.

Published coordinates:

- `io.github.siloverse.gradle:conventions:<version>`
- `io.github.siloverse.gradle:version-catalog:<version>`
- `io.github.siloverse.gradle:platform:<version>`
- Gradle plugin markers for:
  - `io.github.siloverse.jvm-library`
  - `io.github.siloverse.jvm-application`
  - `io.github.siloverse.spring-boot-application`
  - `io.github.siloverse.platform`
  - `io.github.siloverse.library-release`
  - `io.github.siloverse.kotlin-library` (deprecated alias)
  - `io.github.siloverse.kotlin-application` (deprecated alias)

The examples use GitHub owner `siloverse`. If the repository is owned by a different user or org, pass `-Psiloverse.github.owner=<owner>` when publishing and replace `siloverse` in consumer repository URLs.

## Conventions

`io.github.siloverse.jvm-library` applies:

- `java-library`
- Kotlin JVM, when the module uses Kotlin (see [Language Support](#language-support))
- `maven-publish`
- Java toolchain 21
- `-parameters` and UTF-8 for Java, `-java-parameters` and `-Xjsr305=strict` for Kotlin
- sources and javadoc jars
- JUnit Platform
- the shared `platform`
- GitHub Packages publishing defaults

`io.github.siloverse.jvm-application` applies `io.github.siloverse.jvm-library` plus the Gradle `application` plugin.

`io.github.siloverse.spring-boot-application` applies:

- `java`
- Kotlin JVM and Kotlin Spring (all-open), when the module uses Kotlin
- Spring Boot
- Spring dependency-management
- `application`
- `maven-publish`
- Java toolchain 21
- JUnit Platform
- Spring Boot test and Testcontainers JUnit support
- `jackson-module-kotlin` and `kotlin-reflect`, only when the module uses Kotlin
- the shared `platform`

`io.github.siloverse.platform` is for BOM/platform modules, which the `jvm-library`
convention does not fit (a platform has no java component or toolchain). It applies:

- `java-platform`
- `maven-publish`, publishing the `javaPlatform` component as a `mavenJavaPlatform` publication
- GitHub Packages publishing defaults

`io.github.siloverse.library-release` adds release machinery to a library aggregator
project — the project that owns the version for a family of published modules (or a
standalone published project). The applied project's own `build.gradle.kts` must set
`group` and carry a top-level `version = "x.y.z-SNAPSHOT"` line: that line is the single
source of the library version, and only the release task rewrites it. The plugin also
spreads the aggregator's `group` and `version` to its subprojects (Gradle inherits
neither). It registers two tasks:

- `releaseGuard` — every `PublishToMavenRepository` task in the project and its
  subprojects depends on it. It refuses remote publication unless the version is not a
  snapshot, the working tree is clean, and HEAD carries the tag `<name>-v<version>`.
  `publishToMavenLocal` is deliberately not guarded, so snapshot iteration stays
  frictionless.
- `release` — the whole release in one command, run by a human from a PR branch:

  ```bash
  ./gradlew <library>:release -PreleaseVersion=x.y.z
  ```

  It validates (clean tree, not on main or a detached HEAD, branch rebased on a freshly
  fetched main, version moves forward, tag free), rewrites the version line, runs a full
  `build`, commits and tags the release, publishes, commits the next patch `-SNAPSHOT`,
  and pushes branch and tag atomically. Failures before the push revert everything
  local. Afterward, merge the PR **with a merge commit** — a rebase-merge would orphan
  the tag.

  **Container-repo classpath gotcha.** Applying `library-release` at an aggregator puts
  the conventions jar on every child project's plugin classpath. A child that then
  requests a sibling plugin from the same jar **with a version** — `id("io.github.siloverse.jvm-library") version "x.y.z"`
  or a version catalog alias — fails with "already on the classpath with an unknown
  version". Pin the siblings in the aggregator's `plugins` block instead:

  ```kotlin
  plugins {
      id("io.github.siloverse.library-release")
      alias(libs.plugins.siloverse.jvm.library) apply false
  }
  ```

  Children then apply the sibling by bare id (or alias), without a version.

`io.github.siloverse.kotlin-library` and `io.github.siloverse.kotlin-application` still work.
They are thin aliases that force Kotlin on and then delegate to the `jvm-*` plugins. New
modules should use the `jvm-*` ids regardless of language.

## Language Support

A module applying these plugins can contain Java only, Kotlin only, or both at once —
`src/main/java` and `src/main/kotlin` are compiled together, so Java can call Kotlin and
Kotlin can call Java inside the same module.

**Java is the default.** The Kotlin toolchain is applied only when the module needs it, so a
pure Java service does not carry the Kotlin compiler or runtime. Resolution order per module:

1. The build file already applies `org.jetbrains.kotlin.jvm` -> Kotlin support is on.
2. `siloverse.kotlin=true` / `siloverse.kotlin=false` -> that wins. Set it in the consumer's
   `gradle.properties`, or pass `-Psiloverse.kotlin=true` on the command line.
3. Otherwise auto-detect: Kotlin is on when the module has a `src/main/kotlin` or
   `src/test/kotlin` directory, and off otherwise. A new module with no sources yet is
   treated as Java-only.

Auto-detection is a configuration cache input; creating `src/main/kotlin` in a previously
Java-only module invalidates the cache and turns Kotlin support on for the next build — no
build file change needed to start mixing Kotlin into a Java module.

Java-only module:

```kotlin
plugins {
    id("io.github.siloverse.jvm-library") version "<version>"
}
```

Mixed Java + Kotlin Spring Boot service:

```kotlin
plugins {
    id("io.github.siloverse.spring-boot-application") version "<version>"
}

dependencies {
    implementation(libs.bundles.spring.web)
}
```

```
src/main/java/com/example/OrderController.java   // Java @RestController
src/main/kotlin/com/example/PricingService.kt    // Kotlin @Service, injected into the above
```

Pin a module explicitly when auto-detection is not what you want:

```properties
# gradle.properties — always apply Kotlin in this repository, even before any .kt exists
siloverse.kotlin=true
```

## Local Verification

```bash
./gradlew build
./gradlew publishToMavenLocal
```

Verify plugin markers after publishing locally:

```bash
find ~/.m2/repository -path '*io/github/siloverse/jvm-library*' -print
find ~/.m2/repository -path '*io/github/siloverse/spring-boot-application*' -print
```

## Publish To GitHub Packages

`gradle.properties` contains the single shared project version:

```properties
version=0.0.1
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
            from("io.github.siloverse.gradle:version-catalog:<version>")
        }
    }
}
```

For local testing before GitHub Packages publish, add `mavenLocal()` before the GitHub Packages repository in both repository blocks.

## Consumer Build Files

Library (Java, Kotlin or both):

```kotlin
plugins {
    id("io.github.siloverse.jvm-library") version "<version>"
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
    implementation(libs.bundles.spring.web)
    implementation(libs.spring.boot.starter.data.jpa)
    testImplementation(libs.testcontainers.postgresql)
}
```

The `spring-web` bundle is language neutral. `jackson-module-kotlin` and `kotlin-reflect`
are added automatically for modules that compile Kotlin, so nothing extra is needed there.
Spring Data JPA is opt-in through the `libs.spring.boot.starter.data.jpa` catalog alias.

### Published Artifact Coordinates

The convention plugins create a `mavenJava` publication whose coordinates follow the
consumer project:

- `groupId` comes from `project.group`
- `artifactId` comes from `project.name`
- `version` comes from `project.version`

For example, a project named `orders-api` with `group = "com.example"` and
`version = "1.2.3"` publishes as `com.example:orders-api:1.2.3`.

To use different Maven coordinates without changing the project coordinates, configure the
publication explicitly. The convention plugin preserves these overrides:

```kotlin
import org.gradle.api.publish.maven.MavenPublication

publishing {
    publications.named<MavenPublication>("mavenJava") {
        groupId = "com.example.public"
        artifactId = "orders-client"
        version = "2.0.0"
    }
}
```

## Platform/BOM

The convention plugins add the platform automatically. A project can also consume it explicitly:

```kotlin
dependencies {
    implementation(platform("io.github.siloverse.gradle:platform:<version>"))
    testImplementation(platform("io.github.siloverse.gradle:platform:<version>"))
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
