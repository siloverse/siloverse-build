package io.github.siloverse.build.openapi

import org.gradle.api.provider.Property

/**
 * What a silo's `kotlin-client` module declares. Both have conventions derived from the
 * root project name: `user-silo` → service `user-silo`, base package `io.github.siloverse.user.client`.
 */
abstract class OpenApiClientExtension {
    /** The service name: the RestClient bean is `<service>-client`, its URL is `clients.<service>.url`. */
    abstract val service: Property<String>

    /** Generated code goes to `<basePackage>.api`, `.model`, `.infrastructure` and `.configuration`. */
    abstract val basePackage: Property<String>
}
