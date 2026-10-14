package ee.schimke.composeai.mcp

import java.io.File

/**
 * Finds the Gradle builds a directory stands for, so one global `compose-preview mcp serve` entry
 * (no `--project`) works for any project: the client's MCP roots, the server's working directory,
 * or a `project` argument all go through [buildsFor].
 *
 * A build is a directory with `settings.gradle(.kts)`, or a Bazel-family workspace root
 * (`MODULE.bazel`, `WORKSPACE(.bazel)`, `.blazerc`) with no Gradle build of its own: the
 * compose-preview CLI prepares and rebuilds those ([isBazelWorkspace]), so this server never runs
 * Bazel itself. A preview bundle's workspace ([BundleProjects]) is one too. A repository such as
 * wear-os-samples keeps one build per sample one level below its git root, so a root that is not a
 * build is searched up to [SEARCH_DEPTH] levels down.
 */
object ProjectDiscovery {

  const val SEARCH_DEPTH = 2

  private val SETTINGS_FILES = listOf("settings.gradle.kts", "settings.gradle")

  /** Files that make a root (not an ancestor) count as a build: a single-project build too. */
  private val ROOT_BUILD_FILES = SETTINGS_FILES + listOf("build.gradle.kts", "build.gradle")

  /** The CLI's own Bazel-family markers (`detectProjectBuild`), so both agree on a workspace. */
  private val BAZEL_WORKSPACE_FILES =
    listOf("MODULE.bazel", "WORKSPACE.bazel", "WORKSPACE", ".blazerc")

  private val SKIPPED_DIRS = setOf("build", "node_modules")

  fun isBuild(dir: File): Boolean =
    SETTINGS_FILES.any { File(dir, it).isFile } ||
      isBazelWorkspace(dir) ||
      BundleProjects.isBundleWorkspace(dir)

  /**
   * A Bazel-family workspace root that is not also a Gradle build. Gradle wins where both exist, as
   * in the CLI: a Gradle repository that carries a `WORKSPACE` keeps its Gradle preview tasks.
   */
  fun isBazelWorkspace(dir: File): Boolean =
    BAZEL_WORKSPACE_FILES.any { File(dir, it).isFile } &&
      SETTINGS_FILES.none { File(dir, it).isFile } &&
      !File(dir, "gradlew").isFile

  /** `bazel-bin`, `bazel-out`, … : output-tree symlinks no scan should follow. */
  fun isBazelOutputLink(dir: File): Boolean = dir.name.startsWith("bazel-")

  /**
   * The builds [dir] stands for: [dir] itself when it is one; else the nearest ancestor with
   * `settings.gradle(.kts)` (a root or cwd inside a build); else every build up to [SEARCH_DEPTH]
   * levels below it. Never climbs out of a `.claude/worktrees/<name>` worktree, which is a checkout
   * of its own: its edits must not render from the main checkout above it.
   */
  fun buildsFor(dir: File): List<File> {
    val start = canonical(dir).let { if (it.isFile) it.parentFile ?: it else it }
    if (!start.isDirectory) return emptyList()
    if (ROOT_BUILD_FILES.any { File(start, it).isFile } || isBuild(start)) return listOf(start)
    enclosingBuild(start)?.let {
      return listOf(it)
    }
    // Searching below the filesystem root would be a disk crawl, not a discovery.
    if (start.parentFile == null) return emptyList()
    return buildsBelow(start)
  }

  /** The nearest directory at or above [start] that is a build ([isBuild]). */
  fun enclosingBuild(start: File): File? {
    var dir: File? = canonical(start)
    val boundary = worktreeRoot(dir)
    while (dir != null) {
      if (isBuild(dir)) return dir
      if (dir == boundary) return null
      dir = dir.parentFile
    }
    return null
  }

  /**
   * Builds up to [depth] levels below [root], breadth first. Skips `build/`, `node_modules/` and
   * hidden directories (`.git/`, `.gradle/`, `.claude/` …): a worktree under `.claude/worktrees/`
   * is found when it is the root (or the cwd) itself, not as a copy beside the checkout.
   */
  fun buildsBelow(root: File, depth: Int = SEARCH_DEPTH): List<File> {
    val found = mutableListOf<File>()
    var level = listOf(canonical(root))
    repeat(depth) {
      level = level.flatMap { dir ->
        dir
          .listFiles { child ->
            child.isDirectory &&
              !child.name.startsWith(".") &&
              child.name !in SKIPPED_DIRS &&
              !isBazelOutputLink(child)
          }
          .orEmpty()
          .sortedBy { it.name }
          .filter { child ->
            if (isBuild(child)) {
              found += child
              false
            } else true
          }
      }
    }
    return found
  }

  /** The `.claude/worktrees/<name>` directory [dir] lies in, if any. */
  private fun worktreeRoot(dir: File?): File? {
    var current = dir
    while (current != null) {
      val parent = current.parentFile
      if (parent?.name == "worktrees" && parent.parentFile?.name == ".claude") return current
      current = parent
    }
    return null
  }

  private fun canonical(file: File): File = runCatching {
    file.canonicalFile
  }
    .getOrDefault(file.absoluteFile)
}
