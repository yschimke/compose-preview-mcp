package ee.schimke.composeai.mcp

import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlin.io.path.createTempDirectory
import org.junit.Test

class ProjectBootstrapRefreshTest {

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
  }
}
