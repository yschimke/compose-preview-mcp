package ee.schimke.composeai.mcp

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * A wear-os-samples-shaped checkout: the git root is not a build, each sample one level below is,
 * and a Claude Code worktree under `.claude/worktrees/` holds its own copy of a sample.
 */
class ProjectDiscoveryTest {

  @get:Rule val tmp = TemporaryFolder()

  private fun build(dir: File, settings: String = "settings.gradle.kts"): File = dir.apply {
    mkdirs()
    File(this, settings).writeText("")
  }

  private fun fixture(): File {
    val repo = tmp.newFolder("wear-os-samples")
    File(repo, ".git").mkdirs()
    build(File(repo, "ComposeStarter"))
    build(File(repo, "ComposeAdvanced"), settings = "settings.gradle")
    // Never searched: build outputs, node_modules, hidden dirs, and anything deeper than 2 levels.
    build(File(repo, "build/generated"))
    build(File(repo, "node_modules/pkg"))
    build(File(repo, ".hidden/sample"))
    build(File(repo, "nested/deeper/too-deep"))
    // A worktree-style nested build: a copy of the checkout, samples one level below it.
    build(File(repo, ".claude/worktrees/fix-ui/ComposeStarter"))
    File(repo, "ComposeStarter/app/src/main").mkdirs()
    File(repo, ".claude/worktrees/fix-ui/ComposeStarter/app/src").mkdirs()
    return repo.canonicalFile
  }

  @Test
  fun `a git root that is not a build finds the builds up to two levels below it`() {
    val repo = fixture()
    assertThat(ProjectDiscovery.buildsFor(repo))
      .containsExactly(File(repo, "ComposeAdvanced"), File(repo, "ComposeStarter"))
      .inOrder()
  }

  @Test
  fun `a root or cwd inside a build uses the nearest ancestor with settings gradle`() {
    val repo = fixture()
    assertThat(ProjectDiscovery.buildsFor(File(repo, "ComposeStarter/app/src/main")))
      .containsExactly(File(repo, "ComposeStarter"))
    assertThat(ProjectDiscovery.buildsFor(File(repo, "ComposeStarter")))
      .containsExactly(File(repo, "ComposeStarter"))
  }

  @Test
  fun `a root inside a claude worktree is its own candidate and never climbs to the checkout`() {
    val repo = fixture()
    val worktree = File(repo, ".claude/worktrees/fix-ui")
    assertThat(ProjectDiscovery.buildsFor(worktree))
      .containsExactly(File(worktree, "ComposeStarter"))
    assertThat(ProjectDiscovery.buildsFor(File(worktree, "ComposeStarter/app/src")))
      .containsExactly(File(worktree, "ComposeStarter"))
    // Even when the checkout itself is a build, a worktree without one stays empty.
    build(repo)
    File(worktree, "docs").mkdirs()
    assertThat(ProjectDiscovery.buildsFor(File(worktree, "docs"))).isEmpty()
  }

  @Test
  fun `a directory with no build anywhere near it finds nothing`() {
    val plain = tmp.newFolder("plain", "a", "b")
    assertThat(ProjectDiscovery.buildsFor(plain)).isEmpty()
  }

  private fun staged(dir: File, manifest: String = "build/compose-previews/previews.json"): File =
    dir.apply {
      File(this, manifest).apply { parentFile.mkdirs() }.writeText("""{"previews":[]}""")
      File(this, ".compose-preview").mkdirs()
      File(this, ".compose-preview/bazel.json").writeText("{}")
    }

  @Test
  fun `a staged bazel --out directory outside any workspace is recognised`() {
    val out = staged(tmp.newFolder("staged")).canonicalFile
    val result = ProjectDiscovery.stagedOutput(out)
    assertThat(result?.manifest).isEqualTo(File(out, "build/compose-previews/previews.json"))
    assertThat(result?.workspaceRoot).isNull()
  }

  @Test
  fun `a staged directory inside a bazel workspace names the workspace root`() {
    val workspace = tmp.newFolder("ws").canonicalFile
    File(workspace, "MODULE.bazel").writeText("")
    val out = staged(File(workspace, "out/previews").apply { mkdirs() })
    assertThat(ProjectDiscovery.stagedOutput(out)?.workspaceRoot).isEqualTo(workspace)
    assertThat(ProjectDiscovery.stagedOutput(workspace)).isNull()
  }

  @Test
  fun `an --out shared by several targets stages one manifest per target below it`() {
    val out = tmp.newFolder("multi").canonicalFile
    staged(File(out, "app_feature_ui_lib").apply { mkdirs() })
    assertThat(ProjectDiscovery.stagedOutput(out)?.manifest)
      .isEqualTo(File(out, "app_feature_ui_lib/build/compose-previews/previews.json"))
  }

  @Test
  fun `a manifest directly in the directory marks it too`() {
    val out = staged(tmp.newFolder("flat"), manifest = "previews.json")
    assertThat(ProjectDiscovery.stagedOutput(out)).isNotNull()
  }

  @Test
  fun `builds, gradle modules and plain directories are never staged outputs`() {
    assertThat(ProjectDiscovery.stagedOutput(tmp.newFolder("empty"))).isNull()
    // A Gradle module: build.gradle.kts beside the build/ the plugin writes previews.json into.
    val gradle = build(tmp.newFolder("gradle"))
    val module = staged(File(gradle, "app").apply { mkdirs() })
    File(module, "build.gradle.kts").writeText("")
    assertThat(ProjectDiscovery.stagedOutput(module)).isNull()
    // Its output directory, under a Gradle build.
    assertThat(ProjectDiscovery.stagedOutput(File(module, "build"))).isNull()
    // A Bazel package and a Bazel workspace root that happen to hold a manifest.
    val workspace = tmp.newFolder("bazel-ws")
    File(workspace, "WORKSPACE").writeText("")
    staged(workspace)
    assertThat(ProjectDiscovery.stagedOutput(workspace)).isNull()
    val pkg = staged(File(workspace, "app").apply { mkdirs() })
    File(pkg, "BUILD.bazel").writeText("")
    assertThat(ProjectDiscovery.stagedOutput(pkg)).isNull()
  }

  @Test
  fun `the error names the workspace root, or says what to look for, and the bundle route`() {
    val manifest = File("/w/out/build/compose-previews/previews.json")
    val inside =
      ProjectDiscovery.stagedOutputError(
        "/w/out",
        ProjectDiscovery.StagedOutput(manifest, File("/w")),
      )
    assertThat(inside).contains("staged `compose-preview bazel --out` directory")
    assertThat(inside).contains("register the Bazel workspace root instead: /w.")
    assertThat(inside).contains("compose-preview bazel bundle")
    val outside =
      ProjectDiscovery.stagedOutputError("/tmp/out", ProjectDiscovery.StagedOutput(manifest, null))
    assertThat(outside).contains("the directory with MODULE.bazel or WORKSPACE")
  }
}
