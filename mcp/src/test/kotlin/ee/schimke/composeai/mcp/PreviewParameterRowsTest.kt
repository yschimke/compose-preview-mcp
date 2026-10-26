package ee.schimke.composeai.mcp

import com.google.common.truth.Truth.assertThat
import ee.schimke.composeai.daemon.protocol.PreviewRowDto
import ee.schimke.composeai.daemon.protocol.PreviewRowsResult
import org.junit.Test

class PreviewParameterRowsTest {

  private val rows = PreviewParameterRows<String>()

  private fun result(vararg ids: String) =
    PreviewRowsResult("base", ids.mapIndexed { i, id -> PreviewRowDto(i, "PARAM_$i", id) })

  @Test
  fun `rows are fetched once and cached in index order`() {
    var calls = 0
    val fetch = {
      calls++
      PreviewRowsResult(
        "base",
        listOf(PreviewRowDto(1, "B", "base_B"), PreviewRowDto(0, "A", "base_A")),
      )
    }
    assertThat(rows.rows("m", "base", fetch).map { it.id })
      .containsExactly("base_A", "base_B")
      .inOrder()
    assertThat(rows.rows("m", "base", fetch).map { it.id }).hasSize(2)
    assertThat(calls).isEqualTo(1)
    assertThat(rows.baseOf("m", "base_B")).isEqualTo("base")
    assertThat(rows.baseOf("other", "base_B")).isNull()
  }

  @Test
  fun `method-not-found caches no rows, and a failure is asked again`() {
    assertThat(rows.rows("m", "old") { null }).isEmpty()
    assertThat(rows.cached("m", "old")).isEmpty()

    assertThat(rows.rows("m", "flaky") { error("boom") }).isEmpty()
    assertThat(rows.cached("m", "flaky")).isNull()
    assertThat(rows.rows("m", "flaky") { result("flaky_PARAM_0") }.map { it.id })
      .containsExactly("flaky_PARAM_0")
  }

  @Test
  fun `an answer in flight across an invalidation is not cached`() {
    val answer =
      rows.rows("m", "base") {
        rows.invalidate("m")
        result("base_PARAM_0")
      }
    assertThat(answer.map { it.id }).containsExactly("base_PARAM_0")
    assertThat(rows.cached("m", "base")).isNull()
  }

  @Test
  fun `invalidating some base ids keeps the others`() {
    rows.rows("m", "a") { result("a_PARAM_0") }
    rows.rows("m", "b") { result("b_PARAM_0") }
    rows.invalidate("m", listOf("a"))
    assertThat(rows.cachedRows("m").keys).containsExactly("b")
  }

  @Test
  fun `a row id a URI cannot carry is dropped`() {
    assertThat(rows.rows("m", "base") { result("base_a/b", "base_ok") }.map { it.id })
      .containsExactly("base_ok")
  }

  @Test
  fun `concurrent async callers share one request, which fills the cache`() {
    val release = java.util.concurrent.CountDownLatch(1)
    val executor = java.util.concurrent.Executors.newFixedThreadPool(2)
    try {
      var calls = 0
      val fetch = {
        calls++
        release.await()
        result("base_PARAM_0")
      }
      val first = rows.rowsAsync("m", "base", executor, fetch)
      val second = rows.rowsAsync("m", "base", executor, fetch)
      assertThat(second).isSameInstanceAs(first)
      assertThat(first.isDone).isFalse()
      release.countDown()
      assertThat(first.get(5, java.util.concurrent.TimeUnit.SECONDS).map { it.id })
        .containsExactly("base_PARAM_0")
      assertThat(calls).isEqualTo(1)
      assertThat(rows.rowsAsync("m", "base", executor, fetch).isDone).isTrue()
    } finally {
      executor.shutdownNow()
    }
  }

  @Test
  fun `an async request started before an invalidation is not reused after it`() {
    val release = java.util.concurrent.CountDownLatch(1)
    val executor = java.util.concurrent.Executors.newFixedThreadPool(2)
    try {
      val stale =
        rows.rowsAsync("m", "base", executor) {
          release.await()
          result("base_old")
        }
      rows.invalidate("m")
      val fresh = rows.rowsAsync("m", "base", executor) { result("base_new") }
      assertThat(fresh).isNotSameInstanceAs(stale)
      assertThat(fresh.get(5, java.util.concurrent.TimeUnit.SECONDS).map { it.id })
        .containsExactly("base_new")
      release.countDown()
      stale.get(5, java.util.concurrent.TimeUnit.SECONDS)
      assertThat(rows.cached("m", "base")!!.map { it.id }).containsExactly("base_new")
    } finally {
      executor.shutdownNow()
    }
  }

  @Test
  fun `a blocking lookup runs a request still queued behind a busy pool on its own thread`() {
    val release = java.util.concurrent.CountDownLatch(1)
    val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
    try {
      // The pool's one thread is held, so the next request waits in its queue.
      rows.rowsAsync("m", "busy", executor) {
        release.await()
        result("busy_PARAM_0")
      }
      val queued = rows.rowsAsync("m", "base", executor) { result("base_PARAM_0") }
      assertThat(queued.isDone).isFalse()
      val started = System.nanoTime()
      assertThat(rows.rowsBlocking("m", "base", 10_000) { result("base_PARAM_0") }.map { it.id })
        .containsExactly("base_PARAM_0")
      assertThat((System.nanoTime() - started) / 1_000_000).isLessThan(2_000)
      // The listing's future is the same request, answered by the blocking caller.
      assertThat(queued.isDone).isTrue()
    } finally {
      release.countDown()
      executor.shutdownNow()
    }
  }

  @Test
  fun `a blocking lookup waits for a running request at most its timeout`() {
    val release = java.util.concurrent.CountDownLatch(1)
    val running = java.util.concurrent.CountDownLatch(1)
    val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
    try {
      rows.rowsAsync("m", "base", executor) {
        running.countDown()
        release.await()
        result("base_PARAM_0")
      }
      running.await()
      val started = System.nanoTime()
      assertThat(rows.rowsBlocking("m", "base", 300) { error("not asked twice") }).isEmpty()
      assertThat((System.nanoTime() - started) / 1_000_000).isLessThan(2_000)
    } finally {
      release.countDown()
      executor.shutdownNow()
    }
  }
}
