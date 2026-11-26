package ee.schimke.composeai.mcp

import com.google.common.truth.Truth.assertThat
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import org.junit.Test

class TempFileSweepTest {

  @Test
  fun `mcp render cache dirs are deleted only when their owning pid is dead or legacy age elapsed`() {
    val tmp = createTempDirectory("cp-sweep-mcp").toFile()
    val now = System.currentTimeMillis()

    val deadDir =
      File(tmp, "compose-preview-mcp-dead").apply {
        mkdirs()
        TempFileSweep.writePidMarker(this, pid = 11111L)
        File(this, "render.png").writeText("png")
      }
    val liveDir =
      File(tmp, "compose-preview-mcp-live").apply {
        mkdirs()
        TempFileSweep.writePidMarker(this, pid = 22222L)
        File(this, "render.png").writeText("png")
      }
    val recentLegacyDir =
      File(tmp, "compose-preview-mcp-legacy-recent").apply {
        mkdirs()
        setLastModified(now - TimeUnit.MINUTES.toMillis(5))
      }
    val oldLegacyDir =
      File(tmp, "compose-preview-mcp-legacy-old").apply {
        mkdirs()
        setLastModified(now - TimeUnit.HOURS.toMillis(25))
      }

    val result =
      TempFileSweep.sweepStaleTempEntries(
        tmpDir = tmp,
        nowMs = now,
        isPidAlive = { pid -> pid == 22222L },
        liveProcessCommands = { emptyList() },
      )

    assertThat(result).isEqualTo(TempFileSweep.SweepResult(deletedFiles = 0, deletedDirs = 2))
    assertThat(deadDir.exists()).isFalse()
    assertThat(oldLegacyDir.exists()).isFalse()
    assertThat(liveDir.exists()).isTrue()
    assertThat(recentLegacyDir.exists()).isTrue()
  }

  @Test
  fun `argfiles are deleted only when past grace period and unreferenced by live processes`() {
    val tmp = createTempDirectory("cp-sweep-args").toFile()
    val now = System.currentTimeMillis()
    val pastGrace = now - TimeUnit.MINUTES.toMillis(5)
    val withinGrace = now - TimeUnit.SECONDS.toMillis(5)

    val orphanedCp =
      File(tmp, "composeai-cp-dead.args").apply {
        writeText("-cp a.jar")
        setLastModified(pastGrace)
      }
    val orphanedProps =
      File(tmp, "composeai-props-dead.args").apply {
        writeText("-Dfoo=bar")
        setLastModified(pastGrace)
      }
    val activeCp =
      File(tmp, "composeai-cp-live.args").apply {
        writeText("-cp b.jar")
        setLastModified(pastGrace)
      }
    val freshCp =
      File(tmp, "composeai-cp-fresh.args").apply {
        writeText("-cp c.jar")
        setLastModified(withinGrace)
      }
    val unrelated =
      File(tmp, "other-tool.args").apply {
        writeText("keep")
        setLastModified(pastGrace)
      }

    val result =
      TempFileSweep.sweepStaleTempEntries(
        tmpDir = tmp,
        nowMs = now,
        liveProcessCommands = { listOf("java @${activeCp.absolutePath} ee.schimke.DaemonMain") },
      )

    assertThat(result).isEqualTo(TempFileSweep.SweepResult(deletedFiles = 2, deletedDirs = 0))
    assertThat(orphanedCp.exists()).isFalse()
    assertThat(orphanedProps.exists()).isFalse()
    assertThat(activeCp.exists()).isTrue()
    assertThat(freshCp.exists()).isTrue()
    assertThat(unrelated.exists()).isTrue()
  }

  @Test
  fun `argfiles fall back to legacy max age when process command lines are unavailable`() {
    val tmp = createTempDirectory("cp-sweep-no-ps").toFile()
    val now = System.currentTimeMillis()

    val recent =
      File(tmp, "composeai-cp-recent.args").apply {
        writeText("-cp a.jar")
        setLastModified(now - TimeUnit.HOURS.toMillis(2))
      }
    val ancient =
      File(tmp, "composeai-props-ancient.args").apply {
        writeText("-Dfoo=bar")
        setLastModified(now - TimeUnit.HOURS.toMillis(25))
      }

    val result =
      TempFileSweep.sweepStaleTempEntries(
        tmpDir = tmp,
        nowMs = now,
        liveProcessCommands = { null },
      )

    assertThat(result).isEqualTo(TempFileSweep.SweepResult(deletedFiles = 1, deletedDirs = 0))
    assertThat(recent.exists()).isTrue()
    assertThat(ancient.exists()).isFalse()
  }

  @Test
  fun `roborazzi dirs wait for no live daemon or legacy max age`() {
    val tmp = createTempDirectory("cp-sweep-roborazzi").toFile()
    val now = System.currentTimeMillis()

    val recentDir =
      File(tmp, "composeai-roborazzi-results1").apply {
        mkdirs()
        setLastModified(now - TimeUnit.MINUTES.toMillis(5))
      }
    val ancientDir =
      File(tmp, "composeai-roborazzi-results2").apply {
        mkdirs()
        setLastModified(now - TimeUnit.HOURS.toMillis(25))
      }

    // While a daemon is alive, only the 24h+ directory is swept.
    val withDaemon =
      TempFileSweep.sweepStaleTempEntries(
        tmpDir = tmp,
        nowMs = now,
        liveProcessCommands = { listOf("java ee.schimke.composeai.daemon.DaemonMain") },
      )
    assertThat(withDaemon).isEqualTo(TempFileSweep.SweepResult(deletedFiles = 0, deletedDirs = 1))
    assertThat(recentDir.exists()).isTrue()
    assertThat(ancientDir.exists()).isFalse()

    // With no live daemon, the directory past the grace period is swept.
    val withoutDaemon =
      TempFileSweep.sweepStaleTempEntries(
        tmpDir = tmp,
        nowMs = now,
        liveProcessCommands = { listOf("java ee.schimke.composeai.mcp.DaemonMcpMain") },
      )
    assertThat(withoutDaemon)
      .isEqualTo(TempFileSweep.SweepResult(deletedFiles = 0, deletedDirs = 1))
    assertThat(recentDir.exists()).isFalse()
  }

  @Test
  fun `symbolic links in tmpdir are never deleted`() {
    val tmp = createTempDirectory("cp-sweep-symlink").toFile()
    val outside = createTempDirectory("cp-sweep-outside").toFile()
    val secret = File(outside, "keep.txt").apply { writeText("important") }
    val link = File(tmp, "composeai-roborazzi-results-link")
    Files.createSymbolicLink(link.toPath(), outside.toPath())

    val result =
      TempFileSweep.sweepStaleTempEntries(
        tmpDir = tmp,
        nowMs = System.currentTimeMillis() + TimeUnit.DAYS.toMillis(30),
        liveProcessCommands = { emptyList() },
      )

    assertThat(result).isEqualTo(TempFileSweep.SweepResult(deletedFiles = 0, deletedDirs = 0))
    assertThat(secret.exists()).isTrue()
  }
}
