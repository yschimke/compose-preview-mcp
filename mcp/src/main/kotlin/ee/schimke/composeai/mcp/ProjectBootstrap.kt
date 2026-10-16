package ee.schimke.composeai.mcp

import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Prepares a registered build that `compose-preview mcp install` never ran in: none of its modules
 * has a daemon launch descriptor (`build/compose-previews/daemon-launch.json`), so no daemon can
 * start and every preview lookup came back empty ("no preview matches").
 *
 * On first use it runs `./gradlew composePreviewDiscover composePreviewDaemonStart`, with the
 * compose-preview CLI's init script when the build does not apply the plugin itself: the same
 * [InitScripts] lookup (`COMPOSE_PREVIEW_INIT_SCRIPT`, else `~/.cache/composeai/init/<v>/…`) the
 * recompile uses. Until that succeeds the project is reported as "project not prepared: <reason>".
 *
 * A Bazel-family workspace ([ProjectDiscovery.isBazelWorkspace]) is prepared by the compose-preview
 * CLI instead (`compose-preview bazel prepare`), which owns target selection, the aspect and the
 * build; it writes the same descriptor under
 * `.compose-preview/outputs/<key>/build/compose-previews/`. A preview bundle's workspace
 * ([BundleProjects]) is filled the same way, by `compose-preview bundle prepare`.
 */
class ProjectBootstrap(
  private val initScripts: InitScripts = InitScripts(),
  private val runner: GradleTaskRunner = GradleTaskRunner.subprocess(),
  /** The compose-preview launcher for Bazel workspaces; a seam so tests need no CLI. */
  private val cli: () -> File? = {
    CliInitScript.locate(System.getenv(), File(System.getProperty("user.home")))
  },
) {

  sealed interface Outcome {
    /** Descriptors exist (now or already); [ran] says whether this call ran Gradle. */
    data class Ready(val ran: Boolean, val durationMs: Long = 0) : Outcome

    data class NotPrepared(val reason: String) : Outcome
  }

  private val locks = ConcurrentHashMap<String, Any>()

  /** Whether [projectRoot] has at least one module with a daemon launch descriptor. */
  fun isPrepared(projectRoot: File): Boolean = runCatching {
    DescriptorProvider.indexDescriptorsByModulePath(projectRoot).isNotEmpty()
  }
    .getOrDefault(false)

  /**
   * Makes [projectRoot] renderable, running the bootstrap when it is not. Concurrent calls for one
   * build share one Gradle run. [progress] gets one line per step and per Gradle task.
   */
  fun ensurePrepared(projectRoot: File, progress: (String) -> Unit = {}): Outcome {
    if (isPrepared(projectRoot)) return Outcome.Ready(ran = false)
    synchronized(locks.computeIfAbsent(projectRoot.absolutePath) { Any() }) {
      if (isPrepared(projectRoot)) return Outcome.Ready(ran = false)
      if (ProjectDiscovery.isBazelWorkspace(projectRoot)) return prepareBazel(projectRoot, progress)
      BundleProjects.sourceOf(projectRoot)?.let {
        return prepareBundle(projectRoot, it, progress)
      }
      val windows = System.getProperty("os.name").orEmpty().startsWith("Windows")
      val wrapper = File(projectRoot, if (windows) "gradlew.bat" else "gradlew")
      if (!wrapper.isFile) {
        return Outcome.NotPrepared(
          "no Gradle wrapper at ${wrapper.path}, so compose-preview cannot run its discovery " +
            "task there; run `compose-preview mcp install` in ${projectRoot.path}"
        )
      }
      val initScript =
        initScripts.forProject(projectRoot)?.takeIf {
          !initScripts.projectAppliesPlugin(projectRoot)
        }
      val arguments = buildList {
        if (initScript != null) {
          add("--init-script")
          add(initScript.absolutePath)
          addAll(ISOLATED_PROJECTS_OFF)
        }
        add("--console=plain")
        add("--continue")
        addAll(TASKS)
      }
      progress(
        "preparing ${projectRoot.name}: ./gradlew ${TASKS.joinToString(" ")}" +
          if (initScript != null) " (with the compose-preview init script)" else ""
      )
      val started = System.nanoTime()
      val result =
        runner.run(projectRoot, wrapper, arguments) { line ->
          if (line.startsWith("> Task ")) progress(line)
        }
      val durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
      if (isPrepared(projectRoot)) {
        progress("prepared ${projectRoot.name} in ${durationMs}ms")
        return Outcome.Ready(ran = true, durationMs = durationMs)
      }
      val why =
        when {
          result.timedOut -> "the bootstrap timed out after ${durationMs}ms"
          result.exitCode != 0 ->
            "./gradlew ${TASKS.joinToString(" ")} failed: " +
              GradleSourceCompiler.summarizeGradleFailure(result.output)
          else -> "./gradlew ${TASKS.joinToString(" ")} wrote no daemon launch descriptor"
        }
      val hint =
        if (initScript == null && !initScripts.projectAppliesPlugin(projectRoot)) {
          " (the build does not apply the Compose Preview plugin and no compose-preview init " +
            "script was found; run `compose-preview mcp install` once)"
        } else ""
      return Outcome.NotPrepared(why + hint)
    }
  }

  /**
   * Reruns `composePreviewDiscover` in a build that is already prepared, so previews declared since
   * its daemons started reach `previews.json`. The daemon's own incremental discovery missed a
   * newly added file (yschimke/compose-ag-plugin#64), while this Gradle task, the one
   * `compose-preview show` runs, finds it. Returns null when the build has no Gradle wrapper.
   */
  fun rediscover(projectRoot: File, progress: (String) -> Unit = {}): GradleTaskRunner.Result? {
    // A bundle's previews are fixed when it is packed; there is nothing to rediscover.
    if (BundleProjects.isBundleWorkspace(projectRoot)) return null
    if (ProjectDiscovery.isBazelWorkspace(projectRoot)) {
      // `bazel prepare` rebuilds and rediscovers in one step.
      synchronized(locks.computeIfAbsent(projectRoot.absolutePath) { Any() }) {
        val launcher = cli() ?: return null
        progress("rediscovering previews in ${projectRoot.name}: compose-preview bazel prepare")
        return runner.run(projectRoot, launcher, bazelPrepare(projectRoot), progress)
      }
    }
    val windows = System.getProperty("os.name").orEmpty().startsWith("Windows")
    val wrapper = File(projectRoot, if (windows) "gradlew.bat" else "gradlew")
    if (!wrapper.isFile) return null
    synchronized(locks.computeIfAbsent(projectRoot.absolutePath) { Any() }) {
      val initScript =
        initScripts.forProject(projectRoot)?.takeIf {
          !initScripts.projectAppliesPlugin(projectRoot)
        }
      val arguments = buildList {
        if (initScript != null) {
          add("--init-script")
          add(initScript.absolutePath)
          addAll(ISOLATED_PROJECTS_OFF)
        }
        add("--console=plain")
        add("--continue")
        add(DISCOVER_TASK)
      }
      progress("rediscovering previews in ${projectRoot.name}: ./gradlew $DISCOVER_TASK")
      return runner.run(projectRoot, wrapper, arguments) { line ->
        if (line.startsWith("> Task ")) progress(line)
      }
    }
  }

  /** Builds, discovers and writes the descriptor through the CLI; the caller holds the lock. */
  private fun prepareBazel(projectRoot: File, progress: (String) -> Unit): Outcome {
    val launcher =
      cli()
        ?: return Outcome.NotPrepared(
          "${projectRoot.path} is a Bazel workspace, which the compose-preview CLI prepares, and " +
            "no compose-preview launcher was found (COMPOSE_PREVIEW_CLI, PATH or " +
            "~/.local/bin/compose-preview)"
        )
    progress("preparing ${projectRoot.name}: compose-preview bazel prepare")
    val started = System.nanoTime()
    val result = runner.run(projectRoot, launcher, bazelPrepare(projectRoot), progress)
    val durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
    if (isPrepared(projectRoot)) {
      progress("prepared ${projectRoot.name} in ${durationMs}ms")
      return Outcome.Ready(ran = true, durationMs = durationMs)
    }
    return Outcome.NotPrepared(
      when {
        result.timedOut -> "compose-preview bazel prepare timed out after ${durationMs}ms"
        result.exitCode != 0 ->
          "compose-preview bazel prepare failed: " +
            result.output.lines().lastOrNull { it.isNotBlank() }.orEmpty()
        else -> "compose-preview bazel prepare wrote no daemon launch descriptor"
      }
    )
  }

  /** Extracts the bundle and writes its descriptor through the CLI; the caller holds the lock. */
  private fun prepareBundle(workspace: File, source: String, progress: (String) -> Unit): Outcome {
    val launcher =
      cli()
        ?: return Outcome.NotPrepared(
          "$source is a preview bundle, which the compose-preview CLI unpacks, and no " +
            "compose-preview launcher was found (COMPOSE_PREVIEW_CLI, PATH or " +
            "~/.local/bin/compose-preview)"
        )
    progress("preparing bundle $source: compose-preview bundle prepare")
    val started = System.nanoTime()
    val result =
      runner.run(workspace, launcher, BundleProjects.prepareArguments(source, workspace), progress)
    val durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
    if (isPrepared(workspace)) {
      progress("prepared bundle $source in ${durationMs}ms")
      return Outcome.Ready(ran = true, durationMs = durationMs)
    }
    return Outcome.NotPrepared(
      when {
        result.timedOut -> "compose-preview bundle prepare timed out after ${durationMs}ms"
        result.exitCode != 0 ->
          "compose-preview bundle prepare failed: " +
            result.output.lines().lastOrNull { it.isNotBlank() }.orEmpty() +
            " (`bundle prepare` needs a compose-preview CLI newer than 2.59.0)"
        else -> "compose-preview bundle prepare wrote no daemon launch descriptor"
      }
    )
  }

  companion object {
    /**
     * The target comes from the workspace's `.compose-preview/bazel.json` (or the CLI's own
     * selection); the long timeout covers a first build compiling the toolchain.
     */
    fun bazelPrepare(projectRoot: File): List<String> =
      listOf("bazel", "prepare", "--project", projectRoot.absolutePath, "--timeout", "1800") +
        // The explicit `bazel` command collects through Buck2's BXL script only when told to.
        if (ProjectDiscovery.isBuckProject(projectRoot)) listOf("--build-system", "buck")
        else emptyList()

    val TASKS = listOf("composePreviewDiscover", "composePreviewDaemonStart")

    const val DISCOVER_TASK = "composePreviewDiscover"

    /** Mirrors [GradleSourceCompiler]: the injected script cannot run under Isolated Projects. */
    private val ISOLATED_PROJECTS_OFF =
      listOf("-Dorg.gradle.unsafe.isolated-projects=false", "-Dorg.gradle.isolated-projects=false")
  }
}

/** Runs a Gradle wrapper; a seam so tests need no Gradle. */
fun interface GradleTaskRunner {

  data class Result(val exitCode: Int, val output: String, val timedOut: Boolean = false)

  fun run(
    projectRoot: File,
    wrapper: File,
    arguments: List<String>,
    onLine: (String) -> Unit,
  ): Result

  companion object {
    private const val MAX_CAPTURED_CHARS = 64 * 1024

    /**
     * A child process whose output is captured, never inherited: this process's stdout is the MCP
     * transport. Like [GradleSourceCompiler], it passes [androidSdks]' SDK as `ANDROID_HOME` to a
     * build that cannot see one, such as a worktree with no copy of the untracked
     * `local.properties` (yschimke/compose-ag-plugin#64).
     */
    fun subprocess(
      timeoutMs: Long = TimeUnit.MINUTES.toMillis(15),
      androidSdks: AndroidSdks = AndroidSdks(),
    ) = GradleTaskRunner { projectRoot, wrapper, arguments, onLine ->
      val windows = System.getProperty("os.name").orEmpty().startsWith("Windows")
      val command =
        (if (windows) listOf("cmd", "/c", wrapper.absolutePath) else listOf(wrapper.absolutePath)) +
          arguments
      val process = runCatching {
        ProcessBuilder(command)
          .directory(projectRoot)
          .redirectErrorStream(true)
          .apply {
            androidSdks.forBuild(projectRoot)?.let {
              environment()["ANDROID_HOME"] = it.absolutePath
            }
          }
          .start()
      }
        .getOrElse {
          return@GradleTaskRunner Result(-1, "could not start ${wrapper.path}: ${it.message}")
        }
      process.outputStream.close()
      val output = StringBuilder()
      val reader =
        Thread(
            {
              runCatching {
                process.inputStream.bufferedReader().useLines { lines ->
                  lines.forEach { line ->
                    runCatching { onLine(line) }
                    synchronized(output) {
                      if (output.length < MAX_CAPTURED_CHARS) output.appendLine(line)
                    }
                  }
                }
              }
            },
            "compose-preview-mcp-bootstrap",
          )
          .apply {
            isDaemon = true
            start()
          }
      if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
        process.destroyForcibly()
        return@GradleTaskRunner Result(-1, synchronized(output) { output.toString() }, true)
      }
      reader.join(2_000)
      Result(process.exitValue(), synchronized(output) { output.toString() })
    }
  }
}
