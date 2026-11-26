package ee.schimke.composeai.mcp

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * Removes orphaned temp entries left in `java.io.tmpdir` when an MCP server or its render daemons
 * are ended abruptly (for example by SIGKILL, where shutdown hooks and `deleteOnExit()` never run;
 * yschimke/compose-preview-mcp#227).
 *
 * Designed to be safe alongside concurrent MCP servers, CLI invocations and render daemons:
 * - `compose-preview-mcp-*` render cache directories carry a `.pid` marker and are deleted only
 *   when that PID is no longer alive (legacy directories without a `.pid` marker require
 *   [legacyMaxAgeMs]).
 * - `composeai-cp*.args` and `composeai-props*.args` files are deleted only once older than
 *   [argfileGraceMs] and not referenced by any live process command line (or older than
 *   [legacyMaxAgeMs] if process command lines cannot be inspected).
 * - `composeai-roborazzi-results*` directories are deleted only when no live render daemon process
 *   is running and the directory is older than [argfileGraceMs], or when older than
 *   [legacyMaxAgeMs].
 * - Symbolic links are never followed or deleted.
 */
internal object TempFileSweep {

  const val PID_MARKER = ".pid"
  const val MCP_CACHE_PREFIX = "compose-preview-mcp-"
  const val CP_ARGFILE_PREFIX = "composeai-cp"
  const val PROPS_ARGFILE_PREFIX = "composeai-props"
  const val ARGFILE_SUFFIX = ".args"
  const val ROBORAZZI_RESULTS_PREFIX = "composeai-roborazzi-results"

  val DEFAULT_ARGFILE_GRACE_MS: Long = TimeUnit.SECONDS.toMillis(60)
  val DEFAULT_LEGACY_MAX_AGE_MS: Long = TimeUnit.HOURS.toMillis(24)

  data class SweepResult(val deletedFiles: Int, val deletedDirs: Int)

  /**
   * Records the owning server PID in [dir] so a later startup sweep can reclaim it after SIGKILL.
   */
  fun writePidMarker(dir: File, pid: Long = ProcessHandle.current().pid()) {
    runCatching { File(dir, PID_MARKER).writeText("$pid\n") }
  }

  /**
   * Runs [sweepStaleTempEntries] on a background daemon thread so MCP startup latency is unchanged.
   */
  fun sweepAsync(tmpDir: File = File(System.getProperty("java.io.tmpdir") ?: ".")) {
    Thread(
        { runCatching { sweepStaleTempEntries(tmpDir) } },
        "compose-preview-mcp-temp-sweep",
      )
      .apply {
        isDaemon = true
        start()
      }
  }

  fun sweepStaleTempEntries(
    tmpDir: File = File(System.getProperty("java.io.tmpdir") ?: "."),
    nowMs: Long = System.currentTimeMillis(),
    argfileGraceMs: Long = DEFAULT_ARGFILE_GRACE_MS,
    legacyMaxAgeMs: Long = DEFAULT_LEGACY_MAX_AGE_MS,
    isPidAlive: (Long) -> Boolean = ::defaultIsPidAlive,
    liveProcessCommands: () -> List<String>? = ::defaultLiveProcessCommands,
  ): SweepResult {
    if (!tmpDir.isDirectory) return SweepResult(deletedFiles = 0, deletedDirs = 0)
    val candidates =
      tmpDir.listFiles { entry ->
        val name = entry.name
        name.startsWith(MCP_CACHE_PREFIX) ||
          isArgfileName(name) ||
          name.startsWith(ROBORAZZI_RESULTS_PREFIX)
      } ?: return SweepResult(deletedFiles = 0, deletedDirs = 0)
    if (candidates.isEmpty()) return SweepResult(deletedFiles = 0, deletedDirs = 0)

    var liveCommandsLoaded = false
    var liveCommands: List<String>? = null
    fun commands(): List<String>? {
      if (!liveCommandsLoaded) {
        liveCommandsLoaded = true
        liveCommands = runCatching(liveProcessCommands).getOrNull()
      }
      return liveCommands
    }

    var deletedFiles = 0
    var deletedDirs = 0

    for (entry in candidates) {
      if (runCatching { Files.isSymbolicLink(entry.toPath()) }.getOrDefault(true)) continue
      val name = entry.name
      when {
        name.startsWith(MCP_CACHE_PREFIX) && entry.isDirectory -> {
          if (shouldDeleteMcpCacheDir(entry, nowMs, legacyMaxAgeMs, isPidAlive)) {
            if (runCatching { entry.deleteRecursively() }.getOrDefault(false)) deletedDirs++
          }
        }
        isArgfileName(name) && entry.isFile -> {
          if (shouldDeleteArgfile(entry, nowMs, argfileGraceMs, legacyMaxAgeMs, commands())) {
            if (runCatching { entry.delete() }.getOrDefault(false)) deletedFiles++
          }
        }
        name.startsWith(ROBORAZZI_RESULTS_PREFIX) && entry.isDirectory -> {
          if (shouldDeleteRoborazziDir(entry, nowMs, argfileGraceMs, legacyMaxAgeMs, commands())) {
            if (runCatching { entry.deleteRecursively() }.getOrDefault(false)) deletedDirs++
          }
        }
      }
    }
    return SweepResult(deletedFiles = deletedFiles, deletedDirs = deletedDirs)
  }

  private fun isArgfileName(name: String): Boolean =
    name.endsWith(ARGFILE_SUFFIX) &&
      (name.startsWith(CP_ARGFILE_PREFIX) || name.startsWith(PROPS_ARGFILE_PREFIX))

  private fun shouldDeleteMcpCacheDir(
    dir: File,
    nowMs: Long,
    legacyMaxAgeMs: Long,
    isPidAlive: (Long) -> Boolean,
  ): Boolean {
    val pidFile = File(dir, PID_MARKER)
    if (pidFile.isFile) {
      val pid = runCatching { pidFile.readText().trim().toLong() }.getOrNull()
      if (pid != null && pid > 0L) {
        return !runCatching { isPidAlive(pid) }.getOrDefault(true)
      }
    }
    return ageMs(newestModifiedIn(dir), nowMs) >= legacyMaxAgeMs
  }

  private fun shouldDeleteArgfile(
    file: File,
    nowMs: Long,
    argfileGraceMs: Long,
    legacyMaxAgeMs: Long,
    liveCommands: List<String>?,
  ): Boolean {
    val age = ageMs(file.lastModified(), nowMs)
    if (liveCommands == null) return age >= legacyMaxAgeMs
    if (age < argfileGraceMs) return false
    val name = file.name
    return liveCommands.none { cmd -> cmd.contains(name) }
  }

  private fun shouldDeleteRoborazziDir(
    dir: File,
    nowMs: Long,
    argfileGraceMs: Long,
    legacyMaxAgeMs: Long,
    liveCommands: List<String>?,
  ): Boolean {
    val age = ageMs(newestModifiedIn(dir), nowMs)
    if (age >= legacyMaxAgeMs) return true
    if (liveCommands == null || age < argfileGraceMs) return false
    val hasLiveDaemon = liveCommands.any { cmd ->
      cmd.contains("composeai.daemon") || cmd.contains("DaemonMain") || cmd.contains("WorkerMain")
    }
    return !hasLiveDaemon
  }

  private fun ageMs(modifiedMs: Long, nowMs: Long): Long =
    if (modifiedMs <= 0L) 0L else (nowMs - modifiedMs).coerceAtLeast(0L)

  private fun newestModifiedIn(dir: File): Long {
    var newest = dir.lastModified()
    runCatching {
      dir.walkTopDown().maxDepth(4).forEach { file ->
        val mtime = file.lastModified()
        if (mtime > newest) newest = mtime
      }
    }
    return newest
  }

  private fun defaultIsPidAlive(pid: Long): Boolean =
    ProcessHandle.of(pid).map { it.isAlive }.orElse(false)

  private fun defaultLiveProcessCommands(): List<String>? {
    val commands = mutableListOf<String>()
    val selfPid = ProcessHandle.current().pid()
    var sawSelfInfo = false
    ProcessHandle.allProcesses().forEach { handle ->
      val info = handle.info()
      val rendered =
        info.commandLine().orElse(null)
          ?: listOfNotNull(
              info.command().orElse(null),
              info.arguments().map { args -> args.joinToString(" ") }.orElse(null),
            )
            .joinToString(" ")
            .takeIf { it.isNotBlank() }
      if (!rendered.isNullOrBlank()) {
        if (handle.pid() == selfPid) sawSelfInfo = true
        commands += rendered
      }
    }
    return if (sawSelfInfo) commands else null
  }
}
