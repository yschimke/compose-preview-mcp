package ee.schimke.composeai.mcp

import ee.schimke.composeai.daemon.client.WorkspaceId
import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import java.util.Base64

/**
 * [overrides] reduced to what changes the render, so two spellings of one configuration key the
 * same cache entry: a `fontScale` equal to the preview's own ([declaredFontScale], `1.0` when the
 * preview declares none) is dropped, and overrides that set nothing are `null`. Used wherever
 * overrides identify a render (`changedSinceLastRender`, the data-product cache): `render_matrix`
 * sends `fontScale: 1.0` for the cell `render_preview` renders without one.
 */
internal fun canonicalOverrides(
  overrides: PreviewOverrides?,
  declaredFontScale: Float? = null,
): PreviewOverrides? {
  if (overrides == null) return null
  val fontScale = overrides.fontScale
  val reduced =
    if (fontScale != null && fontScale == (declaredFontScale ?: 1.0f))
      overrides.copy(fontScale = null)
    else overrides
  return reduced.takeUnless { it == PreviewOverrides() }
}

/**
 * Parsed `compose-preview://<workspace>/<module>/<fqn>?config=<qualifier>` URI. The three-segment
 * form is load-bearing for multi-workspace operation: a single MCP server can host previews from
 * many workspaces simultaneously, and the workspace segment is what disambiguates them.
 *
 * `module` keeps the leading `:` from a Gradle path encoded as `_` to play well with URL parsers
 * (`:samples:android` becomes `_samples_android` on the wire). The reverse maps back when the
 * supervisor needs to invoke a Gradle path. A Bazel label (`//app/ui:lib`, `@repo//app:lib`), the
 * module path the compose-preview CLI writes for a Bazel workspace, holds `/`, `_` and `@`, so it
 * is written as `~` + its unpadded base64url instead and round-trips exactly.
 */
data class PreviewUri(
  val workspaceId: WorkspaceId,
  val modulePath: String,
  val previewFqn: String,
  val config: String? = null,
  val overridesJson: String? = null,
) {
  init {
    require(modulePath.startsWith(":") || isBazelLabel(modulePath)) {
      "PreviewUri.modulePath must be a Gradle path starting with ':' or a Bazel label " +
        "(got '$modulePath')"
    }
    require(!previewFqn.contains('/') && !previewFqn.contains('?')) {
      "PreviewUri.previewFqn must not contain '/' or '?' (got '$previewFqn')"
    }
  }

  /**
   * The canonical string for this preview: the FQN segment is percent-encoded ([encodeFqn]), so a
   * multipreview name's spaces go out as `%20` and the URI is valid RFC 3986. [parseOrNull] reads
   * both that and the raw form, and `parseOrNull(s)?.toUri()` ([canonical]) is how two spellings of
   * one preview compare equal.
   */
  fun toUri(): String {
    val moduleSegment = encodeModule(modulePath)
    val base = "$SCHEME://${workspaceId.value}/$moduleSegment/${encodeFqn(previewFqn)}"
    val query = buildList {
      config?.let { add("config=$it") }
      overridesJson?.let {
        val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(it.encodeToByteArray())
        add("overrides=$encoded")
      }
    }
    return if (query.isEmpty()) base else "$base?${query.joinToString("&")}"
  }

  override fun toString(): String = toUri()

  companion object {
    const val SCHEME: String = "compose-preview"

    /**
     * Strict parser. Returns null on malformed input rather than throwing — `resources/read` and
     * the watch tools should surface "invalid URI" as a typed protocol error, not a stack trace.
     */
    fun parseOrNull(s: String): PreviewUri? {
      val prefix = "$SCHEME://"
      if (!s.startsWith(prefix)) return null
      val rest = s.removePrefix(prefix)
      val (path, query) = rest.split('?', limit = 2).let { it[0] to it.getOrNull(1) }
      val parts = path.split('/')
      if (parts.size < 3) return null
      val workspace = parts[0]
      val moduleEncoded = parts[1]
      val fqn = decodeFqn(parts.subList(2, parts.size).joinToString("/"))
      if (workspace.isBlank() || moduleEncoded.isBlank() || fqn.isBlank()) return null
      if ('/' in fqn || '?' in fqn) return null
      val configValue =
        query
          ?.split('&')
          ?.firstOrNull { it.substringBefore('=') == "config" }
          ?.removePrefix("config=")
          ?.takeIf { it.isNotEmpty() }
      val overridesJson =
        query
          ?.split('&')
          ?.firstOrNull { it.substringBefore('=') == "overrides" }
          ?.removePrefix("overrides=")
          ?.takeIf { it.isNotEmpty() }
          ?.let { encoded ->
            runCatching { Base64.getUrlDecoder().decode(encoded).decodeToString() }.getOrNull()
              ?: return null
          }
      return PreviewUri(
        workspaceId = WorkspaceId(workspace),
        modulePath = decodeModule(moduleEncoded),
        previewFqn = fqn,
        config = configValue,
        overridesJson = overridesJson,
      )
    }

    fun parse(s: String): PreviewUri =
      requireNotNull(parseOrNull(s)) { "Malformed compose-preview URI: '$s'" }

    /**
     * [s] in the form [toUri] writes, so a client's raw-space or percent-encoded spelling of a
     * preview compares equal to the server's; [s] itself when it is not a preview URI.
     */
    fun canonical(s: String): String = parseOrNull(s)?.toUri() ?: s

    /**
     * Percent-encodes, as UTF-8, every character of [fqn] outside RFC 3986's `pchar` set (the
     * unreserved characters, sub-delims, `:` and `@`): a space, `%`, `#`, `"`, a bracket, anything
     * non-ASCII. A JVM preview id never holds `/` or `?` (the init block refuses them).
     */
    internal fun encodeFqn(fqn: String): String {
      if (fqn.all(::isPathSafe)) return fqn
      return buildString {
        fqn.encodeToByteArray().forEach { byte ->
          val char = (byte.toInt() and 0xFF).toChar()
          if (isPathSafe(char)) append(char)
          else {
            append('%')
            append(HEX[(byte.toInt() shr 4) and 0xF])
            append(HEX[byte.toInt() and 0xF])
          }
        }
      }
    }

    /**
     * Reverses [encodeFqn]. Lenient for URIs written before the FQN was encoded: raw spaces and
     * other characters pass through, and a `%` that does not start a valid escape stays literal.
     */
    internal fun decodeFqn(segment: String): String {
      if ('%' !in segment) return segment
      val bytes = java.io.ByteArrayOutputStream()
      var i = 0
      while (i < segment.length) {
        val char = segment[i]
        val high = if (char == '%') segment.getOrNull(i + 1)?.digitToIntOrNull(16) else null
        val low = if (high != null) segment.getOrNull(i + 2)?.digitToIntOrNull(16) else null
        if (high != null && low != null) {
          bytes.write(high * 16 + low)
          i += 3
        } else {
          val end = if (char.isHighSurrogate() && i + 1 < segment.length) i + 2 else i + 1
          bytes.write(segment.substring(i, end).encodeToByteArray())
          i = end
        }
      }
      return bytes.toByteArray().decodeToString()
    }

    private const val HEX = "0123456789ABCDEF"

    private fun isPathSafe(c: Char): Boolean =
      c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c in "-._~" || c in "!$&'()*+,;=:@"

    private const val LABEL_PREFIX = "~"

    /**
     * `//pkg:target`, `@repo//pkg:target`, or Buck2's cell-qualified `root//pkg:target`: the module
     * path of a Bazel-family daemon. A Gradle path starts with `:` and never holds `//`.
     */
    fun isBazelLabel(modulePath: String): Boolean =
      !modulePath.startsWith(":") && "//" in modulePath && '/' !in modulePath.substringBefore("//")

    /**
     * `:samples:android` → `_samples_android`. Lossy iff a Gradle path contains `_`, which it
     * can't. A Bazel label is `~<base64url>`: `~` never starts a Gradle segment.
     */
    fun encodeModule(modulePath: String): String =
      if (isBazelLabel(modulePath)) {
        LABEL_PREFIX +
          Base64.getUrlEncoder().withoutPadding().encodeToString(modulePath.encodeToByteArray())
      } else modulePath.replace(':', '_')

    /** Reverse of [encodeModule]. */
    fun decodeModule(encoded: String): String {
      if (encoded.startsWith(LABEL_PREFIX)) {
        runCatching {
            Base64.getUrlDecoder().decode(encoded.removePrefix(LABEL_PREFIX)).decodeToString()
          }
          .getOrNull()
          ?.takeIf(::isBazelLabel)
          ?.let {
            return it
          }
      }
      return encoded.replace('_', ':').let { if (it.startsWith(":")) it else ":$it" }
    }
  }
}

/**
 * History resource URI — `compose-preview-history://<workspace>/<module>/<previewFqn>/<entryId>`.
 * Distinct from [PreviewUri] so clients can disambiguate "current state" (live preview) from
 * "snapshot" (historical entry). Per [HISTORY.md § Layer 3 — MCP mapping](
 * ../../../../../../docs/daemon/HISTORY.md#layer-3--mcp-mapping).
 *
 * The `entryId` is the stable hash-based id the daemon attaches to each `HistoryEntry`; it's opaque
 * to MCP — the supervisor passes it back to the daemon's `history/read` / `history/diff` verbatim.
 * We round-trip the value through the URI without any encoding because the daemon's id format
 * already excludes `/` and `?`.
 */
class HistoryUri(
  val workspaceId: WorkspaceId,
  modulePath: String,
  val previewFqn: String,
  val entryId: String,
) {
  val modulePath: String = normalizeModulePath(modulePath)

  init {
    require(!previewFqn.contains('/') && !previewFqn.contains('?')) {
      "HistoryUri.previewFqn must not contain '/' or '?' (got '$previewFqn')"
    }
    require(!entryId.contains('/') && !entryId.contains('?')) {
      "HistoryUri.entryId must not contain '/' or '?' (got '$entryId')"
    }
  }

  fun toUri(): String =
    "$SCHEME://${workspaceId.value}/${PreviewUri.encodeModule(modulePath)}/$previewFqn/$entryId"

  override fun toString(): String = toUri()

  companion object {
    const val SCHEME: String = "compose-preview-history"

    private fun normalizeModulePath(modulePath: String): String {
      val trimmed = modulePath.trim()
      require(trimmed.isNotEmpty()) { "HistoryUri.modulePath must not be blank" }
      return if (trimmed.startsWith(":") || PreviewUri.isBazelLabel(trimmed)) trimmed
      else ":$trimmed"
    }

    fun parseOrNull(s: String): HistoryUri? {
      val prefix = "$SCHEME://"
      if (!s.startsWith(prefix)) return null
      val parts = s.removePrefix(prefix).split('/')
      if (parts.size < 4) return null
      val workspace = parts[0]
      val moduleEncoded = parts[1]
      val fqn = parts[2]
      val entry = parts.subList(3, parts.size).joinToString("/")
      if (workspace.isBlank() || moduleEncoded.isBlank() || fqn.isBlank() || entry.isBlank())
        return null
      return HistoryUri(
        workspaceId = WorkspaceId(workspace),
        modulePath = PreviewUri.decodeModule(moduleEncoded),
        previewFqn = fqn,
        entryId = entry,
      )
    }

    fun parse(s: String): HistoryUri =
      requireNotNull(parseOrNull(s)) { "Malformed compose-preview-history URI: '$s'" }
  }
}

/**
 * Glob over preview FQN — used by the `watch` tool to register an "area of interest". A single `*`
 * matches any sequence of non-`.` characters; `**` matches anything including dots; `?` matches one
 * non-`.` character. Matching is case-sensitive — Kotlin FQNs are.
 *
 * The glob is always evaluated against `previewFqn`; the workspace + module dimensions are filtered
 * separately by the watch tool itself.
 */
class FqnGlob(val pattern: String) {
  private val regex: Regex = compile(pattern)

  fun matches(fqn: String): Boolean = regex.matches(fqn)

  override fun toString(): String = pattern

  companion object {
    private fun compile(pattern: String): Regex {
      val sb = StringBuilder("^")
      var i = 0
      while (i < pattern.length) {
        when (val c = pattern[i]) {
          '*' ->
            if (i + 1 < pattern.length && pattern[i + 1] == '*') {
              sb.append(".*")
              i++
            } else {
              sb.append("[^.]*")
            }
          '?' -> sb.append("[^.]")
          '.',
          '(',
          ')',
          '[',
          ']',
          '{',
          '}',
          '+',
          '|',
          '^',
          '$',
          '\\' -> {
            sb.append('\\')
            sb.append(c)
          }
          else -> sb.append(c)
        }
        i++
      }
      sb.append('$')
      return Regex(sb.toString())
    }
  }
}
