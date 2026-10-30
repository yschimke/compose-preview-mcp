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
import kotlinx.serialization.json.JsonObject
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
        restart|update)
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
      start = { args ->
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
        start = { ProcessBuilder("bash", "-c", "echo 'needs Java 21+' >&2; exit 1").start() },
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
  }

  @Test
  fun `a launch cancelled before ready closes its simulator`() {
    val child = AtomicReference<Process>()
    val slow =
      SimulatorMcp(
        start = {
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
        start = {
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
}
