package ee.schimke.composeai.mcp

import java.io.File
import java.security.MessageDigest

/**
 * A packed preview bundle (`compose-preview bundle pack`, a PNG+ZIP polyglot) as a project. Any
 * build system that can produce a bundle gets MCP rendering without a live-workspace hook: no
 * Gradle, no Bazel, no sources.
 *
 * A bundle named as `project=` (a local file or an http(s)/file URL) gets a workspace directory of
 * its own under the cache, keyed by the bundle's location and marked with [MARKER]. From then on it
 * is an ordinary registered project: [ProjectBootstrap] fills it with `compose-preview bundle
 * prepare`, which writes the bundle's classes, `previews.json` and a `daemon-launch.json` at
 * `<workspace>/build/compose-previews/`, the same layout as a Gradle module, so the descriptor
 * scan, the daemon spawn and every tool work unchanged. A bundle has nothing to recompile
 * ([BuildSystemSourceCompiler]): repack it and register it again.
 */
object BundleProjects {

  /** The marker naming the bundle a workspace was made for; one line, the source. */
  const val MARKER = "compose-preview-bundle.txt"

  private val URL_SCHEMES = listOf("http://", "https://", "file:")

  /** Whether `project=` names a bundle rather than a build: a URL, or a file (not a directory). */
  fun isBundleSource(argument: String): Boolean =
    URL_SCHEMES.any { argument.startsWith(it) } || File(argument).let { it.isAbsolute && it.isFile }

  fun isBundleWorkspace(dir: File): Boolean = File(dir, MARKER).isFile

  /** The bundle [workspace] was made for, or null when it is not a bundle workspace. */
  fun sourceOf(workspace: File): String? =
    File(workspace, MARKER).takeIf { it.isFile }?.readText()?.trim()?.takeIf { it.isNotEmpty() }

  /**
   * The workspace for [source], created (with its [MARKER]) when new. One per bundle location, so
   * registering the same bundle twice reuses its workspace and its workspace id.
   */
  fun workspaceFor(source: String, cacheRoot: File = defaultCacheRoot()): File {
    val normalized =
      if (URL_SCHEMES.any { source.startsWith(it) }) source
      else runCatching { File(source).canonicalPath }.getOrDefault(File(source).absolutePath)
    val name = normalized.substringAfterLast('/').substringBefore('?').substringBeforeLast('.')
    val key = sha256(normalized).take(12)
    val workspace = File(cacheRoot, "${sanitize(name).ifEmpty { "bundle" }}-$key")
    workspace.mkdirs()
    val marker = File(workspace, MARKER)
    if (marker.readTextOrNull()?.trim() != normalized) marker.writeText(normalized + "\n")
    return workspace
  }

  /** `compose-preview bundle prepare <source> -o <workspace>`. */
  fun prepareArguments(source: String, workspace: File): List<String> =
    listOf("bundle", "prepare", source, "-o", workspace.absolutePath)

  /** `$XDG_CACHE_HOME/composeai/mcp-bundles`, else `~/.cache/composeai/mcp-bundles`. */
  fun defaultCacheRoot(
    environment: Map<String, String> = System.getenv(),
    userHome: File = File(System.getProperty("user.home") ?: "."),
  ): File {
    val cache =
      environment["XDG_CACHE_HOME"]?.takeIf { it.isNotBlank() }?.let(::File)
        ?: File(userHome, ".cache")
    return File(cache, "composeai/mcp-bundles")
  }

  private fun sanitize(name: String): String = name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(40)

  private fun sha256(text: String): String =
    MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") {
      "%02x".format(it)
    }

  private fun File.readTextOrNull(): String? = takeIf { it.isFile }?.readText()
}
