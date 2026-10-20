package ee.schimke.composeai.mcp

import com.google.common.truth.Truth.assertThat
import ee.schimke.composeai.daemon.protocol.DaemonLaunchDescriptor
import java.io.File
import kotlinx.serialization.json.Json
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * A Bazel-family workspace is a build MCP accepts, prepared and rebuilt by the compose-preview CLI
 * (`compose-preview bazel prepare`). The CLI is replaced by a fake runner that writes what the
 * normal surface writes, so these cover the routing, not a Bazel build.
 */
class BazelWorkspaceTest {

  @get:Rule val tmp = TemporaryFolder()

  private fun workspace(): File =
    tmp.newFolder("ws").canonicalFile.apply {
      File(this, "MODULE.bazel").writeText("module(name = \"w\")\n")
      File(this, "app/ui").mkdirs()
    }

  private val launcher by lazy { tmp.newFile("compose-preview") }

  /** Records calls; on success writes the descriptor where `bazel prepare` does. */
  private class FakeCli(val exitCode: Int = 0) : GradleTaskRunner {
    val calls = mutableListOf<Pair<File, List<String>>>()

    override fun run(
      projectRoot: File,
      wrapper: File,
      arguments: List<String>,
      onLine: (String) -> Unit,
    ): GradleTaskRunner.Result {
      calls += wrapper to arguments
      if (exitCode != 0) return GradleTaskRunner.Result(exitCode, "ERROR: build failed\n")
      File(projectRoot, ".compose-preview/outputs/abc/build/compose-previews").apply {
        mkdirs()
        File(this, "daemon-launch.json").writeText(DESCRIPTOR)
      }
      return GradleTaskRunner.Result(0, "")
    }
  }

  @Test
  fun `a Bazel workspace is a build, found from inside it and from its parent`() {
    val ws = workspace()
    assertThat(ProjectDiscovery.isBazelWorkspace(ws)).isTrue()
    assertThat(ProjectDiscovery.buildsFor(File(ws, "app/ui"))).containsExactly(ws)
    assertThat(ProjectDiscovery.buildsFor(ws.parentFile)).contains(ws)
  }

  @Test
  fun `a staged bazel prepare --out directory is a Bazel-family build`() {
    val staged = tmp.newFolder("staged")
    File(staged, "build/compose-previews").mkdirs()
    assertThat(ProjectDiscovery.isBazelWorkspace(staged)).isFalse()
    File(staged, ProjectDiscovery.STAGED_PREPARE_ORIGIN).apply {
      parentFile.mkdirs()
      writeText("""{"workspace":"/ws","args":["prepare"]}""")
    }
    assertThat(ProjectDiscovery.isBazelWorkspace(staged)).isTrue()
    assertThat(ProjectDiscovery.isBuild(staged)).isTrue()
    // Refreshed by handing the CLI the staged directory, which replays the recorded prepare.
    assertThat(ProjectBootstrap.bazelPrepare(staged))
      .containsAtLeast("bazel", "prepare", "--project", staged.absolutePath)
      .inOrder()
  }

  @Test
  fun `a Gradle build with a WORKSPACE file stays a Gradle build`() {
    val ws = workspace()
    File(ws, "settings.gradle.kts").writeText("")
    assertThat(ProjectDiscovery.isBazelWorkspace(ws)).isFalse()
  }

  @Test
  fun `bazel output links are never searched`() {
    val ws = workspace()
    // What `bazel-out` would hold if it were walked: a copy of the workspace's own descriptor.
    File(ws, "bazel-out/k8/build/compose-previews").apply {
      mkdirs()
      File(this, "daemon-launch.json").writeText(DESCRIPTOR)
    }
    File(ws, "bazel-ws/nested").mkdirs()
    File(ws, "bazel-ws/nested/settings.gradle.kts").writeText("")
    assertThat(ProjectDiscovery.buildsFor(ws)).containsExactly(ws)
    assertThat(DescriptorProvider.indexDescriptorsByModulePath(ws)).isEmpty()
  }

  @Test
  fun `bootstrap runs compose-preview bazel prepare, not Gradle`() {
    val ws = workspace()
    val cli = FakeCli()
    val outcome = ProjectBootstrap(runner = cli, cli = { launcher }).ensurePrepared(ws)

    assertThat(outcome).isInstanceOf(ProjectBootstrap.Outcome.Ready::class.java)
    val (wrapper, arguments) = cli.calls.single()
    assertThat(wrapper).isEqualTo(launcher)
    assertThat(arguments)
      .containsAtLeast("bazel", "prepare", "--project", ws.absolutePath)
      .inOrder()
  }

  @Test
  fun `bootstrap names the missing CLI and the failing build`() {
    val ws = workspace()
    val missing = ProjectBootstrap(runner = FakeCli(), cli = { null }).ensurePrepared(ws)
    assertThat((missing as ProjectBootstrap.Outcome.NotPrepared).reason)
      .contains("no compose-preview launcher")

    val failed =
      ProjectBootstrap(runner = FakeCli(exitCode = 1), cli = { launcher }).ensurePrepared(ws)
    assertThat((failed as ProjectBootstrap.Outcome.NotPrepared).reason)
      .contains("ERROR: build failed")
  }

  @Test
  fun `the source compiler rebuilds the daemon's label through the CLI`() {
    val ws = workspace()
    val cli = FakeCli()
    val gradle = SourceCompiler { _, _, _ -> error("Gradle must not compile a Bazel workspace") }
    val compiler = BuildSystemSourceCompiler(gradle = gradle, runner = cli, cli = { launcher })

    val outcome = compiler.compile(ws, "//app/ui:lib", listOf(File(ws, "app/ui/Greeting.kt")))

    assertThat(outcome).isInstanceOf(SourceCompileOutcome.Ok::class.java)
    val arguments = cli.calls.single().second
    assertThat(arguments[arguments.indexOf("--target") + 1]).isEqualTo("//app/ui:lib")
  }

  @Test
  fun `the source compiler leaves Gradle builds to Gradle`() {
    val gradleBuild =
      tmp.newFolder("gradle").apply { File(this, "settings.gradle.kts").writeText("") }
    var compiled = false
    val gradle = SourceCompiler { _, _, _ ->
      compiled = true
      SourceCompileOutcome.Ok(0)
    }
    val compiler =
      BuildSystemSourceCompiler(gradle = gradle, runner = FakeCli(), cli = { launcher })
    compiler.compile(gradleBuild, ":app", emptyList())
    assertThat(compiled).isTrue()
  }

  private fun buckProject(): File =
    tmp.newFolder("buck").canonicalFile.apply {
      File(this, ".git").mkdirs()
      File(this, ".buckconfig").writeText("[cells]\n  root = .\n")
      File(this, "app/src").mkdirs()
      // A cell inside the project carries its own .buckconfig; it is not a root.
      File(this, "prelude").mkdirs()
      File(this, "prelude/.buckconfig").writeText("")
    }

  @Test
  fun `a Buck2 project is a build at its outermost buckconfig, not at a cell`() {
    val project = buckProject()
    assertThat(ProjectDiscovery.isBuckProject(project)).isTrue()
    assertThat(ProjectDiscovery.isBuckProject(File(project, "prelude"))).isFalse()
    assertThat(ProjectDiscovery.isBazelWorkspace(project)).isTrue()
    assertThat(ProjectDiscovery.buildsFor(File(project, "app/src"))).containsExactly(project)
    File(project, "buck-out/v2/nested").mkdirs()
    File(project, "buck-out/v2/nested/settings.gradle.kts").writeText("")
    assertThat(ProjectDiscovery.buildsBelow(project)).isEmpty()
  }

  @Test
  fun `a Buck2 project is prepared and rebuilt with --build-system buck`() {
    val project = buckProject()
    val cli = FakeCli()
    assertThat(ProjectBootstrap(runner = cli, cli = { launcher }).ensurePrepared(project))
      .isInstanceOf(ProjectBootstrap.Outcome.Ready::class.java)
    assertThat(cli.calls.single().second).containsAtLeast("--build-system", "buck").inOrder()

    val gradle = SourceCompiler { _, _, _ -> error("Gradle must not compile a Buck2 project") }
    BuildSystemSourceCompiler(gradle = gradle, runner = cli, cli = { launcher })
      .compile(project, "root//app:app", emptyList())
    val rebuild = cli.calls.last().second
    assertThat(rebuild[rebuild.indexOf("--target") + 1]).isEqualTo("root//app:app")
    assertThat(rebuild).containsAtLeast("--build-system", "buck").inOrder()
  }

  @Test
  fun `cell-qualified Buck2 labels are labels, Gradle paths are not`() {
    assertThat(PreviewUri.isBazelLabel("root//app:app")).isTrue()
    assertThat(PreviewUri.isBazelLabel("//app:app")).isTrue()
    assertThat(PreviewUri.isBazelLabel("@vendor//app:lib")).isTrue()
    assertThat(PreviewUri.isBazelLabel(":app:ui")).isFalse()
    assertThat(PreviewUri.isBazelLabel("app/ui//x")).isFalse()
    assertThat(PreviewUri.decodeModule(PreviewUri.encodeModule("root//app:app")))
      .isEqualTo("root//app:app")
  }

  private companion object {
    val DESCRIPTOR = Json {
      encodeDefaults = true
    }
      .encodeToString(
        DaemonLaunchDescriptor.serializer(),
        DaemonLaunchDescriptor(
          schemaVersion = 2,
          modulePath = "//app/ui:lib",
          variant = "",
          enabled = true,
          mainClass = "ee.schimke.composeai.daemon.DaemonMain",
          classpath = emptyList(),
          jvmArgs = emptyList(),
          systemProperties = emptyMap(),
          workingDirectory = ".",
          manifestPath = "previews.json",
        ),
      )
  }
}
