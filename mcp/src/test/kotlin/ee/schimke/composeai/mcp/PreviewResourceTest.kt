package ee.schimke.composeai.mcp

import com.google.common.truth.Truth.assertThat
import ee.schimke.composeai.daemon.client.WorkspaceId
import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import kotlinx.serialization.json.JsonElement
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PreviewResourceTest {

  @get:Rule val tmp = TemporaryFolder()

  @Test
  fun `WorkspaceId derives stable id from canonical path`() {
    val a = tmp.newFolder("a")
    val b = tmp.newFolder("b")
    val idA1 = WorkspaceId.derive("compose-ai-tools", a)
    val idA2 = WorkspaceId.derive("compose-ai-tools", a)
    val idB = WorkspaceId.derive("compose-ai-tools", b)
    assertThat(idA1).isEqualTo(idA2)
    assertThat(idA1).isNotEqualTo(idB)
    assertThat(idA1.value).startsWith("compose-ai-tools-")
    assertThat(idA1.value.removePrefix("compose-ai-tools-").length).isEqualTo(8)
  }

  @Test
  fun `WorkspaceId distinguishes worktrees with the same project name`() {
    val main = tmp.newFolder("repos", "main")
    val feature = tmp.newFolder("repos", "feature")
    val mainId = WorkspaceId.derive("compose-ai-tools", main)
    val featureId = WorkspaceId.derive("compose-ai-tools", feature)
    assertThat(mainId).isNotEqualTo(featureId)
    assertThat(mainId.value.startsWith("compose-ai-tools-")).isTrue()
    assertThat(featureId.value.startsWith("compose-ai-tools-")).isTrue()
  }

  @Test
  fun `Bazel labels round-trip through preview and history URIs`() {
    val ws = WorkspaceId("ws-abc12345")
    for (label in listOf("//app/ui:lib", "//:aspect_app", "@repo//app:my_lib")) {
      val uri = PreviewUri(ws, label, "app.ui.GreetingKt.Greeting", config = "phone")
      val wire = uri.toUri()
      // One path segment: no '/' from the label leaks into the URI path.
      assertThat(wire.removePrefix("compose-preview://").split('?')[0].split('/')).hasSize(3)
      assertThat(PreviewUri.parse(wire)).isEqualTo(uri)

      val history = HistoryUri(ws, label, "app.ui.GreetingKt.Greeting", "e1")
      assertThat(HistoryUri.parseOrNull(history.toUri())!!.modulePath).isEqualTo(label)
    }
    // Gradle paths keep their readable encoding.
    assertThat(PreviewUri(ws, ":samples:android", "a.B").toUri())
      .isEqualTo("compose-preview://ws-abc12345/_samples_android/a.B")
  }

  @Test
  fun `PreviewUri round-trip preserves all four segments`() {
    val ws = WorkspaceId("compose-ai-tools-abc12345")
    val uri =
      PreviewUri(
        workspaceId = ws,
        modulePath = ":samples:android",
        previewFqn = "com.example.app.RedSquare",
        config = "phone-portrait",
      )
    val s = uri.toUri()
    assertThat(s)
      .isEqualTo(
        "compose-preview://compose-ai-tools-abc12345/_samples_android/com.example.app.RedSquare?config=phone-portrait"
      )
    val parsed = PreviewUri.parse(s)
    assertThat(parsed).isEqualTo(uri)
  }

  @Test
  fun `PreviewUri rejects malformed input`() {
    assertThat(PreviewUri.parseOrNull("file:///foo")).isNull()
    assertThat(PreviewUri.parseOrNull("compose-preview://onlyws/")).isNull()
    assertThat(PreviewUri.parseOrNull("compose-preview://")).isNull()
  }

  @Test
  fun `PreviewUri round-trip preserves config and encoded overrides`() {
    val uri =
      PreviewUri(
        workspaceId = WorkspaceId("workspace-abc12345"),
        modulePath = ":app",
        previewFqn = "com.example.Card",
        config = "phone-portrait",
        overridesJson = """{"uiMode":"dark","label":"A&B + ü"}""",
      )

    val encoded = uri.toUri()
    assertThat(encoded).contains("?config=phone-portrait&overrides=")
    assertThat(encoded).doesNotContain("A&B")
    assertThat(PreviewUri.parse(encoded)).isEqualTo(uri)
    assertThat(
        PreviewUri.parseOrNull(
          "compose-preview://workspace-abc12345/_app/com.example.Card?overrides=%%%"
        )
      )
      .isNull()
  }

  @Test
  fun `FqnGlob matches single and double-star semantics`() {
    val singleStar = FqnGlob("com.example.*")
    assertThat(singleStar.matches("com.example.RedSquare")).isTrue()
    // Single-star doesn't cross dot boundaries.
    assertThat(singleStar.matches("com.example.sub.RedSquare")).isFalse()

    val doubleStar = FqnGlob("com.example.**")
    assertThat(doubleStar.matches("com.example.RedSquare")).isTrue()
    assertThat(doubleStar.matches("com.example.sub.RedSquare")).isTrue()

    val questionMark = FqnGlob("Red?quare")
    assertThat(questionMark.matches("RedSquare")).isTrue()
    assertThat(questionMark.matches("Red.quare")).isFalse()
  }

  @Test
  fun `WatchEntry matches by workspace + module + glob`() {
    val ws = WorkspaceId("ws-abc12345")
    val other = WorkspaceId("other-abc12345")
    val uri =
      PreviewUri(
        workspaceId = ws,
        modulePath = ":samples:android",
        previewFqn = "com.example.RedSquare",
      )

    // Workspace-only watch matches.
    assertThat(WatchEntry(workspaceId = ws).matches(uri)).isTrue()
    // Different workspace doesn't match.
    assertThat(WatchEntry(workspaceId = other).matches(uri)).isFalse()
    // Module filter matches.
    assertThat(WatchEntry(workspaceId = ws, modulePath = ":samples:android").matches(uri)).isTrue()
    // Wrong module doesn't match.
    assertThat(WatchEntry(workspaceId = ws, modulePath = ":other").matches(uri)).isFalse()
    // FQN glob matches.
    assertThat(WatchEntry(workspaceId = ws, fqnGlobPattern = "com.example.*").matches(uri)).isTrue()
    // Wrong glob doesn't match.
    assertThat(WatchEntry(workspaceId = ws, fqnGlobPattern = "org.other.**").matches(uri)).isFalse()
  }

  @Test
  fun `a multipreview name is percent-encoded in the URI and decoded back`() {
    // #98: `@WearPreviewDevices` ids hold spaces; the URI must be valid RFC 3986.
    val ws = WorkspaceId("ws-abc12345")
    val fqn = "com.example.ActionButtonPreview_Devices - Large Round"
    val uri = PreviewUri(ws, ":app", fqn, config = "round")
    val wire = uri.toUri()
    assertThat(wire)
      .isEqualTo(
        "compose-preview://ws-abc12345/_app/" +
          "com.example.ActionButtonPreview_Devices%20-%20Large%20Round?config=round"
      )
    assertThat(java.net.URI(wire).scheme).isEqualTo("compose-preview")
    assertThat(PreviewUri.parse(wire)).isEqualTo(uri)
    // A raw spelling from before the encoding still parses, to the same preview.
    val raw = "compose-preview://ws-abc12345/_app/$fqn?config=round"
    assertThat(PreviewUri.parse(raw)).isEqualTo(uri)
    assertThat(PreviewUri.canonical(raw)).isEqualTo(wire)
    // `%`, `#` and non-ASCII round-trip; a `%` that starts no escape stays literal.
    for (odd in listOf("a.B_100%", "a.B_#1", "a.B_Ünï 😀", "a.B_%zz")) {
      assertThat(PreviewUri.parse(PreviewUri(ws, ":app", odd).toUri()).previewFqn).isEqualTo(odd)
    }
    assertThat(PreviewUri.parse("compose-preview://ws/_app/a.B_%zz").previewFqn)
      .isEqualTo("a.B_%zz")
    // An encoded '/' or '?' would not be a JVM preview id.
    assertThat(PreviewUri.parseOrNull("compose-preview://ws/_app/a%2FB")).isNull()
    assertThat(PreviewUri.parseOrNull("compose-preview://ws/_app/a%3FB")).isNull()
    // An ordinary id is unchanged.
    assertThat(PreviewUri(ws, ":app", "com.example.Red").toUri())
      .isEqualTo("compose-preview://ws-abc12345/_app/com.example.Red")
  }

  @Test
  fun `subscriptions match a preview however its URI was spelled`() {
    val subscriptions = Subscriptions()
    val session = NoopSession()
    val encoded = "compose-preview://ws/_app/a.B_Devices%20-%20Small"
    val raw = "compose-preview://ws/_app/a.B_Devices - Small"
    subscriptions.subscribe(raw, session)
    assertThat(subscriptions.sessionsSubscribedTo(encoded)).containsExactly(session)
    // Notifications name the URI the client subscribed to.
    assertThat(subscriptions.subscribedUrisFor(encoded).keys).containsExactly(raw)
    assertThat(subscriptions.subscribedUrisMatching(PreviewUri.parse(encoded)).keys)
      .containsExactly(raw)
    subscriptions.unsubscribe(encoded, session)
    assertThat(subscriptions.sessionsSubscribedTo(raw)).isEmpty()
  }

  @Test
  fun `canonical overrides fold the preview's own font scale and empty overrides`() {
    assertThat(canonicalOverrides(null)).isNull()
    assertThat(canonicalOverrides(PreviewOverrides())).isNull()
    assertThat(canonicalOverrides(PreviewOverrides(fontScale = 1.0f))).isNull()
    assertThat(canonicalOverrides(PreviewOverrides(device = "id:wearos_small_round")))
      .isEqualTo(
        canonicalOverrides(PreviewOverrides(device = "id:wearos_small_round", fontScale = 1f))
      )
    assertThat(canonicalOverrides(PreviewOverrides(fontScale = 1.5f)))
      .isEqualTo(PreviewOverrides(fontScale = 1.5f))
    // A preview declaring its own fontScale: 1.0 is a real change, its own value is not.
    assertThat(canonicalOverrides(PreviewOverrides(fontScale = 1.0f), declaredFontScale = 2f))
      .isEqualTo(PreviewOverrides(fontScale = 1.0f))
    assertThat(canonicalOverrides(PreviewOverrides(fontScale = 2f), declaredFontScale = 2f))
      .isNull()
  }

  private class NoopSession : Session {
    override fun notifyResourceUpdated(uri: String) = Unit

    override fun notifyResourceListChanged() = Unit

    override fun notifyToolListChanged() = Unit

    override fun notifyProgress(
      token: JsonElement,
      progress: Double,
      total: Double?,
      message: String?,
    ) = Unit
  }
}
