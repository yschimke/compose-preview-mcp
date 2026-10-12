plugins {
  base
  alias(libs.plugins.kotlin.jvm) apply false
  alias(libs.plugins.kotlin.serialization) apply false
  alias(libs.plugins.ktfmt) apply false
}

tasks.named("check") {
  group = "verification"
  dependsOn(":mcp:check")
}

// Formatting aggregates, keyed on the ktfmt plugin rather than a module list.
val ktfmtCheckAll = tasks.register("ktfmtCheckAll") { group = "verification" }

val ktfmtFormatAll = tasks.register("ktfmtFormat") { group = "formatting" }

subprojects {
  plugins.withId("com.ncorti.ktfmt.gradle") {
    ktfmtCheckAll.configure { dependsOn(tasks.named("ktfmtCheck")) }
    ktfmtFormatAll.configure { dependsOn(tasks.named("ktfmtFormat")) }
  }
}

// One compile-time choice for the remote UI-builder Remote Compose authoring tools. No
// environment, URL or request parameter can turn a released build's feature set on.
val remoteComposeAuthoring =
  providers.gradleProperty("uiBuilderRemoteCompose").orElse("false").map {
    require(it == "true" || it == "false") { "uiBuilderRemoteCompose must be true or false" }
    it.toBooleanStrict()
  }

tasks.register("generateMcpBuildFeatures") {
  val enabled = remoteComposeAuthoring
  val output = layout.buildDirectory.dir("generated/generateMcpBuildFeatures")
  inputs.property("uiBuilderRemoteCompose", enabled)
  outputs.dir(output)
  doLast {
    val directory = output.get().asFile.apply { mkdirs() }
    directory
      .resolve("McpBuildFeatures.kt")
      .writeText(
        "package ee.schimke.composeai.mcp\n\n" +
          "/** Build-time feature selection. Enable with -PuiBuilderRemoteCompose=true. */\n" +
          "object McpBuildFeatures {\n  const val remoteCompose: Boolean = ${enabled.get()}\n}\n"
      )
  }
}
