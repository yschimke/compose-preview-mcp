package ee.schimke.composeai.mcp

import ee.schimke.composeai.mcp.protocol.CallToolResult
import ee.schimke.composeai.mcp.protocol.ContentBlock
import ee.schimke.composeai.mcp.protocol.ReadResourceResult
import ee.schimke.composeai.mcp.protocol.ResourceContents
import ee.schimke.composeai.mcp.protocol.ResourceDescriptor
import ee.schimke.composeai.mcp.protocol.ToolDef
import java.io.BufferedWriter
import java.io.File
import java.util.ArrayDeque
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * An Android app running in Robolectric's simulator, driven by the agent: the `simulator_*` tools
 * over `compose-preview android-simulator <apk> --control stdio` (run as its original name,
 * `launch`; compose-ai-tools' `docs/SIMULATOR.md`).
 *
 * - `simulator_launch` starts the CLI on an APK and waits for its `ready` event. The window stays
 *   up unless `headless`, so the person watches the agent work (the CLI draws each tap and swipe
 *   over it); with no display the CLI runs headless anyway.
 * - `simulator_command` sends one control-channel command (tap, swipe, text, back, hierarchy,
 *   startActivity, startService, broadcast, query, …) and returns its result, plus the events the
 *   app produced since the last call (activities resumed, services started, unhandled intents).
 * - `simulator_screenshot` returns the display as an image.
 * - `simulator_logs` returns the tail of the simulator's log (the app's logcat, Robolectric).
 * - `simulator_restart` starts the app again in a fresh simulator JVM, keeping its data unless
 *   asked not to; `simulator_update` does the same with another APK (a rebuild), as `adb install
 *   -r` would. Both are the CLI's own `restart` / `update` commands, so the session, its id and the
 *   window's place survive, and both report where the time went.
 * - `simulator_stop` ends a simulator; all of them end with the server.
 *
 * With `pair`, one simulator id is a phone and its paired watch (`launch --pair`): `device` picks
 * which one a command, screenshot or log read is for, every event carries its `device`, and every
 * log line starts with `[phone]` or `[watch]`.
 *
 * Each simulator is one CLI child process speaking JSON lines; stderr (the app's logs) is kept as a
 * bounded tail for error reports and `simulator_logs`. Opens no socket, so it adds nothing to the
 * layer rule.
 */
class SimulatorMcp(
  /**
   * Starts `compose-preview <args>` in a directory (the build it launches from, else this server's
   * own); a seam so tests need neither the CLI nor a JVM.
   */
  private val start: (List<String>, File?) -> Process = defaultStarter(),
  private val readyTimeoutMs: Long = TimeUnit.MINUTES.toMillis(5),
  private val commandTimeoutMs: Long = TimeUnit.MINUTES.toMillis(1),
  /** Runs `adb <args>`; a seam so tests need no SDK. */
  private val adb: (List<String>, Long) -> AdbRun = defaultAdb(),
  /**
   * Compiles a `simulator_reload`'s edited `files` through the preview build (the server's
   * `notify_file_changed`), so the reload can take the classes it wrote instead of running Gradle.
   * Answers null when every file was compiled and each compile succeeded, else why not (reported as
   * `precompileSkipped`); the seam itself is null when the server has no preview build.
   */
  private val precompile: ((List<String>) -> String?)? = null,
  /**
   * Discovery file for the loopback HTTP sidebar bridge
   * (`~/.cache/composeai/simulator-bridge.json`). Null disables the bridge server (e.g. in unit
   * tests that do not need HTTP).
   */
  private val bridgeFile: File? = SimulatorBridgeServer.defaultBridgeFile(),
  /**
   * Timeout for best-effort initial frame capture embedded into `simulator_launch`'s UI-only
   * `_meta`; 0 disables initial capture.
   */
  private val initialScreenshotTimeoutMs: Long = DEFAULT_INITIAL_SCREENSHOT_TIMEOUT_MS,
  /**
   * How long a running simulator may sit unused before being shut down (`0` = disabled). Defaults
   * to 5 minutes (`composeai.mcp.simulatorIdleTimeoutMs` /
   * `COMPOSE_PREVIEW_SIMULATOR_IDLE_TIMEOUT_MS`).
   */
  private val idleTimeoutMs: Long = defaultIdleTimeoutMs(),
) {

  private val simulators = ConcurrentHashMap<String, Simulator>()
  private val exitedSimulators =
    object : LinkedHashMap<String, Simulator>(16, 0.75f, false) {
      override fun removeEldestEntry(eldest: Map.Entry<String, Simulator>): Boolean =
        size > MAX_EXITED_SIMULATORS
    }
  private val nextId = AtomicLong(1)
  private val bridgeLock = Any()
  @Volatile private var bridgeServer: SimulatorBridgeServer? = null
  private val idleReaperLock = Any()
  @Volatile private var idleReaper: java.util.concurrent.ScheduledExecutorService? = null
  @Volatile private var shutdownCalled = false

  internal val bridgePort: Int?
    get() = bridgeServer?.port

  internal val bridgeToken: String?
    get() = bridgeServer?.authToken

  internal fun readViewerHtml(): String = viewerHtml

  internal fun activeSimulatorSummaries(): List<JsonObject> =
    simulators.values
      .sortedBy { it.id }
      .map { sim ->
        buildJsonObject {
          put("id", sim.id)
          put("apk", sim.apk)
          put("running", true)
          val devices = sim.deviceList()
          if (devices.isNotEmpty()) {
            putJsonArray("devices") { devices.forEach { add(JsonPrimitive(it)) } }
          }
          sim.ready.getNow(null)?.let { put("info", it) }
        }
      }

  private fun syncBridge() {
    val file = bridgeFile ?: return
    synchronized(bridgeLock) {
      if (simulators.isNotEmpty()) {
        val srv = bridgeServer ?: SimulatorBridgeServer(this, file).also { bridgeServer = it }
        srv.writeDiscoveryFile()
      } else {
        bridgeServer?.writeDiscoveryFile()
      }
    }
  }

  fun toolDefs(): List<ToolDef> =
    listOf(
      openTool,
      launchTool,
      commandTool,
      screenshotTool,
      watchfaceSnapshotTool,
      logsTool,
      restartTool,
      updateTool,
      reloadTool,
      adbTool,
      stopTool,
    )

  fun resourceDescriptors(): List<ResourceDescriptor> =
    listOf(
      ResourceDescriptor(
        uri = VIEWER_URI,
        name = "Android Simulator",
        description =
          "Interactive Android Simulator display for tapping, swiping, typing, reloading, and " +
            "inspecting a running app.",
        mimeType = MCP_APP_MIME_TYPE,
        meta = resourceMeta(),
      ),
    )

  fun readResource(uri: String): ReadResourceResult? {
    if (uri != VIEWER_URI) return null
    return ReadResourceResult(
      contents =
        listOf(
          ResourceContents.Text(
            uri = uri,
            mimeType = MCP_APP_MIME_TYPE,
            text = viewerHtml,
            meta = resourceMeta(),
          ),
        ),
    )
  }

  suspend fun handle(name: String, args: JsonObject): CallToolResult? =
    when (LEGACY_NAMES[name] ?: name) {
      OPEN -> open(args)
      LAUNCH -> launch(args)
      COMMAND -> withContext(Dispatchers.IO) { command(args) }
      SCREENSHOT -> withContext(Dispatchers.IO) { screenshot(args) }
      WATCHFACE_SNAPSHOT -> withContext(Dispatchers.IO) { watchfaceSnapshot(args) }
      LOGS -> withContext(Dispatchers.IO) { logs(args) }
      RESTART -> withContext(Dispatchers.IO) { replace(RESTART, "restart", args) }
      UPDATE -> withContext(Dispatchers.IO) { replace(UPDATE, "update", args) }
      RELOAD -> withContext(Dispatchers.IO) { reload(args) }
      ADB -> withContext(Dispatchers.IO) { adb(args) }
      STOP -> withContext(Dispatchers.IO) { stop(args) }
      else -> null
    }

  fun shutdown() {
    shutdownCalled = true
    synchronized(idleReaperLock) {
      idleReaper?.shutdownNow()
      idleReaper = null
    }
    simulators.values.forEach { it.close() }
    simulators.clear()
    synchronized(bridgeLock) {
      bridgeServer?.close()
      bridgeServer = null
    }
  }

  private fun ensureIdleReaperStarted() {
    if (idleTimeoutMs <= 0L || shutdownCalled || idleReaper != null) return
    synchronized(idleReaperLock) {
      if (shutdownCalled || idleReaper != null) return
      val periodMs = minOf(30_000L, maxOf(100L, idleTimeoutMs / 2))
      val exec =
        java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r ->
          Thread(r, "mcp-simulator-idle-reaper").apply { isDaemon = true }
        }
      exec.scheduleWithFixedDelay(
        { runCatching { reapIdleSimulators() } },
        periodMs,
        periodMs,
        TimeUnit.MILLISECONDS,
      )
      idleReaper = exec
    }
  }

  internal fun reapIdleSimulators(nowNanos: Long = System.nanoTime()): Int {
    if (idleTimeoutMs <= 0L) return 0
    val thresholdNanos = TimeUnit.MILLISECONDS.toNanos(idleTimeoutMs)
    var stopped = 0
    for ((id, sim) in simulators.entries.toList()) {
      val idleNanos = nowNanos - sim.lastUsedNanos
      if (idleNanos >= thresholdNanos && simulators.remove(id, sim)) {
        val idleSecs = TimeUnit.NANOSECONDS.toSeconds(idleNanos)
        System.err.println("compose-preview-mcp: stopping idle simulator $id after ${idleSecs}s")
        runCatching { sim.close() }
        stopped++
      }
    }
    if (stopped > 0) {
      syncBridge()
    }
    return stopped
  }

  // -----------------------------------------------------------------------------------------------

  /**
   * Serves [OPEN]: opens or exposes the interactive simulator view in hosts supporting MCP App
   * entrypoints (Antigravity's Auxiliary Pane / side pane and OpenAI / Codex global entrypoints).
   *
   * When no simulator is currently running, returns an idle status (isError = false) so the MCP App
   * opens into its ready/idle state. When a simulator is running, returns its summary and initial
   * screenshot(s) embedded in `_meta`.
   */
  private suspend fun open(args: JsonObject): CallToolResult {
    val simId = args.string("simulator")
    val requestedDevice = args.string("device")
    val simulator =
      if (simId != null) {
        simulators[simId]
      } else {
        simulators.values.singleOrNull() ?: simulators.values.minByOrNull { it.id }
      }

    if (simulator == null || simulator.exited) {
      val summaries = activeSimulatorSummaries()
      val structured = buildJsonObject {
        put("running", false)
        put(
          "message",
          if (simId != null) "Simulator $simId is not running."
          else "No Android simulator is currently running. Use simulator_launch to start an app.",
        )
        if (summaries.isNotEmpty()) {
          putJsonArray("simulators") { summaries.forEach { add(it) } }
        }
      }
      val meta = buildJsonObject {
        putJsonObject(RESULT_META_KEY) {
          put("action", "open")
          put("running", false)
          if (simId != null) put("simulator", simId)
        }
      }
      return ok(structured, meta)
    }

    val initialShots =
      if (initialScreenshotTimeoutMs > 0) {
        withContext(Dispatchers.IO) { captureInitialScreenshots(simulator) }
      } else {
        emptyMap()
      }
    val singlePng = if (simulator.deviceList().isEmpty()) initialShots[""] else null
    val multiShots = if (simulator.deviceList().isNotEmpty()) initialShots else emptyMap()

    val structured = buildJsonObject {
      put("simulator", simulator.id)
      put("running", true)
      put("apk", simulator.apk)
      val devices = simulator.deviceList()
      if (devices.isNotEmpty()) {
        putJsonArray("devices") { devices.forEach { add(JsonPrimitive(it)) } }
      }
      simulator.ready.getNow(null)?.let { put("info", it) }
    }

    val meta =
      simulatorResultMeta(
        simulator,
        action = "open",
        running = true,
        device = requestedDevice,
        pngBase64 = singlePng,
        screenshots = multiShots,
      )

    return ok(structured, meta)
  }

  /**
   * Waits for `ready` without holding a thread, so a host cancelling the call cancels the wait; the
   * child is then closed rather than left running with an id nobody received. The simulator is
   * registered only once launched, after the last point the call can be cancelled.
   */
  private suspend fun launch(args: JsonObject): CallToolResult {
    val project = args.string("project")?.let { File(it).absoluteFile }
    if (project != null && !project.isDirectory) {
      return error("simulator_launch: no such project directory: $project")
    }
    val apk = args.string("apk")
    val adbDevice = args.boolean("adb") == true
    val formFactor = args.string("formFactor")
    val rawWatchface = args.string("watchface")
    if (
      apk == null && project == null && !adbDevice && rawWatchface == null && formFactor == null
    ) {
      return error(
        "simulator_launch: `apk` (a path), `project` (a Gradle build), `watchface`, or " +
          "`formFactor` is required, unless `adb` starts a device with nothing installed.",
      )
    }
    if (adbDevice && project != null) {
      return error(
        "simulator_launch: `adb` runs its own device; drop `project` (or `adb`).",
      )
    }
    val file = apk?.let { File(it).absoluteFile }
    if (file != null && !file.isFile) return error("simulator_launch: no such APK: $file")
    val watchfaceFile =
      rawWatchface?.takeIf { it.endsWith(".apk", ignoreCase = true) }?.let { File(it).absoluteFile }
    if (watchfaceFile != null && !watchfaceFile.isFile) {
      return error("simulator_launch: no such watchface APK: $watchfaceFile")
    }
    val watchfaceArg = watchfaceFile?.path ?: rawWatchface
    val rawDwfRuntime = args.string("dwfRuntime")
    val dwfRuntimeArg =
      when {
        rawDwfRuntime == null -> null
        rawDwfRuntime.equals("auto", ignoreCase = true) -> "auto"
        else -> {
          val runtimeFile = File(rawDwfRuntime).absoluteFile
          if (!runtimeFile.isFile) {
            return error("simulator_launch: no such DWF runtime APK: $runtimeFile")
          }
          runtimeFile.path
        }
      }
    val installs =
      (args["install"] as? JsonArray)
        ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        ?.map { File(it).absoluteFile }
        .orEmpty()
    for (extra in installs) {
      if (!extra.isFile) return error("simulator_launch: no such APK to install: $extra")
    }
    val pair = args.string("pair")?.let { File(it).absoluteFile }
    if (pair != null && !pair.isFile) return error("simulator_launch: no such APK to pair: $pair")
    val cliArgs = buildList {
      // `launch` is the CLI's original name for `android-simulator`, kept there as a trampoline to
      // it. Every CLI release has `launch`, and the launcher found here can be an older one than
      // the release that added `android-simulator`, so this keeps asking for `launch`.
      add("launch")
      file?.let { add(it.path) }
      project?.let { addAll(listOf("--project", it.path)) }
      args.string("module")?.let { addAll(listOf("--module", it)) }
      args.string("variant")?.let { addAll(listOf("--variant", it)) }
      if (args.boolean("live") == true) add("--live")
      when (args.boolean("standby")) {
        true -> add("--standby")
        false -> add("--no-standby")
        null -> {}
      }
      (args["gradleArgs"] as? JsonArray)?.forEach { arg ->
        (arg as? JsonPrimitive)?.contentOrNull?.let { addAll(listOf("--gradle-arg", it)) }
      }
      if (adbDevice) add("--adb")
      formFactor?.let { addAll(listOf("--form-factor", it)) }
      add("--control")
      add("stdio")
      if (args.boolean("headless") == true) add("--headless")
      if (args.boolean("showAgent") == false) add("--hide-agent")
      args.string("qualifiers")?.let { addAll(listOf("--qualifiers", it)) }
      args.string("fontScale")?.let { addAll(listOf("--font-scale", it)) }
      args.int("sdk")?.let { addAll(listOf("--sdk", it.toString())) }
      args.string("network")?.let { addAll(listOf("--network", it)) }
      args.string("java")?.let { addAll(listOf("--java", it)) }
      args.string("dataLayer")?.let { addAll(listOf("--data-layer", it)) }
      args.string("fingerprint")?.let { addAll(listOf("--fingerprint", it)) }
      pair?.let { addAll(listOf("--pair", it.path)) }
      args.string("pairQualifiers")?.let { addAll(listOf("--pair-qualifiers", it)) }
      installs.forEach { addAll(listOf("--install", it.path)) }
      watchfaceArg?.let { addAll(listOf("--watchface", it)) }
      dwfRuntimeArg?.let { addAll(listOf("--dwf-runtime", it)) }
      when (args.boolean("themeFromWatchface")) {
        true -> add("--theme-from-watchface")
        false -> add("--no-theme-from-watchface")
        null -> {}
      }
      args.string("playServices")?.let { addAll(listOf("--play-services", it)) }
      if (args.boolean("launcher") == true) add("--launcher")
      (args["widgets"] as? JsonArray)?.forEach { widget ->
        (widget as? JsonPrimitive)?.contentOrNull?.let { addAll(listOf("--widget", it)) }
      }
      (args["tiles"] as? JsonArray)?.forEach { tile ->
        (tile as? JsonPrimitive)?.contentOrNull?.let { addAll(listOf("--tile", it)) }
      }
      (args["displays"] as? JsonArray)?.forEach { display ->
        (display as? JsonPrimitive)?.contentOrNull?.let { addAll(listOf("--display", it)) }
      }
    }
    val dirAnchor = file ?: watchfaceFile ?: installs.firstOrNull()
    val process =
      try {
        withContext(Dispatchers.IO) { start(cliArgs, launchDirectory(project, dirAnchor)) }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        return error("simulator_launch: could not start compose-preview: ${e.message}")
      }
    val id = "sim-${nextId.getAndIncrement()}"
    val expectedReadies = if (adbDevice) 1 else (if (pair != null) 2 else 1) + installs.size
    val simulator =
      Simulator(
        id,
        file?.name
          ?: watchfaceFile?.name
          ?: project?.name
          ?: if (adbDevice) "device" else (formFactor ?: "app"),
        process,
        devices = if (pair != null && installs.isEmpty()) PAIRED else emptySet(),
        expectedReadies = expectedReadies,
        onExit = { exited ->
          if (simulators.remove(id, exited)) {
            synchronized(exitedSimulators) { exitedSimulators[id] = exited }
            syncBridge()
          }
        },
      )
    val ready =
      try {
        withTimeout(readyTimeoutMs) { simulator.ready.await() }
      } catch (e: TimeoutCancellationException) {
        withContext(Dispatchers.IO) { simulator.close() }
        return notStarted(simulator, "no `ready` within ${readyTimeoutMs / 1000}s")
      } catch (e: CancellationException) {
        withContext(NonCancellable + Dispatchers.IO) { simulator.close() }
        throw e
      } catch (e: Exception) {
        withContext(Dispatchers.IO) { simulator.close() }
        return notStarted(simulator, "$e")
      }
    // With adb and an APK the device boots first and installs it after, as `adb install` would:
    // the launch answers once it is installed (or failed to be).
    val installed =
      if (adbDevice && file != null) {
        try {
          withTimeout(readyTimeoutMs) { simulator.install.await() }
        } catch (e: TimeoutCancellationException) {
          withContext(Dispatchers.IO) { simulator.close() }
          return notStarted(
            simulator,
            "${file.name} not installed within ${readyTimeoutMs / 1000}s",
          )
        } catch (e: CancellationException) {
          withContext(NonCancellable + Dispatchers.IO) { simulator.close() }
          throw e
        } catch (e: Exception) {
          withContext(Dispatchers.IO) { simulator.close() }
          return notStarted(simulator, "$e")
        }
      } else null
    if (adbDevice) {
      // Announced as the device comes up; an older CLI without the event just has no `device`.
      runCatching { withTimeout(DEVICE_EVENT_MS) { simulator.deviceInfo.await() } }
    }
    if (installed?.string("event") == "installFailed") {
      withContext(Dispatchers.IO) { simulator.close() }
      return notStarted(simulator, "installing ${file?.name} failed: ${installed.string("error")}")
    }
    simulator.touch()
    simulators[id] = simulator
    if (simulator.exited && simulators.remove(id, simulator)) {
      synchronized(exitedSimulators) { exitedSimulators[id] = simulator }
    } else {
      ensureIdleReaperStarted()
    }
    syncBridge()
    val events = simulator.drainEvents()
    val result = buildJsonObject {
      put("simulator", id)
      put("info", ready)
      simulator.device?.let { put("device", it) }
      installed?.string("package")?.let { put("installed", it) }
      bridgePort?.let { put("bridgePort", it) }
      // The CLI's `conversion` event: whether the APK's classes came from its cache, and the cost.
      val conversions = events.filter { (it as? JsonObject)?.string("event") == "conversion" }
      if (conversions.isNotEmpty()) {
        put(
          "conversion",
          if (pair == null && installs.isEmpty()) conversions.first() else JsonArray(conversions),
        )
      }
      put("events", events)
    }
    val initialShots =
      if (!simulator.exited && initialScreenshotTimeoutMs > 0L) {
        withContext(Dispatchers.IO) { captureInitialScreenshots(simulator) }
      } else {
        emptyMap()
      }
    val singlePng = if (simulator.deviceList().isEmpty()) initialShots[""] else null
    val multiShots = if (simulator.deviceList().isNotEmpty()) initialShots else emptyMap()
    return ok(
      result,
      simulatorResultMeta(
        simulator,
        action = "launch",
        running = !simulator.exited,
        pngBase64 = singlePng,
        screenshots = multiShots,
      ),
    )
  }

  private fun captureInitialScreenshots(simulator: Simulator): Map<String, String> {
    val devices = simulator.deviceList()
    val targets = if (devices.isEmpty()) listOf<String?>(null) else devices
    val captured = LinkedHashMap<String, String>()
    for (dev in targets) {
      if (simulator.exited) break
      val payload = buildJsonObject {
        put("cmd", "screenshot")
        dev?.let { put("device", it) }
      }
      val reply = simulator.send(payload, initialScreenshotTimeoutMs)
      if (reply is Reply.Ok) {
        reply.result
          .string("png")
          ?.takeIf { it.isNotBlank() }
          ?.let { png -> captured[dev ?: ""] = png }
      }
    }
    return captured
  }

  /**
   * Where the CLI runs: the `project`, else the build holding the APK. The CLI finds the SDK from
   * `local.properties` above its working directory, which for a server started outside the checkout
   * (a Claude Code session opened above it) is not the app's build.
   */
  private fun launchDirectory(project: File?, apk: File?): File? =
    project ?: apk?.parentFile?.let { ProjectDiscovery.enclosingBuild(it) }

  private fun notStarted(simulator: Simulator, cause: String): CallToolResult =
    error(
      "simulator_launch: the simulator did not start ($cause).\n" + simulator.stderrTail(),
    )

  private fun command(args: JsonObject): CallToolResult {
    val simulator = simulator(args) ?: return noSimulator()
    val cmd = args.string("cmd") ?: return error("simulator_command: `cmd` is required.")
    val payload = buildJsonObject {
      for ((key, value) in args) if (key != "simulator") put(key, value)
      put("cmd", cmd)
    }
    return when (val reply = simulator.send(payload, commandTimeoutMs)) {
      is Reply.Ok ->
        ok(
          buildJsonObject {
            put("result", reply.result)
            put("events", simulator.drainEvents())
          },
          simulatorResultMeta(
            simulator,
            action = cmd,
            running = true,
            device = args.string("device"),
          ),
        )
      is Reply.Failed -> error("$cmd: ${reply.message}", simulator.drainEvents())
    }
  }

  private fun screenshot(args: JsonObject): CallToolResult {
    val simulator = simulator(args) ?: return noSimulator()
    val device = args.string("device")
    val payload = buildJsonObject {
      put("cmd", "screenshot")
      args.string("path")?.let { put("path", it) }
      device?.let { put("device", it) }
      args["display"]?.let { put("display", it) }
      args.boolean("square")?.let { put("square", it) }
      args.boolean("app")?.let { put("app", it) }
    }
    return when (val reply = simulator.send(payload, commandTimeoutMs)) {
      is Reply.Failed -> error("screenshot: ${reply.message}", simulator.drainEvents())
      is Reply.Ok -> {
        val png = reply.result.string("png")
        val summary = buildJsonObject {
          for ((key, value) in reply.result) if (key != "png") put(key, value)
          put("events", simulator.drainEvents())
        }
        CallToolResult(
          content =
            listOfNotNull(
              png?.let { ContentBlock.Image(it, "image/png") },
              ContentBlock.Text(summary.toString()),
            ),
          structuredContent = summary,
          meta =
            simulatorResultMeta(
              simulator,
              action = "screenshot",
              running = true,
              device = device,
              pngBase64 = png,
            ),
        )
      }
    }
  }

  /**
   * Combined watch face status + display PNG in one tool call so an agent inspects the active watch
   * face, palette colors, configurations, complication slots, pushed slots, and rendered pixels
   * together.
   */
  private fun watchfaceSnapshot(args: JsonObject): CallToolResult {
    val simulator = simulator(args) ?: return noSimulator()
    val device = args.string("device")
    val statusPayload = buildJsonObject {
      put("cmd", "watchface")
      put("op", "status")
      device?.let { put("device", it) }
    }
    val statusReply =
      when (val reply = simulator.send(statusPayload, commandTimeoutMs)) {
        is Reply.Failed ->
          return error("watchface status: ${reply.message}", simulator.drainEvents())
        is Reply.Ok -> reply.result
      }
    val shotPayload = buildJsonObject {
      put("cmd", "screenshot")
      args.string("path")?.let { put("path", it) }
      device?.let { put("device", it) }
    }
    return when (val shotReply = simulator.send(shotPayload, commandTimeoutMs)) {
      is Reply.Failed -> error("screenshot: ${shotReply.message}", simulator.drainEvents())
      is Reply.Ok -> {
        val png = shotReply.result.string("png")
        val shotMeta = buildJsonObject {
          for ((key, value) in shotReply.result) if (key != "png") put(key, value)
        }
        val summary = buildJsonObject {
          put("watchface", statusReply)
          put("screenshot", shotMeta)
          put("events", simulator.drainEvents())
        }
        CallToolResult(
          content =
            listOfNotNull(
              png?.let { ContentBlock.Image(it, "image/png") },
              ContentBlock.Text(summary.toString()),
            ),
          structuredContent = summary,
          meta =
            simulatorResultMeta(
              simulator,
              action = "watchface_snapshot",
              running = true,
              device = device,
              pngBase64 = png,
            ),
        )
      }
    }
  }

  /**
   * `reload`, after the preview build compiled the edited `files`: the CLI takes the classes
   * already on disk (`compile: false`) and skips its Gradle compile. Anything less certain reloads
   * as before, with Gradle: no `files`, `full`, a file the preview build did not compile or failed
   * to, and a fast reload that failed or found nothing changed (the app's module is not one the
   * preview build compiles). Gradle's answer then carries the compile errors, if there are any.
   */
  private fun reload(args: JsonObject): CallToolResult {
    val files =
      (args["files"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
    val compiler = precompile
    if (files.isEmpty() || compiler == null || args.boolean("full") == true) {
      return replace(RELOAD, "reload", args)
    }
    if (simulator(args) == null) return noSimulator()
    val start = System.nanoTime()
    var skipped: String? =
      try {
        compiler(files)
      } catch (e: Exception) {
        "the preview build's compile threw: ${e.message ?: e}"
      }
    val precompileMs = (System.nanoTime() - start) / 1_000_000
    if (skipped == null) {
      val fast = replace(RELOAD, "reload", args, compile = false)
      val mode = fast.structuredContent?.get("mode")?.let { it as? JsonPrimitive }?.contentOrNull
      if (fast.isError != true && mode != "unchanged") {
        return fast.withPrecompile(precompileMs, skipped = null)
      }
      skipped =
        if (fast.isError == true) {
          "the reload without Gradle failed: " +
            (fast.content.firstOrNull() as? ContentBlock.Text)?.text.orEmpty().take(300)
        } else {
          "the preview build's classes changed nothing the app runs (its module is not the " +
            "app's, or the app is built from another variant)"
        }
    }
    return replace(RELOAD, "reload", args).withPrecompile(precompileMs, skipped)
  }

  private fun CallToolResult.withPrecompile(ms: Long, skipped: String?): CallToolResult {
    val structured = structuredContent ?: return this
    val merged = buildJsonObject {
      for ((key, value) in structured) put(key, value)
      put("precompiled", skipped == null)
      put("precompileMs", ms)
      skipped?.let { put("precompileSkipped", it) }
    }
    return if (isError == true) copy(structuredContent = merged)
    else copy(content = listOf(ContentBlock.Text(merged.toString())), structuredContent = merged)
  }

  /**
   * `restart` / `update`: the CLI replaces the simulator JVM and answers once the new one is ready,
   * so this waits as long as a launch may (an updated APK may convert first).
   */
  private fun replace(
    tool: String,
    cmd: String,
    args: JsonObject,
    compile: Boolean? = null,
  ): CallToolResult {
    val simulator = simulator(args) ?: return noSimulator()
    if (cmd == "update") {
      val apk = args.string("apk") ?: return error("$tool: `apk` (a path) is required.")
      if (!File(apk).absoluteFile.isFile) return error("$tool: no such APK: $apk")
    }
    val payload = buildJsonObject {
      put("cmd", cmd)
      args.string("apk")?.let { put("apk", File(it).absolutePath) }
      args.boolean("keepData")?.let { put("keepData", it) }
      args.boolean("full")?.let { put("full", it) }
      args.boolean("hotswap")?.let { put("hotswap", it) }
      args.string("device")?.let { put("device", it) }
      compile?.let { put("compile", it) }
    }
    return when (val reply = simulator.send(payload, readyTimeoutMs)) {
      is Reply.Ok ->
        ok(
          buildJsonObject {
            for ((key, value) in reply.result) put(key, value)
            put("events", simulator.drainEvents())
          },
          simulatorResultMeta(
            simulator,
            action = cmd,
            running = true,
            device = args.string("device"),
          ),
        )
      is Reply.Failed -> error("$tool: ${reply.message}", simulator.drainEvents())
    }
  }

  /**
   * `adb -s <serial> <args>` against a simulator launched with `adb`: the platform-tools `adb`
   * talks to the device the CLI serves, as it would to an emulator. Waits for the device first, as
   * an adb server started just now finds it by scanning the emulator ports.
   */
  private fun adb(args: JsonObject): CallToolResult {
    val simulator = simulator(args) ?: return noSimulator()
    val device =
      simulator.device
        ?: return error(
          "$ADB: ${simulator.id} is not an adb device: launch it with simulator_launch `adb: true`.",
        )
    val serial = device.string("serial") ?: return error("$ADB: the device has no serial.")
    val argv =
      (args["args"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        ?: args.string("command")?.let(::splitCommand)
        ?: return error(
          "$ADB: `args` (adb's arguments, e.g. [\"install\", \"/path/app.apk\"]) is required.",
        )
    val words = argv.dropWhile { it == "adb" }
    if (words.isEmpty()) return error("$ADB: no adb command given.")
    // The device is this simulator; options that pick another device or server are refused.
    val subcommand = words.firstOrNull { !it.startsWith("-") }
    (words.takeWhile { it.startsWith("-") }.firstOrNull { it in DEVICE_OPTIONS }
        ?: subcommand?.takeIf { it in OTHER_DEVICES })
      ?.let {
        return error("$ADB: `$it` reaches past this device; this tool runs against $serial only.")
      }
    // Its own limit, not the launch's: a first install converts the APK and can take longer.
    val timeout =
      (args.int("timeoutMs")?.toLong() ?: ADB_TIMEOUT_MS).coerceIn(1_000, MAX_ADB_TIMEOUT_MS)
    val wait =
      try {
        adb(listOf("-s", serial, "wait-for-device"), ADB_WAIT_MS)
      } catch (e: IllegalStateException) {
        return error("$ADB: ${e.message}")
      }
    if (wait.exitCode != 0) {
      return error(
        "$ADB: $serial did not come up in adb: ${wait.stderr.ifBlank { wait.stdoutText() }}",
      )
    }
    val run = adb(listOf("-s", serial) + words, timeout)
    val output = args.string("output")?.let { File(it).absoluteFile }
    output?.let {
      it.parentFile?.mkdirs()
      it.writeBytes(run.stdout)
    }
    val result = buildJsonObject {
      put("serial", serial)
      put("command", JsonArray((listOf("adb", "-s", serial) + words).map(::JsonPrimitive)))
      put("exitCode", run.exitCode)
      if (run.timedOut) put("timedOut", true)
      if (output != null) {
        put("output", output.path)
        put("bytes", run.stdout.size)
      } else {
        put("stdout", run.stdoutText().take(MAX_ADB_OUTPUT))
      }
      if (run.stderr.isNotBlank()) put("stderr", run.stderr.take(MAX_ADB_OUTPUT))
      put("events", simulator.drainEvents())
    }
    return if (run.exitCode == 0 && !run.timedOut) ok(result)
    else
      CallToolResult(
        content = listOf(ContentBlock.Text(result.toString())),
        structuredContent = result,
        isError = true,
      )
  }

  private fun logs(args: JsonObject): CallToolResult {
    val simulator =
      simulator(args)
        ?: args.string("simulator")?.let { id ->
          synchronized(exitedSimulators) { exitedSimulators[id] }
        }
        ?: return noSimulator()
    val device = args.string("device")
    val filter = args.string("contains")
    val lines =
      simulator
        .stderrLines()
        .filter { device == null || it.startsWith("[$device] ") }
        .filter { filter == null || it.contains(filter) }
        .takeLast((args.int("lines") ?: DEFAULT_LOG_LINES).coerceIn(1, STDERR_LINES))
    return CallToolResult(
      content = listOf(ContentBlock.Text(lines.joinToString("\n"))),
      structuredContent =
        buildJsonObject {
          put("lines", JsonArray(lines.map(::JsonPrimitive)))
          put("events", simulator.drainEvents())
        },
      meta = simulatorResultMeta(simulator, action = "logs", running = true, device = device),
    )
  }

  private fun stop(args: JsonObject): CallToolResult {
    val id = args.string("simulator") ?: simulators.keys.singleOrNull()
    val simulator = id?.let { simulators.remove(it) } ?: return noSimulator()
    simulator.close()
    syncBridge()
    return ok(
      buildJsonObject { put("stopped", simulator.id) },
      simulatorResultMeta(simulator, action = "stop", running = false),
    )
  }

  /** The named simulator, or the only one running. */
  private fun simulator(args: JsonObject): Simulator? {
    val id = args.string("simulator")
    val found = if (id == null) simulators.values.singleOrNull() else simulators[id]
    found?.touch()
    return found
  }

  private fun noSimulator(): CallToolResult =
    error(
      if (simulators.isEmpty()) "No simulator is running: start one with simulator_launch."
      else "Name the simulator: one of ${simulators.keys.sorted().joinToString()}.",
    )

  // -----------------------------------------------------------------------------------------------

  private sealed interface Reply {
    data class Ok(val result: JsonObject) : Reply

    data class Failed(val message: String) : Reply
  }

  /**
   * One `compose-preview launch --control stdio` (`android-simulator`) child. With [devices] (a
   * `--pair` launch) it is ready once each device has sent its `ready`, and [ready] holds each
   * one's info by device.
   */
  private class Simulator(
    val id: String,
    val apk: String,
    private val process: Process,
    val devices: Set<String> = emptySet(),
    private val expectedReadies: Int = if (devices.isEmpty()) 1 else devices.size,
    private val onExit: (Simulator) -> Unit = {},
  ) {
    val ready = CompletableFuture<JsonObject>()
    @Volatile var exited = false
    @Volatile var lastUsedNanos: Long = System.nanoTime()

    fun touch() {
      lastUsedNanos = System.nanoTime()
    }

    /** With `adb`: the CLI's `device` event (serial, ports). */
    val deviceInfo = CompletableFuture<JsonObject>()

    val device: JsonObject?
      get() = deviceInfo.getNow(null)

    /**
     * All device targets reported by this simulator (`["phone", "watch"]` for `--pair`, or the
     * sorted `ready` device keys for multi-app `--pair` + `--install` launches).
     */
    fun deviceList(): List<String> =
      when {
        devices.isNotEmpty() -> devices.sorted()
        readies.isNotEmpty() -> readies.keys.sorted()
        else -> emptyList()
      }

    /** With `adb` and an APK: its `installed` or `installFailed` event. */
    val install = CompletableFuture<JsonObject>()
    private val readies = ConcurrentHashMap<String, JsonObject>()
    private val pending = ConcurrentHashMap<Long, CompletableFuture<Reply>>()
    private val events = ConcurrentLinkedQueue<JsonElement>()
    private val stderr = ArrayDeque<String>()
    private val stderrDone = CountDownLatch(1)
    private val requestIds = AtomicLong(1)
    private val stdin: BufferedWriter = process.outputStream.bufferedWriter()

    init {
      daemon("simulator-$id-stdout") {
        process.inputStream.bufferedReader().useLines { lines -> lines.forEach(::onLine) }
        // Its stdout closes a moment before the process is reaped: wait, so the exit is reported
        // as its code rather than "still running".
        process.waitFor(EXIT_WAIT_MS, TimeUnit.MILLISECONDS)
        val exitedError = IllegalStateException("the simulator exited (${exitCode()})")
        ready.completeExceptionally(exitedError)
        install.completeExceptionally(exitedError)
        pending.values.forEach {
          it.complete(Reply.Failed("${exitedError.message}\n${stderrTail()}"))
        }
        exited = true
        onExit(this)
      }
      daemon("simulator-$id-stderr") {
        process.errorStream.bufferedReader().useLines { lines ->
          lines.forEach { line ->
            synchronized(stderr) {
              stderr.addLast(line)
              while (stderr.size > STDERR_LINES) stderr.removeFirst()
            }
          }
        }
        stderrDone.countDown()
      }
    }

    private fun onLine(line: String) {
      val message = runCatching { Json.parseToJsonElement(line).jsonObject }.getOrNull() ?: return
      val id = (message["id"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()
      when {
        id != null -> {
          val reply =
            if ((message["ok"] as? JsonPrimitive)?.booleanOrNull == true) {
              Reply.Ok(message["result"] as? JsonObject ?: JsonObject(emptyMap()))
            } else {
              Reply.Failed((message["error"] as? JsonPrimitive)?.contentOrNull ?: "failed")
            }
          pending.remove(id)?.complete(reply)
        }
        message.string("event") == "ready" -> onReady(message)
        message.string("event") == "device" ->
          deviceInfo.complete(JsonObject(message.filterKeys { it != "event" }))
        message.string("event") == "installed" || message.string("event") == "installFailed" -> {
          install.complete(message)
          events.add(message)
        }
        message.containsKey("event") -> {
          events.add(message)
          while (events.size > MAX_EVENTS) events.poll()
        }
      }
    }

    private fun onReady(message: JsonObject) {
      val info = JsonObject(message.filterKeys { it != "event" })
      if (devices.isEmpty() && expectedReadies <= 1) {
        ready.complete(info)
        return
      }
      val device = message.string("device") ?: return
      readies[device] = JsonObject(info.filterKeys { it != "device" })
      if (devices.isNotEmpty()) {
        if (readies.keys.containsAll(devices)) {
          ready.complete(JsonObject(devices.associateWith { readies.getValue(it) }))
        }
      } else if (readies.size >= expectedReadies) {
        ready.complete(JsonObject(readies.toSortedMap()))
      }
    }

    fun send(command: JsonObject, timeoutMs: Long): Reply {
      if (!process.isAlive) return Reply.Failed("the simulator exited (${exitCode()})")
      val requestId = requestIds.getAndIncrement()
      val future = CompletableFuture<Reply>()
      pending[requestId] = future
      val line = JsonObject(command + ("id" to JsonPrimitive(requestId))).toString()
      try {
        synchronized(stdin) {
          stdin.write(line)
          stdin.newLine()
          stdin.flush()
        }
        return future.get(timeoutMs, TimeUnit.MILLISECONDS)
      } catch (e: TimeoutException) {
        return Reply.Failed("no reply within ${timeoutMs / 1000}s")
      } catch (e: Exception) {
        return Reply.Failed("$e\n${stderrTail()}")
      } finally {
        pending.remove(requestId)
      }
    }

    fun drainEvents(): JsonArray {
      val drained = mutableListOf<JsonElement>()
      while (true) drained += events.poll() ?: break
      return JsonArray(drained)
    }

    fun stderrLines(): List<String> = synchronized(stderr) { stderr.toList() }

    /** The last lines of stderr; once the process has exited, all of them. */
    fun stderrTail(): String {
      if (!process.isAlive) stderrDone.await(2, TimeUnit.SECONDS)
      return synchronized(stderr) { stderr.toList() }.takeLast(STDERR_REPORTED).joinToString("\n")
    }

    private fun exitCode(): String =
      if (process.isAlive) "still running" else "exit ${process.exitValue()}"

    /** Closing stdin asks the simulator to exit; anything still running after that is ended. */
    fun close() {
      runCatching { stdin.close() }
      if (!process.waitFor(5, TimeUnit.SECONDS)) {
        process.descendants().forEach { it.destroy() }
        process.destroy()
        if (!process.waitFor(5, TimeUnit.SECONDS)) {
          process.descendants().forEach { it.destroyForcibly() }
          process.destroyForcibly()
        }
      }
    }

    private fun daemon(name: String, body: () -> Unit) {
      Thread(body, name).apply {
        isDaemon = true
        start()
      }
    }
  }

  // -----------------------------------------------------------------------------------------------

  private fun ok(result: JsonObject, meta: JsonObject? = null) =
    CallToolResult(
      content = listOf(ContentBlock.Text(result.toString())),
      structuredContent = result,
      meta = meta,
    )

  private fun simulatorResultMeta(
    simulator: Simulator,
    action: String,
    running: Boolean,
    device: String? = null,
    pngBase64: String? = null,
    screenshots: Map<String, String> = emptyMap(),
  ): JsonObject = buildJsonObject {
    putJsonObject(RESULT_META_KEY) {
      put("simulator", simulator.id)
      put("apk", simulator.apk)
      put("action", action)
      put("running", running)
      val devices = simulator.deviceList()
      if (devices.isNotEmpty()) {
        putJsonArray("devices") { devices.forEach { add(JsonPrimitive(it)) } }
      }
      device?.let { put("device", it) }
      pngBase64?.let { put("pngBase64", it) }
      if (screenshots.isNotEmpty()) {
        putJsonObject("screenshots") {
          for ((dev, png) in screenshots) {
            put(dev, png)
          }
        }
      }
    }
  }

  private fun resourceMeta(): JsonObject =
    OpenAiUi.withDisplayModes(
      buildJsonObject { putJsonObject("ui") { put("prefersBorder", true) } },
    )

  /**
   * `simulator_open` carries MCP App and entrypoint metadata: `ui.resourceUri` plus
   * `com.google.antigravity/ui.entrypoints = [{type: "aux_pane"}]` for Antigravity's side pane and
   * `openai/ui.entrypoints = [{type: "global"}]` for ChatGPT / Codex.
   */
  private val openToolMeta: JsonObject by lazy {
    buildJsonObject {
      putJsonObject("ui") {
        put("resourceUri", VIEWER_URI)
        putJsonArray("visibility") {
          add(JsonPrimitive("model"))
          add(JsonPrimitive("app"))
        }
      }
      put("ui/resourceUri", VIEWER_URI)
      putJsonObject(OpenAiUi.ANTIGRAVITY_UI_KEY) {
        putJsonArray("entrypoints") {
          addJsonObject {
            put("type", "aux_pane")
            put("title", "Android Simulator")
          }
        }
      }
      putJsonObject(OpenAiUi.UI_KEY) {
        putJsonArray("entrypoints") { addJsonObject { put("type", "global") } }
      }
    }
  }

  private val openTool =
    ToolDef(
      name = OPEN,
      title = "Android simulator: open interactive view",
      icons = listOf(OpenAiUi.svgIcon(SIMULATOR_ICON_SVG)),
      meta = openToolMeta,
      description =
        "Open the interactive Android Simulator MCP App in the side pane or full view. " +
          "Shows the active simulator session (or an idle screen if none is running) and " +
          "allows tapping, swiping, typing, and inspecting. Takes no arguments (optional " +
          "`simulator` id or `device` when multiple run).",
      inputSchema =
        schema(
          """
          {"type":"object","properties":{
            "simulator":{"type":"string","description":"Simulator id; optional when only one runs."},
            "device":{"type":"string","description":"With a paired launch: which device's display (default phone/primary)."}
          }}
          """,
        ),
      readOnlyHint = true,
    )

  /**
   * Only `simulator_launch` links `ui.resourceUri` so MCP Apps hosts open one interactive simulator
   * view per launch instead of spawning duplicate cards on every follow-up command, screenshot, or
   * log call.
   */
  private val launchToolMeta: JsonObject by lazy {
    OpenAiUi.appToolMeta(
      VIEWER_URI,
      entrypoints = emptyList(),
      visibility = listOf("model", "app"),
    )
  }

  private val appCallableToolMeta: JsonObject
    get() = APP_CALLABLE_TOOL_META

  private fun error(message: String, events: JsonArray? = null): CallToolResult {
    val structured =
      events?.takeIf { it.isNotEmpty() }?.let { buildJsonObject { put("events", it) } }
    return errorCallToolResult(message, structured)
  }

  private val launchTool =
    ToolDef(
      name = LAUNCH,
      title = "Android simulator: launch an APK",
      icons = listOf(OpenAiUi.svgIcon(SIMULATOR_ICON_SVG)),
      meta = launchToolMeta,
      description =
        "Run an Android APK in Robolectric's simulator (no emulator or device) and return a " +
          "simulator id plus the app's package, display size and first activity. The first " +
          "launch of an APK converts its dex (tens of seconds; cached by content after that). " +
          "The person sees the app in a window, with each tap and swipe you send drawn over " +
          "it, unless `headless`. Drive it with simulator_command and simulator_screenshot; " +
          "end it with simulator_stop. Needs Java 21+ and a local Android SDK. With `project` " +
          "(the app's Gradle build) the APK is optional, and simulator_reload delivers an " +
          "edit in seconds by compiling only the classes. With " +
          "`dataLayer: agent` the app's Wearable Data Layer is simulated and you play its " +
          "peer devices (simulator_command dataLayer). With `pair` (a Wear OS APK) a phone " +
          "and its watch run paired over a simulated Data Layer under one simulator id: " +
          "`info` then holds each device's, `device: watch` sends a command or screenshot to " +
          "the watch, every event carries its `device`, and log lines start [phone] or [watch]. " +
          "With `install` extra APKs (such as a ComplicationDataSourceService or Watch Face " +
          "Push app) run beside the main APK on the same device bus. " +
          "With `launcher` a home screen sits under the app: HOME, icons, shortcuts and the " +
          "app's own widgets (simulator_command home, widget, …); `widgets` starts with some " +
          "placed. On a Wear app `launcher` is the watch's system UI instead: watch face " +
          "(androidx WatchFaceService and Declarative / Watch Face Format faces, either via " +
          "`dwfRuntime` or built-in WFF compilation, plus Watch Face Push), launcher, " +
          "notification stream, tiles and widgets carousel, quick settings; `watchface` sets " +
          "the initial watch face APK or component, `themeFromWatchface` applies watch face " +
          "palette colors to dynamic Material 3 system colors, `tiles` starts with tiles or " +
          "Wear Widgets in the carousel, and simulator_watchface_snapshot captures watch face " +
          "status and pixels together. " +
          "With `adb` the simulator is a device adb and Android Studio see (emulator-5554 and " +
          "up), with or without an APK: drive it with simulator_adb as well.",
      inputSchema =
        schema(
          """
          {"type":"object","properties":{
            "apk":{"type":"string","description":"Absolute path to the APK (a debug build works best). Optional with `project`, `watchface`, `formFactor`, or `adb`."},
            "project":{"type":"string","description":"The app's Gradle build (absolute path), for simulator_reload: edit, then reload without packaging an APK."},
            "module":{"type":"string","description":"With project: the app module (default app)."},
            "variant":{"type":"string","description":"With project: the variant (default debug)."},
            "live":{"type":"boolean","description":"With project: reload by itself whenever a source file is saved; each reload is a `reload` event."},
            "gradleArgs":{"type":"array","items":{"type":"string"},"description":"With project: extra Gradle arguments, e.g. --max-workers=2."},
            "standby":{"type":"boolean","description":"Keep a second simulator JVM prepared up to the app's own code, so simulator_restart and simulator_reload take seconds. Default on with project, off otherwise; it costs a second JVM's memory."},
            "headless":{"type":"boolean","description":"No window. Default false: the person can watch."},
            "showAgent":{"type":"boolean","description":"Draw your taps, swipes and commands over the window. Default true."},
            "qualifiers":{"type":"string","description":"Display resource qualifiers, Robolectric syntax, e.g. w411dp-h891dp-xxhdpi-night. Default w360dp-h640dp-hdpi."},
            "fontScale":{"type":"string","description":"Font scale, e.g. 1.3."},
            "sdk":{"type":"integer","description":"Android API level to simulate (default 35)."},
            "network":{"type":"string","description":"Start on wifi (default), cellular, ethernet, vpn, captive (no internet) or offline; append ,airplane (or just airplane) for airplane mode."},
            "java":{"type":"string","description":"JDK 21+ home or java binary, when the default JDK is older."},
            "dataLayer":{"type":"string","enum":["agent"],"description":"Simulate the Wearable Data Layer with you as the peer: what the app sends arrives as datalayer events of kind send; simulator_command dataLayer {action} connects a peer, puts data items, sends messages and replies."},
            "fingerprint":{"type":"string","description":"Build.FINGERPRINT in place of Robolectric's 'robolectric', which some apps check to skip real work under tests."},
            "pair":{"type":"string","description":"Absolute path to a Wear OS APK to run as this phone app's paired watch."},
            "pairQualifiers":{"type":"string","description":"The paired watch's display qualifiers (default a large round watch)."},
            "install":{"type":"array","items":{"type":"string"},"description":"Additional APKs to install on the same simulated device (shared DeviceBus), e.g. a complication provider or Watch Face Push app."},
            "watchface":{"type":"string","description":"Wear: initial watch face to activate on launch — an APK path, a package name, or a WatchFaceService component (pkg/.Cls). Implies launcher on Wear."},
            "dwfRuntime":{"type":"string","description":"Wear Declarative Watch Face runtime APK path, or 'auto' to use a cached/pulled DeclarativeWatchFaceRuntime.apk and fall back to built-in WFF compilation."},
            "themeFromWatchface":{"type":"boolean","description":"Wear: apply active watch face palette colors to Android dynamic Material 3 system colors (system_accent1..3, system_neutral1..2, system_primary_dark, etc.). Default true on Wear."},
            "adb":{"type":"boolean","description":"Serve the simulator to adb and Android Studio as an emulator (emulator-5554 and up): `apk` becomes optional (the device boots to its home screen or watch face), an `apk` given is installed rather than started, and simulator_adb runs adb commands against it. The result's `device` holds its serial and ports. Not with project or pair."},
            "formFactor":{"type":"string","enum":["phone","wear","glimmer","xr"],"description":"The device's form factor; detected from the APK when there is one. With no apk, wear boots a watch."},
            "playServices":{"type":"string","enum":["installed","none","outdated","disabled","invalid"],"description":"Google Play services as the app's availability checks see it: installed (default, SUCCESS), or none / outdated / disabled / invalid to test the app's error paths. simulator_command playServices {state} changes it while the app runs."},
            "launcher":{"type":"boolean","description":"A system UI under the app. On a phone, a home screen: HOME and Back from the app's root go there, its icons reopen the app, a long press shows the app's shortcuts, and the app's widgets are placed, sized, updated and tapped there (simulator_command home, drawer, shortcuts, shortcut, widgets, widget, picker). On a Wear app, the watch's: a watch face, launcher, notification stream, tiles and widgets carousel and quick settings (simulator_command home, launcher, notifications, notification, ongoing, tiles, watchface, health, clock, weather, accel, quickSettings)."},
            "widgets":{"type":"array","items":{"type":"string"},"description":"Widget providers (a receiver class or its short name) to place on the home screen at launch: the app starts once, then the home screen shows with them. Implies launcher."},
            "tiles":{"type":"array","items":{"type":"string"},"description":"Wear: tile or Wear Widget services (a class or its short name) in the carousel at launch. Implies launcher."},
            "displays":{"type":"array","items":{"type":"string"},"description":"Secondary displays to attach at launch, e.g. '1280x720,name=TV'."}
          }}
          """,
        ),
    )

  private val commandTool =
    ToolDef(
      name = COMMAND,
      title = "Android simulator: drive the app",
      meta = appCallableToolMeta,
      description =
        "Send one command to a running simulator and get its result plus the events the app " +
          "produced since the last call (activity resumed/destroyed, service started, intents " +
          "nothing handled, watchfaceColors). Arguments sit beside `cmd`. Commands: info; hierarchy {flat?} " +
          "(views and Compose semantics with text, desc, resourceId, bounds, flags); find " +
          "{selector}; tap {x,y | selector, index?}; longPress {… durationMs?}; swipe " +
          "{x1,y1,x2,y2,durationMs?}; key {key: BACK|ENTER|…}; back; text {text}; launch; " +
          "finish; activities; startActivity {intent}; startService/stopService/bindService/" +
          "unbindService {intent}; services; broadcast {intent}; notifications; query {uri, " +
          "projection?, selection?, args?, sort?, limit?}; components; qualifiers {value: " +
          "+land|+night|…}; permissions {policy?: grant|deny|ask, grant?, deny?, answer?, " +
          "requestId?} (how the app's runtime permission requests are answered, granted by " +
          "default, as a device's permission controller does: already granted, not in the " +
          "manifest and denied twice are not asked; grant/deny name permissions and apply now " +
          "too; under ask a request waits as a permissions event {state: waiting, requestId, " +
          "ask} until answer: grant|deny|dismiss|approximate|selected or {permission: " +
          "grant|deny|dismiss}; reset), wait {ms}. adb equivalents: shell {command: " +
          "'am start -a VIEW -d <uri>' | 'pm grant <pkg> <perm>' | 'input tap x y' | " +
          "'settings put global airplane_mode_on 1' | 'content query --uri …' | 'logcat -d -s Tag' " +
          "| 'dumpsys activity' | 'wm size' | 'cmd uimode night yes' | 'run-as <pkg> ls' …} (or " +
          "args: argv) runs the matching command and returns {command, result}; structured forms: " +
          "settings {op: get|put|delete, namespace: system|secure|global, key, value?}; logcat " +
          "{op: dump|clear|follow|stop, filters? {tag: V…S}, default?, regex?, tail?} (follow " +
          "streams log events); files {op: list|read|write|mkdir|delete|pull|push, path (relative " +
          "to the data dir, /data/data/<pkg>/…, /sdcard/…), to?, from?, text?, recursive?}; " +
          "content {op: query|insert|update|delete, uri, values?, selection?, args?}; network " +
          "{mode?: wifi|cellular|ethernet|vpn|captive|offline, wifi?, data?, airplane?, " +
          "metered?, cellular?: 5g|lte|3g|2g, latencyMs?, kbps?, block? [hosts], dataSaver?} " +
          "(ConnectivityManager, its callbacks and the app's real traffic: offline, connections " +
          "fail as on a device); wifi {op: status|scan|connect|disconnect|request, networks? " +
          "[{ssid, rssi?, security?}], ssid?, answer?: accept|reject, policy?: accept|reject|ask} " +
          "(a WifiNetworkSpecifier request is a wifiRequest event); bluetooth {op: status|enable|" +
          "disable|request|advertise|found|remove|bond|unbond|notify|disconnect, address?, name?, " +
          "rssi?, serviceUuids?, manufacturerData? {companyId: hex}, serviceData?, gatt? " +
          "{services: [{uuid, characteristics: [{uuid, properties, value?}]}]}, characteristic?, " +
          "value? (hex or {text}), answer?, policy?} (BLE peripherals for the app's scans, a GATT " +
          "server whose writes arrive as gatt events; ACTION_REQUEST_ENABLE is a " +
          "bluetoothRequest event); display {size?: WxH|reset, density?: N|reset}; battery " +
          "{level?: 0..100, charging?, plugged?: ac|usb|wireless|none, powerSave?}; location " +
          "{lat, lng, accuracy?, altitude?, bearing?, speed?}; timezone {value}; forceStop; " +
          "clearData; kill (the app killed in the background, its state saved through a Parcel; the " +
          "next launch or startActivity re-creates the task from it); recreate (the top activity, as " +
          "Activity.recreate()); savedState (the top activity's state: bundle JSON and base64 " +
          "savedState, which launch / startActivity {savedState} take back); zoom {value?: in|out|" +
          "actual|fit|'75%'|0.75} (the simulator window's zoom, not the display: screenshots and " +
          "coordinates stay in device pixels; reports scale, sizes and screenX/screenY; errors " +
          "headless); getprop {name?}; package; packages. Wearable Data Layer (launched with " +
          "dataLayer: agent): dataLayer (state: node, peers, capabilities, data items); " +
          "dataLayer {action: connect {node?, name?, package?, capabilities?, nearby?} | " +
          "disconnect {node?} | capabilities {node?, capabilities} | put {node?, path, text|data|" +
          "dataMap, assets?} | delete {node?, path} | message {node?, path, text|data|dataMap} | " +
          "request {…} (the app's answer is a send event of type response) | reply {requestId, " +
          "text|data|dataMap|error; result for a remote activity; responseUrl for OAuth} | config " +
          "{requestTimeoutMs} | channelOpen {node?, path, channel?} (a ChannelClient channel " +
          "to the app; answers its channel token) | channelWrite {channel, text|data|dataMap} | " +
          "channelEof {channel} (ends the app's input) | channelClose {channel, errorCode?}}; " +
          "addCapability/removeCapability {name} on the app's own node. data " +
          "is base64; dataMap is JSON (string, boolean and int values bare, others " +
          "{type: long|float|double|bytes|string[]|stringList|intList|dataMapList|list, value}). " +
          "What the app sends is a " +
          "datalayer event {kind: send, type: hello|message|response|data|capabilities|activity|" +
          "oauth|channelOpen|channelData|channelEof|channelClose, to, path, requestId, token, " +
          "data, text, dataMap, assets (key: base64, as put takes them)}; channels are also " +
          "datalayer events {kind: channel, state: opened|data|inputClosed|outputClosed|closed, " +
          "channel, path}. companion {policy?: accept|reject|ask, answer?: accept|reject, " +
          "requestId?, address?, disassociate? (id)}: CompanionDeviceManager's associations (each " +
          "data-layer peer is one) and how associate() is answered; with ask, a companion event " +
          "{state: chooser, request {requestId, candidates}} waits for the answer. Screen power (Wear ambient): screen {state?: on|ambient|off, timeoutMs?, " +
          "alwaysOn?, burnIn?, lowBit?} (isInteractive, Display.getState, SCREEN_ON/OFF; an " +
          "ambient-aware app gets onEnterAmbient/onUpdateAmbient/onExitAmbient, any other app " +
          "is stopped until wake); ambient {value?, op?: update, burnIn?, lowBit?}; tilt / wake (wrist-tilt " +
          "gesture); palm / sleep; key POWER|SLEEP|WAKEUP. A touch on a sleeping screen only " +
          "wakes it. Home screen (launched with launcher): home (key HOME too; the app stops, " +
          "nothing is destroyed; returns the grid and what is on it); drawer {action?: " +
          "open|close}; shortcuts {action?: list|open, activity?}; shortcut {shortcutId}; widgets " +
          "(the app's providers with spans, resize mode, configure, preview, and the widgets " +
          "placed); widget {action: add {provider, cellX?, cellY?, spanX?, spanY?, configure?} " +
          "(runs its configure activity when it has one) | remove {widgetId} | resize " +
          "{widgetId, spanX, spanY} | update {widgetId?} (APPWIDGET_UPDATE) | reconfigure " +
          "{widgetId} | pin {answer: accept|reject} (answers the app's requestPinAppWidget)}; " +
          "picker {action?: open|close}. Home views carry sim:id/… resource ids (icon, widget, " +
          "widget_resize, drawer_icon, shortcut, picker_preview, pin_accept, …) and a widget's " +
          "own views are tappable by text. info adds surface: app|home|drawer|picker; events " +
          "home, widget {state: bound|configuring|configured|placed|updated|clicked|resized|" +
          "dataChanged|removed|pinRequested, widgetId, reason} and shortcut. Wear system UI " +
          "(a Wear app launched with launcher, tiles, or watchface): home (the watch face; key HOME too); " +
          "launcher {index? | component? | label?} (opens the app list, or launches one; key " +
          "STEM_PRIMARY too); notifications {op?: open|close|clear} (without op, every active " +
          "notification with key, channel, extras, actions, ongoingActivity, and shown: false " +
          "with hiddenBecause when a watch would drop it); notification {key, op: tap|action|" +
          "reply|dismiss|snooze, index?, text?, ms?} (any form factor; reply fills the " +
          "action's RemoteInput with text); ongoing {op?: list|tap, index?}; tiles {op: list|" +
          "add|remove|show|next|prev|refresh, component?, index?} (the carousel; tiles are " +
          "rendered by the protolayout renderer, widgets by the Remote Compose player); " +
          "watchface {op?: status|set|unset|render|config|complication|uncomplication|tap|theme|" +
          "push|pushed|removePush, component?, watchface?, slot?, type?, text?, title?, value?, " +
          "min?, max?, config?, flavor?, apk?, token?, activate?, slotId?, themeFromWatchface?, " +
          "timeMillis?, x?, y?} (hosts androidx WatchFaceService and Declarative / Watch Face " +
          "Format faces, binds local or --install cross-app ComplicationDataSourceService " +
          "providers or inline fake complications, drives user style/flavor configurations, " +
          "inspects and applies watch face palette colors, and manages Watch Face Push slots); " +
          "clock {time? (ISO-8601 or HH:mm[:ss]), timeMillis?, timeZone?, is24Hour?, frozen?, " +
          "rate?, dstMode?, reset?} (pins or scales watch time); health {heartRate?, " +
          "heartRateAccuracy?, steps?, stepGoal?, distanceMeters?, calories?, floors?} (feeds " +
          "watch faces, SensorManager listeners, tiles and widgets); weather {available?, " +
          "condition?, temperature?, temperatureHigh?, temperatureLow?, temperatureUnit?, " +
          "chanceOfPrecipitation?, uvIndex?, hours?, days?} (feeds WFF [WEATHER.*] expressions); " +
          "accel {x?, y?, z?} (feeds WFF gyro/tilt expressions and accelerometer listeners); " +
          "quickSettings {airplane?, wifi?}; rotary {delta? (-1), " +
          "count? (1), intervalMs? (20)} turns the crown. System views carry sim:id/… ids " +
          "(notification_card, notification_action, notification_reply, launcher_item, " +
          "ongoing_chip, tile, widget, qs_airplane, …) with the item's identity as desc; info " +
          "adds surface: watchface|launcher|notifications|tiles|quickSettings|app, and every " +
          "app gets notification events {state: posted|updated|removed, key}. " +
          "A selector is text/desc (exact), " +
          "textContains/" +
          "descContains, resourceId (a Compose testTag when exposed), cls, clickable. An intent " +
          "is {action, data, type, package, component ('.Cls' or 'pkg/cls'), categories, " +
          "flags ([\"CLEAR_TOP\"]…), extras}. UI-changing commands answer after settleMs " +
          "(default 250) so the next screenshot shows their effect.",
      inputSchema =
        schema(
          """
          {"type":"object","required":["cmd"],"properties":{
            "simulator":{"type":"string","description":"Simulator id from simulator_launch; optional when only one runs."},
            "device":{"type":"string","description":"With a paired or multi-app (`install`) launch: which device or package runs the command (e.g. phone, watch, watch/<pkg>, or <pkg>)."},
            "cmd":{"type":"string","enum":["info","hierarchy","find","tap","longPress","swipe","touch","key","back","text","launch","finish","activities","startActivity","startService","stopService","bindService","unbindService","services","broadcast","notifications","query","components","qualifiers","permissions","biometric","speech","credentials","clipboard","alarms","jobs","downloads","pick","assistant","appfunctions","wait","ping","dataLayer","companion","shell","settings","logcat","files","content","network","wifi","bluetooth","display","displays","resize","device","rotate","posture","fontScale","split","foreground","battery","location","timezone","forceStop","clearData","kill","recreate","savedState","snapshotData","getprop","package","packages","screen","ambient","tilt","wake","palm","sleep","playServices","home","drawer","shortcuts","shortcut","widgets","widget","picker","launcher","notification","ongoing","tiles","watchface","clock","health","weather","accel","quickSettings","rotary","zoom"]},
            "text":{"type":"string"},"textContains":{"type":"string"},"desc":{"type":"string"},"descContains":{"type":"string"},
            "resourceId":{"type":"string"},"cls":{"type":"string"},"clickable":{"type":"boolean"},"index":{"type":"integer"},
            "x":{"type":"number"},"y":{"type":"number"},"z":{"type":"number"},"x1":{"type":"number"},"y1":{"type":"number"},"x2":{"type":"number"},"y2":{"type":"number"},
            "durationMs":{"type":"integer"},"steps":{"type":"integer","description":"swipe: its touch steps; health: steps today"},"stepGoal":{"type":"integer","description":"health: daily step goal (default 10000)"},"ms":{"type":"integer"},"settleMs":{"type":"integer"},"until":{"type":"string","enum":["still"],"description":"wait: wait until the screen stops changing"},"quietMs":{"type":"integer","description":"wait until still: quiet window in ms (default 400)"},
            "key":{"type":["string","integer","array"]},"flat":{"type":"boolean"},"value":{"type":["string","number","boolean","object","array"]},
            "intent":{"type":"object"},"savedState":{"type":"string","description":"launch / startActivity: base64 instance state from the savedState command; a new instance is created from it."},"uri":{"type":"string"},"projection":{"type":"array","items":{"type":"string"}},
            "selection":{"type":"string"},"args":{"type":"array","items":{"type":"string"}},"sort":{"type":"string"},"limit":{"type":"integer"},
            "policy":{"type":"string","enum":["grant","deny","accept","reject","ask","succeed","cancel","auto"],"description":"permissions: grant|deny|ask; wifi/bluetooth/companion: accept|reject|ask; biometric: ask|succeed|cancel; speech/pick: ask|cancel; credentials: auto|ask|cancel"},"grant":{"type":"array","items":{"type":"string"}},"deny":{"type":"array","items":{"type":"string"}},"reset":{"type":"boolean"},
            "command":{"type":"string","description":"shell: an adb shell command line"},
            "op":{"type":"string"},"namespace":{"type":"string","enum":["system","secure","global"]},
            "path":{"type":"string"},"to":{"type":"string"},"from":{"type":"string"},"recursive":{"type":"boolean"},
            "values":{"type":"object"},"filters":{"type":"object"},"default":{"type":"string"},"regex":{"type":"string"},"tail":{"type":"integer"},
            "wifi":{"type":"boolean"},"airplane":{"type":"boolean"},"data":{"type":["boolean","string"],"description":"network: mobile data on/off; dataLayer: payload bytes, base64"},
            "mode":{"type":"string","enum":["wifi","cellular","ethernet","vpn","captive","offline"]},"metered":{"type":["boolean","null"]},
            "cellular":{"type":"string","enum":["5g","lte","3g","2g"]},"downKbps":{"type":"integer"},"upKbps":{"type":"integer"},
            "latencyMs":{"type":"integer"},"kbps":{"type":"integer"},"block":{"type":"array","items":{"type":"string"}},"dataSaver":{"type":"boolean"},
            "networks":{"type":"array","items":{"type":"object"}},"ssid":{"type":"string"},"bssid":{"type":"string"},"rssi":{"type":"integer"},
            "answer":{"anyOf":[{"type":"string","enum":["accept","reject","grant","deny","dismiss","approximate","selected","succeed","fail","cancel","negative","lockout","save","none"]},{"type":"integer"},{"type":"object"}]},"requestId":{"type":"integer","description":"wifi/bluetooth/permissions/companion/biometric/speech/credentials/pick request: which waiting request to answer (default the oldest); dataLayer reply: the app request it answers"},
            "enrolled":{"type":"boolean","description":"biometric: whether biometrics are enrolled"},"secure":{"type":"boolean","description":"biometric: whether device has a screen lock"},
            "cancel":{"type":"boolean","description":"speech / pick: cancel the waiting request"},"save":{"type":"object","description":"credentials: save {id, password}"},"forget":{"type":"string","description":"credentials: forget saved id"},"clear":{"type":"boolean","description":"clipboard: clear primary clip"},
            "fire":{"type":["integer","string"],"description":"alarms: index or 'next' to fire now"},"exact":{"type":"boolean","description":"alarms: grant or revoke exact alarm permission"},"run":{"type":"integer","description":"jobs: job id to start now"},
            "files":{"type":"array","items":{"type":"string"},"description":"pick: device or host file paths"},"invoke":{"type":"string","description":"assistant: App Actions capability to invoke"},"params":{"type":"object","description":"assistant / appfunctions: parameters JSON"},"execute":{"type":"string","description":"appfunctions: function id to execute"},"service":{"type":"string","description":"appfunctions: service class name"},
            "display":{"type":["integer","string"],"description":"Target display id (or 'all' for screenshot)"},"widthDp":{"type":"integer"},"heightDp":{"type":"integer"},"posture":{"type":"string"},"orientation":{"type":"string","enum":["vertical","horizontal"]},"hingeDp":{"type":"integer"},"qualifiers":{"type":"string"},
            "address":{"type":"string","description":"bluetooth: a device address, AA:BB:CC:DD:EE:FF"},
            "serviceUuids":{"type":"array","items":{"type":"string"}},"serviceData":{"type":"object"},"manufacturerData":{"type":"object"},
            "txPower":{"type":"integer"},"connectable":{"type":"boolean"},"gatt":{"type":"object"},"characteristic":{"type":"string"},"size":{"type":"string"},"density":{"type":["string","integer"]},
            "name":{"type":"string"},"package":{"type":"string"},"holdMs":{"type":"integer"},"longPress":{"type":"boolean"},
            "action":{"type":"string","enum":["connect","disconnect","capabilities","put","delete","message","request","reply","config","channelOpen","channelWrite","channelEof","channelClose","add","remove","resize","update","reconfigure","pin","open","close","list","change","off","down","move","up"],"description":"dataLayer, widget, drawer, picker, shortcuts, displays, split, touch action"},
            "provider":{"type":"string","description":"widget add: the widget provider, a receiver class or its short name"},"widgetId":{"type":"integer","description":"widget: which placed widget (id is the command's own)"},
            "cellX":{"type":"integer"},"cellY":{"type":"integer"},"spanX":{"type":"integer"},"spanY":{"type":"integer"},"configure":{"type":"boolean","description":"widget add: run the configure activity even when the provider makes it optional"},
            "shortcutId":{"type":"string","description":"shortcut: which app shortcut to open"},
            "component":{"type":"string","description":"tiles / launcher / watchface: a tile, widget, activity, watch face or complication data source by class or short name"},"dataSource":{"type":"string","description":"watchface complication: alias for component"},"watchface":{"type":"string","description":"watchface complication: the watch face whose slot it fills (pkg/class); the app's own face is set if needed, any other means the demo face"},"slot":{"type":"integer","description":"watchface complication / tap: the watch face's complication slot id"},"type":{"type":["integer","string"],"description":"watchface complication / displays: ComplicationType or display type (external|virtual|rear)"},"title":{"type":"string"},"min":{"type":"number"},"max":{"type":"number"},"hasMonochromaticImage":{"type":"boolean"},"hasSmallImage":{"type":"boolean"},"config":{"type":"object","description":"watchface config: map of user style / configuration setting id to option id or boolean"},"flavor":{"type":"string","description":"watchface config: preset flavor id to apply"},"apk":{"type":"string","description":"watchface push: path to a WFF watch face APK to install into Watch Face Push"},"token":{"type":"string","description":"watchface push: validation token"},"activate":{"type":"boolean","description":"watchface push: set the pushed face active immediately"},"slotId":{"type":"string","description":"watchface push / removePush: pushed slot id"},"themeFromWatchface":{"type":"boolean","description":"watchface theme: enable or disable dynamic Material 3 system colors derived from the active watch face"},"label":{"type":"string","description":"launcher / clipboard: an app label or clip label"},
            "time":{"type":"string","description":"clock: ISO-8601 instant or HH:mm[:ss] wall clock"},"timeMillis":{"type":"integer","description":"clock / watchface render: epoch milliseconds"},"timeZone":{"type":"string","description":"clock: IANA time zone id, e.g. America/New_York"},"is24Hour":{"type":"boolean","description":"clock: 24-hour format toggle"},"frozen":{"type":"boolean","description":"clock: freeze simulated watch clock at pinned instant"},"rate":{"type":"number","description":"clock: time progression multiplier when not frozen (1.0 real-time, 60.0 one minute per second)"},"dstMode":{"type":"boolean"},
            "available":{"type":"boolean","description":"weather: whether weather data is available"},"condition":{"type":["string","integer"],"description":"weather: CLEAR_DAY, CLOUDY, FOG, HEAVY_RAIN, PARTLY_CLOUDY, RAIN, SNOW, THUNDERSTORM, WINDY, etc. (or WFF condition code 0..15)"},"temperature":{"type":"number"},"temperatureHigh":{"type":"number"},"temperatureLow":{"type":"number"},"temperatureUnit":{"type":"string","enum":["CELSIUS","FAHRENHEIT","C","F"]},"chanceOfPrecipitation":{"type":"integer"},"uvIndex":{"type":"integer"},"hours":{"type":"array","items":{"type":"object"},"description":"weather: hourly forecast entries"},"days":{"type":"array","items":{"type":"object"},"description":"weather: daily forecast entries"},
            "level":{"type":"integer","description":"battery: percentage 0..100"},"charging":{"type":"boolean","description":"battery: charging state"},"plugged":{"type":"string","enum":["ac","usb","wireless","none"]},"powerSave":{"type":"boolean"},
            "lat":{"type":"number"},"lng":{"type":"number"},"lon":{"type":"number","description":"Alias for lng"},"accuracy":{"type":"number"},"altitude":{"type":"number"},"bearing":{"type":"number"},"speed":{"type":"number"},
            "heartRate":{"type":"number"},"heartRateAccuracy":{"type":"string","enum":["unknown","no_contact","unreliable","low","medium","high"]},"distanceMeters":{"type":"number"},"calories":{"type":"number"},"floors":{"type":"number"},
            "delta":{"type":"number","description":"rotary: AXIS_SCROLL per event; negative scrolls down"},"count":{"type":"integer"},"intervalMs":{"type":"integer"},"activity":{"type":"string","description":"shortcuts: the launcher activity whose shortcuts to list or show"},
            "node":{"type":"string","description":"dataLayer: the peer node id (connect names a new one)"},"nearby":{"type":"boolean"},"capabilities":{"type":"array","items":{"type":"string"}},
            "dataMap":{"type":"object","description":"dataLayer: payload as a DataMap"},"assets":{"type":"object"},
            "channel":{"type":"string","description":"dataLayer channel*: the channel token"},"errorCode":{"type":"integer"},"disassociate":{"type":"integer","description":"companion: an association id to remove"},
            "error":{"type":"string"},"result":{"type":"integer"},"responseUrl":{"type":"string"},"requestTimeoutMs":{"type":"integer"},
            "addCapability":{"type":"string"},"removeCapability":{"type":"string"},
            "state":{"type":"string","enum":["on","ambient","off","installed","none","outdated","disabled","invalid"],"description":"screen: on / ambient / off. playServices: installed / none / outdated / disabled / invalid."},"timeoutMs":{"type":"integer"},"alwaysOn":{"type":"boolean"},"burnIn":{"type":"boolean"},"lowBit":{"type":"boolean"}
          }}
          """,
        ),
    )

  private val screenshotTool =
    ToolDef(
      name = SCREENSHOT,
      title = "Android simulator: screenshot",
      meta = appCallableToolMeta,
      description =
        "The simulator's display as a PNG image (exactly what the app draws; the window's " +
          "agent overlay is never in it). Pass `path` to also write it to disk.",
      inputSchema =
        schema(
          """
          {"type":"object","properties":{
            "simulator":{"type":"string","description":"Simulator id; optional when only one runs."},
            "path":{"type":"string","description":"Also write the PNG here; the image is then not inlined."},
            "device":{"type":"string","description":"With a paired or multi-app launch: which device's display (default phone/primary)."},
            "display":{"type":["integer","string"],"description":"Target display id, or 'all' to write one per display."},
            "square":{"type":"boolean","description":"On a round Wear display, keep the square corners instead of cropping to the circle."},
            "app":{"type":"boolean","description":"On split screen, capture only this app's half."}
          }}
          """,
        ),
      readOnlyHint = true,
    )

  private val watchfaceSnapshotTool =
    ToolDef(
      name = WATCHFACE_SNAPSHOT,
      title = "Android simulator: watch face status and screenshot",
      meta = appCallableToolMeta,
      description =
        "Capture the active Wear watch face's structured status (active face, palette colors, " +
          "themeFromWatchface, user configurations, complication slots with bounds and bound " +
          "values, pushed Watch Face Push slots) together with the current display PNG in one call.",
      inputSchema =
        schema(
          """
          {"type":"object","properties":{
            "simulator":{"type":"string","description":"Simulator id; optional when only one runs."},
            "path":{"type":"string","description":"Also write the PNG here; the image is then not inlined."},
            "device":{"type":"string","description":"With a paired or multi-app launch: which watch device (e.g. watch)."}
          }}
          """,
        ),
      readOnlyHint = true,
    )

  private val logsTool =
    ToolDef(
      name = LOGS,
      title = "Android simulator: log",
      meta = appCallableToolMeta,
      description =
        "The latest lines the simulator logged: the app's logcat, Robolectric and stack traces. " +
          "In a paired launch each line starts [phone] or [watch]; `device` keeps one device's. " +
          "Also returns the events since the last call.",
      inputSchema =
        schema(
          """
          {"type":"object","properties":{
            "simulator":{"type":"string","description":"Simulator id; optional when only one runs."},
            "device":{"type":"string","description":"Only this device's lines, in a paired or multi-app launch."},
            "contains":{"type":"string","description":"Only lines containing this text."},
            "lines":{"type":"integer","description":"How many of the latest lines (default 100)."}
          }}
          """,
        ),
      readOnlyHint = true,
    )

  private val restartTool =
    ToolDef(
      name = RESTART,
      title = "Android simulator: restart the app",
      meta = appCallableToolMeta,
      description =
        "Start the app again in a fresh simulator JVM (a new process: statics, singletons and " +
          "in-memory state reset), without ending the simulator session. Its data (files, " +
          "databases, preferences, external storage) is kept unless keepData is false. Faster " +
          "than simulator_stop + simulator_launch: the converted classes are reused. Returns " +
          "the new `info` and timings: snapshotMs, stopMs, startMs, totalMs and startup " +
          "{jvmMs, sandboxMs, applicationMs, firstActivityMs}.",
      inputSchema =
        schema(
          """
          {"type":"object","properties":{
            "simulator":{"type":"string","description":"Simulator id; optional when only one runs."},
            "device":{"type":"string","description":"With a paired or multi-app launch: which device restarts."},
            "keepData":{"type":"boolean","description":"Keep the app's data (default true); false starts from a clean install."}
          }}
          """,
        ),
      readOnlyHint = false,
      destructiveHint = false,
    )

  private val updateTool =
    ToolDef(
      name = UPDATE,
      title = "Android simulator: update to a rebuilt APK",
      meta = appCallableToolMeta,
      description =
        "Replace the running app with another APK, usually a rebuild of it, as `adb install " +
          "-r` would: its data is kept (keepData false drops it) and the session continues. " +
          "The APK's classes come from the CLI's conversion cache when this exact APK was seen " +
          "before; otherwise it converts first (the result's `conversion` says which, and how " +
          "long). Returns the new `info` and the same timings as simulator_restart.",
      inputSchema =
        schema(
          """
          {"type":"object","required":["apk"],"properties":{
            "simulator":{"type":"string","description":"Simulator id; optional when only one runs."},
            "apk":{"type":"string","description":"Absolute path to the new APK. Keeping data needs the same package."},
            "device":{"type":"string","description":"With a paired or multi-app launch: which device updates."},
            "keepData":{"type":"boolean","description":"Keep the app's data (default true)."}
          }}
          """,
        ),
      readOnlyHint = false,
      destructiveHint = false,
    )

  private val reloadTool =
    ToolDef(
      name = RELOAD,
      title = "Android simulator: live reload the app",
      meta = appCallableToolMeta,
      description =
        "After editing the app's source: compile it (the module's Kotlin/Java compile, which " +
          "also relinks resources; no dexing or APK packaging) and put the changed classes in " +
          "the running app. Needs a simulator_launch with `project`. An edit inside composables " +
          "only is hot-swapped (mode `hotswap`): the classes are redefined and the activity " +
          "re-created as on a rotation, so the app stays on its screen and keeps " +
          "rememberSaveable and saveable navigation state. Anything else restarts the app on " +
          "the changed classes (mode `classes`; data kept, UI state lost), and `hotswapSkipped` " +
          "says why: the first edit of a class since launch, a method or field added or " +
          "removed, a change outside a composable, a resource change, or more than one " +
          "activity. `hotswap: false` always restarts. A resource change swaps in the relinked " +
          "resources (`resources`); an asset or native-library change, or `full`, rebuilds the " +
          "APK (`apk`). A compile error is returned with the compiler's messages and the app " +
          "keeps running. Pass the edited source paths as `files`: the preview build compiles " +
          "them (as notify_file_changed does, refreshing their previews too) and the reload " +
          "skips Gradle, about a second from edit to pixels. When it cannot, it reloads through " +
          "Gradle and `precompileSkipped` says why. Returns mode " +
          "(hotswap|classes|resources|apk|unchanged), hotswapSkipped, build {ok, ms, tasks, " +
          "errors}, classesChanged, the new info, timings, and with `files` precompiled, " +
          "precompileMs and precompileSkipped.",
      inputSchema =
        schema(
          """
          {"type":"object","properties":{
            "simulator":{"type":"string","description":"Simulator id; optional when only one runs."},
            "full":{"type":"boolean","description":"Rebuild and reinstall the APK instead of overlaying classes."},
            "files":{"type":"array","items":{"type":"string"},"description":"The source files edited since the last reload (absolute paths). The preview build compiles them first, so the reload runs no Gradle."},
            "hotswap":{"type":"boolean","description":"false restarts the app even when the edit could be hot-swapped (default true)."},
            "keepData":{"type":"boolean","description":"Keep the app's data (default true)."}
          }}
          """,
        ),
      readOnlyHint = false,
      destructiveHint = false,
    )

  private val adbTool =
    ToolDef(
      name = ADB,
      title = "Android simulator: run adb against it",
      description =
        "Run an adb command against a simulator launched with `adb: true`, which serves itself " +
          "to adb as an emulator does (emulator-5554 and up): `args` is adb's arguments without " +
          "`-s`, e.g. [\"install\", \"/abs/app.apk\"], [\"shell\", \"am\", \"start\", \"-n\", " +
          "\"com.example/.Main\"], [\"shell\", \"pm list packages -3\"], [\"push\", \"/abs/f\", " +
          "\"/sdcard/f\"], [\"exec-out\", \"screencap\", \"-p\"] with `output`. An install " +
          "boots a simulator on the new APK (minutes the first time while its dex converts, " +
          "seconds after); the app is installed, not started. Wear tiles go in with " +
          "[\"shell\", \"am broadcast -a com.google.android.wearable.app.DEBUG_SURFACE --es " +
          "operation add-tile --ecn component pkg/.TileService\"], as Android Studio sends " +
          "them; Studio's watch face and complication run configurations (set-watchface, " +
          "set-complication, show-watchface) work the same way. Returns exitCode, stdout (or the bytes written to `output`), stderr and the " +
          "simulator's events. Needs the SDK's platform-tools adb (ANDROID_HOME or PATH). " +
          "simulator_command and simulator_screenshot keep working on the same device.",
      inputSchema =
        schema(
          """
          {"type":"object","properties":{
            "simulator":{"type":"string","description":"Simulator id; optional when only one runs."},
            "args":{"type":"array","items":{"type":"string"},"description":"adb's arguments, without -s (the tool adds the simulator's serial)."},
            "command":{"type":"string","description":"Instead of args: one command line, split as a shell would, e.g. \"shell getprop ro.build.version.sdk\"."},
            "output":{"type":"string","description":"Absolute path to write stdout to, as bytes, for a command whose output is data (exec-out screencap -p, exec-out run-as … cat). A pull writes to its own local path: [\"pull\", \"/sdcard/f\", \"/abs/f\"]."},
            "timeoutMs":{"type":"integer","description":"How long the command may take, at most 1800000 (default 600000: a first install converts the APK)."}
          }}
          """,
        ),
      readOnlyHint = false,
      destructiveHint = true,
    )

  private val stopTool =
    ToolDef(
      name = STOP,
      title = "Android simulator: stop it",
      meta = appCallableToolMeta,
      description = "End a simulator started with simulator_launch, closing its window.",
      inputSchema =
        schema(
          """
          {"type":"object","properties":{"simulator":{"type":"string","description":"Simulator id; optional when only one runs."}}}
          """,
        ),
      readOnlyHint = false,
      destructiveHint = true,
      idempotentHint = true,
    )

  /** One adb run: its exit code, stdout as bytes, stderr as text. */
  class AdbRun(
    val exitCode: Int,
    val stdout: ByteArray,
    val stderr: String,
    val timedOut: Boolean = false,
  ) {
    fun stdoutText(): String = String(stdout, Charsets.UTF_8)
  }

  companion object {
    const val VIEWER_URI: String = "ui://compose-preview/simulator"
    const val MCP_APP_MIME_TYPE: String = "text/html;profile=mcp-app"
    const val RESULT_META_KEY: String = "compose-preview/simulator"
    internal const val VIEWER_ASSET: String = "simulator.html"
    internal const val DEFAULT_INITIAL_SCREENSHOT_TIMEOUT_MS: Long = 3_000L
    const val DEFAULT_IDLE_TIMEOUT_MS: Long = 5L * 60L * 1000L

    internal fun defaultIdleTimeoutMs(
      raw: String? =
        System.getProperty("composeai.mcp.simulatorIdleTimeoutMs")
          ?: System.getenv("COMPOSE_PREVIEW_SIMULATOR_IDLE_TIMEOUT_MS"),
    ): Long {
      if (raw.isNullOrBlank()) return DEFAULT_IDLE_TIMEOUT_MS
      val parsed = raw.trim().toLongOrNull()
      return if (parsed != null && parsed >= 0L) parsed else DEFAULT_IDLE_TIMEOUT_MS
    }

    /**
     * Visibility-only MCP App metadata (`ui.visibility = ["model", "app"]`, no `resourceUri`):
     * keeps simulator tools app-callable without causing hosts to mount an inline card in chat.
     */
    internal val APP_CALLABLE_TOOL_META: JsonObject = buildJsonObject {
      putJsonObject("ui") {
        putJsonArray("visibility") {
          add(JsonPrimitive("model"))
          add(JsonPrimitive("app"))
        }
      }
    }

    /** A phone frame with a play indicator; monochrome 20x20 stroke icon. */
    private const val SIMULATOR_ICON_SVG: String =
      """<svg xmlns="http://www.w3.org/2000/svg" width="20" height="20" viewBox="0 0 20 20" fill="none" stroke="currentColor" stroke-width="1.33" stroke-linecap="round" stroke-linejoin="round"><rect x="5" y="2.5" width="10" height="15" rx="2"/><path d="M8.5 15h3"/><path d="M8.75 7.5v4l3.5-2z"/></svg>"""

    internal val viewerHtml: String by lazy {
      checkNotNull(SimulatorMcp::class.java.classLoader.getResourceAsStream(VIEWER_ASSET)) {
          "missing bundled Android Simulator MCP App asset: $VIEWER_ASSET"
        }
        .bufferedReader()
        .use { it.readText() }
    }

    const val OPEN = "simulator_open"
    const val LAUNCH = "simulator_launch"
    const val COMMAND = "simulator_command"
    const val SCREENSHOT = "simulator_screenshot"
    const val WATCHFACE_SNAPSHOT = "simulator_watchface_snapshot"
    const val STOP = "simulator_stop"
    const val LOGS = "simulator_logs"
    const val RESTART = "simulator_restart"
    const val UPDATE = "simulator_update"
    const val RELOAD = "simulator_reload"
    const val ADB = "simulator_adb"
    /** The tools `tools/list` advertises, named for the CLI's `android-simulator` command. */
    val TOOL_NAMES: List<String> =
      listOf(
        OPEN,
        LAUNCH,
        COMMAND,
        SCREENSHOT,
        WATCHFACE_SNAPSHOT,
        LOGS,
        RESTART,
        UPDATE,
        RELOAD,
        ADB,
        STOP,
      )

    /**
     * Previous `android_simulator_*` names: still answered so existing host permission allowlists
     * or saved prompts keep working, while `tools/list` advertises the shorter `simulator_*` names.
     */
    val LEGACY_NAMES: Map<String, String> = TOOL_NAMES.associateBy { "android_$it" }

    /** Every name [handle] answers: [TOOL_NAMES] and their [LEGACY_NAMES]. */
    val HANDLED_NAMES: Set<String> = TOOL_NAMES.toSet() + LEGACY_NAMES.keys

    /** adb options that would point a command at another device or server. */
    private val DEVICE_OPTIONS = setOf("-s", "-t", "-d", "-e", "-H", "-P", "-L")

    /** adb commands about other devices or the server, not this one. */
    private val OTHER_DEVICES =
      setOf("connect", "disconnect", "kill-server", "start-server", "pair", "devices", "mdns")

    private const val ADB_TIMEOUT_MS = 600_000L
    private const val MAX_ADB_TIMEOUT_MS = 1_800_000L
    private const val ADB_WAIT_MS = 30_000L
    private const val DEVICE_EVENT_MS = 5_000L
    private const val MAX_ADB_OUTPUT = 64 * 1024

    /**
     * The SDK's `adb`: `ANDROID_HOME` / `ANDROID_SDK_ROOT` `platform-tools/adb`, else `adb` on
     * `PATH`.
     */
    fun defaultAdb(
      environment: Map<String, String> = System.getenv(),
    ): (List<String>, Long) -> AdbRun = { args, timeoutMs ->
      val sdkAdb =
        listOfNotNull(environment["ANDROID_HOME"], environment["ANDROID_SDK_ROOT"])
          .map { File(it, "platform-tools/adb") }
          .firstOrNull { it.canExecute() }
      val process =
        try {
          ProcessBuilder(listOf(sdkAdb?.path ?: "adb") + args).start()
        } catch (e: java.io.IOException) {
          throw IllegalStateException("no adb: set ANDROID_HOME or put platform-tools on PATH", e)
        }
      process.outputStream.close()
      val stdout = CompletableFuture.supplyAsync { process.inputStream.readBytes() }
      val stderr = CompletableFuture.supplyAsync { process.errorStream.reader().readText() }
      val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
      if (!finished) process.destroyForcibly()
      AdbRun(
        exitCode = if (finished) process.exitValue() else -1,
        stdout = runCatching { stdout.get(5, TimeUnit.SECONDS) }.getOrElse { ByteArray(0) },
        stderr = runCatching { stderr.get(5, TimeUnit.SECONDS) }.getOrElse { "" },
        timedOut = !finished,
      )
    }

    /** A command line split as `sh` splits words: quotes and backslashes, nothing else. */
    fun splitCommand(line: String): List<String> {
      val words = mutableListOf<String>()
      val word = StringBuilder()
      var inWord = false
      var quote: Char? = null
      var i = 0
      while (i < line.length) {
        val c = line[i]
        when {
          quote != null && c == quote -> quote = null
          quote == '"' && c == '\\' && i + 1 < line.length -> word.append(line[++i])
          quote != null -> word.append(c)
          c == '\'' || c == '"' -> {
            quote = c
            inWord = true
          }
          c == '\\' && i + 1 < line.length -> {
            word.append(line[++i])
            inWord = true
          }
          c.isWhitespace() -> {
            if (inWord) words += word.toString()
            word.clear()
            inWord = false
          }
          else -> {
            word.append(c)
            inWord = true
          }
        }
        i++
      }
      if (inWord) words += word.toString()
      return words
    }

    /** The devices of a paired launch, as the CLI's `device` names them. */
    private val PAIRED = setOf("phone", "watch")

    private const val STDERR_LINES = 2000
    private const val DEFAULT_LOG_LINES = 100
    private const val STDERR_REPORTED = 40
    private const val MAX_EVENTS = 200
    private const val MAX_EXITED_SIMULATORS = 8
    private const val EXIT_WAIT_MS = 5_000L

    /**
     * `compose-preview` from `COMPOSE_PREVIEW_CLI`, `PATH` or `~/.local/bin`, as `init-script`
     * finds it. For local development point `COMPOSE_PREVIEW_CLI` at a compose-ai-tools
     * `cli/build/install/compose-preview/bin/compose-preview`.
     *
     * In a build directory it runs there, and is given [AndroidSdks]' SDK as `ANDROID_HOME` when
     * neither this server's environment nor the build's `local.properties` names one, as a Gradle
     * child is.
     */
    fun defaultStarter(
      environment: Map<String, String> = System.getenv(),
      userHome: File = File(System.getProperty("user.home")),
      androidSdks: AndroidSdks = AndroidSdks(environment, userHome),
    ): (List<String>, File?) -> Process = { args, directory ->
      val cli =
        CliInitScript.locate(environment, userHome)
          ?: throw IllegalStateException(
            "no compose-preview launcher was found (COMPOSE_PREVIEW_CLI, PATH or " +
              "~/.local/bin/compose-preview)",
          )
      ProcessBuilder(listOf(cli.absolutePath) + args)
        .apply {
          if (directory != null) {
            directory(directory)
            androidSdks.forBuild(directory)?.let { environment()["ANDROID_HOME"] = it.absolutePath }
          }
        }
        .start()
    }

    private fun schema(text: String): JsonElement = Json.parseToJsonElement(text.trimIndent())

    private fun JsonObject.string(key: String): String? =
      (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.boolean(key: String): Boolean? =
      (this[key] as? JsonPrimitive)?.booleanOrNull

    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull
  }
}
