package ee.schimke.composeai.mcp

import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlin.io.path.createTempDirectory
import org.junit.Test

class WorkspaceStoreTest {
  @Test
  fun `malformed cached entries do not stop valid workspaces from loading`() {
    val root = createTempDirectory("cp-workspace-malformed").toFile()
    val storeFile = File(root, "workspaces.json")
    for (bad in
      listOf(
        """{"id":{},"path":"/bad"}""",
        """{"id":"bad","path":[]}""",
        """{"id":"other","path":"/other","lastUsed":{}}""",
        """{"id":"another","path":"/another","name":[]}""",
      )) {
      storeFile.writeText("""{"workspaces":[$bad,{"id":"valid","path":"/valid","lastUsed":1}]}""")
      val store = WorkspaceStore(storeFile)
      assertThat(store.get("valid")?.path).isEqualTo("/valid")
      assertThat(store.all().map { it.id }).contains("valid")
    }
  }

  @Test
  fun `a sibling process's save does not bring back an id another process forgot`() {
    val root = createTempDirectory("cp-workspace-store").toFile()
    val storeFile = File(root, "workspaces.json")
    val first = WorkspaceStore(storeFile)
    first.remember("a-0", File(root, "a"), "a")
    first.remember("b-0", File(root, "b"), "b")
    // A second server process, started while both were stored.
    val second = WorkspaceStore(storeFile)
    assertThat(second.get("a-0")).isNotNull()

    // #1188: unregister_project in the first process, then any save in the second.
    first.forget("a-0")
    second.remember("c-0", File(root, "c"), "c")

    assertThat(WorkspaceStore(storeFile).all().map { it.id }).containsExactly("b-0", "c-0")
    assertThat(second.all().map { it.id }).containsExactly("b-0", "c-0")
    // The other process's own new entry survives the first process's next save.
    first.remember("d-0", File(root, "d"), "d")
    assertThat(WorkspaceStore(storeFile).all().map { it.id }).containsExactly("b-0", "c-0", "d-0")
  }
}
