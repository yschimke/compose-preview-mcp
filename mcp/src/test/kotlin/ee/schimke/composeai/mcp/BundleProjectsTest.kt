package ee.schimke.composeai.mcp

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * A packed preview bundle as a project: its own workspace, filled by `compose-preview bundle
 * prepare` (a fake runner here, writing what that command writes), with nothing to recompile.
 */
class BundleProjectsTest {

  @get:Rule val tmp = TemporaryFolder()

  private val launcher by lazy { tmp.newFile("compose-preview") }
  private val cache by lazy { tmp.newFolder("cache") }

  private fun bundle(): File = tmp.newFile("catalog.png").apply { writeBytes(byteArrayOf(1, 2, 3)) }

  /** Records calls; on success writes the descriptor where `bundle prepare -o` does. */
  private class FakeCli(val exitCode: Int = 0) : GradleTaskRunner {
    val calls = mutableListOf<List<String>>()

    override fun run(
      projectRoot: File,
      wrapper: File,
      arguments: List<String>,
      onLine: (String) -> Unit,
    ): GradleTaskRunner.Result {
      calls += arguments
      if (exitCode != 0) return GradleTaskRunner.Result(exitCode, "bundle prepare: not a bundle\n")
      val out = File(arguments[arguments.indexOf("-o") + 1], "build/compose-previews")
      out.mkdirs()
      File(out, "daemon-launch.json")
        .writeText(
          """{"schemaVersion":2,"modulePath":":catalog","variant":"desktop","enabled":true,""" +
            """"mainClass":"x","classpath":[],"jvmArgs":[],"systemProperties":{},""" +
            """"workingDirectory":"${out.path}","manifestPath":"previews.json"}"""
        )
      return GradleTaskRunner.Result(0, "")
    }
  }

  @Test
  fun `a file or URL is a bundle source and a directory is not`() {
    assertThat(BundleProjects.isBundleSource(bundle().absolutePath)).isTrue()
    assertThat(BundleProjects.isBundleSource("https://example.com/catalog.png")).isTrue()
    assertThat(BundleProjects.isBundleSource(tmp.newFolder("build").absolutePath)).isFalse()
    assertThat(BundleProjects.isBundleSource("catalog.png")).isFalse()
  }

  @Test
  fun `one bundle location has one workspace, which discovery treats as a build`() {
    val source = bundle()
    val workspace = BundleProjects.workspaceFor(source.absolutePath, cache)
    assertThat(BundleProjects.workspaceFor(source.absolutePath, cache)).isEqualTo(workspace)
    assertThat(BundleProjects.workspaceFor("https://example.com/catalog.png", cache))
      .isNotEqualTo(workspace)
    assertThat(workspace.name).startsWith("catalog-")
    assertThat(BundleProjects.sourceOf(workspace)).isEqualTo(source.canonicalPath)
    assertThat(ProjectDiscovery.buildsFor(workspace)).containsExactly(workspace)
  }

  @Test
  fun `bootstrap unpacks the bundle through the CLI`() {
    val source = bundle()
    val workspace = BundleProjects.workspaceFor(source.absolutePath, cache)
    val cli = FakeCli()
    val bootstrap = ProjectBootstrap(runner = cli, cli = { launcher })

    assertThat(bootstrap.ensurePrepared(workspace))
      .isInstanceOf(ProjectBootstrap.Outcome.Ready::class.java)
    assertThat(cli.calls.single())
      .containsExactly("bundle", "prepare", source.canonicalPath, "-o", workspace.absolutePath)
      .inOrder()
    assertThat(DescriptorProvider.indexDescriptorsByModulePath(workspace).keys)
      .containsExactly(":catalog")
    // Packed previews are fixed: nothing to rediscover, nothing to recompile.
    assertThat(bootstrap.rediscover(workspace)).isNull()
    val compiled =
      BuildSystemSourceCompiler(
          gradle = { _, _, _ -> error("a bundle has no Gradle build") },
          runner = cli,
          cli = { launcher },
        )
        .compile(workspace, ":catalog", emptyList())
    assertThat(compiled).isInstanceOf(SourceCompileOutcome.Unavailable::class.java)
    assertThat(cli.calls).hasSize(1)
  }

  @Test
  fun `a bundle the CLI cannot unpack says why`() {
    val workspace = BundleProjects.workspaceFor(bundle().absolutePath, cache)
    val outcome =
      ProjectBootstrap(runner = FakeCli(exitCode = 1), cli = { launcher }).ensurePrepared(workspace)
    assertThat((outcome as ProjectBootstrap.Outcome.NotPrepared).reason)
      .contains("bundle prepare: not a bundle")
  }
}
