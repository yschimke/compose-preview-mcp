# The local MCP server

Moved from compose-preview-server's README with the server.

`compose-preview-mcp` exposes the local stdio MCP server used by editor and agent plugins.
`render_preview` remains a token-frugal semantics observation by default; a local client that can
read the same filesystem passes `inline: false` to receive the rendered PNG's absolute `pngPath`,
dimensions, SHA-256, elapsed render time, and a per-session `changed` signal instead of image bytes.
After discovery, `find_previews_for_file` maps an absolute source path — or one relative to a
registered workspace — to the preview URIs declared there. Both returned URIs can be passed
directly to `render_preview`.

## Design guidelines: `preview_guidelines_prompt`, `check_preview_guidelines`

Two tools check previews against a catalog's design guidelines (`ui-builder.guidelines.json`, the
module's `build/compose-previews/` copy unless `guidelines` names a file or URL), on the same
`ee.schimke.composeai:design-guidelines` engine as the CLI's `compose-preview guidelines`. Both take
`previews` (preview names, FQNs or `compose-preview://` URIs, judged as one batch), `guidelines` and
`surface` (`screen`, `widget` or `component`).

- **`preview_guidelines_prompt`** needs no key. It returns the batched request — the rules, each
  preview's source and accessibility nodes, and the renders as image blocks — so an agent can judge
  it with its own model.
- **`check_preview_guidelines`** asks OpenRouter, with the key from `COMPOSE_PREVIEW_OPENROUTER_KEY`,
  else the one `compose-preview guidelines key set` stored in the user's credentials file
  (`~/.config/compose-preview/credentials.json`). A stored key is used only for a workspace whose
  Gradle root the user trusted with `compose-preview guidelines key trust <dir>`; the tool cannot
  grant that itself, and refuses before rendering, naming the command. Never a tool argument;
  without a key the tool says how to set one. `model` defaults to
  `deepseek/deepseek-v4.1-flash` and `max_cost` (US dollars, default 0.10) caps the call. It returns
  each preview's verdicts with the node ids and picture regions they point at, the model that
  answered and the cost.

  With `base_url` (and `model`, the endpoint's own name) it asks an OpenAI-compatible endpoint
  instead, like `compose-preview guidelines --backend openai`: a loopback server needs no key, and
  any other only gets previews when the user stored a key for that origin with
  `compose-preview guidelines key set --for <base_url>` and trusted the project. The server reads no
  key from `COMPOSE_PREVIEW_MODEL_KEY` here, because the caller picks the endpoint. Endpoints report
  no cost, so `max_cost` does not apply.

## Apps in Robolectric's simulator: `simulator_*`

Seven tools run an Android APK in Robolectric's simulator and let the agent drive it, over
compose-ai-tools' `compose-preview launch <apk> --control stdio` (its `docs/SIMULATOR.md` has the
control protocol and the constraints). No emulator, device or `adb`.

- **`simulator_launch`** `{apk, headless?, showAgent?, qualifiers?, fontScale?, sdk?, java?, pair?, standby?}` starts
  one and waits for its `ready` event: a simulator id, the package, display size and first
  activity. The first launch of an APK converts its dex (tens of seconds); later ones come from the
  CLI's cache. **The window stays up unless `headless`**, so the person watches the agent work: the
  CLI draws each tap and swipe the agent sends over it and names the command along the bottom.
  With no display (CI, a cloud sandbox) the CLI runs headless anyway.
- **`simulator_command`** `{simulator?, cmd, …}` sends one control-channel command — `tap`,
  `longPress`, `swipe`, `key`, `back`, `text`, `hierarchy`, `find`, `launch`, `finish`,
  `activities`, `startActivity`, `startService`, `stopService`, `bindService`, `unbindService`,
  `services`, `broadcast`, `notifications`, `query`, `components`, `qualifiers`, `permissions`
  (`policy: deny` or `deny: [names]` to refuse the app's runtime permission requests), `wait`,
  `info`, and the screen's power: `screen` (`state: on | ambient | off`, `timeoutMs`, `alwaysOn`,
  `burnIn`, `lowBit`), `ambient`, `tilt` / `wake` and `palm` / `sleep`, which drive a Wear app's
  ambient callbacks, and `companion` (the app's `CompanionDeviceManager` associations and how
  `associate()` is answered: `policy: accept | reject | ask`, `answer`) — with its arguments beside `cmd`, and returns its result plus the `events` the app produced since
  the last call (activities resumed or destroyed by its own navigation, services started, intents
  nothing handled).
- **`simulator_screenshot`** returns the display as an image block (never the window's overlay).
- **`simulator_logs`** `{simulator?, device?, contains?, lines?}` returns the latest lines the
  simulator logged (the app's logcat, Robolectric, stack traces), plus the events since the last
  call.
- **`simulator_restart`** `{simulator?, device?, keepData?}` starts the app again in a fresh
  simulator JVM: a new process, so statics, singletons and in-memory state reset, while its data
  (files, databases, preferences, external storage) is kept unless `keepData: false`. The session
  and its id survive. **`simulator_update`** `{apk, keepData?}` does the same with another APK, a
  rebuild, as `adb install -r` does; its classes come from the CLI's conversion cache when that
  exact APK was converted before. Both answer with the new `info` and where the time went
  (`standby`, `snapshotMs`, `stopMs`, `startMs`, `totalMs`, and `startup`: `jvmMs`, `sandboxMs`,
  `applicationMs`, `firstActivityMs`); `simulator_update` adds `conversion` (`cached`, `ms`).
  `standby: true` on the launch keeps a second simulator JVM prepared up to the app's own code,
  which a restart takes over: the JVM, Robolectric's sandbox and the framework's setup are already
  paid.
- **`simulator_stop`** closes one. `simulator` is optional while only one runs; every simulator
  ends with the server.

**A phone and its watch.** `pair` (a Wear OS APK) runs `launch --pair`: the phone app and the watch
app in two simulators joined by a simulated Wearable Data Layer, under one simulator id.
`simulator_launch` waits for both and returns `info` as `{phone: …, watch: …}`; `device: "watch"`
on `simulator_command`, `simulator_screenshot` or `simulator_logs` addresses the watch (the phone is
the default); every event carries its `device`, and every log line starts `[phone]` or `[watch]`.

Each simulator is a `compose-preview` child process speaking JSON lines; its stderr (the app's
logs) is kept as a bounded tail, read with `simulator_logs` and returned when it fails to start or
exits. The CLI is found the
way `init-script` finds it: `COMPOSE_PREVIEW_CLI`, then `PATH`, then `~/.local/bin`.

**Developing against an unreleased CLI.** Point the server at a local compose-ai-tools build
instead of a release:

```sh
(cd ../compose-ai-tools && ./gradlew :cli:installDist :samples:android:assembleDebug)
./gradlew :mcp:installDist
COMPOSE_PREVIEW_CLI=$PWD/../compose-ai-tools/cli/build/install/compose-preview/bin/compose-preview \
  mcp/build/install/compose-preview-mcp/bin/compose-preview-mcp
```

and register that command with the agent host in place of `compose-preview mcp serve`. The
simulator needs Java 21+ (`java`, or `COMPOSE_PREVIEW_SIMULATOR_JAVA` in the server's
environment) and an Android SDK (`ANDROID_HOME`).

## `.uid` designs: `design_open`

In ChatGPT or Codex desktop, opening a `.uid` UI Builder design opens it in the UI Builder editor
instead of the default viewer, and edits save back to the file
([compose-ui-builder#364](https://github.com/yschimke/compose-ui-builder/issues/364)). The editor
is compose-ui-builder's MCP App
([#366](https://github.com/yschimke/compose-ui-builder/pull/366),
[its design note](https://github.com/yschimke/compose-ui-builder/blob/main/docs/design/UI_BUILDER_MCP_APP_HOST.md)).
It reads, saves and follows the file itself, through the host's `resources/read`,
`openai/resources/write` and `resources/subscribe`. This server adds three things:

- **`design_open`**, a file-entrypoint tool (`extensions: ["uid"]`) that takes `FileInput`
  `{file: {name, resourceUri}}` and only acknowledges the call. It has no `{path}` form for model
  calls. The editor can load and save only through a host that offers the `openai/resource`
  capability, so in Claude Code or Antigravity it would open an editor with no file.
- **`ui://compose-ui-builder/editor`**, the archive's `mcp-app/ui-builder-mcp-app.html` shell,
  served as `text/html;profile=mcp-app`. Its placeholders are filled in on every read: the asset
  base URL, and the layout from the `uiBuilderMcpAppLayout` setting (below).
  `_meta.ui.csp.resourceDomains` and `connectDomains` name the asset origin. Its display mode is
  fullscreen only.
- **The asset origin**, `http://127.0.0.1:<ephemeral>/ui-builder/v/<version>/`. The editor is
  about 45 MB unpacked, too large to inline, so the shell loads it from this origin. The listener
  starts on the first read of the editor resource. It serves only the files in the archive,
  answers GET, HEAD and OPTIONS only, and refuses path traversal. It sends:
  - `Access-Control-Allow-Origin: *`, with no credentials. The app's sandboxed frame may have an
    opaque `null` origin, and echoing that would grant the same access as `*`;
  - `Access-Control-Allow-Private-Network: true` in answer to a Private Network Access preflight;
  - `application/wasm` for `.wasm`, and the right type for each script, stylesheet, JSON file and
    font.

The `compose-preview-mcp` distribution carries the editor archive as
`ui-builder/compose-preview-ui-builder-web.zip`. It is the `composeai-ui-builder` release the
catalog pins, and it is read in place, without unpacking. To use another archive, point
`COMPOSE_PREVIEW_UI_BUILDER_WEB` at a ZIP or an unpacked directory. The tool and the resource are
registered only when the archive's `ui-builder-web.json` declares `"mcpApp": 1`. An older archive,
or no archive, leaves both out, and no error is reported.

The editor opens on a **focused canvas** by default: the design fills the panel, with a slim file
bar (name, saved state, Save, and **Full editor**), and a node's menu offers Quick edit and Comment,
which sends the node to the chat ([compose-ui-builder#378](https://github.com/yschimke/compose-ui-builder/pull/378)).
To open designs in the whole desktop editor instead, set `uiBuilderMcpAppLayout` to `full` — on the
plugin's settings page, with `settings_update {"set": {"uiBuilderMcpAppLayout": "full"}}`, or in
`~/.compose-preview/settings.json`. The value is `focused` (the default) or `full`; anything else
opens focused. It is read each time the editor resource is read, so a change applies to the next
design opened, and the editor's own button switches either way.

Still to check in a real host, as part of the
[#1236](https://github.com/yschimke/compose-preview-server/issues/1236) probe:

- whether ChatGPT/Codex desktop accepts a loopback `http://127.0.0.1:<port>` origin in
  `resourceDomains` / `connectDomains` for a local stdio server, and whether its sandbox sends the
  PNA preflight;
- whether the sandbox allows WebAssembly compilation (`'wasm-unsafe-eval'`) and WebGL;
- whether `resources/read` on the `.uid` file reports `writable: true`, so that saves happen.

