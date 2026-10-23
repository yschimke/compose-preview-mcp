package ee.schimke.composeai.mcp

import com.google.common.truth.Truth.assertThat
import ee.schimke.composeai.daemon.client.WorkspaceId
import ee.schimke.composeai.daemon.protocol.CompileErrorDetail
import ee.schimke.composeai.daemon.protocol.CompileResultKind
import ee.schimke.composeai.daemon.protocol.CompileSourcesResult
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.After
import org.junit.Assume.assumeFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Issue #1169: an agent edits a preview's source, calls `notify_file_changed`, then
 * `render_preview`, and gets the old image back.
 *
 * The [FakeDaemon] here behaves like the real one: it renders whatever the module's *compiled
 * classes* say (a file only the compiler writes), and `fileChanged` only swaps its classloader. So
 * a render is fresh only if something recompiled the module before the swap — which is what the
 * server now does through [SourceCompiler].
 */
class SourceRecompileTest {

  @get:Rule val tmp = TemporaryFolder()

  private val json = Json { ignoreUnknownKeys = true }
  private val factory = FakeDaemonClientFactory()
  private val descriptors = FakeDescriptorProvider()
  private val supervisor =
    DaemonSupervisor(descriptorProvider = descriptors, clientFactory = factory)
  private lateinit var server: DaemonMcpServer
  private lateinit var client: McpTestClient
  private lateinit var session: McpSession

  /** What the fake compiler returns next; null compiles successfully. */
  @Volatile private var nextFailure: SourceCompileOutcome? = null
  /** The work record the fake compiler reports, shaped like a real Android `:app` recompile. */
  @Volatile
  private var nextWork: CompileWork =
    CompileWork(
      task = ":app:composePreviewCompile",
      ms = 1,
      initScript = true,
      tasks =
        listOf(
          ":app:preBuild UP-TO-DATE",
          ":app:generateDebugResValues UP-TO-DATE",
          ":app:packageDebugResources UP-TO-DATE",
          ":app:compileDebugKotlin",
          ":app:compileDebugJavaWithJavac NO-SOURCE",
          ":app:composePreviewCompile",
        ),
    )
  private val compiles = CopyOnWriteArrayList<List<File>>()
  /** `fileChanged` notifications the daemon had already received when each compile ran. */
  private val fileChangesSeenAtCompile = CopyOnWriteArrayList<Int>()
  private lateinit var daemon: FakeDaemon

  private fun start(compiler: SourceCompiler?, compileInProcess: Boolean = true) {
    server =
      DaemonMcpServer(
        supervisor,
        workingDirectory = null,
        sourcePollIntervalMs = 0,
        samplingIntervalMs = 0,
        sourceCompiler = compiler,
        compileInProcess = compileInProcess,
      )
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

  private class Fixture(val workspaceId: WorkspaceId, val source: File, val classes: File)

  /** A module whose "compiled classes" file mirrors the source text only after a compile. */
  private fun fixture(): Fixture {
    val projectDir = tmp.newFolder("workspace")
    val source = File(projectDir, "app/src/main/kotlin/com/example/MainActivity.kt")
    source.parentFile.mkdirs()
    source.writeText("""@Preview fun Header() { Text("Header") }""")
    // A module is a `src/` beside a build file, which is where change detection looks.
    File(projectDir, "app/build.gradle.kts").writeText("")
    val classes = tmp.newFile("compiled-render.png")
    classes.writeText(source.readText())
    val workspaceId = registerWorkspace(projectDir)
    supervisor.daemonFor(workspaceId, ":app")
    daemon = factory.daemons.getValue(workspaceId to ":app")
    daemon.emitDiscovery(PREVIEW_ID, sourceFile = "app/src/main/kotlin/com/example/MainActivity.kt")
    client.expectNotification("notifications/resources/list_changed", 2_000)
    daemon.autoRenderPngPath = { id -> if (id == PREVIEW_ID) classes.absolutePath else null }
    return Fixture(workspaceId, source, classes)
  }

  private fun fakeCompiler(classes: () -> File): SourceCompiler =
    SourceCompiler { _, modulePath, sources ->
      assertThat(modulePath).isEqualTo(":app")
      compiles += sources
      fileChangesSeenAtCompile += daemon.fileChanges.size
      nextFailure
        ?: run {
          classes().writeText(sources.single().readText())
          SourceCompileOutcome.Ok(durationMs = 1, work = nextWork)
        }
    }

  private fun edit(fixture: Fixture, text: String) {
    fixture.source.writeText(text)
    fixture.source.setLastModified(fixture.source.lastModified() + 2_000)
  }

  private fun notifyChanged(fixture: Fixture): String =
    client
      .callTool(
        "notify_file_changed",
        buildJsonObject {
          put("workspaceId", fixture.workspaceId.value)
          put("path", fixture.source.absolutePath)
        },
        timeoutMs = 10_000,
      )
      .firstTextContent()

  private class Render(
    val bytes: String,
    val texts: List<String>,
    val sha256: String,
    val changed: Boolean,
    /** `_meta.work` of the result: this edit→render cycle's [EditCycleWork]. */
    val work: kotlinx.serialization.json.JsonObject?,
    /** The payload's own `stale` field. */
    val stale: String? = null,
  )

  private fun render(fixture: Fixture): Render {
    val result =
      client.callTool(
        "render_preview",
        buildJsonObject {
          put("uri", PreviewUri(fixture.workspaceId, ":app", PREVIEW_ID).toUri())
          put("inline", false)
        },
        timeoutMs = 10_000,
      )
    val payload = json.parseToJsonElement(result.firstTextContent()).jsonObject
    val png = File(payload["pngPath"]!!.jsonPrimitive.content)
    return Render(
      png.readText(),
      result.textContents(),
      sha256 = payload["sha256"]!!.jsonPrimitive.content,
      changed = payload["changed"]!!.jsonPrimitive.content.toBoolean(),
      work = result.raw["_meta"]?.jsonObject?.get("work")?.jsonObject,
      stale = payload["stale"]?.jsonPrimitive?.content,
    )
  }

  /** #1189: a daemon that can compile in process does, and Gradle never runs. */
  @Test
  fun `a daemon with the in-process compiler compiles a Kotlin edit without Gradle`() {
    lateinit var fixture: Fixture
    start(fakeCompiler { fixture.classes })
    fixture = fixture()
    val inProcess = CopyOnWriteArrayList<List<String>>()
    daemon.onCompileSources = { sources ->
      inProcess += sources
      fixture.classes.writeText(File(sources.single()).readText())
      CompileSourcesResult(result = CompileResultKind.OK, durationMs = 5)
    }
    render(fixture)
    inProcess.clear()
    val gradleBefore = compiles.size

    edit(fixture, """@Preview fun Header() { Text("In process") }""")
    val fresh = render(fixture)

    assertThat(fresh.bytes).contains("In process")
    assertThat(inProcess).containsExactly(listOf(fixture.source.absolutePath))
    assertThat(compiles).hasSize(gradleBefore)
    assertThat(fresh.work.toString()).contains(DaemonMcpServer.IN_PROCESS_COMPILE_TASK)
  }

  /**
   * The daemon's warm compiler compiles only what the change set names, and defers every compile
   * without one to the Build Tools API, so the edited file has to arrive as `changes.modified`.
   */
  @Test
  fun `an in-process compile names the edited file in its change set`() {
    lateinit var fixture: Fixture
    start(fakeCompiler { fixture.classes })
    fixture = fixture()
    val params = CopyOnWriteArrayList<kotlinx.serialization.json.JsonObject?>()
    daemon.onCompileSourcesParams = { params += it }
    daemon.onCompileSources = { sources ->
      fixture.classes.writeText(File(sources.single()).readText())
      CompileSourcesResult(result = CompileResultKind.OK, durationMs = 5)
    }
    render(fixture)
    params.clear()

    edit(fixture, """@Preview fun Header() { Text("Change set") }""")
    assertThat(render(fixture).bytes).contains("Change set")

    val changes = params.single()!!["changes"]!!.jsonObject
    assertThat(changes["modified"]!!.jsonArray.map { it.jsonPrimitive.content })
      .containsExactly(fixture.source.absolutePath)
    assertThat(changes["removed"]?.jsonArray.orEmpty()).isEmpty()
  }

  /**
   * The first in-process compile of a daemon pays seconds for a cold compiler, so the server asks
   * it to warm up — once, and after the first image, so warming does not compete with it.
   */
  @Test
  fun `the server asks a daemon to warm its compiler once, after the first render`() {
    lateinit var fixture: Fixture
    start(fakeCompiler { fixture.classes })
    fixture = fixture()
    // The warm-up is sent from an executor, so the test waits for it rather than racing it.
    val warmedWith = java.util.concurrent.LinkedBlockingQueue<List<String>>()
    daemon.onCompileWarmUp = { sources ->
      warmedWith.put(sources)
      java.util.concurrent.CompletableFuture.completedFuture(warmUpResult(ready = true))
    }
    daemon.onCompileSources = { sources ->
      fixture.classes.writeText(File(sources.single()).readText())
      CompileSourcesResult(result = CompileResultKind.OK, durationMs = 5)
    }
    assertThat(warmedWith).isEmpty()
    render(fixture)
    // It warms with the rendered preview's own file, the one about to be edited.
    val sources = warmedWith.poll(10, java.util.concurrent.TimeUnit.SECONDS)
    assertThat(sources).isNotNull()
    assertThat(sources!!.map { File(it).canonicalPath })
      .containsExactly(fixture.source.canonicalPath)
    edit(fixture, """@Preview fun Header() { Text("Warm") }""")
    val fresh = render(fixture)

    assertThat(fresh.bytes).contains("Warm")
    assertThat(fresh.work.toString()).contains(DaemonMcpServer.IN_PROCESS_COMPILE_TASK)
    // Asked once: the second render does not ask again.
    assertThat(warmedWith.poll(500, java.util.concurrent.TimeUnit.MILLISECONDS)).isNull()
  }

  /** A daemon with nothing to warm says so at once, and the next edit compiles in process. */
  @Test
  fun `a daemon with nothing to warm still compiles the next edit in process`() {
    lateinit var fixture: Fixture
    start(fakeCompiler { fixture.classes })
    fixture = fixture()
    daemon.onCompileWarmUp = {
      java.util.concurrent.CompletableFuture.completedFuture(warmUpResult(ready = false))
    }
    daemon.onCompileSources = { sources ->
      fixture.classes.writeText(File(sources.single()).readText())
      CompileSourcesResult(result = CompileResultKind.OK, durationMs = 5)
    }
    render(fixture)
    val gradleBefore = compiles.size

    edit(fixture, """@Preview fun Header() { Text("Cold") }""")
    val fresh = render(fixture)

    assertThat(fresh.bytes).contains("Cold")
    assertThat(fresh.work.toString()).contains(DaemonMcpServer.IN_PROCESS_COMPILE_TASK)
    assertThat(compiles).hasSize(gradleBefore)
  }

  /**
   * An edit made while the daemon warms up still goes to the daemon, which has it wait for the
   * warm-up; Gradle would compete with the warm-up for the CPU and rewrite its classes.
   */
  @Test
  fun `an edit during the warm-up compiles in process, not through Gradle`() {
    lateinit var fixture: Fixture
    start(fakeCompiler { fixture.classes })
    fixture = fixture()
    val warmUp =
      java.util.concurrent.CompletableFuture<
        ee.schimke.composeai.daemon.protocol.CompileWarmUpResult
      >()
    daemon.onCompileWarmUp = { _ -> warmUp }
    val inProcess = CopyOnWriteArrayList<List<String>>()
    daemon.onCompileSources = { sources ->
      inProcess += sources
      fixture.classes.writeText(File(sources.single()).readText())
      CompileSourcesResult(result = CompileResultKind.OK, durationMs = 5)
    }
    try {
      render(fixture)
      val gradleBefore = compiles.size

      edit(fixture, """@Preview fun Header() { Text("While warming") }""")
      val during = render(fixture)

      assertThat(warmUp.isDone).isFalse()
      assertThat(during.bytes).contains("While warming")
      assertThat(inProcess).containsExactly(listOf(fixture.source.absolutePath))
      assertThat(compiles).hasSize(gradleBefore)
    } finally {
      warmUp.complete(warmUpResult(ready = true))
    }
  }

  private fun warmUpResult(ready: Boolean) =
    ee.schimke.composeai.daemon.protocol.CompileWarmUpResult.Builder(ready)
      .also { if (!ready) it.message = "no warm compiler is bundled for Kotlin 2.4.20" }
      .build()

  /** Writes [moduleDir]'s launch descriptor publishing [sourceDir] as its `sourceWatch`. */
  private fun publishSourceDir(
    moduleDir: File,
    modulePath: String,
    sourceDir: File,
    buildFiles: List<File> = emptyList(),
  ) {
    File(moduleDir, "build/compose-previews/daemon-launch.json").apply {
      parentFile.mkdirs()
      writeText(
        """
        {"schemaVersion":2,"modulePath":"$modulePath","variant":"debug","enabled":true,
         "mainClass":"M","classpath":[],"jvmArgs":[],"systemProperties":{},
         "workingDirectory":"${moduleDir.absolutePath}","manifestPath":"${moduleDir.absolutePath}/p.json",
         "sourceWatch":{"sourceDirs":["${sourceDir.absolutePath}"],
           "buildFiles":[${buildFiles.joinToString(",") { "\"${it.absolutePath}\"" }}]}}
        """
          .trimIndent()
      )
    }
  }

  /**
   * #57: the Build Tools API compiles exactly the `sources` it is given, so an edit that uses a
   * sibling file only resolves when the whole module is there. The change set still names only the
   * edit, which is what the warm compiler compiles.
   */
  @Test
  fun `an in-process compile sends every module source and names only the edit as changed`() {
    lateinit var fixture: Fixture
    start(fakeCompiler { fixture.classes })
    fixture = fixture()
    val app = File(fixture.source.path.substringBefore("/src/"))
    val sourceDir = File(app, "src/main/kotlin")
    val sibling = File(sourceDir, "com/example/Theme.kt").apply { writeText("val accent = 1") }
    publishSourceDir(app, ":app", sourceDir)
    val params = CopyOnWriteArrayList<kotlinx.serialization.json.JsonObject?>()
    daemon.onCompileSourcesParams = { params += it }
    daemon.onCompileSources = { _ ->
      fixture.classes.writeText(fixture.source.readText())
      CompileSourcesResult(result = CompileResultKind.OK, durationMs = 5)
    }
    render(fixture)
    params.clear()

    edit(fixture, """@Preview fun Header() { Text("Whole module") }""")
    assertThat(render(fixture).bytes).contains("Whole module")

    val sent = params.single()!!
    assertThat(sent["sources"]!!.jsonArray.map { it.jsonPrimitive.content })
      .containsExactly(fixture.source.absolutePath, sibling.absolutePath)
    assertThat(
        sent["changes"]!!.jsonObject["modified"]!!.jsonArray.map { it.jsonPrimitive.content }
      )
      .containsExactly(fixture.source.absolutePath)
  }

  /**
   * #57: after an in-process compile the daemon has already swapped onto the new classes, so a
   * `fileChanged` per edited file would only swap it again before the render.
   */
  @Test
  fun `a daemon that compiled in process is not sent fileChanged as well`() {
    lateinit var fixture: Fixture
    start(fakeCompiler { fixture.classes })
    fixture = fixture()
    daemon.onCompileSources = { _ ->
      fixture.classes.writeText(fixture.source.readText())
      CompileSourcesResult(result = CompileResultKind.OK, durationMs = 5)
    }
    render(fixture)
    val before = daemon.fileChanges.size

    edit(fixture, """@Preview fun Header() { Text("One swap") }""")
    assertThat(render(fixture).bytes).contains("One swap")
    assertThat(daemon.fileChanges).hasSize(before)
  }

  /**
   * #57: one module's edit used to reach every module's in-process compile, where it fails against
   * the wrong classpath, Gradle succeeds, and that module never compiled in process again.
   */
  @Test
  fun `another module's edit is left to Gradle and does not switch the in-process compile off`() {
    val gradle = CopyOnWriteArrayList<String>()
    start(
      SourceCompiler { _, modulePath, _ ->
        gradle += modulePath
        SourceCompileOutcome.Ok(durationMs = 1, work = nextWork)
      }
    )
    val projectDir = tmp.newFolder("workspace")
    fun module(name: String): File {
      File(projectDir, "$name/build.gradle.kts").apply { parentFile.mkdirs() }.writeText("")
      return File(projectDir, "$name/src/main/kotlin/com/example/Shared.kt").apply {
        parentFile.mkdirs()
        writeText("val $name = 1")
      }
    }
    val appSource = module("app")
    val featureSource = module("feature")
    val workspaceId = registerWorkspace(projectDir)
    val asked = mutableMapOf<String, CopyOnWriteArrayList<List<String>>>()
    for (modulePath in listOf(":app", ":feature")) {
      supervisor.daemonFor(workspaceId, modulePath)
      val calls = CopyOnWriteArrayList<List<String>>().also { asked[modulePath] = it }
      factory.daemons.getValue(workspaceId to modulePath).onCompileSources = { sources ->
        calls += sources
        CompileSourcesResult(result = CompileResultKind.OK, durationMs = 1)
      }
    }
    fun notify(file: File) {
      file.setLastModified(file.lastModified() + 2_000)
      client.callTool(
        "notify_file_changed",
        buildJsonObject {
          put("workspaceId", workspaceId.value)
          put("path", file.absolutePath)
        },
        timeoutMs = 10_000,
      )
    }

    // No preview names the file, so every module recompiles; only :app compiles it in process.
    appSource.writeText("val app = 2")
    notify(appSource)
    assertThat(asked.getValue(":app")).containsExactly(listOf(appSource.absolutePath))
    assertThat(asked.getValue(":feature")).isEmpty()
    assertThat(gradle).containsExactly(":feature")

    featureSource.writeText("val feature = 2")
    notify(featureSource)
    assertThat(asked.getValue(":feature")).containsExactly(listOf(featureSource.absolutePath))
  }

  /**
   * `source_watch` shows the starting list (what the build published in its launch descriptor) and
   * narrows the pre-render check to the files an agent names, which still recompile.
   */
  @Test
  fun `source_watch lists the published source dirs and keeps a focused edit fresh`() {
    lateinit var fixture: Fixture
    start(fakeCompiler { fixture.classes }, compileInProcess = false)
    fixture = fixture()
    val app = fixture.source.parentFile.parentFile.parentFile.parentFile.parentFile.parentFile
    val sourceDir = File(app, "src/main/kotlin")
    File(app, "build/compose-previews/daemon-launch.json").apply {
      parentFile.mkdirs()
      writeText(
        """
        {"schemaVersion":2,"modulePath":":app","variant":"debug","enabled":true,
         "mainClass":"M","classpath":[],"jvmArgs":[],"systemProperties":{},
         "workingDirectory":"${app.absolutePath}","manifestPath":"${app.absolutePath}/p.json",
         "sourceWatch":{"sourceDirs":["${sourceDir.absolutePath}"]}}
        """
          .trimIndent()
      )
    }
    fun sourceWatch(paths: List<String>?) =
      json
        .parseToJsonElement(
          client
            .callTool(
              "source_watch",
              buildJsonObject {
                put("workspaceId", fixture.workspaceId.value)
                if (paths != null)
                  putJsonArray("paths") { paths.forEach { add(JsonPrimitive(it)) } }
              },
              timeoutMs = 10_000,
            )
            .firstTextContent()
        )
        .jsonObject

    val listed = sourceWatch(null)
    val published = listed["published"]!!.jsonArray.single().jsonObject
    assertThat(published["moduleDir"]!!.jsonPrimitive.content).isEqualTo(app.absolutePath)
    assertThat(published["sourceDirs"]!!.jsonArray.map { it.jsonPrimitive.content })
      .containsExactly(sourceDir.absolutePath)
    assertThat(listed["focus"]!!.jsonArray).isEmpty()

    render(fixture)
    val focused = sourceWatch(listOf(fixture.source.absolutePath))
    assertThat(focused["focus"]!!.jsonArray.map { it.jsonPrimitive.content })
      .containsExactly(fixture.source.absolutePath)
    edit(fixture, """@Preview fun Header() { Text("Focused") }""")
    assertThat(render(fixture).bytes).contains("Focused")

    assertThat(sourceWatch(emptyList())["focus"]!!.jsonArray).isEmpty()
  }

  /** On unless `COMPOSE_PREVIEW_COMPILE_IN_PROCESS` says otherwise. */
  @Test
  fun `the in-process compile is on by default and the environment can turn it off`() {
    val env = DaemonMcpServer.COMPILE_IN_PROCESS_ENV
    assertThat(DaemonMcpServer.compileInProcessEnabled(emptyMap())).isTrue()
    for (on in listOf("1", "true", "")) {
      assertThat(DaemonMcpServer.compileInProcessEnabled(mapOf(env to on))).isTrue()
    }
    for (off in listOf("0", "false", "OFF", " 0 ")) {
      assertThat(DaemonMcpServer.compileInProcessEnabled(mapOf(env to off))).isFalse()
    }
  }

  /** Turned off, the daemon is never asked and every edit compiles through Gradle. */
  @Test
  fun `a disabled in-process compile leaves every edit to Gradle`() {
    lateinit var fixture: Fixture
    start(fakeCompiler { fixture.classes }, compileInProcess = false)
    fixture = fixture()
    val asked = java.util.concurrent.atomic.AtomicInteger()
    daemon.onCompileSources = {
      asked.incrementAndGet()
      CompileSourcesResult(result = CompileResultKind.OK, durationMs = 1)
    }
    render(fixture)
    val gradleBefore = compiles.size

    edit(fixture, """@Preview fun Header() { Text("Gradle by default") }""")
    assertThat(render(fixture).bytes).contains("Gradle by default")
    assertThat(compiles).hasSize(gradleBefore + 1)
    assertThat(asked.get()).isEqualTo(0)
  }

  /** #1189: `fallback` (no BTA wiring, KSP, …) falls back to Gradle, and is remembered. */
  @Test
  fun `a daemon that declines the in-process compile falls back to Gradle`() {
    lateinit var fixture: Fixture
    start(fakeCompiler { fixture.classes })
    fixture = fixture()
    val asked = java.util.concurrent.atomic.AtomicInteger()
    daemon.onCompileSources = {
      asked.incrementAndGet()
      CompileSourcesResult(result = CompileResultKind.FALLBACK, durationMs = 1)
    }
    render(fixture)
    val gradleBefore = compiles.size

    edit(fixture, """@Preview fun Header() { Text("Via Gradle") }""")
    assertThat(render(fixture).bytes).contains("Via Gradle")
    assertThat(compiles).hasSize(gradleBefore + 1)

    edit(fixture, """@Preview fun Header() { Text("Via Gradle again") }""")
    assertThat(render(fixture).bytes).contains("Via Gradle again")
    assertThat(compiles).hasSize(gradleBefore + 2)
    // Declined once, never asked again.
    assertThat(asked.get()).isEqualTo(1)
  }

  private fun inProcessCompileError(asked: java.util.concurrent.atomic.AtomicInteger) =
    { sources: List<String> ->
      asked.incrementAndGet()
      CompileSourcesResult(
        result = CompileResultKind.COMPILE_ERROR,
        errors =
          listOf(
            CompileErrorDetail(
              file = sources.single(),
              line = 1,
              column = 25,
              message = "Unresolved reference 'Txt'.",
            )
          ),
        durationMs = 3,
      )
    }

  /** #1189: an in-process compile error Gradle agrees with is reported as stale. */
  @Test
  fun `an in-process compile error that Gradle confirms marks the render stale`() {
    lateinit var fixture: Fixture
    start(fakeCompiler { fixture.classes })
    fixture = fixture()
    val asked = java.util.concurrent.atomic.AtomicInteger()
    daemon.onCompileSources = inProcessCompileError(asked)
    render(fixture)
    val gradleBefore = compiles.size
    val askedBefore = asked.get()

    nextFailure =
      SourceCompileOutcome.Failed("compileDebugKotlin failed: Unresolved reference 'Txt'.")
    edit(fixture, """@Preview fun Header() { Txt("broken") }""")
    val stale = render(fixture)

    assertThat(asked.get()).isEqualTo(askedBefore + 1)
    assertThat(compiles).hasSize(gradleBefore + 1)
    assertThat(stale.texts.any { it.startsWith("stale:") && it.contains("Unresolved reference") })
      .isTrue()

    // The in-process compiler was right, so it is still trusted for the next edit.
    nextFailure = null
    edit(fixture, """@Preview fun Header() { Text("fixed") }""")
    render(fixture)
    assertThat(asked.get()).isEqualTo(askedBefore + 2)
  }

  /** #1189: when Gradle compiles what the daemon rejected, the daemon's compiler is not trusted. */
  @Test
  fun `an in-process compile error Gradle does not reproduce falls back for good`() {
    lateinit var fixture: Fixture
    start(fakeCompiler { fixture.classes })
    fixture = fixture()
    val app = File(fixture.source.path.substringBefore("/src/"))
    publishSourceDir(app, ":app", File(app, "src/main/kotlin"))
    val asked = java.util.concurrent.atomic.AtomicInteger()
    daemon.onCompileSources = inProcessCompileError(asked)
    render(fixture)
    val askedBefore = asked.get()

    edit(fixture, """@Preview fun Header() { Text("Gradle is right") }""")
    val fresh = render(fixture)
    assertThat(fresh.bytes).contains("Gradle is right")
    assertThat(fresh.texts.none { it.startsWith("stale:") }).isTrue()

    edit(fixture, """@Preview fun Header() { Text("again") }""")
    assertThat(render(fixture).bytes).contains("again")
    assertThat(asked.get()).isAtMost(askedBefore + 1)
  }

  /**
   * #67: a module that published no source list is sent only its edits, which can fail on an edit
   * that needs the rest of the module. Gradle compiling it says nothing about the next edit.
   */
  @Test
  fun `an edits-only compile error Gradle does not reproduce keeps the in-process compiler`() {
    lateinit var fixture: Fixture
    start(fakeCompiler { fixture.classes })
    fixture = fixture()
    val asked = java.util.concurrent.atomic.AtomicInteger()
    daemon.onCompileSources = inProcessCompileError(asked)
    render(fixture)
    val askedBefore = asked.get()

    val stderr = java.io.ByteArrayOutputStream()
    val original = System.err
    System.setErr(java.io.PrintStream(stderr, true))
    try {
      edit(fixture, """@Preview fun Header() { Text("Gradle is right") }""")
      assertThat(render(fixture).bytes).contains("Gradle is right")
      edit(fixture, """@Preview fun Header() { Text("again") }""")
      assertThat(render(fixture).bytes).contains("again")
    } finally {
      System.setErr(original)
    }
    assertThat(asked.get()).isEqualTo(askedBefore + 2)
    // Said once, not on every edit.
    assertThat(stderr.toString().lines().filter { "published no source list" in it }).hasSize(1)
  }

  /**
   * #67: a deleted file is only in the change set. Sent as a source too, the compile failed on the
   * missing path and switched the in-process compiler off for good.
   */
  @Test
  fun `a deleted file is reported as removed and not sent as a source`() {
    lateinit var fixture: Fixture
    start(fakeCompiler { fixture.classes })
    fixture = fixture()
    val app = File(fixture.source.path.substringBefore("/src/"))
    val sourceDir = File(app, "src/main/kotlin")
    val sibling = File(sourceDir, "com/example/Theme.kt").apply { writeText("val accent = 1") }
    publishSourceDir(app, ":app", sourceDir)
    val params = CopyOnWriteArrayList<kotlinx.serialization.json.JsonObject?>()
    daemon.onCompileSourcesParams = { params += it }
    daemon.onCompileSources = { _ ->
      fixture.classes.writeText(fixture.source.readText())
      CompileSourcesResult(result = CompileResultKind.OK, durationMs = 5)
    }
    render(fixture)
    params.clear()

    sibling.delete()
    sibling.parentFile.setLastModified(sibling.parentFile.lastModified() + 2_000)
    edit(fixture, """@Preview fun Header() { Text("No theme") }""")
    assertThat(render(fixture).bytes).contains("No theme")

    val sent = params.single()!!
    assertThat(sent["sources"]!!.jsonArray.map { it.jsonPrimitive.content })
      .containsExactly(fixture.source.absolutePath)
    assertThat(sent["changes"]!!.jsonObject["removed"]!!.jsonArray.map { it.jsonPrimitive.content })
      .containsExactly(sibling.absolutePath)
  }

  /**
   * Two modules, each declaring a preview in its own `Screen.kt`, both edited before either
   * renders. Returns each module's in-process compiles and the modules Gradle compiled.
   */
  private fun twoModulesEditedTogether(
    appDependsOnFeature: Boolean
  ): Pair<Map<String, List<List<String>>>, List<String>> {
    val gradle = CopyOnWriteArrayList<String>()
    start(
      SourceCompiler { _, modulePath, _ ->
        gradle += modulePath
        SourceCompileOutcome.Ok(durationMs = 1, work = nextWork)
      }
    )
    val projectDir = tmp.newFolder("workspace")
    fun module(name: String): File {
      File(projectDir, "$name/build.gradle.kts").apply { parentFile.mkdirs() }.writeText("")
      return File(projectDir, "$name/src/main/kotlin/com/example/Screen.kt").apply {
        parentFile.mkdirs()
        writeText("""@Preview fun Header() { Text("$name") }""")
      }
    }
    val appSource = module("app")
    val featureSource = module("feature")
    if (appDependsOnFeature) {
      descriptors.classpaths[":app"] = listOf(File(projectDir, "feature/build/classes").path)
    }
    val workspaceId = registerWorkspace(projectDir)
    val asked = mutableMapOf<String, CopyOnWriteArrayList<List<String>>>()
    val png = tmp.newFile("render.png").apply { writeText("png") }
    for ((modulePath, name) in listOf(":app" to "app", ":feature" to "feature")) {
      supervisor.daemonFor(workspaceId, modulePath)
      val fake = factory.daemons.getValue(workspaceId to modulePath)
      val calls = CopyOnWriteArrayList<List<String>>().also { asked[modulePath] = it }
      fake.onCompileSources = { sources ->
        calls += sources
        CompileSourcesResult(result = CompileResultKind.OK, durationMs = 1)
      }
      fake.emitDiscovery(PREVIEW_ID, sourceFile = "$name/src/main/kotlin/com/example/Screen.kt")
      client.expectNotification("notifications/resources/list_changed", 2_000)
      fake.autoRenderPngPath = { png.absolutePath }
    }
    fun render(modulePath: String) =
      client.callTool(
        "render_preview",
        buildJsonObject {
          put("uri", PreviewUri(workspaceId, modulePath, PREVIEW_ID).toUri())
          put("inline", false)
        },
        timeoutMs = 10_000,
      )
    render(":app")
    render(":feature")
    asked.values.forEach { it.clear() }

    featureSource.writeText("""@Preview fun Header() { Text("feature 2") }""")
    featureSource.setLastModified(featureSource.lastModified() + 2_000)
    appSource.writeText("""@Preview fun Header() { Text("app 2") }""")
    appSource.setLastModified(appSource.lastModified() + 2_000)
    render(":app")
    render(":feature")
    return asked to gradle
  }

  /**
   * #67: an edit to a file another module declares a preview in is that module's leaf. A sibling
   * daemon that picked it up from the source tree used to run Gradle for it, and could no longer
   * compile its own edit in process.
   */
  @Test
  fun `another module's preview file does not recompile this module`() {
    val (asked, gradle) = twoModulesEditedTogether(appDependsOnFeature = false)
    assertThat(asked.getValue(":app").single().single())
      .endsWith("app/src/main/kotlin/com/example/Screen.kt")
    assertThat(asked.getValue(":feature").single().single())
      .endsWith("feature/src/main/kotlin/com/example/Screen.kt")
    assertThat(gradle).isEmpty()
  }

  /**
   * #68 review: a module whose classpath carries another module's output depends on it, so that
   * module's preview file may declare a composable this one's previews call. It is still compiled.
   */
  @Test
  fun `a preview file in a module this one depends on still recompiles it`() {
    val (asked, gradle) = twoModulesEditedTogether(appDependsOnFeature = true)
    assertThat(asked.getValue(":app")).isEmpty()
    assertThat(gradle).containsExactly(":app")
    assertThat(asked.getValue(":feature").single().single())
      .endsWith("feature/src/main/kotlin/com/example/Screen.kt")
  }

  /**
   * #73: an edit to another module's file that declares no preview (a view model, a theme) reached
   * every module's compile, and ran Gradle for modules that don't depend on it.
   */
  @Test
  fun `another module's non-preview edit does not recompile a module that does not use it`() {
    val gradle = CopyOnWriteArrayList<String>()
    start(
      SourceCompiler { _, modulePath, _ ->
        gradle += modulePath
        SourceCompileOutcome.Ok(durationMs = 1, work = nextWork)
      }
    )
    val projectDir = tmp.newFolder("workspace")
    for (name in listOf("app", "feature")) {
      File(projectDir, "$name/build.gradle.kts").apply { parentFile.mkdirs() }.writeText("")
      File(projectDir, "$name/src/main/kotlin/com/example/Screen.kt").apply {
        parentFile.mkdirs()
        writeText("""@Preview fun Header() { Text("$name") }""")
      }
    }
    val viewModel =
      File(projectDir, "feature/src/main/kotlin/com/example/ViewModel.kt").apply {
        writeText("class ViewModel")
      }
    val workspaceId = registerWorkspace(projectDir)
    supervisor.daemonFor(workspaceId, ":app")
    val app = factory.daemons.getValue(workspaceId to ":app")
    val asked = CopyOnWriteArrayList<List<String>>()
    app.onCompileSources = { sources ->
      asked += sources
      CompileSourcesResult(result = CompileResultKind.OK, durationMs = 1)
    }
    app.emitDiscovery(PREVIEW_ID, sourceFile = "app/src/main/kotlin/com/example/Screen.kt")
    client.expectNotification("notifications/resources/list_changed", 2_000)
    val png = tmp.newFile("render.png").apply { writeText("png") }
    app.autoRenderPngPath = { png.absolutePath }
    fun render() =
      client.callTool(
        "render_preview",
        buildJsonObject {
          put("uri", PreviewUri(workspaceId, ":app", PREVIEW_ID).toUri())
          put("inline", false)
        },
        timeoutMs = 10_000,
      )
    render()
    asked.clear()

    viewModel.writeText("class ViewModel(val edited: Int)")
    viewModel.setLastModified(viewModel.lastModified() + 2_000)
    render()
    assertThat(asked).isEmpty()
    assertThat(gradle).isEmpty()
  }

  /**
   * #73/#76: a published build file's edit used to go unseen. It recompiles the module with its
   * launch descriptor, and restarts the daemon from it before the render: the running process keeps
   * the classpath it started with, and a Bazel daemon ignores a classpath `fileChanged`.
   */
  @Test
  fun `a published build file's edit rebuilds the descriptor and restarts the daemon`() {
    lateinit var fixture: Fixture
    val relaunched = CopyOnWriteArrayList<List<File>>()
    start(
      object : SourceCompiler {
        override fun compile(projectRoot: File, modulePath: String, sources: List<File>) =
          SourceCompileOutcome.Ok(durationMs = 1, work = nextWork).also { compiles += sources }

        override fun compileAndRelaunch(
          projectRoot: File,
          modulePath: String,
          sources: List<File>,
        ) = SourceCompileOutcome.Ok(durationMs = 1, work = nextWork).also { relaunched += sources }
      }
    )
    fixture = fixture()
    val app = File(fixture.source.path.substringBefore("/src/"))
    val build = File(app, "build.gradle.kts")
    publishSourceDir(app, ":app", File(app, "src/main/kotlin"), buildFiles = listOf(build))
    render(fixture)
    val first = daemon
    first.fileChanges.clear()
    factory.daemonConfigurer = { replacement ->
      replacement.autoRenderPngPath = { fixture.classes.absolutePath }
    }

    build.writeText("plugins { }")
    build.setLastModified(build.lastModified() + 2_000)
    fixture.classes.writeText("after the build file edit")
    assertThat(render(fixture).bytes).isEqualTo("after the build file edit")

    assertThat(relaunched.single().map { it.absolutePath }).containsExactly(build.absolutePath)
    assertThat(compiles).isEmpty()
    assertThat(factory.spawnHistory).hasSize(2)
    assertThat(first.fileChanges).isEmpty()
  }

  /** #76: the descriptor rewrite rides along with the compile, in the same Gradle invocation. */
  @Test
  fun `gradle compiler rewrites the launch descriptor when asked to relaunch`() {
    assumeFalse(System.getProperty("os.name").orEmpty().startsWith("Windows"))
    val root = tmp.newFolder("gradle-relaunch")
    val args = File(root, "args.txt")
    File(root, "gradlew").apply {
      writeText("#!/bin/sh\necho \"$@\" > '${args.absolutePath}'\nexit 0\n")
      setExecutable(true)
    }

    GradleSourceCompiler().compileAndRelaunch(root, ":app", emptyList())

    assertThat(args.readText())
      .contains(":app:composePreviewCompile :app:composePreviewDaemonStart")
  }

  @Test
  fun `notify_file_changed recompiles before the daemon swaps so the next render is fresh`() {
    lateinit var fixture: Fixture
    start(fakeCompiler { fixture.classes })
    fixture = fixture()
    assertThat(render(fixture).bytes).contains("\"Header\"")

    edit(fixture, """@Preview fun Header() { Text("Hello Android") }""")
    val notified = notifyChanged(fixture)

    assertThat(notified).contains("recompiled :app")
    assertThat(compiles).containsExactly(listOf(fixture.source))
    // The swap has to come after the compile, or it reloads the old classes.
    assertThat(fileChangesSeenAtCompile).containsExactly(0)
    val swap = daemon.fileChanges.poll(2_000, TimeUnit.MILLISECONDS)
    assertThat(swap?.get("kind")?.jsonPrimitive?.contentOrNull).isEqualTo("source")

    val fresh = render(fixture)
    assertThat(fresh.bytes).contains("Hello Android")
    assertThat(fresh.texts.none { it.startsWith("stale:") }).isTrue()
    // notify_file_changed already compiled this edit; the render must not compile it again.
    assertThat(compiles).hasSize(1)
  }

  @Test
  fun `an edit only the background poller saw is recompiled by the next render`() {
    lateinit var fixture: Fixture
    start(fakeCompiler { fixture.classes })
    fixture = fixture()
    assertThat(render(fixture).bytes).contains("\"Header\"")

    edit(fixture, """@Preview fun Header() { Text("Hello Android") }""")
    // The poller records the edit as seen; before #1169 that swallowed it for the render probe.
    server.runSourceFreshnessPoll()
    assertThat(compiles).isEmpty()

    assertThat(render(fixture).bytes).contains("Hello Android")
    assertThat(compiles).hasSize(1)
  }

  @Test
  fun `a failed recompile is reported as one stale line on the render`() {
    lateinit var fixture: Fixture
    start(fakeCompiler { fixture.classes })
    fixture = fixture()
    render(fixture)

    nextFailure =
      SourceCompileOutcome.Failed(
        ":app:composePreviewCompile failed: MainActivity.kt:1:30 Unresolved reference 'Txt'"
      )
    edit(fixture, """@Preview fun Header() { Txt("Hello Android") }""")
    val swapsBefore = daemon.fileChanges.size
    assertThat(notifyChanged(fixture)).contains("Unresolved reference 'Txt'")
    // The daemon keeps its last good classloader: swapping onto a failed compile's output rendered
    // older code and dropped the file's previews from discovery (compose-ag-plugin#64).
    assertThat(daemon.fileChanges.size).isEqualTo(swapsBefore)

    val stale = render(fixture)
    assertThat(stale.bytes).contains("\"Header\"")
    val lines = stale.texts.filter { it.startsWith("stale:") }
    assertThat(lines).hasSize(1)
    assertThat(lines.single()).contains("MainActivity.kt")
    assertThat(lines.single()).contains("Unresolved reference 'Txt'")
    // And in the payload, for a client that reads only that object.
    assertThat(stale.stale).isEqualTo(lines.single())

    // Fixing the edit clears the note.
    nextFailure = null
    edit(fixture, """@Preview fun Header() { Text("Hello Android") }""")
    notifyChanged(fixture)
    val fixed = render(fixture)
    assertThat(fixed.bytes).contains("Hello Android")
    assertThat(fixed.texts.none { it.startsWith("stale:") }).isTrue()
  }

  @Test
  fun `a render says so when the module cannot be recompiled at all`() {
    lateinit var fixture: Fixture
    start(GradleSourceCompiler())
    fixture = fixture()
    render(fixture)

    edit(fixture, """@Preview fun Header() { Text("Hello Android") }""")
    val stale = render(fixture)

    assertThat(stale.bytes).contains("\"Header\"")
    val line = stale.texts.single { it.startsWith("stale:") }
    assertThat(line).contains("could not recompile :app")
    assertThat(line).contains("no Gradle wrapper")
    // The source change is still forwarded, so classes built elsewhere are picked up.
    assertThat(daemon.fileChanges.poll(2_000, TimeUnit.MILLISECONDS)).isNotNull()
  }

  @Test
  fun `without a compiler the server keeps forwarding fileChanged straight away`() {
    start(compiler = null)
    val fixture = fixture()
    render(fixture)
    edit(fixture, """@Preview fun Header() { Text("Hello Android") }""")
    notifyChanged(fixture)
    assertThat(daemon.fileChanges.poll(2_000, TimeUnit.MILLISECONDS)).isNotNull()
    assertThat(render(fixture).texts.none { it.startsWith("stale:") }).isTrue()
  }

  @Test
  fun `gradle compiler runs composePreviewCompile through the project's wrapper`() {
    assumeFalse(System.getProperty("os.name").orEmpty().startsWith("Windows"))
    val root = tmp.newFolder("gradle-root")
    val args = File(root, "args.txt")
    val wrapper = File(root, "gradlew")
    wrapper.writeText("#!/bin/sh\necho \"$@\" > '${args.absolutePath}'\nexit 0\n")
    wrapper.setExecutable(true)

    val ok = GradleSourceCompiler().compile(root, ":app", emptyList())

    assertThat(ok).isInstanceOf(SourceCompileOutcome.Ok::class.java)
    assertThat(args.readText()).contains(":app:composePreviewCompile")

    wrapper.writeText(
      "#!/bin/sh\necho 'e: file:///x/MainActivity.kt:3:5 Unresolved reference Txt'\nexit 1\n"
    )
    val failed = GradleSourceCompiler().compile(root, ":app", emptyList())
    assertThat(failed).isInstanceOf(SourceCompileOutcome.Failed::class.java)
    assertThat((failed as SourceCompileOutcome.Failed).reason)
      .isEqualTo(
        ":app:composePreviewCompile failed: file:///x/MainActivity.kt:3:5 Unresolved reference Txt"
      )
  }

  @Test
  fun `an unchanged render neither recompiles nor swaps the classloader`() {
    lateinit var fixture: Fixture
    start(fakeCompiler { fixture.classes })
    fixture = fixture()
    render(fixture)
    render(fixture)
    render(fixture)
    assertThat(compiles).isEmpty()
    assertThat(daemon.fileChanges).isEmpty()
  }

  @Test
  fun `a compile that outlives its budget is killed and reported as stale`() {
    assumeFalse(System.getProperty("os.name").orEmpty().startsWith("Windows"))
    val wrapperDir = tmp.newFolder("slow-root")
    File(wrapperDir, "gradlew").apply {
      writeText("#!/bin/sh\nsleep 30\n")
      setExecutable(true)
    }
    val startedAt = System.nanoTime()
    val outcome = GradleSourceCompiler(timeoutMs = 300).compile(wrapperDir, ":app", emptyList())
    assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)).isLessThan(10_000L)
    assertThat(outcome).isInstanceOf(SourceCompileOutcome.Failed::class.java)
    assertThat((outcome as SourceCompileOutcome.Failed).reason).contains("timed out")

    // Through the server, the timeout surfaces as the render's stale line, not silence.
    lateinit var fixture: Fixture
    start(SourceCompiler { _, _, _ -> outcome })
    fixture = fixture()
    render(fixture)
    edit(fixture, """@Preview fun Header() { Text("Hello Android") }""")
    val line = render(fixture).texts.single { it.startsWith("stale:") }
    assertThat(line).contains("timed out")
  }

  /** #1181: the daemon's per-render trace reaches `_meta.work.daemonTrace` unchanged. */
  @Test
  fun `the daemon's work trace passes through to the render's work record`() {
    lateinit var fixture: Fixture
    start(fakeCompiler { fixture.classes })
    fixture = fixture()
    assertThat(render(fixture).work!!.containsKey("daemonTrace")).isFalse()

    val trace = buildJsonObject {
      putJsonArray("dataKinds") { add(JsonPrimitive("compose/semantics")) }
      putJsonArray("processors") { add(JsonPrimitive("capture")) }
    }
    daemon.autoRenderWorkTrace = { trace }
    edit(fixture, """@Preview fun Header() { Text("Traced") }""")
    val traced = render(fixture)
    assertThat(traced.bytes).contains("Traced")
    assertThat(traced.work!!["daemonTrace"]).isEqualTo(trace)
  }

  @Test
  fun `each edit cycle records only the module's compile tasks and renders fresh`() {
    lateinit var fixture: Fixture
    start(fakeCompiler { fixture.classes })
    fixture = fixture()
    var previous = render(fixture)
    assertThat(previous.work).isNotNull()
    assertThat(previous.work!!.containsKey("compile")).isFalse()

    listOf("Hello Android", "Hello Wear", "Hello again").forEachIndexed { cycle, text ->
      edit(fixture, """@Preview fun Header() { Text("$text") }""")
      val notified =
        client.callTool(
          "notify_file_changed",
          buildJsonObject {
            put("workspaceId", fixture.workspaceId.value)
            put("path", fixture.source.absolutePath)
          },
          timeoutMs = 10_000,
        )
      val notifiedWork =
        notified.raw["_meta"]!!.jsonObject["work"]!!.jsonObject["compile"]!!.jsonObject[":app"]!!
      assertThat(notifiedWork.jsonObject["initScript"]!!.jsonPrimitive.content).isEqualTo("true")

      val fresh = render(fixture)
      // Fresh: the bytes carry the edit and the hash moved.
      assertThat(fresh.bytes).contains(text)
      assertThat(fresh.changed).isTrue()
      assertThat(fresh.sha256).isNotEqualTo(previous.sha256)
      // Structural: exactly one compile per edit, and it ran only the allowed task set.
      assertThat(compiles).hasSize(cycle + 1)
      val work = recordedCompile(fresh)
      assertThat(work.task).isEqualTo(":app:composePreviewCompile")
      assertThat(work.disallowedTasks(allowedModules = setOf(":app"))).isEmpty()
      assertThat(work.executed)
        .containsExactly(":app:compileDebugKotlin", ":app:composePreviewCompile")
      previous = fresh
    }

    // An unchanged render compiles nothing and says so.
    val unchanged = render(fixture)
    assertThat(compiles).hasSize(3)
    assertThat(unchanged.work!!.containsKey("compile")).isFalse()
    assertThat(unchanged.work!!["renderMs"]).isNotNull()
  }

  @Test
  fun `the task policy rejects packaging, lint, tests and other modules`() {
    val work =
      CompileWork(
        task = ":app:composePreviewCompile",
        ms = 1,
        initScript = false,
        tasks =
          listOf(
            ":app:compileDebugKotlin",
            ":app:assembleDebug",
            ":app:lintAnalyzeDebug UP-TO-DATE",
            ":app:compileDebugUnitTestKotlin",
            ":app:testDebugUnitTest",
            ":app:bundleDebugAar",
            ":app:packageDebug",
            // The R class inputs a Kotlin compile needs: allowed, unlike the APK above.
            ":app:packageDebugResources UP-TO-DATE",
            ":wear:compileDebugKotlin",
            ":app:composePreviewCompile",
          ),
      )
    assertThat(work.disallowedTasks(allowedModules = setOf(":app")))
      .containsExactly(
        ":app:assembleDebug",
        ":app:lintAnalyzeDebug",
        ":app:compileDebugUnitTestKotlin",
        ":app:testDebugUnitTest",
        ":app:bundleDebugAar",
        ":app:packageDebug",
        ":wear:compileDebugKotlin",
      )
      .inOrder()
    // Without a module constraint only the name rule applies.
    assertThat(work.disallowedTasks()).doesNotContain(":wear:compileDebugKotlin")
  }

  @Test
  fun `gradle compiler records the tasks Gradle ran and whether it injected the init script`() {
    assumeFalse(System.getProperty("os.name").orEmpty().startsWith("Windows"))
    val home = tmp.newFolder("home")
    cliInitScript(home, "2.28.0")
    val root = tmp.newFolder("starter")
    File(root, "settings.gradle.kts").writeText("include(\":app\")")
    stubWrapper(
      root,
      """
      |echo '> Task :app:preBuild UP-TO-DATE'
      |echo '> Task :app:compileDebugKotlin'
      |echo 'w: some warning'
      |echo '> Task :app:compileDebugJavaWithJavac NO-SOURCE'
      |echo '> Task :app:composePreviewCompile'
      |echo ''
      |echo 'BUILD SUCCESSFUL in 3s'
      |exit 0
      """
        .trimMargin(),
    )

    val outcome =
      GradleSourceCompiler(initScripts = InitScripts(environment = emptyMap(), userHome = home))
        .compile(root, ":app", emptyList())

    val work = (outcome as SourceCompileOutcome.Ok).work!!
    assertThat(work.initScript).isTrue()
    assertThat(work.task).isEqualTo(":app:composePreviewCompile")
    assertThat(work.tasks)
      .containsExactly(
        ":app:preBuild UP-TO-DATE",
        ":app:compileDebugKotlin",
        ":app:compileDebugJavaWithJavac NO-SOURCE",
        ":app:composePreviewCompile",
      )
      .inOrder()
    assertThat(work.executed)
      .containsExactly(":app:compileDebugKotlin", ":app:composePreviewCompile")
    assertThat(work.disallowedTasks(setOf(":app"))).isEmpty()
  }

  private fun recordedCompile(render: Render): CompileWork {
    val compile = render.work!!["compile"]!!.jsonObject
    return CompileWork(
      task = compile["task"]!!.jsonPrimitive.content,
      ms = compile["ms"]!!.jsonPrimitive.content.toLong(),
      initScript = compile["initScript"]!!.jsonPrimitive.content.toBoolean(),
      tasks = compile["tasks"]!!.jsonArray.map { it.jsonPrimitive.content },
      trigger = compile["trigger"]?.jsonPrimitive?.content,
    )
  }

  @Test
  fun `an edit nobody notified is detected and recompiled before the render`() {
    lateinit var fixture: Fixture
    start(fakeCompiler { fixture.classes })
    fixture = fixture()
    // A second file the preview uses: host edit tools change it and never call notify.
    val strings = File(fixture.source.parentFile, "Strings.kt")
    strings.writeText("""const val HEADER = "Header"""")
    val cold = render(fixture)
    assertThat(cold.bytes).contains("\"Header\"")

    strings.writeText("""const val HEADER = "Hello Android"""")
    strings.setLastModified(strings.lastModified() + 2_000)
    val fresh = render(fixture)

    assertThat(fresh.bytes).contains("Hello Android")
    assertThat(fresh.changed).isTrue()
    assertThat(compiles).containsExactly(listOf(strings))
    val work = recordedCompile(fresh)
    assertThat(work.trigger).isEqualTo(DaemonMcpServer.TRIGGER_DETECTED)
    assertThat(work.task).isEqualTo(":app:composePreviewCompile")
    assertThat(fresh.work!!["scan"]!!.jsonObject["changed"]!!.jsonPrimitive.content).isEqualTo("1")
  }

  @Test
  fun `an unchanged render compiles nothing and lists no directory`() {
    lateinit var fixture: Fixture
    start(fakeCompiler { fixture.classes })
    fixture = fixture()
    render(fixture)
    val unchanged = render(fixture)

    assertThat(compiles).isEmpty()
    assertThat(unchanged.work!!.containsKey("compile")).isFalse()
    val scan = unchanged.work!!["scan"]!!.jsonObject
    assertThat(scan["initial"]!!.jsonPrimitive.content).isEqualTo("false")
    assertThat(scan["listed"]!!.jsonPrimitive.content).isEqualTo("0")
    assertThat(scan["changed"]!!.jsonPrimitive.content).isEqualTo("0")
    assertThat(scan["complete"]!!.jsonPrimitive.content).isEqualTo("true")
  }

  @Test
  fun `notify records its trigger and the render does not compile the same edit again`() {
    lateinit var fixture: Fixture
    start(fakeCompiler { fixture.classes })
    fixture = fixture()
    render(fixture)
    edit(fixture, """@Preview fun Header() { Text("Hello Android") }""")
    notifyChanged(fixture)
    val fresh = render(fixture)
    assertThat(fresh.bytes).contains("Hello Android")
    assertThat(compiles).hasSize(1)
    assertThat(recordedCompile(fresh).trigger).isEqualTo(DaemonMcpServer.TRIGGER_NOTIFY)
  }

  @Test
  fun `a failed compile then a fixed edit without notify renders fresh with no stale line`() {
    lateinit var fixture: Fixture
    start(fakeCompiler { fixture.classes })
    fixture = fixture()
    render(fixture)

    nextFailure =
      SourceCompileOutcome.Failed(
        ":app:composePreviewCompile failed: MainActivity.kt:1:30 Unresolved reference 'Txt'"
      )
    edit(fixture, """@Preview fun Header() { Txt("Hello Android") }""")
    val broken = render(fixture)
    assertThat(broken.texts.filter { it.startsWith("stale:") }).hasSize(1)
    // A failure is not retried on every render: the next unchanged render compiles nothing.
    render(fixture)
    assertThat(compiles).hasSize(1)

    nextFailure = null
    edit(fixture, """@Preview fun Header() { Text("Hello Android") }""")
    val fixed = render(fixture)
    assertThat(fixed.bytes).contains("Hello Android")
    assertThat(fixed.texts.none { it.startsWith("stale:") }).isTrue()
    assertThat(compiles).hasSize(2)
  }

  @Test
  fun `an edit in a worktree nested in the registered build registers the worktree`() {
    lateinit var fixture: Fixture
    start(fakeCompiler { fixture.classes })
    fixture = fixture()
    val workspaceRoot = File(tmp.root, "workspace")
    val worktree = File(workspaceRoot, ".claude/worktrees/build-verification")
    File(worktree, "settings.gradle.kts").apply {
      parentFile.mkdirs()
      writeText("include(\":app\")")
    }
    val edited = File(worktree, "app/src/main/kotlin/com/example/MainActivity.kt")
    edited.parentFile.mkdirs()
    edited.writeText("""@Preview fun Header() { Text("Hello Android") }""")

    val result =
      client
        .callTool(
          "notify_file_changed",
          buildJsonObject {
            put("workspaceId", fixture.workspaceId.value)
            put("path", edited.absolutePath)
          },
          timeoutMs = 10_000,
        )
        .firstTextContent()

    assertThat(result).contains("edited file ${edited.absolutePath} is not in registered project")
    assertThat(result).contains("rendering from ${worktree.canonicalPath}")
    // The registered build was neither compiled nor told about a file that is not its own, and
    // its own change detection does not look inside the hidden worktree directory either.
    assertThat(compiles).isEmpty()
    assertThat(daemon.fileChanges).isEmpty()
    render(fixture)
    assertThat(compiles).isEmpty()
    assertThat(client.callTool("list_projects").firstTextContent()).contains(worktree.canonicalPath)
  }

  @Test
  fun `the stale line carries gradle's root cause`() {
    assumeFalse(System.getProperty("os.name").orEmpty().startsWith("Windows"))
    val root = tmp.newFolder("no-sdk")
    stubWrapper(
      root,
      """
      |echo 'FAILURE: Build failed with an exception.'
      |echo ''
      |echo '* What went wrong:'
      |echo "Could not determine the dependencies of task ':app:compileDebugKotlin'."
      |echo '> SDK location not found. Define a valid SDK location with an ANDROID_HOME environment variable.'
      |echo ''
      |echo '* Try:'
      |exit 1
      """
        .trimMargin(),
    )
    val failed =
      GradleSourceCompiler(
          initScripts = InitScripts(emptyMap(), tmp.newFolder("home-a")),
          androidSdks = AndroidSdks(emptyMap(), tmp.newFolder("home-b")),
        )
        .compile(root, ":app", emptyList())

    val reason = (failed as SourceCompileOutcome.Failed).reason
    assertThat(reason)
      .contains("Could not determine the dependencies of task ':app:compileDebugKotlin'")
    assertThat(reason).contains("Cause: SDK location not found")

    // And through the server, on the render's stale line.
    lateinit var fixture: Fixture
    start(SourceCompiler { _, _, _ -> failed })
    fixture = fixture()
    render(fixture)
    edit(fixture, """@Preview fun Header() { Text("Hello Android") }""")
    val line = render(fixture).texts.single { it.startsWith("stale:") }
    assertThat(line).contains("SDK location not found")
  }

  @Test
  fun `a worktree build gets the main checkout's sdk as ANDROID_HOME`() {
    assumeFalse(System.getProperty("os.name").orEmpty().startsWith("Windows"))
    val sdk = tmp.newFolder("sdk")
    val main = tmp.newFolder("ComposeStarterRepo")
    File(main, "ComposeStarter").mkdirs()
    File(main, "ComposeStarter/local.properties").writeText("sdk.dir=${sdk.absolutePath}\n")
    val gitDir = File(main, ".git/worktrees/build-verification").apply { mkdirs() }
    File(gitDir, "commondir").writeText("../..\n")
    val worktree = File(main, ".claude/worktrees/build-verification").apply { mkdirs() }
    File(worktree, ".git").writeText("gitdir: ${gitDir.absolutePath}\n")
    val build = File(worktree, "ComposeStarter").apply { mkdirs() }

    val home = tmp.newFolder("empty-home")
    assertThat(AndroidSdks.mainCheckoutOf(build)?.canonicalFile)
      .isEqualTo(File(main, "ComposeStarter").canonicalFile)
    assertThat(AndroidSdks(emptyMap(), home).forBuild(build)).isEqualTo(sdk)
    // An SDK the environment already names wins; so does the build's own local.properties.
    assertThat(AndroidSdks(mapOf("ANDROID_HOME" to "/opt/sdk"), home).forBuild(build)).isNull()
    File(build, "local.properties").writeText("sdk.dir=/elsewhere\n")
    assertThat(AndroidSdks(emptyMap(), home).forBuild(build)).isNull()
    File(build, "local.properties").delete()

    val seen = File(build, "android-home.txt")
    File(build, "gradlew").apply {
      writeText("#!/bin/sh\necho \"${'$'}ANDROID_HOME\" > '${seen.absolutePath}'\nexit 0\n")
      setExecutable(true)
    }
    GradleSourceCompiler(
        initScripts = InitScripts(emptyMap(), home),
        androidSdks = AndroidSdks(emptyMap(), home),
      )
      .compile(build, ":app", emptyList())
    assertThat(seen.readText().trim()).isEqualTo(sdk.absolutePath)

    // The first-use bootstrap too: it failed with "SDK location not found" in the same worktree
    // while `doctor` reported the SDK (compose-ag-plugin#64, #63).
    seen.delete()
    ProjectBootstrap(
        initScripts = InitScripts(emptyMap(), home),
        runner = GradleTaskRunner.subprocess(androidSdks = AndroidSdks(emptyMap(), home)),
      )
      .ensurePrepared(build)
    assertThat(seen.readText().trim()).isEqualTo(sdk.absolutePath)
  }

  /** A stub `gradlew` that appends its arguments to `args.txt` and runs [body]. */
  private fun stubWrapper(root: File, body: String): File {
    val args = File(root, "args.txt")
    File(root, "gradlew").apply {
      writeText("#!/bin/sh\necho \"$@\" >> '${args.absolutePath}'\n$body\n")
      setExecutable(true)
    }
    return args
  }

  private fun cliInitScript(home: File, version: String): File =
    File(home, ".cache/composeai/init/$version/${InitScripts.FILE_NAME}").apply {
      parentFile.mkdirs()
      writeText("// init script $version")
    }

  @Test
  fun `gradle compile injects the CLI's newest init script when the build does not apply the plugin`() {
    assumeFalse(System.getProperty("os.name").orEmpty().startsWith("Windows"))
    val home = tmp.newFolder("home")
    cliInitScript(home, "2.9.0")
    val newest = cliInitScript(home, "2.28.0")
    val root = tmp.newFolder("starter")
    File(root, "settings.gradle.kts").writeText("include(\":app\")")
    val args = stubWrapper(root, "exit 0")

    val outcome =
      GradleSourceCompiler(initScripts = InitScripts(environment = emptyMap(), userHome = home))
        .compile(root, ":app", emptyList())

    assertThat(outcome).isInstanceOf(SourceCompileOutcome.Ok::class.java)
    assertThat(args.readText().trim())
      .isEqualTo(
        "--init-script ${newest.absolutePath} -Dorg.gradle.unsafe.isolated-projects=false " +
          "-Dorg.gradle.isolated-projects=false --console=plain :app:composePreviewCompile"
      )
  }

  @Test
  fun `gradle compile retries with the init script once when the task is missing`() {
    assumeFalse(System.getProperty("os.name").orEmpty().startsWith("Windows"))
    val home = tmp.newFolder("home")
    val script = cliInitScript(home, "2.28.0")
    val root = tmp.newFolder("mentions-plugin")
    // The scan sees the plugin id (say, only in a comment) but the build does not apply it.
    File(root, "build.gradle.kts").writeText("// ee.schimke.composeai.preview is applied elsewhere")
    val args =
      stubWrapper(
        root,
        """
        |case "$*" in *--init-script*) exit 0;; esac
        |echo "* What went wrong:"
        |echo "Cannot locate tasks that match ':app:composePreviewCompile' as task 'composePreviewCompile' not found in project ':app'."
        |exit 1
        """
          .trimMargin(),
      )
    val compiler =
      GradleSourceCompiler(initScripts = InitScripts(environment = emptyMap(), userHome = home))

    assertThat(compiler.compile(root, ":app", emptyList()))
      .isInstanceOf(SourceCompileOutcome.Ok::class.java)
    assertThat(compiler.compile(root, ":app", emptyList()))
      .isInstanceOf(SourceCompileOutcome.Ok::class.java)
    val calls = args.readLines()
    // Plain, then the retry, then straight to the init script on the next edit.
    assertThat(calls).hasSize(3)
    assertThat(calls[0]).doesNotContain("--init-script")
    assertThat(calls[1]).contains("--init-script ${script.absolutePath}")
    assertThat(calls[2]).contains("--init-script ${script.absolutePath}")
  }

  @Test
  fun `init scripts honour the CLI's opt-outs and say what to do when none exists`() {
    assumeFalse(System.getProperty("os.name").orEmpty().startsWith("Windows"))
    val home = tmp.newFolder("home")
    cliInitScript(home, "2.28.0")
    val root = tmp.newFolder("plain")
    assertThat(InitScripts(mapOf("COMPOSE_PREVIEW_NO_AUTO_INJECT" to "1"), home).forProject(root))
      .isNull()
    val explicit = tmp.newFile("explicit.init.gradle.kts")
    assertThat(
        InitScripts(mapOf("COMPOSE_PREVIEW_INIT_SCRIPT" to explicit.absolutePath), home)
          .forProject(root)
      )
      .isEqualTo(explicit)
    val xdg = tmp.newFolder("xdg")
    val xdgScript =
      File(xdg, "composeai/init/3.0.0/${InitScripts.FILE_NAME}").apply {
        parentFile.mkdirs()
        writeText("//")
      }
    assertThat(InitScripts(mapOf("XDG_CACHE_HOME" to xdg.absolutePath), home).forProject(root))
      .isEqualTo(xdgScript)
    val devLoop = tmp.newFolder("dev-loop")
    File(devLoop, "settings.gradle.kts").writeText("includeBuild(\"gradle-plugin\")")
    assertThat(InitScripts(emptyMap(), home).forProject(devLoop)).isNull()

    stubWrapper(
      root,
      "echo \"* What went wrong:\"; echo \"Task 'composePreviewCompile' not found in project ':app'.\"; exit 1",
    )
    val failed =
      GradleSourceCompiler(initScripts = InitScripts(emptyMap(), tmp.newFolder("empty-home")))
        .compile(root, ":app", emptyList())
    assertThat((failed as SourceCompileOutcome.Failed).reason)
      .contains("compose-preview mcp install")
  }

  @Test
  fun `default sandbox replicas scale with the machine`() {
    assertThat(DaemonSupervisor.defaultReplicasFor(2)).isEqualTo(0)
    assertThat(DaemonSupervisor.defaultReplicasFor(4)).isEqualTo(1)
    assertThat(DaemonSupervisor.defaultReplicasFor(8)).isEqualTo(3)
    assertThat(DaemonSupervisor.defaultReplicasFor(64)).isEqualTo(4)
  }

  @Test
  fun `gradle failure summary falls back to what went wrong`() {
    assertThat(
        GradleSourceCompiler.summarizeGradleFailure(
          "\nFAILURE: Build failed.\n\n* What went wrong:\nTask 'composePreviewCompile' not found.\n"
        )
      )
      .isEqualTo("Task 'composePreviewCompile' not found.")
  }

  private fun registerWorkspace(projectDir: File): WorkspaceId {
    val text =
      client
        .callTool(
          "register_project",
          buildJsonObject {
            put("path", projectDir.absolutePath)
            put("rootProjectName", "starter")
          },
        )
        .firstTextContent()
    client.expectNotification("notifications/resources/list_changed", 2_000)
    return WorkspaceId(
      json.parseToJsonElement(text).jsonObject["workspaceId"]!!.jsonPrimitive.content
    )
  }

  private fun pipedPair(): Pair<OutputStream, InputStream> {
    val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
    val client = java.net.Socket(server.inetAddress, server.localPort)
    val accepted = server.accept()
    server.close()
    return client.getOutputStream() to accepted.getInputStream()
  }

  private companion object {
    const val PREVIEW_ID = "com.example.HeaderPreview"
  }
}
