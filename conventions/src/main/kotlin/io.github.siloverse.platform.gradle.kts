import io.github.siloverse.build.configureMavenPublishing
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.kotlin.dsl.`java-platform`
import org.gradle.kotlin.dsl.`maven-publish`

// Convention for BOM/platform modules. The jvm-library convention does not fit a
// platform (no java component, no toolchain), so platforms get their own: the
// java-platform component published with the shared GitHub Packages defaults.
plugins {
    `java-platform`
    `maven-publish`
}

publishing {
    publications.register<MavenPublication>("mavenJavaPlatform") {
        from(components["javaPlatform"])
    }
}

configureMavenPublishing()
