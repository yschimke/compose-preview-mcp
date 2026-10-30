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
 * - `simulator_stop` ends a simulator; all of them end with the server.
 *
 * Each simulator is one CLI child process speaking JSON lines; stderr (the app's logs) is kept as a
 * bounded tail for error reports. Opens no socket, so it adds nothing to the layer rule.
 */
class SimulatorMcp(
  /** Starts `compose-preview <args>`; a seam so tests need neither the CLI nor a JVM. */
  private val start: (List<String>) -> Process = defaultStarter(),
  private val readyTimeoutMs: Long = TimeUnit.MINUTES.toMillis(5),
  private val commandTimeoutMs: Long = TimeUnit.MINUTES.toMillis(1),
) {

  private val simulators = ConcurrentHashMap<String, Simulator>()
  private val nextId = AtomicLong(1)

  fun toolDefs(): List<ToolDef> = listOf(launchTool, commandTool, screenshotTool, stopTool)

  suspend fun handle(name: String, args: JsonObject): CallToolResult? =
    when (name) {
      LAUNCH -> launch(args)
      COMMAND -> withContext(Dispatchers.IO) { command(args) }
      SCREENSHOT -> withContext(Dispatchers.IO) { screenshot(args) }
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
    val apk = args.string("apk") ?: return error("simulator_launch: `apk` (a path) is required.")
    val file = File(apk).absoluteFile
    if (!file.isFile) return error("simulator_launch: no such APK: $file")
    val cliArgs = buildList {
      add("launch")
      add(file.path)
      add("--control")
      add("stdio")
      if (args.boolean("headless") == true) add("--headless")
      if (args.boolean("showAgent") == false) add("--hide-agent")
      args.string("qualifiers")?.let { addAll(listOf("--qualifiers", it)) }
      args.string("fontScale")?.let { addAll(listOf("--font-scale", it)) }
      args.int("sdk")?.let { addAll(listOf("--sdk", it.toString())) }
      args.string("java")?.let { addAll(listOf("--java", it)) }
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
    val simulator = Simulator(id, file.name, process)
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
    simulators[id] = simulator
    val result = buildJsonObject {
      put("simulator", id)
      put("info", ready)
      put("events", simulator.drainEvents())
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

  /** One `compose-preview launch --control stdio` child. */
  private class Simulator(val id: String, val apk: String, private val process: Process) {
    val ready = CompletableFuture<JsonObject>()
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
        message.string("event") == "ready" ->
          ready.complete(JsonObject(message.filterKeys { it != "event" }))
        message.containsKey("event") -> {
          events.add(message)
          while (events.size > MAX_EVENTS) events.poll()
        }
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
          "end it with simulator_stop. Needs Java 21+ and a local Android SDK.",
      inputSchema =
        schema(
          """
          {"type":"object","required":["apk"],"properties":{
            "apk":{"type":"string","description":"Absolute path to the APK (a debug build works best)."},
            "headless":{"type":"boolean","description":"No window. Default false: the person can watch."},
            "showAgent":{"type":"boolean","description":"Draw your taps, swipes and commands over the window. Default true."},
            "qualifiers":{"type":"string","description":"Display resource qualifiers, Robolectric syntax, e.g. w411dp-h891dp-xxhdpi-night. Default w360dp-h640dp-hdpi."},
            "fontScale":{"type":"string","description":"Font scale, e.g. 1.3."},
            "sdk":{"type":"integer","description":"Android API level to simulate (default 35)."},
            "java":{"type":"string","description":"JDK 21+ home or java binary, when the default JDK is older."}
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
          "+land|+night|…}; permissions {policy?: grant|deny, grant?, deny?} (how the app's " +
          "runtime permission requests are answered, granted by default; grant/deny name " +
          "permissions and apply now too); wait {ms}. A selector is text/desc (exact), textContains/" +
          "descContains, resourceId (a Compose testTag when exposed), cls, clickable. An intent " +
          "is {action, data, type, package, component ('.Cls' or 'pkg/cls'), categories, " +
          "flags ([\"CLEAR_TOP\"]…), extras}. UI-changing commands answer after settleMs " +
          "(default 250) so the next screenshot shows their effect.",
      inputSchema =
        schema(
          """
          {"type":"object","required":["cmd"],"properties":{
            "simulator":{"type":"string","description":"Simulator id from simulator_launch; optional when only one runs."},
            "cmd":{"type":"string","enum":["info","hierarchy","find","tap","longPress","swipe","key","back","text","launch","finish","activities","startActivity","startService","stopService","bindService","unbindService","services","broadcast","notifications","query","components","qualifiers","permissions","wait","ping"]},
            "text":{"type":"string"},"textContains":{"type":"string"},"desc":{"type":"string"},"descContains":{"type":"string"},
            "resourceId":{"type":"string"},"cls":{"type":"string"},"clickable":{"type":"boolean"},"index":{"type":"integer"},
            "x":{"type":"number"},"y":{"type":"number"},"x1":{"type":"number"},"y1":{"type":"number"},"x2":{"type":"number"},"y2":{"type":"number"},
            "durationMs":{"type":"integer"},"steps":{"type":"integer"},"ms":{"type":"integer"},"settleMs":{"type":"integer"},
            "key":{"type":["string","integer"]},"flat":{"type":"boolean"},"value":{"type":"string"},
            "intent":{"type":"object"},"uri":{"type":"string"},"projection":{"type":"array","items":{"type":"string"}},
            "selection":{"type":"string"},"args":{"type":"array","items":{"type":"string"}},"sort":{"type":"string"},"limit":{"type":"integer"},
            "policy":{"type":"string","enum":["grant","deny"]},"grant":{"type":"array","items":{"type":"string"}},"deny":{"type":"array","items":{"type":"string"}}
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
            "path":{"type":"string","description":"Also write the PNG here; the image is then not inlined."}
          }}
          """
        ),
      readOnlyHint = true,
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

  companion object {
    const val LAUNCH = "simulator_launch"
    const val COMMAND = "simulator_command"
    const val SCREENSHOT = "simulator_screenshot"
    const val STOP = "simulator_stop"
    val TOOL_NAMES: List<String> = listOf(LAUNCH, COMMAND, SCREENSHOT, STOP)

    private const val STDERR_LINES = 400
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
