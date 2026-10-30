package ee.schimke.composeai.mcp

import ee.schimke.composeai.mcp.protocol.CallToolResult
import ee.schimke.composeai.mcp.protocol.ContentBlock
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
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * An Android app running in Robolectric's simulator, driven by the agent: the `simulator_*` tools
 * over `compose-preview launch <apk> --control stdio` (compose-ai-tools' `docs/SIMULATOR.md`).
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
  /** Starts `compose-preview <args>`; a seam so tests need neither the CLI nor a JVM. */
  private val start: (List<String>) -> Process = defaultStarter(),
  private val readyTimeoutMs: Long = TimeUnit.MINUTES.toMillis(5),
  private val commandTimeoutMs: Long = TimeUnit.MINUTES.toMillis(1),
  /** Runs `adb <args>`; a seam so tests need no SDK. */
  private val adb: (List<String>, Long) -> AdbRun = defaultAdb(),
) {

  private val simulators = ConcurrentHashMap<String, Simulator>()
  private val nextId = AtomicLong(1)

  fun toolDefs(): List<ToolDef> =
    listOf(
      launchTool,
      commandTool,
      screenshotTool,
      logsTool,
      restartTool,
      updateTool,
      reloadTool,
      adbTool,
      stopTool,
    )

  suspend fun handle(name: String, args: JsonObject): CallToolResult? =
    when (name) {
      LAUNCH -> launch(args)
      COMMAND -> withContext(Dispatchers.IO) { command(args) }
      SCREENSHOT -> withContext(Dispatchers.IO) { screenshot(args) }
      LOGS -> withContext(Dispatchers.IO) { logs(args) }
      RESTART -> withContext(Dispatchers.IO) { replace(RESTART, "restart", args) }
      UPDATE -> withContext(Dispatchers.IO) { replace(UPDATE, "update", args) }
      RELOAD -> withContext(Dispatchers.IO) { replace(RELOAD, "reload", args) }
      ADB -> withContext(Dispatchers.IO) { adb(args) }
      STOP -> withContext(Dispatchers.IO) { stop(args) }
      else -> null
    }

  fun shutdown() {
    simulators.values.forEach { it.close() }
    simulators.clear()
  }

  // -----------------------------------------------------------------------------------------------

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
    if (apk == null && project == null && !adbDevice) {
      return error(
        "simulator_launch: `apk` (a path) or `project` (a Gradle build) is required, unless " +
          "`adb` starts a device with nothing installed."
      )
    }
    if (adbDevice && project != null) {
      return error("simulator_launch: `adb` runs its own device; drop `project` (or `adb`).")
    }
    val file = apk?.let { File(it).absoluteFile }
    if (file != null && !file.isFile) return error("simulator_launch: no such APK: $file")
    val pair = args.string("pair")?.let { File(it).absoluteFile }
    if (pair != null && !pair.isFile) return error("simulator_launch: no such APK to pair: $pair")
    val cliArgs = buildList {
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
      args.string("formFactor")?.let { addAll(listOf("--form-factor", it)) }
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
      args.string("playServices")?.let { addAll(listOf("--play-services", it)) }
      if (args.boolean("launcher") == true) add("--launcher")
      (args["widgets"] as? JsonArray)?.forEach { widget ->
        (widget as? JsonPrimitive)?.contentOrNull?.let { addAll(listOf("--widget", it)) }
      }
      (args["tiles"] as? JsonArray)?.forEach { tile ->
        (tile as? JsonPrimitive)?.contentOrNull?.let { addAll(listOf("--tile", it)) }
      }
    }
    val process =
      try {
        withContext(Dispatchers.IO) { start(cliArgs) }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        return error("simulator_launch: could not start compose-preview: ${e.message}")
      }
    val id = "sim-${nextId.getAndIncrement()}"
    val simulator =
      Simulator(
        id,
        file?.name ?: project?.name ?: if (adbDevice) "device" else "app",
        process,
        devices = if (pair != null) PAIRED else emptySet(),
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
    simulators[id] = simulator
    val events = simulator.drainEvents()
    val result = buildJsonObject {
      put("simulator", id)
      put("info", ready)
      simulator.device?.let { put("device", it) }
      installed?.string("package")?.let { put("installed", it) }
      // The CLI's `conversion` event: whether the APK's classes came from its cache, and the cost.
      val conversions = events.filter { (it as? JsonObject)?.string("event") == "conversion" }
      if (conversions.isNotEmpty()) {
        put("conversion", if (pair == null) conversions.first() else JsonArray(conversions))
      }
      put("events", events)
    }
    return ok(result)
  }

  private fun notStarted(simulator: Simulator, cause: String): CallToolResult =
    error("simulator_launch: the simulator did not start ($cause).\n" + simulator.stderrTail())

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
          }
        )
      is Reply.Failed -> error("$cmd: ${reply.message}", simulator.drainEvents())
    }
  }

  private fun screenshot(args: JsonObject): CallToolResult {
    val simulator = simulator(args) ?: return noSimulator()
    val payload = buildJsonObject {
      put("cmd", "screenshot")
      args.string("path")?.let { put("path", it) }
      args.string("device")?.let { put("device", it) }
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
        )
      }
    }
  }

  /**
   * `restart` / `update`: the CLI replaces the simulator JVM and answers once the new one is ready,
   * so this waits as long as a launch may (an updated APK may convert first).
   */
  private fun replace(tool: String, cmd: String, args: JsonObject): CallToolResult {
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
      args.string("device")?.let { put("device", it) }
    }
    return when (val reply = simulator.send(payload, readyTimeoutMs)) {
      is Reply.Ok ->
        ok(
          buildJsonObject {
            for ((key, value) in reply.result) put(key, value)
            put("events", simulator.drainEvents())
          }
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
          "$ADB: ${simulator.id} is not an adb device: launch it with simulator_launch `adb: true`."
        )
    val serial = device.string("serial") ?: return error("$ADB: the device has no serial.")
    val argv =
      (args["args"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        ?: args.string("command")?.let(::splitCommand)
        ?: return error(
          "$ADB: `args` (adb's arguments, e.g. [\"install\", \"/path/app.apk\"]) is required."
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
        "$ADB: $serial did not come up in adb: ${wait.stderr.ifBlank { wait.stdoutText() }}"
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
    val simulator = simulator(args) ?: return noSimulator()
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
    )
  }

  private fun stop(args: JsonObject): CallToolResult {
    val id = args.string("simulator") ?: simulators.keys.singleOrNull()
    val simulator = id?.let { simulators.remove(it) } ?: return noSimulator()
    simulator.close()
    return ok(buildJsonObject { put("stopped", simulator.id) })
  }

  /** The named simulator, or the only one running. */
  private fun simulator(args: JsonObject): Simulator? {
    val id = args.string("simulator") ?: return simulators.values.singleOrNull()
    return simulators[id]
  }

  private fun noSimulator(): CallToolResult =
    error(
      if (simulators.isEmpty()) "No simulator is running: start one with simulator_launch."
      else "Name the simulator: one of ${simulators.keys.sorted().joinToString()}."
    )

  // -----------------------------------------------------------------------------------------------

  private sealed interface Reply {
    data class Ok(val result: JsonObject) : Reply

    data class Failed(val message: String) : Reply
  }

  /**
   * One `compose-preview launch --control stdio` child. With [devices] (a `--pair` launch) it is
   * ready once each device has sent its `ready`, and [ready] holds each one's info by device.
   */
  private class Simulator(
    val id: String,
    val apk: String,
    private val process: Process,
    private val devices: Set<String> = emptySet(),
  ) {
    val ready = CompletableFuture<JsonObject>()

    /** With `adb`: the CLI's `device` event (serial, ports). */
    val deviceInfo = CompletableFuture<JsonObject>()

    val device: JsonObject?
      get() = deviceInfo.getNow(null)

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
        val exited = IllegalStateException("the simulator exited (${exitCode()})")
        ready.completeExceptionally(exited)
        install.completeExceptionally(exited)
        pending.values.forEach { it.complete(Reply.Failed("${exited.message}\n${stderrTail()}")) }
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
      if (devices.isEmpty()) {
        ready.complete(info)
        return
      }
      val device = message.string("device") ?: return
      readies[device] = JsonObject(info.filterKeys { it != "device" })
      if (readies.keys.containsAll(devices)) {
        ready.complete(JsonObject(devices.associateWith { readies.getValue(it) }))
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

  private fun ok(result: JsonObject) =
    CallToolResult(
      content = listOf(ContentBlock.Text(result.toString())),
      structuredContent = result,
    )

  private fun error(message: String, events: JsonArray? = null): CallToolResult {
    val structured =
      events?.takeIf { it.isNotEmpty() }?.let { buildJsonObject { put("events", it) } }
    return errorCallToolResult(message, structured)
  }

  private val launchTool =
    ToolDef(
      name = LAUNCH,
      title = "Launch an APK in the simulator",
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
          "With `launcher` a home screen sits under the app: HOME, icons, shortcuts and the " +
          "app's own widgets (simulator_command home, widget, …); `widgets` starts with some " +
          "placed. On a Wear app `launcher` is the watch's system UI instead: watch face, " +
          "launcher, notification stream, tiles and widgets carousel, quick settings; `tiles` " +
          "starts with tiles or Wear Widgets in the carousel. " +
          "With `adb` the simulator is a device adb and Android Studio see (emulator-5554 and " +
          "up), with or without an APK: drive it with simulator_adb as well.",
      inputSchema =
        schema(
          """
          {"type":"object","properties":{
            "apk":{"type":"string","description":"Absolute path to the APK (a debug build works best). Optional with `project`: the CLI builds it."},
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
            "adb":{"type":"boolean","description":"Serve the simulator to adb and Android Studio as an emulator (emulator-5554 and up): `apk` becomes optional (the device boots to its home screen or watch face), an `apk` given is installed rather than started, and simulator_adb runs adb commands against it. The result's `device` holds its serial and ports. Not with project or pair."},
            "formFactor":{"type":"string","enum":["phone","wear","glimmer","xr"],"description":"The device's form factor; detected from the APK when there is one. With adb and no apk, wear boots a watch."},
            "playServices":{"type":"string","enum":["installed","none","outdated","disabled","invalid"],"description":"Google Play services as the app's availability checks see it: installed (default, SUCCESS), or none / outdated / disabled / invalid to test the app's error paths. simulator_command playServices {state} changes it while the app runs."},
            "launcher":{"type":"boolean","description":"A system UI under the app. On a phone, a home screen: HOME and Back from the app's root go there, its icons reopen the app, a long press shows the app's shortcuts, and the app's widgets are placed, sized, updated and tapped there (simulator_command home, drawer, shortcuts, shortcut, widgets, widget, picker). On a Wear app, the watch's: a watch face, launcher, notification stream, tiles and widgets carousel and quick settings (simulator_command home, launcher, notifications, notification, ongoing, tiles, health, quickSettings)."},
            "widgets":{"type":"array","items":{"type":"string"},"description":"Widget providers (a receiver class or its short name) to place on the home screen at launch: the app starts once, then the home screen shows with them. Implies launcher."},
            "tiles":{"type":"array","items":{"type":"string"},"description":"Wear: tile or Wear Widget services (a class or its short name) in the carousel at launch. Implies launcher."}
          }}
          """
        ),
    )

  private val commandTool =
    ToolDef(
      name = COMMAND,
      title = "Drive the simulator",
      description =
        "Send one command to a running simulator and get its result plus the events the app " +
          "produced since the last call (activity resumed/destroyed, service started, intents " +
          "nothing handled). Arguments sit beside `cmd`. Commands: info; hierarchy {flat?} " +
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
          "bluetoothRequest event); display {size?: WxH|reset, density?: N|reset}; forceStop; " +
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
          "is stopped until wake); ambient {value?, op?: update}; tilt / wake (wrist-tilt " +
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
          "(a Wear app launched with launcher or tiles): home (the watch face; key HOME too); " +
          "launcher {index? | component? | label?} (opens the app list, or launches one; key " +
          "STEM_PRIMARY too); notifications {op?: open|close|clear} (without op, every active " +
          "notification with key, channel, extras, actions, ongoingActivity, and shown: false " +
          "with hiddenBecause when a watch would drop it); notification {key, op: tap|action|" +
          "reply|dismiss|snooze, index?, text?, ms?} (any form factor; reply fills the " +
          "action's RemoteInput with text); ongoing {op?: list|tap, index?}; tiles {op: list|" +
          "add|remove|show|next|prev|refresh, component?, index?} (the carousel; tiles are " +
          "rendered by the protolayout renderer, widgets by the Remote Compose player); health " +
          "{heartRate?, heartRateAccuracy?, steps?, distanceMeters?, calories?, floors?} (what " +
          "tiles and widgets read); quickSettings {airplane?, wifi?}; rotary {delta? (-1), " +
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
            "device":{"type":"string","enum":["phone","watch"],"description":"With a paired launch: which device runs the command (default phone)."},
            "cmd":{"type":"string","enum":["info","hierarchy","find","tap","longPress","swipe","key","back","text","launch","finish","activities","startActivity","startService","stopService","bindService","unbindService","services","broadcast","notifications","query","components","qualifiers","permissions","wait","ping","dataLayer","companion","shell","settings","logcat","files","content","network","wifi","bluetooth","display","forceStop","clearData","kill","recreate","savedState","getprop","package","packages","screen","ambient","tilt","wake","palm","sleep","playServices","home","drawer","shortcuts","shortcut","widgets","widget","picker","launcher","notification","ongoing","tiles","health","quickSettings","rotary","zoom"]},
            "text":{"type":"string"},"textContains":{"type":"string"},"desc":{"type":"string"},"descContains":{"type":"string"},
            "resourceId":{"type":"string"},"cls":{"type":"string"},"clickable":{"type":"boolean"},"index":{"type":"integer"},
            "x":{"type":"number"},"y":{"type":"number"},"x1":{"type":"number"},"y1":{"type":"number"},"x2":{"type":"number"},"y2":{"type":"number"},
            "durationMs":{"type":"integer"},"steps":{"type":"integer","description":"swipe: its touch steps; health: steps today"},"ms":{"type":"integer"},"settleMs":{"type":"integer"},
            "key":{"type":["string","integer","array"]},"flat":{"type":"boolean"},"value":{"type":["string","number","boolean","object","array"]},
            "intent":{"type":"object"},"savedState":{"type":"string","description":"launch / startActivity: base64 instance state from the savedState command; a new instance is created from it."},"uri":{"type":"string"},"projection":{"type":"array","items":{"type":"string"}},
            "selection":{"type":"string"},"args":{"type":"array","items":{"type":"string"}},"sort":{"type":"string"},"limit":{"type":"integer"},
            "policy":{"type":"string","enum":["grant","deny","accept","reject","ask"],"description":"permissions: grant|deny|ask; wifi/bluetooth request and companion: accept|reject|ask"},"grant":{"type":"array","items":{"type":"string"}},"deny":{"type":"array","items":{"type":"string"}},"reset":{"type":"boolean"},
            "command":{"type":"string","description":"shell: an adb shell command line"},
            "op":{"type":"string"},"namespace":{"type":"string","enum":["system","secure","global"]},
            "path":{"type":"string"},"to":{"type":"string"},"from":{"type":"string"},"recursive":{"type":"boolean"},
            "values":{"type":"object"},"filters":{"type":"object"},"default":{"type":"string"},"regex":{"type":"string"},"tail":{"type":"integer"},
            "wifi":{"type":"boolean"},"airplane":{"type":"boolean"},"data":{"type":["boolean","string"],"description":"network: mobile data on/off; dataLayer: payload bytes, base64"},
            "mode":{"type":"string","enum":["wifi","cellular","ethernet","vpn","captive","offline"]},"metered":{"type":["boolean","null"]},
            "cellular":{"type":"string","enum":["5g","lte","3g","2g"]},"downKbps":{"type":"integer"},"upKbps":{"type":"integer"},
            "latencyMs":{"type":"integer"},"kbps":{"type":"integer"},"block":{"type":"array","items":{"type":"string"}},"dataSaver":{"type":"boolean"},
            "networks":{"type":"array","items":{"type":"object"}},"ssid":{"type":"string"},"bssid":{"type":"string"},"rssi":{"type":"integer"},
            "answer":{"anyOf":[{"type":"string","enum":["accept","reject","grant","deny","dismiss","approximate","selected"]},{"type":"object","additionalProperties":{"type":"string","enum":["grant","deny","dismiss"]}}],"description":"wifi/bluetooth/companion request: accept|reject; permissions: grant|deny|dismiss|approximate|selected, or {permission: grant|deny|dismiss}"},"requestId":{"type":"integer","description":"wifi/bluetooth/permissions/companion request: which waiting request to answer (default the oldest); dataLayer reply: the app request it answers"},
            "address":{"type":"string","description":"bluetooth: a device address, AA:BB:CC:DD:EE:FF"},
            "serviceUuids":{"type":"array","items":{"type":"string"}},"serviceData":{"type":"object"},"manufacturerData":{"type":"object"},
            "txPower":{"type":"integer"},"connectable":{"type":"boolean"},"gatt":{"type":"object"},"characteristic":{"type":"string"},"size":{"type":"string"},"density":{"type":["string","integer"]},
            "name":{"type":"string"},"package":{"type":"string"},"holdMs":{"type":"integer"},"longPress":{"type":"boolean"},
            "action":{"type":"string","enum":["connect","disconnect","capabilities","put","delete","message","request","reply","config","channelOpen","channelWrite","channelEof","channelClose","add","remove","resize","update","reconfigure","pin","open","close","list"],"description":"dataLayer: what the peer you play does. widget: add|remove|resize|update|reconfigure|pin. drawer / picker: open|close. shortcuts: list|open."},
            "provider":{"type":"string","description":"widget add: the widget provider, a receiver class or its short name"},"widgetId":{"type":"integer","description":"widget: which placed widget (id is the command's own)"},
            "cellX":{"type":"integer"},"cellY":{"type":"integer"},"spanX":{"type":"integer"},"spanY":{"type":"integer"},"configure":{"type":"boolean","description":"widget add: run the configure activity even when the provider makes it optional"},
            "shortcutId":{"type":"string","description":"shortcut: which app shortcut to open"},
            "component":{"type":"string","description":"tiles / launcher: a tile, widget or activity by class or short name"},"label":{"type":"string","description":"launcher: an app by its label"},
            "heartRate":{"type":"number"},"heartRateAccuracy":{"type":"string","enum":["unknown","no_contact","unreliable","low","medium","high"]},"distanceMeters":{"type":"number"},"calories":{"type":"number"},"floors":{"type":"number"},
            "delta":{"type":"number","description":"rotary: AXIS_SCROLL per event; negative scrolls down"},"count":{"type":"integer"},"intervalMs":{"type":"integer"},"activity":{"type":"string","description":"shortcuts: the launcher activity whose shortcuts to list or show"},
            "node":{"type":"string","description":"dataLayer: the peer node id (connect names a new one)"},"nearby":{"type":"boolean"},"capabilities":{"type":"array","items":{"type":"string"}},
            "dataMap":{"type":"object","description":"dataLayer: payload as a DataMap"},"assets":{"type":"object"},
            "channel":{"type":"string","description":"dataLayer channel*: the channel token"},"errorCode":{"type":"integer"},"disassociate":{"type":"integer","description":"companion: an association id to remove"},
            "error":{"type":"string"},"result":{"type":"integer"},"responseUrl":{"type":"string"},"requestTimeoutMs":{"type":"integer"},
            "addCapability":{"type":"string"},"removeCapability":{"type":"string"},
            "state":{"type":"string","enum":["on","ambient","off","installed","none","outdated","disabled","invalid"],"description":"screen: on / ambient / off. playServices: installed / none / outdated / disabled / invalid."},"timeoutMs":{"type":"integer"},"alwaysOn":{"type":"boolean"},"burnIn":{"type":"boolean"},"lowBit":{"type":"boolean"}
          }}
          """
        ),
    )

  private val screenshotTool =
    ToolDef(
      name = SCREENSHOT,
      title = "Simulator screenshot",
      description =
        "The simulator's display as a PNG image (exactly what the app draws; the window's " +
          "agent overlay is never in it). Pass `path` to also write it to disk.",
      inputSchema =
        schema(
          """
          {"type":"object","properties":{
            "simulator":{"type":"string","description":"Simulator id; optional when only one runs."},
            "path":{"type":"string","description":"Also write the PNG here; the image is then not inlined."},
            "device":{"type":"string","enum":["phone","watch"],"description":"With a paired launch: which device's display (default phone)."}
          }}
          """
        ),
      readOnlyHint = true,
    )

  private val logsTool =
    ToolDef(
      name = LOGS,
      title = "Simulator log",
      description =
        "The latest lines the simulator logged: the app's logcat, Robolectric and stack traces. " +
          "In a paired launch each line starts [phone] or [watch]; `device` keeps one device's. " +
          "Also returns the events since the last call.",
      inputSchema =
        schema(
          """
          {"type":"object","properties":{
            "simulator":{"type":"string","description":"Simulator id; optional when only one runs."},
            "device":{"type":"string","enum":["phone","watch"],"description":"Only this device's lines, in a paired launch."},
            "contains":{"type":"string","description":"Only lines containing this text."},
            "lines":{"type":"integer","description":"How many of the latest lines (default 100)."}
          }}
          """
        ),
      readOnlyHint = true,
    )

  private val restartTool =
    ToolDef(
      name = RESTART,
      title = "Restart the simulated app",
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
            "device":{"type":"string","enum":["phone","watch"],"description":"With a paired launch: which device restarts (default phone)."},
            "keepData":{"type":"boolean","description":"Keep the app's data (default true); false starts from a clean install."}
          }}
          """
        ),
    )

  private val updateTool =
    ToolDef(
      name = UPDATE,
      title = "Update the simulated app to a rebuilt APK",
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
            "device":{"type":"string","enum":["phone","watch"],"description":"With a paired launch: which device updates (default phone)."},
            "keepData":{"type":"boolean","description":"Keep the app's data (default true)."}
          }}
          """
        ),
    )

  private val reloadTool =
    ToolDef(
      name = RELOAD,
      title = "Live reload the simulated app",
      description =
        "After editing the app's source: compile it (the module's Kotlin/Java compile, which " +
          "also relinks resources; no dexing or APK packaging) and restart the app on the " +
          "classes that changed, ahead of the converted APK. Needs a simulator_launch with " +
          "`project`. Data is kept; UI state (back stack, scroll) is not. A resource change " +
          "swaps in the relinked resources; an asset or native-library change, or `full`, " +
          "rebuilds the APK instead. A compile error is returned with the compiler's messages " +
          "and the app keeps running. Returns mode (classes|resources|apk|unchanged), build " +
          "{ok, ms, tasks, errors}, classesChanged, the new info, and timings.",
      inputSchema =
        schema(
          """
          {"type":"object","properties":{
            "simulator":{"type":"string","description":"Simulator id; optional when only one runs."},
            "full":{"type":"boolean","description":"Rebuild and reinstall the APK instead of overlaying classes."},
            "keepData":{"type":"boolean","description":"Keep the app's data (default true)."}
          }}
          """
        ),
    )

  private val adbTool =
    ToolDef(
      name = ADB,
      title = "Run adb against the simulator",
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
          "them. Returns exitCode, stdout (or the bytes written to `output`), stderr and the " +
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
          """
        ),
    )

  private val stopTool =
    ToolDef(
      name = STOP,
      title = "Stop the simulator",
      description = "End a simulator started with simulator_launch, closing its window.",
      inputSchema =
        schema(
          """
          {"type":"object","properties":{"simulator":{"type":"string","description":"Simulator id; optional when only one runs."}}}
          """
        ),
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
    const val LAUNCH = "simulator_launch"
    const val COMMAND = "simulator_command"
    const val SCREENSHOT = "simulator_screenshot"
    const val STOP = "simulator_stop"
    const val LOGS = "simulator_logs"
    const val RESTART = "simulator_restart"
    const val UPDATE = "simulator_update"
    const val RELOAD = "simulator_reload"
    const val ADB = "simulator_adb"
    val TOOL_NAMES: List<String> =
      listOf(LAUNCH, COMMAND, SCREENSHOT, LOGS, RESTART, UPDATE, RELOAD, ADB, STOP)

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
      environment: Map<String, String> = System.getenv()
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
        stdout = stdout.get(5, TimeUnit.SECONDS),
        stderr = stderr.get(5, TimeUnit.SECONDS),
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

    /**
     * `compose-preview` from `COMPOSE_PREVIEW_CLI`, `PATH` or `~/.local/bin`, as `init-script`
     * finds it. For local development point `COMPOSE_PREVIEW_CLI` at a compose-ai-tools
     * `cli/build/install/compose-preview/bin/compose-preview`.
     */
    fun defaultStarter(
      environment: Map<String, String> = System.getenv(),
      userHome: File = File(System.getProperty("user.home")),
    ): (List<String>) -> Process = { args ->
      val cli =
        CliInitScript.locate(environment, userHome)
          ?: throw IllegalStateException(
            "no compose-preview launcher was found (COMPOSE_PREVIEW_CLI, PATH or " +
              "~/.local/bin/compose-preview)"
          )
      ProcessBuilder(listOf(cli.absolutePath) + args).start()
    }

    private fun schema(text: String): JsonElement = Json.parseToJsonElement(text.trimIndent())

    private fun JsonObject.string(key: String): String? =
      (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.boolean(key: String): Boolean? =
      (this[key] as? JsonPrimitive)?.booleanOrNull

    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull
  }
}
