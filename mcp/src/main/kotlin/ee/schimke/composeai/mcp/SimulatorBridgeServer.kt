package ee.schimke.composeai.mcp

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Loopback-only HTTP bridge (`127.0.0.1:0`) exposing running [SimulatorMcp] sessions to local
 * sidebar hosts such as Antigravity's Auxiliary Pane sidecar (`compose-agent-plugins`).
 *
 * Started lazily when the first simulator launches and stopped on [SimulatorMcp.shutdown]. Writes a
 * `0600` discovery file (`~/.cache/composeai/simulator-bridge.json`) carrying `{pid, port, token}`
 * so only local processes running as the same user can authenticate to `/api/...`.
 */
internal class SimulatorBridgeServer(
  private val mcp: SimulatorMcp,
  private val bridgeFile: File?,
) : AutoCloseable {
  private val token: String = randomToken()
  private val server: HttpServer =
    HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
      executor = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "simulator-bridge").apply { isDaemon = true }
      }
      createContext("/", ::handleRoot)
      createContext("/api/simulators", ::handleApi)
      start()
    }
  private val closed = AtomicBoolean(false)

  val port: Int
    get() = server.address.port

  val authToken: String
    get() = token

  init {
    writeDiscoveryFile()
  }

  fun writeDiscoveryFile() {
    val target = bridgeFile ?: return
    if (closed.get()) return
    runCatching {
      target.parentFile?.mkdirs()
      val payload = buildJsonObject {
        put("pid", ProcessHandle.current().pid())
        put("port", port)
        put("token", token)
        putJsonArray("simulators") { mcp.activeSimulatorSummaries().forEach { add(it) } }
      }
        .toString()
      val tmp = File(target.parentFile, "${target.name}.${ProcessHandle.current().pid()}.tmp")
      tmp.writeText(payload, StandardCharsets.UTF_8)
      runCatching {
        Files.setPosixFilePermissions(tmp.toPath(), PosixFilePermissions.fromString("rw-------"))
      }
      Files.move(
        tmp.toPath(),
        target.toPath(),
        StandardCopyOption.REPLACE_EXISTING,
        StandardCopyOption.ATOMIC_MOVE,
      )
    }
  }

  override fun close() {
    if (!closed.compareAndSet(false, true)) return
    runCatching { server.stop(0) }
    val target = bridgeFile ?: return
    runCatching {
      if (target.isFile) {
        val currentPid = ProcessHandle.current().pid()
        val parsed = runCatching {
          Json.parseToJsonElement(target.readText()).jsonObject
        }
          .getOrNull()
        val ownerPid = (parsed?.get("pid") as? JsonPrimitive)?.content?.toLongOrNull()
        if (ownerPid == null || ownerPid == currentPid) {
          target.delete()
        }
      }
    }
  }

  private fun handleRoot(exchange: HttpExchange) {
    if (exchange.requestMethod != "GET") {
      sendJson(exchange, 405, buildJsonObject { put("error", "method not allowed") })
      return
    }
    val html = mcp.readViewerHtml()
    val bytes = html.toByteArray(StandardCharsets.UTF_8)
    exchange.responseHeaders.set("Content-Type", "text/html; charset=utf-8")
    exchange.responseHeaders.set("Cache-Control", "no-store")
    exchange.sendResponseHeaders(200, bytes.size.toLong())
    exchange.responseBody.use { it.write(bytes) }
  }

  private fun handleApi(exchange: HttpExchange) {
    if (!isAuthorized(exchange)) {
      sendJson(exchange, 401, buildJsonObject { put("error", "unauthorized") })
      return
    }
    val path = exchange.requestURI.rawPath.removePrefix("/api/simulators").trim('/')
    val segments = if (path.isEmpty()) emptyList() else path.split('/')
    val query = parseQuery(exchange.requestURI.rawQuery)
    when {
      segments.isEmpty() && exchange.requestMethod == "GET" -> {
        val body = buildJsonObject {
          putJsonArray("simulators") { mcp.activeSimulatorSummaries().forEach { add(it) } }
        }
        sendJson(exchange, 200, body)
      }
      segments.size == 2 -> {
        val simId = decode(segments[0])
        val action = segments[1]
        handleSimulatorAction(exchange, simId, action, query)
      }
      else -> sendJson(exchange, 404, buildJsonObject { put("error", "not found") })
    }
  }

  private fun handleSimulatorAction(
    exchange: HttpExchange,
    simId: String,
    action: String,
    query: Map<String, String>,
  ) {
    val bodyJson =
      if (exchange.requestMethod == "POST") {
        val raw = exchange.requestBody.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
        if (raw.isBlank()) {
          JsonObject(emptyMap())
        } else {
          runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrNull()
            ?: run {
              sendJson(exchange, 400, buildJsonObject { put("error", "invalid JSON body") })
              return
            }
        }
      } else {
        JsonObject(emptyMap())
      }

    val toolName =
      when (action) {
        "screenshot" -> SimulatorMcp.SCREENSHOT
        "command" -> SimulatorMcp.COMMAND
        "logs" -> SimulatorMcp.LOGS
        "reload" -> SimulatorMcp.RELOAD
        "restart" -> SimulatorMcp.RESTART
        "stop" -> SimulatorMcp.STOP
        else -> {
          sendJson(exchange, 404, buildJsonObject { put("error", "unknown action: $action") })
          return
        }
      }

    val args = buildJsonObject {
      for ((k, v) in bodyJson) put(k, v)
      put("simulator", simId)
      query["device"]?.takeIf { it.isNotBlank() }?.let { put("device", it) }
      query["lines"]?.toIntOrNull()?.let { put("lines", it) }
    }

    val result = runBlocking { mcp.handle(toolName, args) }
    if (result == null) {
      sendJson(exchange, 404, buildJsonObject { put("error", "unknown tool") })
      return
    }
    val payload = buildJsonObject {
      if (result.isError == true) put("isError", true)
      result.structuredContent?.let { put("structuredContent", it) }
      result.meta?.let { put("_meta", it) }
      putJsonArray("content") {
        result.content.forEach { block ->
          when (block) {
            is ee.schimke.composeai.mcp.protocol.ContentBlock.Text ->
              add(
                buildJsonObject {
                  put("type", "text")
                  put("text", block.text)
                },
              )
            is ee.schimke.composeai.mcp.protocol.ContentBlock.Image ->
              add(
                buildJsonObject {
                  put("type", "image")
                  put("data", block.data)
                  put("mimeType", block.mimeType)
                },
              )
            else -> {}
          }
        }
      }
    }
    sendJson(exchange, if (result.isError == true) 400 else 200, payload)
  }

  private fun isAuthorized(exchange: HttpExchange): Boolean {
    val header = exchange.requestHeaders.getFirst("X-Simulator-Token")
    if (header == token) return true
    val auth = exchange.requestHeaders.getFirst("Authorization")
    if (auth == "Bearer $token") return true
    return false
  }

  private fun sendJson(exchange: HttpExchange, status: Int, body: JsonObject) {
    val bytes = body.toString().toByteArray(StandardCharsets.UTF_8)
    exchange.responseHeaders.set("Content-Type", "application/json; charset=utf-8")
    exchange.responseHeaders.set("Cache-Control", "no-store")
    exchange.sendResponseHeaders(status, bytes.size.toLong())
    exchange.responseBody.use { it.write(bytes) }
  }

  private fun parseQuery(rawQuery: String?): Map<String, String> {
    if (rawQuery.isNullOrBlank()) return emptyMap()
    val result = LinkedHashMap<String, String>()
    for (pair in rawQuery.split('&')) {
      val idx = pair.indexOf('=')
      if (idx > 0) {
        result[decode(pair.substring(0, idx))] = decode(pair.substring(idx + 1))
      }
    }
    return result
  }

  private fun decode(value: String): String = URLDecoder.decode(value, StandardCharsets.UTF_8)

  companion object {
    fun defaultBridgeFile(): File {
      val cacheHome =
        System.getenv("XDG_CACHE_HOME")?.takeIf { it.isNotBlank() }
          ?: File(System.getProperty("user.home"), ".cache").path
      return File(cacheHome, "composeai/simulator-bridge.json")
    }

    private fun randomToken(): String {
      val bytes = ByteArray(24)
      SecureRandom().nextBytes(bytes)
      return bytes.joinToString("") { "%02x".format(it) }
    }
  }
}
