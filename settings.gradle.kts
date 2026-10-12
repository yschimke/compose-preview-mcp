pluginManagement {
  repositories {
    gradlePluginPortal()
    google()
    mavenCentral()
  }
}

dependencyResolutionManagement {
  repositoriesMode.set(RepositoriesMode.PREFER_PROJECT)
  repositories {
    google()
    mavenCentral()

    // The UI-builder editor archive (`compose-preview-ui-builder-web`, ~40 MB Wasm) for
    // `design_open`, shipped as a GitHub release asset rather than on Maven Central; an ivy
    // repository keeps it a normal versioned dependency. `metadataSources { artifact() }` because
    // the asset has no POM/module metadata. The pattern spells the tag's `v` prefix separately from
    // the file name's. Fenced to this one module.
    ivy("https://github.com/yschimke/compose-ui-builder/releases/download") {
      name = "uiBuilderWebRelease"
      patternLayout { artifact("v[revision]/[module]-[revision].[ext]") }
      content { includeModule("ee.schimke.composeai", "compose-preview-ui-builder-web") }
      metadataSources { artifact() }
    }
  }
}

rootProject.name = "compose-preview-mcp"

// The UI builder as an opt-in composite build; unset means the released coordinates. CI's
// remote-Compose lane builds `:mcp` against a checkout this way.
providers.gradleProperty("composeUiBuilderDir").orNull?.let { path ->
  val directory = file(path).canonicalFile
  require(directory.resolve("settings.gradle.kts").isFile) {
    "-PcomposeUiBuilderDir names $directory, which is not a Gradle build. Clone " +
      "yschimke/compose-ui-builder there, or point at your checkout with " +
      "-PcomposeUiBuilderDir=<path>."
  }
  logger.lifecycle("Composite build: uiBuilder -> $directory")
  includeBuild(directory) {
    dependencySubstitution {
      substitute(module("ee.schimke.composeai:compose-preview-ui-builder-web"))
        .using(project(":ui-builder-web"))
    }
  }
}

include(":mcp")
