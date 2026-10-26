package ee.schimke.composeai.mcp

import ee.schimke.composeai.daemon.protocol.PreviewRowsResult
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
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
 *
 * [rowsAsync] shares one request per (key, base id, generation) between concurrent callers, so a
 * listing that stopped waiting and the next one that asks again attach to the same request rather
 * than queueing a second one behind it.
 */
internal class PreviewParameterRows<K : Any> {

  private class Cached(val rows: List<PreviewParameterRow>)

  private val cache = ConcurrentHashMap<K, ConcurrentHashMap<String, Cached>>()
  private val generations = ConcurrentHashMap<K, AtomicLong>()

  /**
   * One request for (key, base id) at [generation]. [claimed] goes true when a thread starts it: a
   * pool worker, or a [rowsBlocking] caller that took over a request still queued behind others.
   */
  private class InFlight(
    val generation: Long,
    val future: CompletableFuture<List<PreviewParameterRow>> = CompletableFuture(),
    val claimed: AtomicBoolean = AtomicBoolean(false),
    val queued: AtomicBoolean = AtomicBoolean(false),
  )

  private val inFlight = ConcurrentHashMap<Pair<K, String>, InFlight>()

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
    return fetchAndCache(key, baseId, generation(key).get(), fetch)
  }

  /**
   * [baseId]'s rows for a caller that needs them now, never queued behind [rowsAsync]'s pool: a
   * request nobody has started (none yet, or one still waiting for a pool thread) runs on the
   * calling thread, and one already running is waited on for at most [timeoutMs] from this call.
   * Empty when that wait runs out, as for a failed fetch.
   */
  fun rowsBlocking(
    key: K,
    baseId: String,
    timeoutMs: Long,
    fetch: () -> PreviewRowsResult?,
  ): List<PreviewParameterRow> {
    cached(key, baseId)?.let {
      return it
    }
    val (slot, flight) = flightFor(key, baseId)
    if (runIfUnclaimed(key, baseId, slot, flight, fetch)) return flight.future.join()
    return runCatching { flight.future.get(timeoutMs, TimeUnit.MILLISECONDS) }
      .getOrElse { emptyList() }
  }

  /**
   * [baseId]'s rows as a future: already complete when cached, else [fetch] run on [executor] (or
   * the request another caller already started for this generation). Never completes exceptionally;
   * a failed fetch completes with no rows, as [rows] returns.
   */
  fun rowsAsync(
    key: K,
    baseId: String,
    executor: Executor,
    fetch: () -> PreviewRowsResult?,
  ): CompletableFuture<List<PreviewParameterRow>> {
    cached(key, baseId)?.let {
      return CompletableFuture.completedFuture(it)
    }
    val (slot, flight) = flightFor(key, baseId)
    if (!flight.claimed.get() && flight.queued.compareAndSet(false, true)) {
      runCatching { executor.execute { runIfUnclaimed(key, baseId, slot, flight, fetch) } }
        .onFailure {
          // A shut-down executor: nothing will answer, so answer "no rows" now.
          if (flight.claimed.compareAndSet(false, true)) {
            inFlight.remove(slot, flight)
            flight.future.complete(emptyList())
          }
        }
    }
    return flight.future
  }

  /** The request for [baseId] at the current generation, joined or newly registered. */
  private fun flightFor(key: K, baseId: String): Pair<Pair<K, String>, InFlight> {
    val generation = generation(key).get()
    val slot = key to baseId
    val flight =
      inFlight.compute(slot) { _, existing ->
        if (existing != null && existing.generation == generation) existing
        else InFlight(generation)
      }!!
    return slot to flight
  }

  /** Runs [flight] on this thread unless another thread already took it; true when this one did. */
  private fun runIfUnclaimed(
    key: K,
    baseId: String,
    slot: Pair<K, String>,
    flight: InFlight,
    fetch: () -> PreviewRowsResult?,
  ): Boolean {
    if (!flight.claimed.compareAndSet(false, true)) return false
    val rows =
      try {
        fetchAndCache(key, baseId, flight.generation, fetch)
      } finally {
        inFlight.remove(slot, flight)
      }
    flight.future.complete(rows)
    return true
  }

  private fun fetchAndCache(
    key: K,
    baseId: String,
    generation: Long,
    fetch: () -> PreviewRowsResult?,
  ): List<PreviewParameterRow> {
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
