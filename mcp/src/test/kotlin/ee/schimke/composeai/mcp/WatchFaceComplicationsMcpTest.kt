package ee.schimke.composeai.mcp

import com.google.common.truth.Truth.assertThat
import ee.schimke.composeai.daemon.client.WorkspaceId
import ee.schimke.composeai.daemon.protocol.AmbientStateOverride
import ee.schimke.composeai.daemon.protocol.DataProductCapability
import ee.schimke.composeai.daemon.protocol.DataProductTransport
import ee.schimke.composeai.daemon.protocol.RemoteNamedValue
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WatchFaceComplicationsMcpTest {

  @get:Rule val tmp = TemporaryFolder()

  private lateinit var supervisor: DaemonSupervisor
  private lateinit var factory: FakeDaemonClientFactory
  private lateinit var server: DaemonMcpServer
  private lateinit var client: McpTestClient
  private lateinit var session: McpSession

  private val json = Json { ignoreUnknownKeys = true }

  @Before
  fun setUp() {
    factory = FakeDaemonClientFactory()
    factory.daemonConfigurer = { daemon ->
      daemon.advertisedSupportedOverrides =
        listOf("widthPx", "heightPx", "density", "device", "ambient", "remoteCompose")
      daemon.advertisedDataProducts =
        listOf(
          DataProductCapability(
            kind = "watchface/complications",
            schemaVersion = 1,
            transport = DataProductTransport.BOTH,
            attachable = true,
            fetchable = true,
            requiresRerender = false,
          ),
        )
    }
    supervisor =
      DaemonSupervisor(descriptorProvider = FakeDescriptorProvider(), clientFactory = factory)
    server = DaemonMcpServer(supervisor, workingDirectory = null)

    val (clientToServer, serverFromClient) = pipedPair()
    val (serverToClient, clientFromServer) = pipedPair()
    session = server.newSession(input = serverFromClient, output = serverToClient)
    session.start()
    client = McpTestClient(input = clientFromServer, output = clientToServer)
    client.initialize()
  }

  @After
  fun tearDown() {
    runCatching { client.close() }
    runCatching { session.close() }
    runCatching { supervisor.shutdown() }
  }

  @Test
  fun `WatchFaceOverrides distinguishes complication type installation from in-place data update`() {
    // 1. Installing a new complication type resets unmentioned fields cleanly so previous seeds
    //    do not bleed across complication types.
    val installShortText =
      WatchFaceOverrides.mergeIntoRemoteCompose(
        overridesObj =
          json
            .parseToJsonElement(
              """
              {
                "complications": {
                  "1": {
                    "type": "SHORT_TEXT",
                    "text": "132 BPM"
                  },
                  "2": "EMPTY"
                }
              }
              """,
            )
            .jsonObject,
        explicitRemoteCompose = null,
        explicitAmbient = null,
      )
    val installMap = installShortText.remoteCompose!!.namedValues
    assertThat(installMap["dwf.complication.1.type"])
      .isEqualTo(RemoteNamedValue.IntValue(WatchFaceOverrides.TYPE_SHORT_TEXT))
    assertThat(installMap["dwf.complication.1.text"])
      .isEqualTo(RemoteNamedValue.StringValue("132 BPM"))
    assertThat(installMap["dwf.complication.1.title"]).isEqualTo(RemoteNamedValue.StringValue(""))
    assertThat(installMap["dwf.complication.1.has_monochromatic_image"])
      .isEqualTo(RemoteNamedValue.BooleanValue(false))
    assertThat(installMap["dwf.complication.2.type"])
      .isEqualTo(RemoteNamedValue.IntValue(WatchFaceOverrides.TYPE_EMPTY))
    assertThat(installMap["dwf.complication.2.text"]).isEqualTo(RemoteNamedValue.StringValue(""))

    // 2. Updating complication data in place (omitting `type`) preserves type and unmentioned keys.
    val updateDataOnly =
      WatchFaceOverrides.mergeIntoRemoteCompose(
        overridesObj =
          json
            .parseToJsonElement(
              """
              {
                "complications": {
                  "1": {
                    "value": 84.5,
                    "text": "85%",
                    "colors": ["#00E676", "#FFEA00"],
                    "interpolate": true
                  }
                }
              }
              """,
            )
            .jsonObject,
        explicitRemoteCompose = null,
        explicitAmbient = null,
      )
    val updateMap = updateDataOnly.remoteCompose!!.namedValues
    assertThat(updateMap).doesNotContainKey("dwf.complication.1.type")
    assertThat(updateMap).doesNotContainKey("dwf.complication.1.title")
    assertThat(updateMap["dwf.complication.1.value"]).isEqualTo(RemoteNamedValue.FloatValue(84.5f))
    assertThat(updateMap["dwf.complication.1.text"]).isEqualTo(RemoteNamedValue.StringValue("85%"))
    assertThat(updateMap["dwf.complication.1.color_count"]).isEqualTo(RemoteNamedValue.IntValue(2))
    assertThat(updateMap["dwf.complication.1.color.0"])
      .isEqualTo(RemoteNamedValue.ColorValue("#FF00E676"))
    assertThat(updateMap["dwf.complication.1.color.1"])
      .isEqualTo(RemoteNamedValue.ColorValue("#FFFFEA00"))
    assertThat(updateMap["dwf.complication.1.has_ranged_value_colors"])
      .isEqualTo(RemoteNamedValue.BooleanValue(true))
    assertThat(updateMap["dwf.complication.1.ranged_value_colors_interpolate"])
      .isEqualTo(RemoteNamedValue.BooleanValue(true))

    // 3. Weighted elements & image complication types populate their flags and weights.
    val weightedAndPhoto =
      WatchFaceOverrides.mergeIntoRemoteCompose(
        overridesObj =
          json
            .parseToJsonElement(
              """
              {
                "complications": {
                  "3": {
                    "type": "WEIGHTED_ELEMENTS",
                    "colors": ["#2979FF", "#00E5FF", "#FF4081"],
                    "weights": [50, 30, 20]
                  },
                  "4": {
                    "type": "PHOTO_IMAGE",
                    "imageColor": "#FFD180",
                    "accentColor": "#FF6D00"
                  }
                }
              }
              """,
            )
            .jsonObject,
        explicitRemoteCompose = null,
        explicitAmbient = null,
      )
    val wpMap = weightedAndPhoto.remoteCompose!!.namedValues
    assertThat(wpMap["dwf.complication.3.type"])
      .isEqualTo(RemoteNamedValue.IntValue(WatchFaceOverrides.TYPE_WEIGHTED_ELEMENTS))
    assertThat(wpMap["dwf.complication.3.has_weighted_elements"])
      .isEqualTo(RemoteNamedValue.BooleanValue(true))
    assertThat(wpMap["dwf.complication.3.weight.0"]).isEqualTo(RemoteNamedValue.FloatValue(50f))
    assertThat(wpMap["dwf.complication.3.weight.1"]).isEqualTo(RemoteNamedValue.FloatValue(30f))
    assertThat(wpMap["dwf.complication.3.weight.2"]).isEqualTo(RemoteNamedValue.FloatValue(20f))
    assertThat(wpMap["dwf.complication.3.weight.3"]).isEqualTo(RemoteNamedValue.FloatValue(0f))
    assertThat(wpMap["dwf.complication.4.type"])
      .isEqualTo(RemoteNamedValue.IntValue(WatchFaceOverrides.TYPE_PHOTO_IMAGE))
    assertThat(wpMap["dwf.complication.4.has_photo_image"])
      .isEqualTo(RemoteNamedValue.BooleanValue(true))
    assertThat(wpMap["dwf.complication.4.image_color"])
      .isEqualTo(RemoteNamedValue.ColorValue("#FFFFD180"))
    assertThat(wpMap["dwf.complication.4.accent_color"])
      .isEqualTo(RemoteNamedValue.ColorValue("#FFFF6D00"))
  }

  @Test
  fun `render_preview and get_preview_data forward complication and watchFace overrides`() {
    val projectDir = tmp.newFolder("wf-project")
    tmp.newFolder("wf-project", "app")
    val workspaceId = registerWorkspace(projectDir, "watchface-demo")
    val daemon = warmDaemonFor(workspaceId, ":app")
    val previewId = "dwf__raw_watchface"
    daemon.emitDiscovery(previewId)
    client.expectNotification("notifications/resources/list_changed", 2_000)

    val png = tmp.newFile("watchface.png")
    writeSolidPng(png, 0xFF102030.toInt())
    daemon.autoRenderPngPath = { png.absolutePath }
    daemon.dataFetchHandler = { pid, kind, _, _ ->
      if (pid == previewId && kind == "watchface/complications") {
        FakeDaemon.DataFetchOutcome.Ok(
          kind = kind,
          schemaVersion = 1,
          payload =
            buildJsonObject {
              put("previewId", previewId)
              putJsonArray("slots") {
                add(
                  buildJsonObject {
                    put("slotId", 1)
                    putJsonArray("supportedTypes") {
                      add(JsonPrimitive("SHORT_TEXT"))
                      add(JsonPrimitive("RANGED_VALUE"))
                    }
                  },
                )
              }
            },
        )
      } else {
        FakeDaemon.DataFetchOutcome.Unknown
      }
    }

    val uri = PreviewUri(workspaceId, ":app", previewId).toUri()

    val renderRes =
      client.callTool(
        "render_preview",
        buildJsonObject {
          put("uri", uri)
          put("observe", "hash")
          putJsonObject("overrides") {
            putJsonObject("watchFace") {
              put("isAmbient", true)
              put("batteryPercent", 12)
              put("heartRate", 144)
              putJsonObject("configurations") { put("show_seconds", false) }
              putJsonObject("themeColors") { put("accent", "#00E5FF") }
            }
            putJsonObject("complications") {
              putJsonObject("1") {
                put("type", "RANGED_VALUE")
                put("value", 75)
                put("min", 0)
                put("max", 100)
                put("text", "75%")
              }
            }
          }
        },
      )
    assertThat(renderRes.isError()).isFalse()
    val sentOverrides = daemon.renderOverrides.last()!!
    assertThat(sentOverrides.ambient?.state).isEqualTo(AmbientStateOverride.AMBIENT)
    val named = sentOverrides.remoteCompose!!.namedValues
    assertThat(named["dwf.is_ambient"]).isEqualTo(RemoteNamedValue.BooleanValue(true))
    assertThat(named["dwf.battery_percent"]).isEqualTo(RemoteNamedValue.FloatValue(12f))
    assertThat(named["dwf.battery_is_low"]).isEqualTo(RemoteNamedValue.BooleanValue(true))
    assertThat(named["metrics.heart_rate"]).isEqualTo(RemoteNamedValue.FloatValue(144f))
    assertThat(named["dwf.config.show_seconds"]).isEqualTo(RemoteNamedValue.BooleanValue(false))
    assertThat(named["dwf.color.accent.0"]).isEqualTo(RemoteNamedValue.ColorValue("#FF00E5FF"))
    assertThat(named["dwf.complication.1.type"])
      .isEqualTo(RemoteNamedValue.IntValue(WatchFaceOverrides.TYPE_RANGED_VALUE))
    assertThat(named["dwf.complication.1.value"]).isEqualTo(RemoteNamedValue.FloatValue(75f))
    assertThat(named["dwf.complication.1.text"]).isEqualTo(RemoteNamedValue.StringValue("75%"))

    val dataRes =
      client.callTool(
        "get_preview_data",
        buildJsonObject {
          put("uri", uri)
          put("kind", "watchface/complications")
        },
      )
    assertThat(dataRes.isError()).isFalse()
    val dataJson = json.parseToJsonElement(dataRes.firstTextContent()).jsonObject
    assertThat(dataJson["kind"]!!.jsonPrimitive.content).isEqualTo("watchface/complications")
    assertThat(dataJson["schemaVersion"]!!.jsonPrimitive.content.toInt()).isEqualTo(1)
    val payloadJson = dataJson["payload"]!!.jsonObject
    assertThat(payloadJson["slots"]!!.jsonArray).hasSize(1)
  }

  @Test
  fun `render_matrix scenarios mode renders complication scenarios and stitches contactSheet`() {
    val projectDir = tmp.newFolder("wf-matrix-project")
    tmp.newFolder("wf-matrix-project", "app")
    val workspaceId = registerWorkspace(projectDir, "watchface-matrix")
    val daemon = warmDaemonFor(workspaceId, ":app")
    val previewId = "dwf__raw_watchface"
    daemon.emitDiscovery(previewId)
    client.expectNotification("notifications/resources/list_changed", 2_000)

    val renderSeq = AtomicInteger(0)
    val colors = intArrayOf(0xFF1E293B.toInt(), 0xFF0F766E.toInt(), 0xFF7C2D12.toInt())
    daemon.autoRenderPngPath = {
      val idx = renderSeq.getAndIncrement()
      val f = tmp.newFile("scenario-$idx.png")
      writeSolidPng(f, colors[idx % colors.size], width = 64, height = 64)
      f.absolutePath
    }

    val uri = PreviewUri(workspaceId, ":app", previewId).toUri()
    val matrixRes =
      client.callTool(
        "render_matrix",
        buildJsonObject {
          put("uri", uri)
          put("contactSheet", true)
          put(
            "scenarios",
            buildJsonArray {
              add(buildJsonObject { put("label", "Default") })
              add(
                buildJsonObject {
                  put("label", "ShortText Workout")
                  putJsonObject("overrides") {
                    putJsonObject("complications") {
                      putJsonObject("1") {
                        put("type", "SHORT_TEXT")
                        put("title", "HR")
                        put("text", "156")
                        put("monochromaticImage", true)
                      }
                    }
                  }
                },
              )
              add(
                buildJsonObject {
                  put("label", "Empty Ambient")
                  putJsonObject("overrides") {
                    putJsonObject("watchFace") { put("isAmbient", true) }
                    putJsonObject("complications") { putJsonObject("1") { put("type", "EMPTY") } }
                  }
                },
              )
            },
          )
        },
      )

    assertThat(matrixRes.isError()).isFalse()
    val (sheetB64, mime) = matrixRes.firstImageContent()
    assertThat(mime).isEqualTo("image/png")
    val sheetImg = ImageIO.read(ByteArrayInputStream(Base64.getDecoder().decode(sheetB64)))
    assertThat(sheetImg).isNotNull()
    assertThat(sheetImg.width).isGreaterThan(64)

    val textBlock = matrixRes.textContents().single()
    val payload = json.parseToJsonElement(textBlock).jsonObject
    assertThat(payload["schema"]!!.jsonPrimitive.content).isEqualTo("compose-preview-matrix/v1")
    assertThat(payload["mode"]!!.jsonPrimitive.content).isEqualTo("scenarios")
    assertThat(payload["cellCount"]!!.jsonPrimitive.content.toInt()).isEqualTo(3)
    assertThat(payload["contactSheet"]!!.jsonPrimitive.content.toBoolean()).isTrue()

    val cells = payload["cells"]!!.jsonArray
    assertThat(cells).hasSize(3)
    assertThat(cells[0].jsonObject["label"]!!.jsonPrimitive.content).isEqualTo("Default")
    assertThat(cells[0].jsonObject["changed"]!!.jsonPrimitive.content.toBoolean()).isFalse()
    assertThat(cells[1].jsonObject["label"]!!.jsonPrimitive.content).isEqualTo("ShortText Workout")
    assertThat(cells[1].jsonObject["changed"]!!.jsonPrimitive.content.toBoolean()).isTrue()
    assertThat(cells[2].jsonObject["label"]!!.jsonPrimitive.content).isEqualTo("Empty Ambient")
    assertThat(cells[2].jsonObject["changed"]!!.jsonPrimitive.content.toBoolean()).isTrue()

    assertThat(daemon.renderOverrides).hasSize(3)
    assertThat(daemon.renderOverrides[0]).isNull()
    assertThat(
        daemon.renderOverrides[1]!!.remoteCompose!!.namedValues["dwf.complication.1.type"],
      )
      .isEqualTo(RemoteNamedValue.IntValue(WatchFaceOverrides.TYPE_SHORT_TEXT))
    assertThat(daemon.renderOverrides[2]!!.ambient?.state).isEqualTo(AmbientStateOverride.AMBIENT)
  }

  private fun registerWorkspace(projectDir: File, rootName: String): WorkspaceId {
    val resp =
      client.callTool(
        "register_project",
        buildJsonObject {
          put("path", projectDir.absolutePath)
          put("rootProjectName", rootName)
        },
      )
    val payload = json.parseToJsonElement(resp.firstTextContent()).jsonObject
    val ws = payload["workspaceId"]!!.jsonPrimitive.content
    client.expectNotification("notifications/resources/list_changed", 2_000)
    return WorkspaceId(ws)
  }

  private fun warmDaemonFor(workspaceId: WorkspaceId, modulePath: String): FakeDaemon {
    supervisor.daemonFor(workspaceId, modulePath)
    return factory.daemons.getValue(workspaceId to modulePath)
  }

  private fun writeSolidPng(file: File, argb: Int, width: Int = 16, height: Int = 16) {
    val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
    for (y in 0 until image.height) {
      for (x in 0 until image.width) {
        image.setRGB(x, y, argb)
      }
    }
    ImageIO.write(image, "png", file)
  }

  private fun pipedPair(): Pair<OutputStream, InputStream> {
    val serverSocket = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
    val socket = java.net.Socket(serverSocket.inetAddress, serverSocket.localPort)
    val accepted = serverSocket.accept()
    serverSocket.close()
    return socket.getOutputStream() to accepted.getInputStream()
  }
}
