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
}
