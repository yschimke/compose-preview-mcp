package ee.schimke.composeai.mcp

import com.google.common.truth.Truth.assertThat
import ee.schimke.composeai.mcp.protocol.CallToolResult
import ee.schimke.composeai.mcp.protocol.ContentBlock
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * [SimulatorMcp] against a stand-in for `compose-preview launch --control stdio`: a shell script
 * that speaks the protocol (a `ready` event, then one reply per command line, with an event before
 * a `tap` and an error for `explode`), so the tools are tested without a JVM or an APK.
 */
class SimulatorMcpTest {
  @get:Rule val tmp = TemporaryFolder()

  private val started = mutableListOf<List<String>>()

  private val fake =
    """
    echo "starting" >&2
    echo '{"event":"ready","package":"com.example","width":540,"height":960}'
    while IFS= read -r line; do
      id=$(printf '%s' "${'$'}line" | sed -n 's/.*"id":\([0-9]*\).*/\1/p')
      cmd=$(printf '%s' "${'$'}line" | sed -n 's/.*"cmd":"\([^"]*\)".*/\1/p')
      case "${'$'}cmd" in
        tap)
          echo '{"event":"activity","state":"resumed","activity":"com.example.Next"}'
          echo "{\"id\":${'$'}id,\"ok\":true,\"result\":{\"x\":1,\"y\":2}}" ;;
        screenshot)
          echo "{\"id\":${'$'}id,\"ok\":true,\"result\":{\"width\":540,\"height\":960,\"png\":\"iVBORw0KGgo=\"}}" ;;
        restart|update|reload)
          echo '{"event":"restarted","reason":"restart"}'
          echo "{\"id\":${'$'}id,\"ok\":true,\"result\":{\"totalMs\":5,\"sent\":${'$'}line}}" ;;
        explode)
          echo "app threw" >&2
          echo "{\"id\":${'$'}id,\"ok\":false,\"error\":\"java.lang.IllegalStateException: boom\"}" ;;
        *)
          echo "{\"id\":${'$'}id,\"ok\":true,\"result\":{\"cmd\":\"${'$'}cmd\"}}" ;;
      esac
    done
    """
      .trimIndent()

  private val mcp =
    SimulatorMcp(
      start = { args, _ ->
        started += args
        ProcessBuilder("bash", "-c", fake).start()
      },
      readyTimeoutMs = 10_000,
      commandTimeoutMs = 10_000,
    )

  @After fun tearDown() = mcp.shutdown()

  private fun call(name: String, args: JsonObject): CallToolResult = runBlocking {
    mcp.handle(name, args)!!
  }

  private fun apk(): File = tmp.newFile("app.apk").apply { writeText("not really an apk") }

  @Test
  fun `launch starts the cli on the apk with the control channel and returns ready info`() {
    val apk = apk()
    val result =
      call(
        SimulatorMcp.LAUNCH,
        buildJsonObject {
          put("apk", apk.path)
          put("headless", true)
          put("qualifiers", "+night")
          put("sdk", 34)
        },
      )

    assertThat(result.isError).isNull()
    assertThat(started.single())
      .containsExactly(
        "launch",
        apk.absolutePath,
        "--control",
        "stdio",
        "--headless",
        "--qualifiers",
        "+night",
        "--sdk",
        "34",
      )
      .inOrder()
    val structured = result.structuredContent!!
    assertThat(structured["simulator"]!!.jsonPrimitive.content).isEqualTo("sim-1")
    assertThat(structured["info"]!!.jsonObject["package"]!!.jsonPrimitive.content)
      .isEqualTo("com.example")
  }

  @Test
  fun `a window is the default, with the agent overlay unless asked otherwise`() {
    val apk = apk()
    call(SimulatorMcp.LAUNCH, buildJsonObject { put("apk", apk.path) })
    assertThat(started.single()).containsNoneOf("--headless", "--hide-agent")
    mcp.shutdown()
    call(
      SimulatorMcp.LAUNCH,
      buildJsonObject {
        put("apk", apk.path)
        put("showAgent", false)
      },
    )
    assertThat(started.last()).contains("--hide-agent")
  }

  @Test
  fun `an agent data layer and a fingerprint pass through to the launch`() {
    val apk = apk()
    call(
      SimulatorMcp.LAUNCH,
      buildJsonObject {
        put("apk", apk.path)
        put("dataLayer", "agent")
        put("fingerprint", "google/sim/sim:16/X/1:user/release-keys")
      },
    )
    assertThat(started.single())
      .containsAtLeast(
        "--data-layer",
        "agent",
        "--fingerprint",
        "google/sim/sim:16/X/1:user/release-keys",
      )
      .inOrder()
  }

  @Test
  fun `a Play services state passes through to the launch`() {
    call(
      SimulatorMcp.LAUNCH,
      buildJsonObject {
        put("apk", apk().path)
        put("playServices", "none")
      },
    )
    assertThat(started.single()).containsAtLeast("--play-services", "none").inOrder()
  }

  @Test
  fun `a home screen and its widgets pass through to the launch`() {
    call(
      SimulatorMcp.LAUNCH,
      buildJsonObject {
        put("apk", apk().path)
        put("launcher", true)
        put(
          "widgets",
          buildJsonArray {
            add("WeatherAppWidgetReceiver")
            add("TaskListWidgetReceiver")
          },
        )
      },
    )
    assertThat(started.single())
      .containsAtLeast(
        "--launcher",
        "--widget",
        "WeatherAppWidgetReceiver",
        "--widget",
        "TaskListWidgetReceiver",
      )
      .inOrder()
  }

  @Test
  fun `Wear tiles pass through to the launch`() {
    call(
      SimulatorMcp.LAUNCH,
      buildJsonObject {
        put("apk", apk().path)
        put("tiles", buildJsonArray { add("GoalsTileService") })
      },
    )
    assertThat(started.single()).containsAtLeast("--tile", "GoalsTileService").inOrder()
  }

  @Test
  fun `the command schema has the Wear system UI's commands and arguments`() {
    val command = mcp.toolDefs().single { it.name == SimulatorMcp.COMMAND }
    val properties = command.inputSchema.jsonObject["properties"]!!.jsonObject
    val cmds = properties["cmd"]!!.jsonObject["enum"]!!.jsonArray.map { it.jsonPrimitive.content }
    assertThat(cmds)
      .containsAtLeast(
        "launcher",
        "notifications",
        "notification",
        "ongoing",
        "tiles",
        "health",
        "quickSettings",
        "rotary",
      )
    assertThat(properties.keys)
      .containsAtLeast("component", "label", "heartRate", "heartRateAccuracy", "delta", "count")
  }

  @Test
  fun `the command schema has the home screen's commands and widget actions`() {
    val command = mcp.toolDefs().single { it.name == SimulatorMcp.COMMAND }
    val properties = command.inputSchema.jsonObject["properties"]!!.jsonObject
    val cmds = properties["cmd"]!!.jsonObject["enum"]!!.jsonArray.map { it.jsonPrimitive.content }
    assertThat(cmds)
      .containsAtLeast("home", "drawer", "shortcuts", "shortcut", "widgets", "widget", "picker")
    val actions =
      properties["action"]!!.jsonObject["enum"]!!.jsonArray.map { it.jsonPrimitive.content }
    assertThat(actions)
      .containsAtLeast("add", "remove", "resize", "update", "reconfigure", "pin", "open", "close")
    assertThat(actions).containsAtLeast("connect", "reply")
    assertThat(properties.keys)
      .containsAtLeast("provider", "widgetId", "spanX", "spanY", "shortcutId")
  }

  @Test
  fun `the command schema accepts the screen and Play services states`() {
    val command = mcp.toolDefs().single { it.name == SimulatorMcp.COMMAND }
    val properties = command.inputSchema.jsonObject["properties"]!!.jsonObject
    val cmds = properties["cmd"]!!.jsonObject["enum"]!!.jsonArray.map { it.jsonPrimitive.content }
    assertThat(cmds).containsAtLeast("screen", "playServices")
    val states =
      properties["state"]!!.jsonObject["enum"]!!.jsonArray.map { it.jsonPrimitive.content }
    assertThat(states)
      .containsExactly(
        "on",
        "ambient",
        "off",
        "installed",
        "none",
        "outdated",
        "disabled",
        "invalid",
      )
  }

  @Test
  fun `commands return results and the events the app produced`() {
    call(SimulatorMcp.LAUNCH, buildJsonObject { put("apk", apk().path) })

    val tap =
      call(
        SimulatorMcp.COMMAND,
        buildJsonObject {
          put("cmd", "tap")
          put("text", "Open")
        },
      )

    assertThat(tap.isError).isNull()
    val structured = tap.structuredContent!!
    assertThat(structured["result"]!!.jsonObject["x"]!!.jsonPrimitive.content).isEqualTo("1")
    val events = structured["events"]!!.jsonArray
    assertThat(events.single().jsonObject["activity"]!!.jsonPrimitive.content)
      .isEqualTo("com.example.Next")
    // Drained: the next call reports only new events.
    val info = call(SimulatorMcp.COMMAND, buildJsonObject { put("cmd", "info") })
    assertThat(info.structuredContent!!["events"]!!.jsonArray).isEmpty()
  }

  @Test
  fun `a failed command is an error result carrying the simulator's message`() {
    call(SimulatorMcp.LAUNCH, buildJsonObject { put("apk", apk().path) })
    val result = call(SimulatorMcp.COMMAND, buildJsonObject { put("cmd", "explode") })
    assertThat(result.isError).isTrue()
    assertThat((result.content.first() as ContentBlock.Text).text).contains("boom")
  }

  @Test
  fun `screenshot returns the png as an image block`() {
    call(SimulatorMcp.LAUNCH, buildJsonObject { put("apk", apk().path) })
    val result = call(SimulatorMcp.SCREENSHOT, JsonObject(emptyMap()))
    val image = result.content.filterIsInstance<ContentBlock.Image>().single()
    assertThat(image.mimeType).isEqualTo("image/png")
    assertThat(image.data).isEqualTo("iVBORw0KGgo=")
    assertThat(result.structuredContent!!.containsKey("png")).isFalse()
  }

  @Test
  fun `two simulators must be named, and stop ends one`() {
    call(SimulatorMcp.LAUNCH, buildJsonObject { put("apk", apk().path) })
    call(SimulatorMcp.LAUNCH, buildJsonObject { put("apk", tmp.newFile("b.apk").path) })

    val ambiguous = call(SimulatorMcp.COMMAND, buildJsonObject { put("cmd", "info") })
    assertThat(ambiguous.isError).isTrue()
    assertThat((ambiguous.content.first() as ContentBlock.Text).text).contains("sim-1, sim-2")

    call(SimulatorMcp.STOP, buildJsonObject { put("simulator", "sim-1") })
    val only = call(SimulatorMcp.COMMAND, buildJsonObject { put("cmd", "info") })
    assertThat(only.isError).isNull()
  }

  @Test
  fun `a simulator that exits before ready reports its stderr`() {
    val failing =
      SimulatorMcp(
        start = { _, _ ->
          ProcessBuilder("bash", "-c", "echo 'needs Java 21+' >&2; exit 1").start()
        },
        readyTimeoutMs = 10_000,
      )
    val result = runBlocking {
      failing.handle(
        SimulatorMcp.LAUNCH,
        buildJsonObject { put("apk", tmp.newFile("c.apk").path) },
      )!!
    }
    assertThat(result.isError).isTrue()
    assertThat((result.content.first() as ContentBlock.Text).text).contains("needs Java 21+")
    // Its exit code, not "still running": stdout closes before the process is reaped.
    assertThat((result.content.first() as ContentBlock.Text).text).contains("exit 1")
    assertThat((result.content.first() as ContentBlock.Text).text).doesNotContain("still running")
  }

  @Test
  fun `the CLI runs in the project, or the build holding the APK`() {
    val project = tmp.newFolder("app")
    File(project, "settings.gradle.kts").writeText("")
    val apk = File(project, "app/build/outputs/apk/debug/app-debug.apk")
    apk.parentFile.mkdirs()
    apk.writeText("")
    val directories = mutableListOf<File?>()
    val recording =
      SimulatorMcp(
        start = { _, directory ->
          directories += directory
          ProcessBuilder("bash", "-c", fake).start()
        },
        readyTimeoutMs = 10_000,
      )
    runBlocking {
      recording.handle(SimulatorMcp.LAUNCH, buildJsonObject { put("project", project.path) })
      recording.handle(SimulatorMcp.LAUNCH, buildJsonObject { put("apk", apk.path) })
      recording.handle(
        SimulatorMcp.LAUNCH,
        buildJsonObject { put("apk", tmp.newFile("loose.apk").path) },
      )
    }
    recording.shutdown()
    assertThat(directories.map { it?.canonicalFile })
      .containsExactly(project.canonicalFile, project.canonicalFile, null)
      .inOrder()
  }

  @Test
  fun `a launch cancelled before ready closes its simulator`() {
    val child = AtomicReference<Process>()
    val slow =
      SimulatorMcp(
        start = { _, _ ->
          ProcessBuilder("bash", "-c", "echo converting >&2; sleep 60").start().also(child::set)
        },
        readyTimeoutMs = 60_000,
      )
    runBlocking {
      val call = async {
        slow.handle(SimulatorMcp.LAUNCH, buildJsonObject { put("apk", apk().path) })
      }
      while (child.get() == null) delay(20)
      delay(200)
      call.cancelAndJoin()
    }
    assertThat(child.get().isAlive).isFalse()
    // Nothing was registered, so no simulator answers.
    val result = runBlocking {
      slow.handle(SimulatorMcp.COMMAND, buildJsonObject { put("cmd", "info") })!!
    }
    assertThat((result.content.first() as ContentBlock.Text).text).contains("No simulator")
  }

  @Test
  fun `a missing apk is refused before anything starts`() {
    val result = call(SimulatorMcp.LAUNCH, buildJsonObject { put("apk", "/no/such.apk") })
    assertThat(result.isError).isTrue()
    assertThat(started).isEmpty()
  }

  @Test
  fun `a paired launch waits for both devices and keeps their log lines apart`() {
    val pairedFake =
      """
      echo '[phone] I/App: phone up' >&2
      echo '{"device":"phone","event":"ready","package":"com.example"}'
      echo '[watch] I/App: watch up' >&2
      echo '{"device":"watch","event":"ready","package":"com.example.wear"}'
      while IFS= read -r line; do
        id=$(printf '%s' "${'$'}line" | sed -n 's/.*"id":\([0-9]*\).*/\1/p')
        echo "{\"device\":\"watch\",\"event\":\"activity\",\"state\":\"resumed\"}"
        echo "{\"device\":\"watch\",\"id\":${'$'}id,\"ok\":true,\"result\":{}}"
      done
      """
        .trimIndent()
    val args = mutableListOf<String>()
    val paired =
      SimulatorMcp(
        start = { it, _ ->
          args += it
          ProcessBuilder("bash", "-c", pairedFake).start()
        },
        readyTimeoutMs = 10_000,
        commandTimeoutMs = 10_000,
      )
    try {
      val watch = tmp.newFile("wear.apk")
      val launch = runBlocking {
        paired.handle(
          SimulatorMcp.LAUNCH,
          buildJsonObject {
            put("apk", apk().path)
            put("pair", watch.path)
          },
        )!!
      }
      assertThat(launch.isError).isNull()
      assertThat(args).containsAtLeast("--pair", watch.absolutePath).inOrder()
      val info = launch.structuredContent!!["info"]!!.jsonObject
      assertThat(info["watch"]!!.jsonObject["package"]!!.jsonPrimitive.content)
        .isEqualTo("com.example.wear")
      assertThat(info["phone"]!!.jsonObject["package"]!!.jsonPrimitive.content)
        .isEqualTo("com.example")

      val command = runBlocking {
        paired.handle(
          SimulatorMcp.COMMAND,
          buildJsonObject {
            put("cmd", "info")
            put("device", "watch")
          },
        )!!
      }
      val event = command.structuredContent!!["events"]!!.jsonArray.single().jsonObject
      assertThat(event["device"]!!.jsonPrimitive.content).isEqualTo("watch")

      val logs = runBlocking {
        paired.handle(SimulatorMcp.LOGS, buildJsonObject { put("device", "watch") })!!
      }
      assertThat(logs.structuredContent!!["lines"]!!.jsonArray.map { it.jsonPrimitive.content })
        .containsExactly("[watch] I/App: watch up")
    } finally {
      paired.shutdown()
    }
  }

  @Test
  fun `restart and update are the cli's own commands, with their timings and events`() {
    call(SimulatorMcp.LAUNCH, buildJsonObject { put("apk", apk().path) })

    val restart = call(SimulatorMcp.RESTART, buildJsonObject { put("keepData", false) })
    assertThat(restart.isError).isNull()
    val restarted = restart.structuredContent!!
    assertThat(restarted["totalMs"]!!.jsonPrimitive.content).isEqualTo("5")
    val sent = restarted["sent"]!!.jsonObject
    assertThat(sent["cmd"]!!.jsonPrimitive.content).isEqualTo("restart")
    assertThat(sent["keepData"]!!.jsonPrimitive.content).isEqualTo("false")
    assertThat(restarted["events"]!!.jsonArray.single().jsonObject["event"]!!.jsonPrimitive.content)
      .isEqualTo("restarted")

    val rebuilt = tmp.newFile("rebuilt.apk")
    val update = call(SimulatorMcp.UPDATE, buildJsonObject { put("apk", rebuilt.path) })
    assertThat(update.isError).isNull()
    val updateSent = update.structuredContent!!["sent"]!!.jsonObject
    assertThat(updateSent["cmd"]!!.jsonPrimitive.content).isEqualTo("update")
    assertThat(updateSent["apk"]!!.jsonPrimitive.content).isEqualTo(rebuilt.absolutePath)
  }

  @Test
  fun `update needs an apk that exists`() {
    call(SimulatorMcp.LAUNCH, buildJsonObject { put("apk", apk().path) })
    val missing = call(SimulatorMcp.UPDATE, buildJsonObject { put("apk", "/no/such.apk") })
    assertThat(missing.isError).isTrue()
    assertThat(call(SimulatorMcp.UPDATE, JsonObject(emptyMap())).isError).isTrue()
  }

  @Test
  fun `a launch from a project needs no apk, and reload is the cli's reload`() {
    val project = tmp.newFolder("project")
    val launch =
      call(
        SimulatorMcp.LAUNCH,
        buildJsonObject {
          put("project", project.path)
          put("module", "phone")
          put("live", true)
          put(
            "gradleArgs",
            kotlinx.serialization.json.JsonArray(
              listOf(kotlinx.serialization.json.JsonPrimitive("--max-workers=2"))
            ),
          )
        },
      )
    assertThat(launch.isError).isNull()
    assertThat(started.single())
      .containsExactly(
        "launch",
        "--project",
        project.absolutePath,
        "--module",
        "phone",
        "--live",
        "--gradle-arg",
        "--max-workers=2",
        "--control",
        "stdio",
      )
      .inOrder()

    val reload = call(SimulatorMcp.RELOAD, buildJsonObject { put("full", true) })
    assertThat(reload.isError).isNull()
    val sent = reload.structuredContent!!["sent"]!!.jsonObject
    assertThat(sent["cmd"]!!.jsonPrimitive.content).isEqualTo("reload")
    assertThat(sent["full"]!!.jsonPrimitive.content).isEqualTo("true")
  }

  @Test
  fun `a launch needs an apk or a project`() {
    val result = call(SimulatorMcp.LAUNCH, JsonObject(emptyMap()))
    assertThat(result.isError).isTrue()
    assertThat(started).isEmpty()
  }

  /** `launch --adb`'s stand-in: the device event, a system boot, then the APK installed. */
  private val adbFake =
    """
    echo '{"event":"device","serial":"emulator-5556","adbPort":5557,"consolePort":5556,"grpcPort":8556,"avd":"Compose_Preview_Phone","formFactor":"phone"}'
    echo '{"event":"ready","package":"ee.schimke.composeai.simulator.system"}'
    echo '{"event":"ready","package":"com.example"}'
    echo '{"event":"installed","package":"com.example"}'
    while IFS= read -r line; do :; done
    """
      .trimIndent()

  private fun adbMcp(
    script: String = adbFake,
    started: MutableList<List<String>> = mutableListOf(),
    adbCalls: MutableList<List<String>> = mutableListOf(),
    answer: (List<String>) -> SimulatorMcp.AdbRun = {
      SimulatorMcp.AdbRun(0, "ok\n".toByteArray(), "")
    },
  ) =
    SimulatorMcp(
      start = { it, _ ->
        started += it
        ProcessBuilder("bash", "-c", script).start()
      },
      readyTimeoutMs = 10_000,
      commandTimeoutMs = 10_000,
      adb = { args, _ ->
        adbCalls += args
        answer(args)
      },
    )

  @Test
  fun `an adb launch needs no apk and returns the device`() {
    val started = mutableListOf<List<String>>()
    val device = adbMcp(started = started)
    try {
      val result = runBlocking {
        device.handle(
          SimulatorMcp.LAUNCH,
          buildJsonObject {
            put("adb", true)
            put("formFactor", "wear")
          },
        )!!
      }
      assertThat(result.isError).isNull()
      assertThat(started.single())
        .containsExactly("launch", "--adb", "--form-factor", "wear", "--control", "stdio")
        .inOrder()
      val serial = result.structuredContent!!["device"]!!.jsonObject["serial"]!!.jsonPrimitive
      assertThat(serial.content).isEqualTo("emulator-5556")
    } finally {
      device.shutdown()
    }
  }

  @Test
  fun `an adb launch with an apk answers once it is installed`() {
    val device = adbMcp()
    try {
      val result = runBlocking {
        device.handle(
          SimulatorMcp.LAUNCH,
          buildJsonObject {
            put("adb", true)
            put("apk", apk().path)
          },
        )!!
      }
      assertThat(result.isError).isNull()
      assertThat(result.structuredContent!!["installed"]!!.jsonPrimitive.content)
        .isEqualTo("com.example")
    } finally {
      device.shutdown()
    }
  }

  @Test
  fun `an apk the device cannot install fails the launch`() {
    val failing =
      adbMcp(
        script =
          """
          echo '{"event":"device","serial":"emulator-5554","adbPort":5555,"consolePort":5554}'
          echo '{"event":"ready","package":"ee.schimke.composeai.simulator.system"}'
          echo '{"event":"installFailed","apk":"x.apk","error":"invalid package name"}'
          while IFS= read -r line; do :; done
          """
            .trimIndent()
      )
    try {
      val result = runBlocking {
        failing.handle(
          SimulatorMcp.LAUNCH,
          buildJsonObject {
            put("adb", true)
            put("apk", apk().path)
          },
        )!!
      }
      assertThat(result.isError).isTrue()
      assertThat((result.content.first() as ContentBlock.Text).text)
        .contains("invalid package name")
    } finally {
      failing.shutdown()
    }
  }

  @Test
  fun `simulator_adb runs adb against the device's serial`() {
    val calls = mutableListOf<List<String>>()
    val device = adbMcp(adbCalls = calls)
    try {
      runBlocking { device.handle(SimulatorMcp.LAUNCH, buildJsonObject { put("adb", true) })!! }
      val result = runBlocking {
        device.handle(
          SimulatorMcp.ADB,
          buildJsonObject { put("command", "shell 'pm list packages' -3") },
        )!!
      }
      assertThat(result.isError).isNull()
      assertThat(calls)
        .containsExactly(
          listOf("-s", "emulator-5556", "wait-for-device"),
          listOf("-s", "emulator-5556", "shell", "pm list packages", "-3"),
        )
        .inOrder()
      assertThat(result.structuredContent!!["stdout"]!!.jsonPrimitive.content).isEqualTo("ok\n")
    } finally {
      device.shutdown()
    }
  }

  @Test
  fun `simulator_adb writes stdout bytes to output and reports a failing exit`() {
    val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte())
    val device =
      adbMcp(
        answer = { args ->
          when {
            "screencap" in args -> SimulatorMcp.AdbRun(0, png, "")
            "false" in args -> SimulatorMcp.AdbRun(1, ByteArray(0), "boom")
            else -> SimulatorMcp.AdbRun(0, ByteArray(0), "")
          }
        }
      )
    try {
      runBlocking { device.handle(SimulatorMcp.LAUNCH, buildJsonObject { put("adb", true) })!! }
      val out = File(tmp.root, "screen.png")
      val shot = runBlocking {
        device.handle(
          SimulatorMcp.ADB,
          buildJsonObject {
            put("args", JsonArray(listOf("exec-out", "screencap", "-p").map(::JsonPrimitive)))
            put("output", out.path)
          },
        )!!
      }
      assertThat(shot.isError).isNull()
      assertThat(out.readBytes()).isEqualTo(png)
      val failed = runBlocking {
        device.handle(SimulatorMcp.ADB, buildJsonObject { put("command", "shell false") })!!
      }
      assertThat(failed.isError).isTrue()
      assertThat(failed.structuredContent!!["exitCode"]!!.jsonPrimitive.content).isEqualTo("1")
      assertThat(failed.structuredContent!!["stderr"]!!.jsonPrimitive.content).isEqualTo("boom")
    } finally {
      device.shutdown()
    }
  }

  @Test
  fun `simulator_adb refuses other devices and simulators without adb`() {
    val calls = mutableListOf<List<String>>()
    val device = adbMcp(adbCalls = calls)
    try {
      runBlocking { device.handle(SimulatorMcp.LAUNCH, buildJsonObject { put("adb", true) })!! }
      for (line in listOf("-s other shell ls", "connect localhost:5555", "kill-server")) {
        val refused = runBlocking {
          device.handle(SimulatorMcp.ADB, buildJsonObject { put("command", line) })!!
        }
        assertThat(refused.isError).isTrue()
      }
      // An option of the device command itself is fine.
      val ok = runBlocking {
        device.handle(SimulatorMcp.ADB, buildJsonObject { put("command", "shell ls -d /sdcard") })!!
      }
      assertThat(ok.isError).isNull()
      assertThat(calls.filter { "wait-for-device" !in it })
        .containsExactly(listOf("-s", "emulator-5556", "shell", "ls", "-d", "/sdcard"))
    } finally {
      device.shutdown()
    }
    // A simulator launched without adb has no device.
    call(SimulatorMcp.LAUNCH, buildJsonObject { put("apk", apk().path) })
    val noDevice = call(SimulatorMcp.ADB, buildJsonObject { put("command", "devices") })
    assertThat((noDevice.content.first() as ContentBlock.Text).text).contains("adb: true")
  }

  @Test
  fun `simulator_adb gives a command its own timeout, not the launch's`() {
    val timeouts = mutableListOf<Long>()
    val device =
      SimulatorMcp(
        start = { _, _ -> ProcessBuilder("bash", "-c", adbFake).start() },
        readyTimeoutMs = 10_000,
        commandTimeoutMs = 10_000,
        adb = { _, timeout ->
          timeouts += timeout
          SimulatorMcp.AdbRun(0, ByteArray(0), "")
        },
      )
    try {
      runBlocking { device.handle(SimulatorMcp.LAUNCH, buildJsonObject { put("adb", true) })!! }
      runBlocking {
        device.handle(SimulatorMcp.ADB, buildJsonObject { put("command", "install a.apk") })!!
      }
      runBlocking {
        device.handle(
          SimulatorMcp.ADB,
          buildJsonObject {
            put("command", "install a.apk")
            put("timeoutMs", 900_000)
          },
        )!!
      }
      // After each wait-for-device: the default, then the asked-for limit, neither cut to 10s.
      assertThat(timeouts.filterIndexed { i, _ -> i % 2 == 1 }).containsExactly(600_000L, 900_000L)
    } finally {
      device.shutdown()
    }
  }

  @Test
  fun `commands split as a shell splits words`() {
    assertThat(SimulatorMcp.splitCommand("""shell am start -n "com.x/.A b" 'it''s' a\ b"""))
      .containsExactly("shell", "am", "start", "-n", "com.x/.A b", "its", "a b")
      .inOrder()
  }
}
