#!/usr/bin/env python3
"""Print the provenance of the viewer this repository's release [tag] re-publishes.

    python3 scripts/viewer-provenance.py v4.0.0 > compose-preview-viewer.provenance.json

The viewer is compose-preview-server's (docs/design/MCP_REPOSITORY.md). The record names where its
bytes come from, the server release and the commit that last changed it, and the release here that
re-publishes it, so a consumer (compose-agent-plugins' `scripts/update-viewer.py`) can verify both.
"""

from __future__ import annotations

import hashlib
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
VIEWER = ROOT / "mcp-app" / "compose-preview-viewer.html"
SERVER_ASSETS = ROOT / "mcp-app" / "server-assets.provenance.json"
REPOSITORY = "https://github.com/yschimke/compose-preview-mcp"


def main(argv: list[str]) -> None:
    if len(argv) != 2 or not re.fullmatch(r"v\d+\.\d+\.\d+", argv[1]):
        sys.exit("usage: viewer-provenance.py vX.Y.Z")
    tag = argv[1]
    viewer = VIEWER.read_bytes()
    server = json.loads(SERVER_ASSETS.read_text(encoding="utf-8"))
    record = server["files"]["compose-preview-viewer.html"]
    sha256 = hashlib.sha256(viewer).hexdigest()
    if sha256 != record["sha256"]:
        sys.exit("mcp-app/compose-preview-viewer.html does not match its server provenance")
    protocol = re.search(rb"protocolVersion: '([0-9-]+)',", viewer)
    if protocol is None:
        sys.exit("the viewer names no MCP Apps protocolVersion")
    print(
        json.dumps(
            {
                "repository": REPOSITORY,
                "release": tag,
                "releaseAsset": f"{REPOSITORY}/releases/download/{tag}/compose-preview-viewer.html",
                "source": {
                    "repository": server["repository"],
                    "release": server["release"],
                    "commit": record["commit"],
                    "path": record["path"],
                },
                "mcpAppsProtocolVersion": protocol.group(1).decode("ascii"),
                "sha256": sha256,
            },
            indent=2,
        )
    )


if __name__ == "__main__":
    main(sys.argv)
