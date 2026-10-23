package ee.schimke.composeai.mcp

import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlin.io.path.createTempDirectory
import org.junit.Test

class SourceTreeTest {
  @Test
  fun `a test source set created after the first walk is not watched`() {
    val module = createTempDirectory("cp-source-tree").toFile()
    File(module, "build.gradle.kts").writeText("")
    val src = File(module, "src")
    File(src, "main/kotlin").mkdirs()
    File(src, "main/kotlin/A.kt").writeText("fun a() {}")
    val tree = SourceTree(module, budgetMs = 10_000)
    assertThat(tree.refresh().initial).isTrue()

    // #1186: src/test and a new main-side source set appear later.
    File(src, "test/kotlin").mkdirs()
    File(src, "test/kotlin/ATest.kt").writeText("class ATest")
    File(src, "debug/kotlin").mkdirs()
    val debug = File(src, "debug/kotlin/D.kt").apply { writeText("fun d() {}") }
    // Make sure the src/ mtime moves even on a coarse-grained filesystem clock.
    src.setLastModified(src.lastModified() - 10_000)

    assertThat(tree.refresh().changed.map { it.canonicalFile }).containsExactly(debug.canonicalFile)
    File(src, "test/kotlin/ATest.kt").writeText("class ATest { val edited = 1 }")
    assertThat(tree.refresh().changed).isEmpty()
  }

  /** Writes [text] and moves the mtime, so a change shows on a coarse filesystem clock. */
  private fun File.edit(text: String): File = apply {
    parentFile.mkdirs()
    val before = if (exists()) lastModified() else 0L
    writeText(text)
    setLastModified(maxOf(before, System.currentTimeMillis()) + 5_000)
  }

  @Test
  fun `a module's published dirs replace the src convention and reach modules it misses`() {
    val root = createTempDirectory("cp-source-tree-published").toFile()
    // A Gradle module with a custom srcDir outside src/, and a stray src/ file it doesn't compile.
    val gradle = File(root, "app").apply { mkdirs() }
    File(gradle, "build.gradle.kts").writeText("")
    val custom = File(gradle, "extra/kotlin/Custom.kt").edit("fun custom() {}")
    val unused = File(gradle, "src/unused/kotlin/Unused.kt").edit("fun unused() {}")
    // A Bazel target: no build.gradle, so the convention never finds it.
    val bazel = File(root, "ui/BUILD.bazel").edit("")
    val target = File(root, "ui/Screen.kt").edit("fun screen() {}")
    val strings = File(root, "ui/res/values/strings.xml").edit("<resources/>")
    val tree =
      SourceTree(root, budgetMs = 10_000) {
        listOf(
          SourceTree.DeclaredModule(gradle, listOf(File(gradle, "extra/kotlin")), emptyList()),
          SourceTree.DeclaredModule(
            bazel.parentFile,
            listOf(bazel.parentFile),
            listOf(File(root, "ui/res")),
          ),
        )
      }
    tree.refresh()

    custom.edit("fun custom() = 1")
    target.edit("fun screen() = 1")
    strings.edit("<resources><string name=\"a\">A</string></resources>")
    unused.edit("fun unused() = 1")
    assertThat(tree.refresh().changed.map { it.canonicalFile })
      .containsExactly(custom.canonicalFile, target.canonicalFile, strings.canonicalFile)
  }

  @Test
  fun `a focused refresh checks only the focus and an unfocused one finds the rest`() {
    val module = createTempDirectory("cp-source-tree-focus").toFile()
    File(module, "build.gradle.kts").writeText("")
    val focused = File(module, "src/main/kotlin/ui/Screen.kt").edit("fun screen() {}")
    val other = File(module, "src/main/kotlin/data/Repo.kt").edit("fun repo() {}")
    val tree = SourceTree(module, budgetMs = 10_000)
    tree.refresh()
    val focus = SourceTree.Focus(listOf("src/main/kotlin/ui"), module)

    focused.edit("fun screen() = 1")
    other.edit("fun repo() = 1")
    val scoped = tree.refresh(focus)
    assertThat(scoped.changed.map { it.canonicalFile }).containsExactly(focused.canonicalFile)
    val full = tree.refresh()
    assertThat(full.changed.map { it.canonicalFile }).containsExactly(other.canonicalFile)
    assertThat(scoped.statted).isLessThan(full.statted)
    // A glob focus works the same way.
    other.edit("fun repo() = 2")
    assertThat(tree.refresh(SourceTree.Focus(listOf("**/ui/*.kt"), module)).changed).isEmpty()
    assertThat(tree.refresh().changed.map { it.canonicalFile }).containsExactly(other.canonicalFile)
  }

  /** #67: a launch descriptor written after the first walk is read by a later unfocused one. */
  @Test
  fun `a module published after the first walk is watched by a later refresh`() {
    val root = createTempDirectory("cp-source-tree-late").toFile()
    val target = File(root, "ui/Screen.kt").edit("fun screen() {}")
    val published = mutableListOf<SourceTree.DeclaredModule>()
    val tree = SourceTree(root, budgetMs = 10_000, moduleRescanMs = 0) { published.toList() }
    tree.refresh()
    assertThat(tree.modulesOf(target)).isEmpty()

    // A generated directory the build only creates later is picked up the same way.
    val generated = File(root, "out/gen")
    published +=
      SourceTree.DeclaredModule(root, listOf(target.parentFile, generated), emptyList(), "//ui:ui")
    tree.refresh()
    assertThat(tree.modulesOf(target)).containsExactly("//ui:ui")
    target.edit("fun screen() = 1")
    assertThat(tree.refresh().changed.map { it.canonicalFile })
      .containsExactly(target.canonicalFile)

    val made = File(generated, "Gen.kt").edit("fun gen() {}")
    tree.refresh()
    assertThat(tree.kotlinSourcesOf("//ui:ui")!!.map { it.canonicalFile })
      .containsExactly(target.canonicalFile, made.canonicalFile)
  }

  /** #67: every Bazel target of a workspace has the workspace root as its module directory. */
  @Test
  fun `targets that share a module directory are told apart by module path`() {
    val root = createTempDirectory("cp-source-tree-bazel").toFile()
    val a = File(root, "feature-a/A.kt").edit("fun a() {}")
    val b = File(root, "feature-b/B.kt").edit("fun b() {}")
    val tree =
      SourceTree(root, budgetMs = 10_000) {
        listOf(
          SourceTree.DeclaredModule(root, listOf(a.parentFile), emptyList(), "//feature-a:ui"),
          SourceTree.DeclaredModule(root, listOf(b.parentFile), emptyList(), "//feature-b:ui"),
        )
      }
    tree.refresh()

    assertThat(tree.modulesOf(a)).containsExactly("//feature-a:ui")
    assertThat(tree.modulesOf(b)).containsExactly("//feature-b:ui")
    assertThat(tree.moduleKey("//feature-b:ui", listOf(root))).isEqualTo("//feature-b:ui")
    assertThat(tree.kotlinSourcesOf("//feature-a:ui")!!.map { it.canonicalFile })
      .containsExactly(a.canonicalFile)
    assertThat(tree.moduleKey("//other:ui", listOf(root))).isNull()
  }

  /**
   * #68 review: a `src/` the first walk found by convention stops being watched once its module
   * publishes narrower directories, so an edit to a source set the build no longer compiles is not
   * reported.
   */
  @Test
  fun `a module that publishes later stops watching the rest of its src`() {
    val root = createTempDirectory("cp-source-tree-narrowed").toFile()
    val app = File(root, "app").apply { mkdirs() }
    File(app, "build.gradle.kts").writeText("")
    val main = File(app, "src/main/kotlin/Main.kt").edit("fun main() {}")
    val release = File(app, "src/release/kotlin/Release.kt").edit("fun release() {}")
    val published = mutableListOf<SourceTree.DeclaredModule>()
    val tree = SourceTree(root, budgetMs = 10_000, moduleRescanMs = 0) { published.toList() }
    tree.refresh()

    published +=
      SourceTree.DeclaredModule(app, listOf(File(app, "src/main/kotlin")), emptyList(), ":app")
    tree.refresh()
    release.edit("fun release() = 1")
    main.edit("fun main() = Unit")
    assertThat(tree.refresh().changed.map { it.canonicalFile }).containsExactly(main.canonicalFile)
    assertThat(tree.kotlinSourcesOf(":app")!!.map { it.canonicalFile })
      .containsExactly(main.canonicalFile)
  }

  /** #70 review: a published directory that is deleted still reports its files as deleted. */
  @Test
  fun `deleting a published directory reports its files`() {
    val root = createTempDirectory("cp-source-tree-deleted").toFile()
    val generated = File(root, "out/gen")
    val gen = File(generated, "Gen.kt").edit("fun gen() {}")
    val tree =
      SourceTree(root, budgetMs = 10_000, moduleRescanMs = 0) {
        listOf(SourceTree.DeclaredModule(root, listOf(generated), emptyList(), "//gen:gen"))
      }
    tree.refresh()

    generated.deleteRecursively()
    assertThat(tree.refresh().changed.map { it.path }).containsExactly(gen.path)
  }

  /** #73: a published build file is watched, and its edit is reported like a source's. */
  @Test
  fun `a published build file is watched`() {
    val root = createTempDirectory("cp-source-tree-build-files").toFile()
    val screen = File(root, "ui/Screen.kt").edit("fun screen() {}")
    val build = File(root, "ui/BUILD.bazel").edit("")
    val config = File(root, ".compose-preview/bazel.json").edit("{}")
    val tree =
      SourceTree(root, budgetMs = 10_000) {
        listOf(
          SourceTree.DeclaredModule(
            root,
            listOf(screen.parentFile),
            emptyList(),
            "//ui:ui",
            buildFiles = listOf(build, config),
          )
        )
      }
    tree.refresh()

    config.edit("{\"providers\":[]}")
    assertThat(tree.refresh().changed.map { it.canonicalFile })
      .containsExactly(config.canonicalFile)
    assertThat(tree.isBuildFile(config)).isTrue()
    assertThat(tree.isBuildFile(screen)).isFalse()
    assertThat(tree.modulesOf(config)).containsExactly("//ui:ui")
  }

  /**
   * #73: a Bazel target has no `build/` to read a compile time from, so an edit made after
   * `prepare` and before the first walk was taken as already built. Its published outputs say.
   */
  @Test
  fun `an edit newer than a published output is reported on the first walk`() {
    val root = createTempDirectory("cp-source-tree-outputs").toFile()
    val built = File(root, "ui/Built.kt").edit("fun built() {}")
    val manifest = File(root, ".compose-preview/outputs/ui/previews.json").edit("{}")
    val edited = File(root, "ui/Edited.kt").edit("fun edited() {}")
    built.setLastModified(manifest.lastModified() - 10_000)
    edited.setLastModified(manifest.lastModified() + 10_000)
    val tree =
      SourceTree(root, budgetMs = 10_000) {
        listOf(
          SourceTree.DeclaredModule(
            root,
            listOf(built.parentFile),
            emptyList(),
            "//ui:ui",
            outputs = listOf(manifest),
          )
        )
      }

    val first = tree.refresh()
    assertThat(first.initial).isTrue()
    assertThat(first.changed.map { it.canonicalFile }).containsExactly(edited.canonicalFile)
  }

  /**
   * #73: two modules that publish one directory (two Bazel targets in a package, two compilations
   * sharing `commonMain`) both own its files, and each compiles them with its own.
   */
  @Test
  fun `a directory several modules publish belongs to each of them`() {
    val root = createTempDirectory("cp-source-tree-shared").toFile()
    val common = File(root, "src/commonMain/kotlin/Common.kt").edit("val common = 1")
    val jvm = File(root, "src/jvmMain/kotlin/Jvm.kt").edit("val jvm = common")
    val android = File(root, "src/androidMain/kotlin/Android.kt").edit("val android = common")
    val tree =
      SourceTree(root, budgetMs = 10_000) {
        listOf(
          SourceTree.DeclaredModule(
            root,
            listOf(common.parentFile, jvm.parentFile),
            emptyList(),
            ":jvm",
          ),
          SourceTree.DeclaredModule(
            root,
            listOf(common.parentFile, android.parentFile),
            emptyList(),
            ":android",
          ),
        )
      }
    tree.refresh()

    assertThat(tree.modulesOf(common)).containsExactly(":jvm", ":android")
    assertThat(tree.modulesOf(jvm)).containsExactly(":jvm")
    assertThat(tree.kotlinSourcesOf(":android")!!.map { it.canonicalFile })
      .containsExactly(common.canonicalFile, android.canonicalFile)
  }

  /** #74 review: one module's later build must not hide another module's unbuilt edit. */
  @Test
  fun `each source is compared with its own module's output`() {
    val root = createTempDirectory("cp-source-tree-own-outputs").toFile()
    val a = File(root, "a/A.kt").edit("fun a() {}")
    val b = File(root, "b/B.kt").edit("fun b() {}")
    val aManifest = File(root, "out/a/previews.json").edit("{}")
    val bManifest = File(root, "out/b/previews.json").edit("{}")
    // A was edited after A was built, then B was built.
    a.setLastModified(aManifest.lastModified() + 10_000)
    bManifest.setLastModified(aManifest.lastModified() + 20_000)
    b.setLastModified(aManifest.lastModified())
    val tree =
      SourceTree(root, budgetMs = 10_000) {
        listOf(
          SourceTree.DeclaredModule(
            root,
            listOf(a.parentFile),
            emptyList(),
            "//a:a",
            outputs = listOf(aManifest),
          ),
          SourceTree.DeclaredModule(
            root,
            listOf(b.parentFile),
            emptyList(),
            "//b:b",
            outputs = listOf(bManifest),
          ),
        )
      }

    assertThat(tree.refresh().changed.map { it.canonicalFile }).containsExactly(a.canonicalFile)
  }
}
