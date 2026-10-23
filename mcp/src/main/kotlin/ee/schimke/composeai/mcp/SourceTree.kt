package ee.schimke.composeai.mcp

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Tells a render whether any source in a Gradle build changed since the last look, so the server
 * can recompile before rendering when nobody called `notify_file_changed`. Host edit tools (Claude
 * Code's Edit, an IDE's save) never notify this server, so freshness cannot depend on that call.
 *
 * It covers what a preview's classes are built from: `*.kt` / `*.java` and resources (`res/`,
 * `composeResources/`) under every module's `src/`, apart from test source sets. It skips hidden
 * directories, which is also what keeps a Claude Code worktree under `.claude/worktrees/` out of
 * the build that contains it, and `build/` outputs.
 *
 * The first [refresh] walks the tree once and caches every directory's and file's stamp. Later
 * refreshes only `stat`: a directory is re-listed only when its own mtime moved (a file was added,
 * removed or renamed into it, as atomic saves do), and a file counts as changed when its mtime or
 * size moved. An unchanged build therefore costs one stat per cached entry and no directory walk,
 * and [budgetMs] caps even that: a refresh that runs out of time stops, reports `complete = false`,
 * and the next one resumes where it stopped.
 *
 * Files newer than every compiled class output in the build count as changed on the first walk: an
 * edit made before the server first looked would otherwise become the baseline and render stale
 * (the first-render-after-edit case the per-file probe missed).
 */
class SourceTree(
  val root: File,
  private val budgetMs: Long = DEFAULT_BUDGET_MS,
  /**
   * How often an unfocused [refresh] reads [declared] again, so a launch descriptor written after
   * the first walk, or a published directory the build creates later (generated sources), is
   * watched too.
   */
  private val moduleRescanMs: Long = DEFAULT_MODULE_RESCAN_MS,
  /**
   * Modules whose build published where they're built from (the launch descriptor's `sourceWatch`),
   * read on the first walk and every [moduleRescanMs] after. Each replaces the `src/` convention
   * for its module, and one the convention can't find (a Bazel or Buck2 target) is watched all the
   * same.
   */
  private val declared: () -> List<DeclaredModule> = { emptyList() },
) {

  /** A module's published source and resource directories; see [SourceTree.declared]. */
  data class DeclaredModule(
    val moduleDir: File,
    val sourceDirs: List<File>,
    val resourceDirs: List<File>,
    /**
     * The build's name for the module (`:app`, `//feature:ui`). Every Bazel or Buck2 target of a
     * workspace shares one [moduleDir], so this, when set, is what tells them apart.
     */
    val modulePath: String? = null,
  )

  /**
   * Paths an agent said it will edit: a [refresh] with a focus checks only what's in (or leads to)
   * them, and the rest waits for an unfocused one. Each entry is a file or directory prefix, or a
   * glob (`*`, `**`, `?`) matched against absolute paths.
   */
  class Focus(entries: List<String>, root: File) {
    private val prefixes = mutableListOf<String>()
    private val globs = mutableListOf<java.nio.file.PathMatcher>()

    init {
      for (raw in entries.map { it.trim() }.filter { it.isNotEmpty() }) {
        val absolute = if (File(raw).isAbsolute) raw else File(root, raw).path
        if (raw.any { it == '*' || it == '?' }) {
          globs += java.nio.file.FileSystems.getDefault().getPathMatcher("glob:$absolute")
        } else {
          prefixes += File(absolute).normalize().path
        }
      }
    }

    val isEmpty: Boolean
      get() = prefixes.isEmpty() && globs.isEmpty()

    /** Whether [file] is inside the focus. */
    fun covers(file: File): Boolean {
      val path = file.path
      return prefixes.any { path == it || path.startsWith("$it${File.separator}") } ||
        globs.any { it.matches(file.toPath()) }
    }

    /** Whether [dir] holds focused files or leads to them, so its listing matters. */
    fun reaches(dir: File): Boolean {
      val path = dir.path
      return covers(dir) ||
        prefixes.any { it.startsWith("$path${File.separator}") } ||
        // A glob may match anywhere below: keep every directory under its literal head.
        globs.isNotEmpty()
    }
  }

  data class Refresh(
    /** Files that changed since the previous refresh, in the order they were seen. */
    val changed: List<File>,
    /** Entries stat-ed. */
    val statted: Int,
    /** Directories listed: every one on the first walk, only moved ones after. */
    val listed: Int,
    val ms: Long,
    /** False when [budgetMs] ran out before every entry was checked. */
    val complete: Boolean,
    /** True for the first walk, which builds the cache. */
    val initial: Boolean,
  )

  private data class Stamp(val modifiedMs: Long, val size: Long)

  private val dirs = LinkedHashMap<File, Long>()
  private val files = LinkedHashMap<File, Stamp>()
  private var initialized = false

  /**
   * A module, identified by [key]: its published module path, else its directory. [modulePath] is
   * null for a module found by convention.
   */
  private data class Module(
    val key: String,
    val dir: File,
    val modulePath: String?,
    val published: Boolean,
  )

  /** Each watched root's path, with the module it belongs to; set by the first walk. */
  private val moduleRoots = mutableListOf<Pair<String, Module>>()
  private var modulesScannedAt = 0L
  private var fileCursor = 0

  /** Bumped on every refresh that saw a change; callers compare it with what they compiled. */
  @Volatile
  var generation: Long = 0
    private set

  /** Which [generation] each changed file was last seen changing in. */
  private val changedAt = LinkedHashMap<File, Long>()

  /** Root directories whose files all count as sources: published resource directories. */
  private val resourceRoots = mutableListOf<String>()

  /**
   * Stats the cached entries for changes. With a [focus], only entries inside it (and directories
   * that lead to it) are checked, so a large build pays for what the agent is editing; an unfocused
   * refresh later checks everything else. The first walk always covers the whole tree.
   */
  @Synchronized
  fun refresh(focus: Focus? = null): Refresh {
    val startedAt = System.nanoTime()
    if (!initialized) return initialWalk(startedAt)
    val scoped = focus?.takeUnless { it.isEmpty }
    val deadline = startedAt + TimeUnit.MILLISECONDS.toNanos(budgetMs)
    val changed = LinkedHashSet<File>()
    var statted = 0
    var listed = 0
    var complete = true
    if (
      scoped == null &&
        startedAt - modulesScannedAt >= TimeUnit.MILLISECONDS.toNanos(moduleRescanMs)
    ) {
      listed += rescanModules { changed += it }
    }
    // Directories first: a moved directory mtime is how additions, deletions and atomic renames
    // show up, and a re-list is the only walk an unchanged tree never does.
    for ((dir, known) in dirs.entries.toList()) {
      if (scoped != null && !scoped.reaches(dir)) continue
      statted++
      val now = dir.lastModified()
      if (now == known) continue
      if (now == 0L) {
        dirs.remove(dir)
        files.keys
          .filter { it.parentFile == dir }
          .forEach {
            files.remove(it)
            changed += it
          }
        continue
      }
      dirs[dir] = now
      listed++
      val children = dir.listFiles().orEmpty()
      children
        .filter { it.isFile && isSource(it) && it !in files }
        .forEach {
          files[it] = stamp(it)
          changed += it
        }
      children
        .filter { it.isDirectory && watched(dir, it) && it !in dirs }
        .forEach { sub -> listed += register(sub) { changed += it } }
    }
    val entries =
      if (scoped == null) files.entries.toList() else files.entries.filter { scoped.covers(it.key) }
    if (scoped != null) {
      for ((file, known) in entries) {
        statted++
        val now = stamp(file)
        if (now.modifiedMs == 0L) {
          files.remove(file)
          changed += file
        } else if (now != known) {
          files[file] = now
          changed += file
        }
      }
    } else if (entries.isNotEmpty()) {
      if (fileCursor >= entries.size) fileCursor = 0
      var index = fileCursor
      var checked = 0
      while (checked < entries.size) {
        if (System.nanoTime() > deadline) {
          complete = false
          break
        }
        val (file, known) = entries[index]
        statted++
        val now = stamp(file)
        if (now.modifiedMs == 0L) {
          files.remove(file)
          changed += file
        } else if (now != known) {
          files[file] = now
          changed += file
        }
        checked++
        index = (index + 1) % entries.size
      }
      fileCursor = index
    }
    if (changed.isNotEmpty()) {
      generation++
      changed.forEach { changedAt[it] = generation }
    }
    return Refresh(
      changed = changed.toList(),
      statted = statted,
      listed = listed,
      ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt),
      complete = complete,
      initial = false,
    )
  }

  /**
   * The key of the module [file] belongs to: the module whose watched root holds it, the deepest
   * when roots nest. `null` before the first walk, or for a file outside every module.
   */
  @Synchronized
  fun moduleOf(file: File): String? = moduleOfPath(file.absoluteFile.normalize().path)?.key

  /**
   * The directory of the module [file] belongs to, as [moduleOf] finds it. For a Bazel or Buck2
   * target that is the workspace root, which every target shares.
   */
  @Synchronized
  fun moduleDirOf(file: File): File? = moduleOfPath(file.absoluteFile.normalize().path)?.dir

  private fun moduleOfPath(path: String): Module? =
    moduleRoots
      .filter { (root, _) -> path.startsWith("$root${File.separator}") }
      .maxByOrNull { (root, _) -> root.length }
      ?.second

  /**
   * The key of the module a daemon for [modulePath] builds: the module that published under that
   * path, else the one found by convention in the first of [moduleDirs] the tree watches. `null`
   * for a module the tree doesn't know, as for a Bazel target that published nothing.
   */
  @Synchronized
  fun moduleKey(modulePath: String, moduleDirs: List<File>): String? {
    moduleRoots
      .firstOrNull { (_, module) -> module.modulePath == modulePath }
      ?.let {
        return it.second.key
      }
    return moduleDirs.firstNotNullOfOrNull { dir ->
      val normalized = dir.absoluteFile.normalize()
      moduleRoots
        .firstOrNull { (_, module) -> module.modulePath == null && module.dir == normalized }
        ?.second
        ?.key
    }
  }

  /**
   * Every Kotlin source of the module [key] names, when its build published where it is built from,
   * else `null`. A `src/` found by convention also holds other targets' and variants' source sets
   * (`iosMain`, `release`), which no single compilation reads, so it has no answer here.
   */
  @Synchronized
  fun kotlinSourcesOf(key: String): List<File>? {
    if (moduleRoots.none { (_, module) -> module.key == key && module.published }) return null
    return files.keys.filter { it.extension == "kt" && moduleOf(it) == key }.sorted()
  }

  /** Files that changed after [generation], for the compile's source list. */
  @Synchronized
  fun changedSince(generation: Long): List<File> =
    changedAt.filterValues { it > generation }.keys.toList()

  private fun initialWalk(startedAt: Long): Refresh {
    var listed = 0
    watchRoots().forEach { src -> listed += register(src) {} }
    modulesScannedAt = startedAt
    initialized = true
    val compiledAt = newestClassOutput()
    val pending =
      if (compiledAt == null) emptyList()
      else files.filter { (_, stamp) -> stamp.modifiedMs > compiledAt }.keys.toList()
    if (pending.isNotEmpty()) {
      generation++
      pending.forEach { changedAt[it] = generation }
    }
    return Refresh(
      changed = pending,
      statted = dirs.size + files.size,
      listed = listed,
      ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt),
      complete = true,
      initial = true,
    )
  }

  /**
   * Reads [declared] again and registers every watch root not yet watched: a module whose launch
   * descriptor appeared since the last scan, or a published directory that didn't exist then. As on
   * the first walk, a file newer than every class output is reported to [onChanged]; one older is
   * already compiled. Returns how many directories it listed.
   */
  private fun rescanModules(onChanged: (File) -> Unit): Int {
    modulesScannedAt = System.nanoTime()
    val roots = watchRoots()
    prune(roots)
    val fresh = roots.filter { it !in dirs }
    if (fresh.isEmpty()) return 0
    val compiledAt = newestClassOutput()
    var listed = 0
    fresh.forEach { dir ->
      listed +=
        register(dir) { file ->
          if (compiledAt != null && file.lastModified() > compiledAt) onChanged(file)
        }
    }
    return listed
  }

  /**
   * Forgets every cached directory and file outside [roots]: a `src/` the first walk found by
   * convention stops being watched once its module publishes narrower directories, and an edit to a
   * source set the build no longer compiles must not reach a compile.
   */
  private fun prune(roots: List<File>) {
    val prefixes = roots.map { it.path }
    fun inside(file: File): Boolean {
      val path = file.path
      return prefixes.any { path == it || path.startsWith("$it${File.separator}") }
    }
    dirs.keys.removeAll { !inside(it) }
    files.keys.removeAll { !inside(it) }
    changedAt.keys.removeAll { !inside(it) }
  }

  /** Adds [dir] and everything below it; returns how many directories it listed. */
  private fun register(dir: File, onFile: (File) -> Unit): Int {
    var listed = 0
    val stack = ArrayDeque(listOf(dir))
    while (stack.isNotEmpty()) {
      val next = stack.removeLast()
      dirs[next] = next.lastModified()
      listed++
      next.listFiles().orEmpty().forEach { child ->
        if (child.isDirectory) {
          if (watched(next, child)) stack.addLast(child)
        } else if (isSource(child)) {
          files[child] = stamp(child)
          onFile(child)
        }
      }
    }
    return listed
  }

  /**
   * What the tree watches: each published module's source and resource directories, and the `src/`
   * of every module that published none. Rebuilds [moduleRoots] and [resourceRoots].
   */
  private fun watchRoots(): List<File> {
    val published = runCatching(declared).getOrDefault(emptyList())
    val publishedModules = published.map { it.moduleDir.absoluteFile.normalize() }.toSet()
    resourceRoots.clear()
    published
      .flatMap { it.resourceDirs }
      .forEach { resourceRoots += it.absoluteFile.normalize().path }
    val roots = LinkedHashSet<File>()
    moduleRoots.clear()
    sourceRoots()
      .filter { it.parentFile.absoluteFile.normalize() !in publishedModules }
      .forEach { src ->
        roots += src
        val dir = src.parentFile.absoluteFile.normalize()
        val module = Module(key = dir.path, dir = dir, modulePath = null, published = false)
        moduleRoots += src.absoluteFile.normalize().path to module
      }
    published.forEach { declared ->
      val dir = declared.moduleDir.absoluteFile.normalize()
      val module =
        Module(
          key = declared.modulePath ?: dir.path,
          dir = dir,
          modulePath = declared.modulePath,
          published = true,
        )
      (declared.sourceDirs + declared.resourceDirs)
        .map { it.absoluteFile.normalize() }
        .forEach { dir ->
          moduleRoots += dir.path to module
          if (dir.isDirectory) roots += dir
        }
    }
    return roots.toList()
  }

  /**
   * Each module's `src/`: a `src` directory beside a Gradle build file, at most a few levels deep.
   */
  private fun sourceRoots(): List<File> {
    val roots = mutableListOf<File>()
    val stack = ArrayDeque(listOf(root to 0))
    while (stack.isNotEmpty()) {
      val (dir, depth) = stack.removeLast()
      val src = File(dir, "src")
      if (src.isDirectory && BUILD_FILES.any { File(dir, it).isFile }) roots += src
      if (depth >= MAX_MODULE_DEPTH) continue
      dir.listFiles().orEmpty().forEach { child ->
        if (child.isDirectory && walkable(child) && child.name != "src") {
          stack.addLast(child to depth + 1)
        }
      }
    }
    return roots
  }

  private fun newestClassOutput(): Long? {
    var newest: Long? = null
    val moduleDirs = sourceRoots().map { it.parentFile }
    moduleDirs.forEach { module ->
      CLASS_OUTPUTS.map { File(module, it) }
        .filter(File::isDirectory)
        .forEach { output ->
          output
            .walkTopDown()
            .filter { it.isFile && it.extension == "class" }
            .forEach { newest = maxOf(newest ?: 0L, it.lastModified()) }
        }
    }
    return newest
  }

  private fun stamp(file: File) = Stamp(file.lastModified(), file.length())

  private fun walkable(dir: File): Boolean = !dir.name.startsWith(".") && dir.name !in SKIPPED_DIRS

  /** Whether [child], a directory in [parent], is watched: walkable and not a test source set. */
  private fun watched(parent: File, child: File): Boolean =
    walkable(child) && !(parent.name == "src" && TEST_SOURCE_SET.containsMatchIn(child.name))

  private fun isSource(file: File): Boolean {
    if (file.extension in SOURCE_EXTENSIONS) return true
    if (resourceRoots.any { file.path.startsWith("$it${File.separator}") }) return true
    val path = file.invariantSeparatorsPath
    return RESOURCE_SEGMENTS.any { path.contains(it) }
  }

  companion object {
    const val DEFAULT_BUDGET_MS = 200L
    const val DEFAULT_MODULE_RESCAN_MS = 5_000L
    private const val MAX_MODULE_DEPTH = 4
    private val BUILD_FILES = listOf("build.gradle.kts", "build.gradle")
    private val SKIPPED_DIRS = setOf("build", "out", "node_modules", "gradle")
    private val SOURCE_EXTENSIONS = setOf("kt", "java")
    private val RESOURCE_SEGMENTS = listOf("/res/", "/composeResources/")
    private val TEST_SOURCE_SET = Regex("(?i)test")
    private val CLASS_OUTPUTS =
      listOf(
        "build/tmp/kotlin-classes",
        "build/intermediates/built_in_kotlinc",
        "build/intermediates/javac",
        "build/classes",
      )
  }
}
