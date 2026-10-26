package ee.schimke.composeai.mcp

import ee.schimke.composeai.daemon.protocol.PreviewRowsResult
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** One `@PreviewParameter` row: [id] is the daemon's addressable previewId, used verbatim. */
internal data class PreviewParameterRow(val id: String, val index: Int, val label: String)

/**
 * Per-module cache of the daemon's `preview/rows` answers, keyed by base preview id (issue #97).
 *
 * Discovery reads bytecode, so a `@PreviewParameter` preview arrives as one base id; only the
 * daemon can construct the provider and say how many rows there are. The rows are asked for lazily
 * (the first listing or name lookup that needs them) and kept until [invalidate]: a recompile, a
 * classpath change or a rediscovery of the preview can change the provider's values.
 *
 * A daemon that answers method-not-found (it predates `preview/rows`, or its host cannot enumerate
 * providers) is cached as "no rows", so the base id is listed and rendered as before. A failed
 * request is not cached; the next lookup asks again.
 *
 * Each key carries a generation, so an answer that was in flight across an [invalidate] is dropped
 * rather than cached over the fresh state.
 */
internal class PreviewParameterRows<K : Any> {

  private class Cached(val rows: List<PreviewParameterRow>)

  private val cache = ConcurrentHashMap<K, ConcurrentHashMap<String, Cached>>()
  private val generations = ConcurrentHashMap<K, AtomicLong>()

  /** The cached rows of [baseId], or null when it has not been asked yet. */
  fun cached(key: K, baseId: String): List<PreviewParameterRow>? = cache[key]?.get(baseId)?.rows

  /**
   * [baseId]'s rows, asking [fetch] (the daemon's `preview/rows`) when they are not cached. Empty
   * for an ordinary preview, for a daemon that cannot enumerate, and when [fetch] fails.
   */
  fun rows(key: K, baseId: String, fetch: () -> PreviewRowsResult?): List<PreviewParameterRow> {
    cached(key, baseId)?.let {
      return it
    }
    val generation = generation(key).get()
    val result =
      runCatching(fetch)
        .onFailure {
          System.err.println(
            "compose-preview-mcp: preview/rows failed for $baseId: ${it.message ?: it.javaClass.simpleName}"
          )
        }
        .getOrElse {
          return emptyList()
        }
    val rows =
      result
        ?.rows
        .orEmpty()
        .filter { it.id.isNotBlank() && '/' !in it.id && '?' !in it.id }
        .sortedBy { it.index }
        .map { PreviewParameterRow(id = it.id, index = it.index, label = it.label) }
    if (generation(key).get() == generation) {
      cache.computeIfAbsent(key) { ConcurrentHashMap() }[baseId] = Cached(rows)
    }
    return rows
  }

  /** The base id whose cached rows include [rowId], or null. */
  fun baseOf(key: K, rowId: String): String? =
    cache[key]?.entries?.firstOrNull { (_, cached) -> cached.rows.any { it.id == rowId } }?.key

  /** Every cached row of [key], by base id; never asks the daemon. */
  fun cachedRows(key: K): Map<String, List<PreviewParameterRow>> =
    cache[key]?.mapValues { it.value.rows }.orEmpty()

  /** Forgets every row of [key]: the module recompiled, its classpath changed, or it went away. */
  fun invalidate(key: K) {
    generation(key).incrementAndGet()
    cache.remove(key)
  }

  /** Forgets the rows of [baseIds] alone: discovery added, changed or removed them. */
  fun invalidate(key: K, baseIds: Collection<String>) {
    if (baseIds.isEmpty()) return
    generation(key).incrementAndGet()
    cache[key]?.keys?.removeAll(baseIds.toSet())
  }

  private fun generation(key: K): AtomicLong = generations.computeIfAbsent(key) { AtomicLong() }
}
