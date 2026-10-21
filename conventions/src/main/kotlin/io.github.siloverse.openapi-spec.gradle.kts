// Convention for the module that boots the silo's web application in tests (the `silo`
// module). It wires the two system properties that `OpenApiSpec.verify(mockMvc)` from
// io.github.siloverse:openapi-springdoc reads, so the silo's spec test is one line:
//
//   @Test fun `the committed contract matches the application`() = OpenApiSpec.verify(mockMvc)
//
// - siloverse.openapi.spec   = <repo root>/openapi-spec.yaml, the committed contract
// - siloverse.openapi.update = true when the build runs with -PupdateOpenApiSpec
//
// Without the flag a drifted contract fails the test and names the fix; with it the file
// is rewritten from the running application — review the diff, commit it. A missing file
// is written the first time. The spec is a test input, so editing it reruns the test.
val contract = rootProject.layout.projectDirectory.file("openapi-spec.yaml")
val update = providers.gradleProperty("updateOpenApiSpec").map { true }.orElse(false)

tasks.withType<Test>().configureEach {
    systemProperty("siloverse.openapi.spec", contract.asFile.absolutePath)
    systemProperty("siloverse.openapi.update", update.get().toString())
    inputs.files(contract).withPropertyName("openApiContract").optional()
    outputs.upToDateWhen { !update.get() }
}
