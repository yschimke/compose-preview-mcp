package ee.schimke.composeai.mcp

import ee.schimke.composeai.daemon.protocol.DaemonLaunchDescriptor
import java.io.File
import java.nio.file.Files

/** Outcome of running `<module>:composePreviewDaemonStart` to refresh a launch descriptor. */
sealed interface DescriptorRefreshOutcome {
  /** Refresh ran and exited 0 (descriptor rewritten or confirmed up-to-date). */
  data object Refreshed : DescriptorRefreshOutcome

  /** Refresh ran and failed (non-zero exit, timeout, or exception). */
  data class Failed(val reason: String) : DescriptorRefreshOutcome

  /** Refresh could not run (no Gradle wrapper, Bazel/bundle workspace, or disabled). */
  data class Unavailable(val reason: String) : DescriptorRefreshOutcome
}

/** Warning surfaced in `status` and render notes when a module's launch descriptor is stale. */
data class StaleDescriptorNote(
  val modulePath: String,
  val newerBuildFile: File,
  val newerBuildFileModifiedMs: Long,
  val descriptorModifiedMs: Long,
  val reason: String? = null,
  val message: String,
)

/**
 * Compares a module's `daemon-launch.json` against the build files that shape it: the descriptor's
 * own `sourceWatch.buildFiles` plus workspace-level Gradle convention-plugin builds (`buildSrc` and
 * `includeBuild(...)` from `settings.gradle(.kts)`), so descriptors written before the Gradle
 * plugin watched included builds still detect convention-plugin edits
 * (yschimke/compose-preview-mcp#258).
 */
internal object DescriptorFreshness {

  private const val MAX_CONVENTION_DEPTH = 8
  private const val MAX_CONVENTION_FILES = 500
  private val SKIPPED_DIRS = setOf("build", "out", "node_modules")
  private val TEST_SOURCE_SET = Regex("(?i)test")
  private val BUILD_FILE_EXTENSIONS = setOf("kts", "gradle", "kt", "java", "properties", "toml")
  private val BLOCK_COMMENT = Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL)
  private val LINE_COMMENT = Regex("""//[^\n]*""")
  private val INCLUDE_BUILD = Regex("""\bincludeBuild\s*(?:\(\s*|\s+)["']([^"']+)["']""")

  /**
   * Every build file that determines whether [descriptor] is current: its `sourceWatch.buildFiles`
   * plus (for Gradle builds) convention-plugin files under `buildSrc` and included builds.
   */
  fun watchedBuildFiles(projectRoot: File, descriptor: DaemonLaunchDescriptor): List<File> {
    val byPath = LinkedHashMap<String, File>()
    descriptor.sourceWatch?.buildFiles.orEmpty().forEach { raw ->
      val file = File(raw).let { if (it.isAbsolute) it else File(projectRoot, raw) }.normalize()
      byPath.putIfAbsent(file.absolutePath, file)
    }
    if (
      !ProjectDiscovery.isBazelWorkspace(projectRoot) &&
        !BundleProjects.isBundleWorkspace(projectRoot)
    ) {
      conventionPluginBuildFiles(projectRoot).forEach { file ->
        val normalized = file.absoluteFile.normalize()
        byPath.putIfAbsent(normalized.path, normalized)
      }
    }
    return byPath.values.toList()
  }

  /**
   * Build scripts and plugin sources in `buildSrc` and any `includeBuild(...)` named in
   * [projectRoot]'s `settings.gradle(.kts)`. Skips hidden dirs, build outputs, symlinks and test
   * source sets, and caps the walk so a large composite build stays cheap to stat.
   */
  fun conventionPluginBuildFiles(projectRoot: File): List<File> {
    val dirs = LinkedHashSet<File>()
    val buildSrc = File(projectRoot, "buildSrc").absoluteFile.normalize()
    if (buildSrc.isDirectory) dirs += buildSrc
    includedBuildDirs(projectRoot).forEach { dir -> if (dir.isDirectory) dirs += dir }
    if (dirs.isEmpty()) return emptyList()

    val collected = mutableListOf<File>()
    for (dir in dirs) {
      if (collected.size >= MAX_CONVENTION_FILES) break
      dir
        .walkTopDown()
        .maxDepth(MAX_CONVENTION_DEPTH)
        .onEnter { child ->
          if (child == dir) return@onEnter true
          if (child.name.startsWith(".") || child.name in SKIPPED_DIRS) return@onEnter false
          if (runCatching { Files.isSymbolicLink(child.toPath()) }.getOrDefault(false)) {
            return@onEnter false
          }
          !(child.parentFile?.name == "src" && TEST_SOURCE_SET.containsMatchIn(child.name))
        }
        .filter { it.isFile && it.extension in BUILD_FILE_EXTENSIONS }
        .forEach { file -> if (collected.size < MAX_CONVENTION_FILES) collected += file }
    }
    return collected
  }

  /**
   * The newest existing watched build file whose mtime is strictly greater than [descriptorFile]'s
   * mtime (or [verifiedUpToMs], when `composePreviewDaemonStart` already confirmed up-to-date for
   * that timestamp), or `null` when the descriptor is current or absent.
   */
  fun newerBuildFile(
    projectRoot: File,
    descriptor: DaemonLaunchDescriptor,
    descriptorFile: File?,
    verifiedUpToMs: Long = 0L,
  ): File? {
    if (descriptorFile == null || !descriptorFile.isFile) return null
    val descriptorMtime = descriptorFile.lastModified()
    if (descriptorMtime <= 0L) return null
    val freshAt = maxOf(descriptorMtime, verifiedUpToMs)
    return watchedBuildFiles(projectRoot, descriptor)
      .filter { it.isFile && it.lastModified() > freshAt }
      .maxByOrNull { it.lastModified() }
  }

  fun staleNote(
    projectRoot: File,
    modulePath: String,
    newerBuildFile: File,
    descriptorModifiedMs: Long,
    reason: String? = null,
  ): StaleDescriptorNote =
    StaleDescriptorNote(
      modulePath = modulePath,
      newerBuildFile = newerBuildFile,
      newerBuildFileModifiedMs = newerBuildFile.lastModified(),
      descriptorModifiedMs = descriptorModifiedMs,
      reason = reason,
      message = staleDescriptorMessage(projectRoot, modulePath, newerBuildFile, reason),
    )

  fun staleDescriptorMessage(
    projectRoot: File,
    modulePath: String,
    newerBuildFile: File,
    reason: String? = null,
  ): String {
    val display = displayBuildFile(projectRoot, newerBuildFile)
    val command =
      if (ProjectDiscovery.isBazelWorkspace(projectRoot)) "`compose-preview bazel prepare`"
      else "`./gradlew ${modulePath.trimEnd(':')}:${GradleSourceCompiler.DAEMON_START_TASK}`"
    val detail = reason?.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()
    return "launch descriptor for $modulePath is older than $display$detail; run $command to " +
      "refresh it"
  }

  fun displayBuildFile(projectRoot: File, file: File): String {
    val rootPath = runCatching { projectRoot.canonicalFile }.getOrDefault(projectRoot.absoluteFile)
    val filePath = runCatching { file.canonicalFile }.getOrDefault(file.absoluteFile)
    val relative = runCatching { filePath.relativeTo(rootPath).invariantSeparatorsPath }.getOrNull()
    return if (relative != null && !relative.startsWith("..")) relative else file.path
  }

  private fun includedBuildDirs(projectRoot: File): List<File> {
    val settings =
      listOf("settings.gradle.kts", "settings.gradle")
        .map { File(projectRoot, it) }
        .firstOrNull(File::isFile) ?: return emptyList()
    val text = runCatching { settings.readText() }.getOrNull() ?: return emptyList()
    val stripped = LINE_COMMENT.replace(BLOCK_COMMENT.replace(text, ""), "")
    return INCLUDE_BUILD.findAll(stripped)
      .map { match -> File(projectRoot, match.groupValues[1]).absoluteFile.normalize() }
      .toList()
  }
}
