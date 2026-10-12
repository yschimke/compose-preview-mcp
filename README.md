# compose-preview-mcp

The local [Model Context Protocol](https://modelcontextprotocol.io) server for Compose previews:
what `compose-preview mcp serve` launches and what
[compose-agent-plugins](https://github.com/yschimke/compose-agent-plugins) wires into each agent
harness. It moved here from
[compose-preview-server](https://github.com/yschimke/compose-preview-server) with its history;
[`docs/design/MCP_REPOSITORY.md`](docs/design/MCP_REPOSITORY.md) says why.

| Path | What |
| --- | --- |
| `mcp/` | The server (`ee.schimke.composeai.mcp`), built into the `compose-preview-mcp` distribution |
| `mcp-app/rc-viewer.html`, `mcp-app/openai-probe.html` | MCP App pages only this server serves |
| `mcp-app/compose-preview-viewer.html`, `mcp-app/preview-library.html` | Copied from compose-preview-server releases, with `server-assets.provenance.json` |
| `harness/` | Playwright checks for `rc-viewer.html` |
| `docs/LOCAL_MCP.md`, `docs/openai-extensions-probe.md` | The tools and apps the server offers, and the OpenAI MCP Extensions probe |

## Building

```sh
./gradlew check            # tests, format check, layer boundary
./gradlew :mcp:distTar     # mcp/build/distributions/compose-preview-mcp-<version>.tar.gz
npm --prefix harness ci && npm --prefix harness run rc-viewer
```

## Releases

Each GitHub release carries `compose-preview-mcp-<version>.tar.gz`, `compose-preview-viewer.html`
and `compose-preview-viewer.provenance.json`. The version line is this repository's own, starting at
4.0.0, above the 3.x releases compose-preview-server shipped the server under.
