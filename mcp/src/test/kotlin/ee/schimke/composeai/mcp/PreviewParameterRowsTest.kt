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
}
