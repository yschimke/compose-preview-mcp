# The MCP repository

**Status: proposed.** This note decides what moves into `compose-preview-mcp`, where it sits in
the repository layers, and the order of the move. Nothing has moved yet.

## Why a repository

`compose-preview-mcp` is what an agent installs. compose-agent-plugins wires it into every harness,
and its cards bundle the MCP App viewer. Both currently come from
[compose-preview-server](https://github.com/yschimke/compose-preview-server), which is otherwise a
deployment repository: the hosted catalog, `serve-web`, the UI builder and their images. So the
agent artifact rides that release train:
- its version moves when a deployment changes;
- a viewer fix such as yschimke/compose-preview-server#1620 waits on a server release before
  compose-agent-plugins can bundle it;
- its CI runs the server's 4,000-odd tests for a change that touches neither.

## The layer rule, and why this is not the rejected fourth repository

[`REPOSITORY_LAYERS.md`](https://github.com/yschimke/compose-ai-tools/blob/main/docs/design/REPOSITORY_LAYERS.md)
is normative, and it rejected one extra repository already: `compose-preview-runtime` "bought
another release train for the same graph while leaving the placement question unanswered". This
proposal has to answer the placement question, and it does.

**`compose-preview-mcp` is a layer-3 leaf**, beside `compose-preview-vscode` and
`compose-preview-xr`. That is the rule applied, not an exception:
- **It is a host.** An MCP server is a platform host for agents, the same kind of thing as the VS
  Code extension is for an editor.
- **Nothing depends on it as a coordinate.** The CLI's `compose-preview mcp serve` execs the
  published binary across a process boundary, exactly as `serve` launches the server. The
  compose-agent-plugins repository wires the binary and copies the viewer as release assets.
- **It only depends downward:**
  - layer 1 (compose-ai-tools) and 1a (compose-preview-daemon): `daemon-core`,
    `render-session-api`, `daemon-client`, `render-matrix`, `design-guidelines`,
    `data-layoutinspector-core`;
  - layer 0 (compose-preview-contracts);
  - layer 2 (compose-preview-server) only for the viewer, below.

It differs from `compose-preview-runtime` in the way `compose-preview-daemon` did. The module set
is closed: `:mcp` has no project dependency in compose-preview-server, and nothing there depends on
it. The release train it buys has a reason of its own: the agent surface changes on a different
cadence from the deployments.

## What moves, and what stays

| Thing | Today | After |
| --- | --- | --- |
| `:mcp` (`ee.schimke.composeai.mcp`, 76 Kotlin files with its tests) | server repo | **here** |
| `compose-preview-mcp-<v>.tar.gz` release asset | server release | **here** |
| `mcp-app/rc-viewer.html`, `mcp-app/openai-probe.html` (only `:mcp` reads them) | server repo | **here** |
| `mcp-app/compose-preview-viewer.html`, `mcp-app/preview-library.html` | server repo | **stay**: the hosted `/mcp` (`ServeCatalogMcp`, `ServeLibraryMcp`) serves them too |
| The hosted `/mcp` endpoint and its deployments | server repo | stay |
| The viewer tests (`scripts/mcp-viewer-*.test.mjs`) | server repo | stay with the viewer |

**The viewer stays in layer 2.** It is a web surface that both MCP servers serve. Moving it here
would make the server (layer 2) depend on a leaf (layer 3), which is the cycle the rule forbids.
Instead this repository consumes the viewer from a pinned server release and **re-publishes it as
its own release asset, byte for byte, with the same provenance file**
(`compose-preview-viewer.provenance.json`: release, source commit, sha256). Then compose-agent-plugins
takes both the binary and the viewer from one release here: `scripts/update-viewer.py` changes
repository, not shape. A viewer fix still needs a server release first, but no longer a full
server deployment cycle.

## What the move has to untangle

`:mcp` is close to standalone; these are the edges, all small:

1. **Build flags.** The root `generateMcpBuildFeatures` task (the `-PuiBuilderRemoteCompose`
   switch) becomes this build's own task.
2. **The `.rc` viewer's player bundle.** `stageRcViewerPlayer` uses `buildSrc`'s `StageRcPlayerJs`
   and the shim `server/src/rc-player/inert-custom-host.js`.
   - Copy both here; the shim is a few lines.
   - Or better: publish the shimmed bundle from rc-players, so neither repository carries it.
3. **The UI-builder web archive** (`design_open`). It already resolves `ui-builder-web` as an
   artifact, so the edge doesn't change.
4. **CI.** These move with the module:
   - `:mcp:test`;
   - the remote-Compose lane (`-PuiBuilderRemoteCompose=true :mcp:test`);
   - `edit-loop.yml`;
   - the harness specs that open `rc-viewer.html` (`mcp-app-rc-viewer.spec.mjs`).
5. **The house gates.** These are copied, not shared:
   - attribution, PR title, `ktfmtCheckAll`;
   - release-please;
   - the distribution's `java-min.properties`.

## Version line and launch

The binary gets its own version line from here: `compose-preview-mcp` 1.0.0, not the server's 3.x.

compose-ai-tools changes in step:
- the `mcp serve` launcher (`McpCommand`) fetches `compose-preview-mcp-<v>.tar.gz` from this
  repository's releases;
- the pin moves beside the server pin in `docs/VERSIONING.md` § 8.1;
- the release order in `CROSS_REPO_RELEASES.md` gains this repository after the server.

## Order of the move

Each step is reviewable as a move ("renaming a package is a separate change from moving a module",
`PREVIEW_SERVER_SPLIT.md`), so sources keep `ee.schimke.composeai.mcp` and tests keep JUnit 4 and
Truth.

1. **This note**, and `REPOSITORY_LAYERS.md` amended in compose-ai-tools to place the repository at
   layer 3.
2. **Stand the module up here with history** (`git filter-repo --path mcp/ --path
   mcp-app/rc-viewer.html --path mcp-app/openai-probe.html`, plus the build edges above).
   - Build it against the same published coordinates.
   - Pin the viewer from the current server release.
   - Release `compose-preview-mcp` 1.0.0 with the binary and the re-published viewer.
3. **Point the consumers here:**
   - compose-ai-tools' `mcp serve` launcher;
   - compose-agent-plugins' MCP wiring (`src/plugins.json` `repository`) and `update-viewer.py`.
4. **Delete `:mcp` from compose-preview-server**, along with its release asset and CI lanes. The
   server's last release that ships the binary stays fetchable, so older CLIs keep working.

## Open questions

- **The card helper.** Does `compose-preview-card.py` (compose-agent-plugins) move here, next to
  the viewer it wraps? It is harness glue (Antigravity's `<agent-embed>`), which argues for staying.
- **rc player bundle.** Copy the shim (step 2's first choice), or publish the shimmed bundle from
  rc-players first? The second is cleaner but puts a third repository on the critical path.
