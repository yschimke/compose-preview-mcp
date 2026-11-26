package ee.schimke.composeai.mcp

import com.google.common.truth.Truth.assertThat
import ee.schimke.composeai.daemon.protocol.DaemonLaunchDescriptor
import java.io.File
import kotlin.io.path.createTempDirectory
import org.junit.Test

class ProjectBootstrapRefreshTest {

  private fun gradleRoot(prefix: String): File =
    createTempDirectory(prefix).toFile().apply {
      File(this, "settings.gradle.kts").writeText("rootProject.name = \"sample\"\n")
      File(this, "gradlew").apply {
        writeText("#!/bin/sh\n")
        setExecutable(true)
      }
    }

  private fun descriptor(
    moduleDir: File,
    modulePath: String = ":app",
    buildFiles: List<File> = emptyList(),
  ): DaemonLaunchDescriptor {
    val buildFilesJson = buildFiles.joinToString(",") { "\"${it.absolutePath}\"" }
    return DaemonLaunchDescriptor.parse(
      """
      {
        "schemaVersion": 2,
        "modulePath": "$modulePath",
        "variant": "debug",
        "enabled": true,
        "mainClass": "ee.schimke.composeai.daemon.DaemonMain",
        "classpath": [],
        "jvmArgs": ["-Xmx1024m"],
        "systemProperties": {},
        "workingDirectory": "${moduleDir.absolutePath}",
        "manifestPath": "${File(moduleDir, "build/compose-previews/previews.json").absolutePath}",
        "sourceWatch": {
          "sourceDirs": ["${File(moduleDir, "src/main/kotlin").absolutePath}"],
          "buildFiles": [$buildFilesJson]
        }
      }
      """
        .trimIndent(),
    )
  }

  @Test
  fun `a stale descriptor is rewritten by the module's daemon-start task alone`() {
    val root = createTempDirectory("cp-refresh").toFile()
    File(root, "gradlew").apply {
      writeText("#!/bin/sh\n")
      setExecutable(true)
    }
    val home = createTempDirectory("cp-refresh-home").toFile()
    val ran = mutableListOf<List<String>>()
    val bootstrap =
      ProjectBootstrap(
        initScripts = InitScripts(emptyMap(), home),
        runner = { _, _, arguments, _ ->
          ran += arguments
          GradleTaskRunner.Result(exitCode = 0, output = "")
        },
      )

    bootstrap.refreshDescriptor(root, ":wear:app")

    assertThat(ran.single()).contains(":wear:app:composePreviewDaemonStart")
    assertThat(ran.single()).doesNotContain("composePreviewDiscover")
  }

  @Test
  fun `a build without a wrapper is left alone`() {
    val root = createTempDirectory("cp-refresh-nowrapper").toFile()
    val bootstrap = ProjectBootstrap(runner = { _, _, _, _ -> error("must not run Gradle") })
    assertThat(bootstrap.refreshDescriptor(root, ":app")).isNull()
    assertThat(bootstrap.refreshDescriptorOutcome(root, ":app"))
      .isInstanceOf(DescriptorRefreshOutcome.Unavailable::class.java)
  }

  @Test
  fun `refreshIfStale runs composePreviewDaemonStart when descriptor is older than buildFile even with newer classes`() {
    val root = gradleRoot("cp-refresh-stale-build")
    val app = File(root, "app").apply { mkdirs() }
    val buildFile = File(app, "build.gradle.kts").apply { writeText("plugins {}\n") }
    val descriptorFile =
      File(app, "build/compose-previews/daemon-launch.json").apply {
        parentFile.mkdirs()
        writeText("{}")
      }
    val classFile =
      File(app, "build/tmp/kotlin-classes/debug/MainKt.class").apply {
        parentFile.mkdirs()
        writeText("")
      }
    val now = System.currentTimeMillis()
    descriptorFile.setLastModified(now - 60_000)
    buildFile.setLastModified(now - 30_000)
    classFile.setLastModified(now)

    val home = createTempDirectory("cp-refresh-home").toFile()
    val ran = mutableListOf<List<String>>()
    val bootstrap =
      ProjectBootstrap(
        initScripts = InitScripts(emptyMap(), home),
        runner = { _, _, arguments, _ ->
          ran += arguments
          GradleTaskRunner.Result(exitCode = 0, output = "")
        },
      )

    val outcome =
      bootstrap.refreshIfStale(
        root,
        ":app",
        descriptorFile,
        descriptor(app, ":app", listOf(buildFile)),
      )

    assertThat(outcome).isEqualTo(DescriptorRefreshOutcome.Refreshed)
    assertThat(ran.single()).contains(":app:composePreviewDaemonStart")
  }

  @Test
  fun `refreshIfStale skips Gradle when descriptor is newer than all watched build files`() {
    val root = gradleRoot("cp-refresh-fresh")
    val app = File(root, "app").apply { mkdirs() }
    val buildFile = File(app, "build.gradle.kts").apply { writeText("plugins {}\n") }
    val descriptorFile =
      File(app, "build/compose-previews/daemon-launch.json").apply {
        parentFile.mkdirs()
        writeText("{}")
      }
    val now = System.currentTimeMillis()
    buildFile.setLastModified(now - 60_000)
    descriptorFile.setLastModified(now)

    val bootstrap = ProjectBootstrap(runner = { _, _, _, _ -> error("must not run Gradle") })

    val outcome =
      bootstrap.refreshIfStale(
        root,
        ":app",
        descriptorFile,
        descriptor(app, ":app", listOf(buildFile)),
      )

    assertThat(outcome).isNull()
  }

  @Test
  fun `refreshIfStale detects convention plugin edits in included build-logic and buildSrc`() {
    val root = gradleRoot("cp-refresh-build-logic")
    File(root, "settings.gradle.kts")
      .writeText("pluginManagement { includeBuild(\"build-logic\") }\n")
    val app = File(root, "app").apply { mkdirs() }
    val appBuild = File(app, "build.gradle.kts").apply { writeText("") }
    val convention =
      File(root, "build-logic/src/main/kotlin/PreviewConvention.kt").apply {
        parentFile.mkdirs()
        writeText("class PreviewConvention\n")
      }
    val descriptorFile =
      File(app, "build/compose-previews/daemon-launch.json").apply {
        parentFile.mkdirs()
        writeText("{}")
      }
    val now = System.currentTimeMillis()
    appBuild.setLastModified(now - 120_000)
    descriptorFile.setLastModified(now - 60_000)
    convention.setLastModified(now)

    val home = createTempDirectory("cp-refresh-home").toFile()
    val ran = mutableListOf<List<String>>()
    val bootstrap =
      ProjectBootstrap(
        initScripts = InitScripts(emptyMap(), home),
        runner = { _, _, arguments, _ ->
          ran += arguments
          GradleTaskRunner.Result(exitCode = 0, output = "")
        },
      )

    // Descriptor's own buildFiles list predates convention-plugin watching (does not list
    // build-logic).
    val outcome =
      bootstrap.refreshIfStale(
        root,
        ":app",
        descriptorFile,
        descriptor(app, ":app", listOf(appBuild)),
      )

    assertThat(outcome).isEqualTo(DescriptorRefreshOutcome.Refreshed)
    assertThat(ran.single()).contains(":app:composePreviewDaemonStart")
  }

  @Test
  fun `refreshDescriptorOutcome surfaces Gradle failures cleanly`() {
    val root = gradleRoot("cp-refresh-fail")
    val home = createTempDirectory("cp-refresh-home").toFile()
    val bootstrap =
      ProjectBootstrap(
        initScripts = InitScripts(emptyMap(), home),
        runner = { _, _, _, _ ->
          GradleTaskRunner.Result(
            exitCode = 1,
            output =
              "* What went wrong:\nExecution failed for task ':app:composePreviewDaemonStart'.\n> SDK location not found\n",
          )
        },
      )

    val outcome = bootstrap.refreshDescriptorOutcome(root, ":app")

    assertThat(outcome).isInstanceOf(DescriptorRefreshOutcome.Failed::class.java)
    assertThat((outcome as DescriptorRefreshOutcome.Failed).reason)
      .contains("SDK location not found")
  }
}
