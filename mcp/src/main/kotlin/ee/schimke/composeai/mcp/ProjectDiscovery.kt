package ee.schimke.composeai.mcp

import java.io.File

/**
 * Finds the Gradle builds a directory stands for, so one global `compose-preview mcp serve` entry
 * (no `--project`) works for any project: the client's MCP roots, the server's working directory,
 * or a `project` argument all go through [buildsFor].
 *
 * A build is a directory with `settings.gradle(.kts)`, or a Bazel-family workspace root
 * (`MODULE.bazel`, `WORKSPACE(.bazel)`, `.blazerc`) or Buck2 project root (`.buckroot`, the
 * outermost `.buckconfig`) with no Gradle build of its own: the compose-preview CLI prepares and
 * rebuilds those ([isBazelWorkspace]), so this server never runs Bazel or Buck2 itself. A preview
 * bundle's workspace ([BundleProjects]) is one too. A repository such as wear-os-samples keeps one
 * build per sample one level below its git root, so a root that is not a build is searched up to
 * [SEARCH_DEPTH] levels down.
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
   * The record `compose-preview bazel prepare --out <dir>` leaves in `<dir>` (newer CLIs): how to
   * prepare that staged directory again from its own workspace, which the CLI does when handed the
   * directory as `--project` (compose-ai-tools#6160).
   */
  const val STAGED_PREPARE_ORIGIN = ".compose-preview/prepare-origin.json"

  /**
   * A workspace the CLI's Bazel-family driver prepares: a Bazel workspace root, a Buck2 project
   * root ([isBuckProject]), or a staged `bazel prepare --out` directory ([STAGED_PREPARE_ORIGIN]),
   * that is not also a Gradle build. Gradle wins where both exist, as in the CLI: a Gradle
   * repository that carries a `WORKSPACE` keeps its Gradle preview tasks.
   *
   * A staged directory counts so it can be registered as it is, and refreshed through
   * [ProjectBootstrap.bazelPrepare] after a source edit: the CLI replays the recorded prepare in
   * the original workspace. Before the record, a staged directory needed a hand-placed `WORKSPACE`
   * marker, and the CLI then ran the default `bazel` there, with no build to find.
   */
  fun isBazelWorkspace(dir: File): Boolean =
    (BAZEL_WORKSPACE_FILES.any { File(dir, it).isFile } ||
      isBuckProject(dir) ||
      File(dir, STAGED_PREPARE_ORIGIN).isFile) &&
      SETTINGS_FILES.none { File(dir, it).isFile } &&
      !File(dir, "gradlew").isFile

  /**
   * A Buck2 project root, as the CLI's `detectBuckRoot` reads one: `.buckroot`, or a `.buckconfig`
   * with none above it. A cell inside the project (a vendored prelude) has its own `.buckconfig`
   * and is not a root. The CLI drives it with `--build-system buck`
   * ([ProjectBootstrap.bazelPrepare]).
   */
  fun isBuckProject(dir: File): Boolean {
    if (File(dir, ".buckroot").isFile) return true
    if (!File(dir, ".buckconfig").isFile) return false
    if (File(dir, ".git").exists() || File(dir, ".hg").exists()) return true
    var parent = dir.parentFile
    while (parent != null) {
      if (File(parent, ".buckroot").isFile || File(parent, ".buckconfig").isFile) return false
      if (File(parent, ".git").exists() || File(parent, ".hg").exists()) return true
      parent = parent.parentFile
    }
    return true
  }

  /** `bazel-bin`, `bazel-out`, … and Buck2's `buck-out`: output trees no scan should follow. */
  fun isBazelOutputLink(dir: File): Boolean =
    dir.name.startsWith("bazel-") || dir.name == "buck-out"

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

  /**
   * A directory `compose-preview bazel … --out <dir>` staged: a previews manifest and renders, but
   * no project to build. [manifest] is the `previews.json` that marks it; [workspaceRoot] is the
   * Bazel-family workspace it sits inside, when it sits inside one.
   */
  data class StagedOutput(val manifest: File, val workspaceRoot: File?)

  /** Where `--out` puts the manifest: under the normal build surface, or directly in the dir. */
  private val STAGED_MANIFESTS = listOf("build/compose-previews/previews.json", "previews.json")

  /** Bazel/Buck package files: a directory holding one is source, never a staged output. */
  private val PACKAGE_FILES = listOf("BUILD", "BUILD.bazel", "BUCK")

  /**
   * [dir] as a staged `--out` directory, or null when it is not one. It is one when it holds a
   * previews manifest (its own, or, for an `--out` shared by several targets, one per target one
   * level below) and no build: no Gradle or Bazel-family file in it, and no Gradle build or preview
   * bundle at or above it. A Gradle module (`build.gradle.kts` beside its `build/` output) or a
   * bundle workspace is therefore never mistaken for one.
   */
  fun stagedOutput(dir: File): StagedOutput? {
    val start = canonical(dir)
    if (!start.isDirectory) return null
    if (holdsBuild(start)) return null
    val manifest =
      stagedManifestIn(start)
        ?: run {
          val children =
            start
              .listFiles { child -> child.isDirectory && !child.name.startsWith(".") }
              .orEmpty()
              .sortedBy { it.name }
          // A child that is a build (a sample's own Gradle build, with its own previews.json)
          // makes this a checkout of builds, never one `--out` shared by several targets.
          if (children.any { holdsBuild(it) }) return null
          children.firstNotNullOfOrNull { stagedManifestIn(it) }
        }
        ?: return null
    var parent = start.parentFile
    while (parent != null) {
      if (isBazelWorkspace(parent)) return StagedOutput(manifest, parent)
      if (isBuild(parent)) return null
      parent = parent.parentFile
    }
    return StagedOutput(manifest, workspaceRoot = null)
  }

  /**
   * Why `register_project` refuses [staged], and what to register instead: the Bazel workspace the
   * renders came from, or a bundle packed from it to view them on their own.
   */
  fun stagedOutputError(path: String, staged: StagedOutput): String {
    val workspace =
      staged.workspaceRoot?.let { "register the Bazel workspace root instead: ${it.path}" }
        ?: "register the Bazel workspace root instead (the directory with MODULE.bazel or " +
          "WORKSPACE that `compose-preview bazel` ran in)"
    return "register_project: '$path' is a staged `compose-preview bazel --out` directory " +
      "(${staged.manifest.path} and its renders, but no Gradle or Bazel project to build), so " +
      "its previews cannot be rendered from here. To render them, $workspace. To view the " +
      "renders without the workspace, pack them as a preview bundle (`compose-preview bazel " +
      "bundle --target <label> --output bundle.png`, run in the workspace) and serve or open " +
      "that instead; register_project accepts the bundle file as its path."
  }

  /** A build, a Gradle module or a Bazel/Buck package: source, never staged output. */
  private fun holdsBuild(dir: File): Boolean =
    isBuild(dir) ||
      ROOT_BUILD_FILES.any { File(dir, it).isFile } ||
      PACKAGE_FILES.any { File(dir, it).isFile }

  private fun stagedManifestIn(dir: File): File? =
    STAGED_MANIFESTS.map { File(dir, it) }.firstOrNull { it.isFile }

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
