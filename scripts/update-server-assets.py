#!/usr/bin/env python3
"""Move the MCP App pages this repository borrows to a compose-preview-server release.

    python3 scripts/update-server-assets.py v3.132.0

`compose-preview-viewer.html` and `preview-library.html` are owned by compose-preview-server,
because its hosted `/mcp` endpoint serves them too (docs/design/MCP_REPOSITORY.md). This copies
both, byte for byte, from that release's tag into `mcp-app/`, and records for each the commit that
last changed it at that tag and its sha256 in `mcp-app/server-assets.provenance.json`. The pages
are never edited here: a change to one is a compose-preview-server release first.

`--check` verifies the checked-in pages against the provenance file instead, without the network.
"""

from __future__ import annotations

import hashlib
import json
import re
import sys
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MCP_APP = ROOT / "mcp-app"
PROVENANCE = MCP_APP / "server-assets.provenance.json"
REPOSITORY = "https://github.com/yschimke/compose-preview-server"
API = "https://api.github.com/repos/yschimke/compose-preview-server"
RAW = "https://raw.githubusercontent.com/yschimke/compose-preview-server"
FILES = ("compose-preview-viewer.html", "preview-library.html")
TAG = re.compile(r"^v\d+\.\d+\.\d+$")
SHA = re.compile(r"^[0-9a-f]{40}$")


def fetch(url: str) -> bytes:
    request = urllib.request.Request(url, headers={"User-Agent": "compose-preview-mcp"})
    with urllib.request.urlopen(request, timeout=60) as response:
        return response.read()


def source_commit(tag: str, path: str) -> str:
    """The last commit at [tag] that changed [path]."""
    commits = json.loads(fetch(f"{API}/commits?sha={tag}&path={path}&per_page=1"))
    if not commits:
        sys.exit(f"{tag} has no commit touching {path}")
    return commits[0]["sha"]


def check() -> None:
    provenance = json.loads(PROVENANCE.read_text(encoding="utf-8"))
    problems = []
    if provenance.get("repository") != REPOSITORY:
        problems.append(f"repository is {provenance.get('repository')!r}, not {REPOSITORY}")
    if not TAG.fullmatch(str(provenance.get("release", ""))):
        problems.append(f"release {provenance.get('release')!r} is not a vX.Y.Z tag")
    files = provenance.get("files", {})
    if sorted(files) != sorted(FILES):
        problems.append(f"files are {sorted(files)}, expected {sorted(FILES)}")
    for name, record in files.items():
        page = MCP_APP / name
        if not page.is_file():
            problems.append(f"{page.relative_to(ROOT)} is missing")
            continue
        actual = hashlib.sha256(page.read_bytes()).hexdigest()
        if record.get("sha256") != actual:
            problems.append(
                f"{name}: sha256 {actual} does not match the provenance's {record.get('sha256')}; "
                "the page was edited here, or copied without scripts/update-server-assets.py"
            )
        if not SHA.fullmatch(str(record.get("commit", ""))):
            problems.append(f"{name}: commit {record.get('commit')!r} is not a full sha")
    if problems:
        sys.exit("server assets:\n  " + "\n  ".join(problems))
    print(f"server assets match {provenance['release']}")


def update(tag: str) -> None:
    previous = json.loads(PROVENANCE.read_text(encoding="utf-8")) if PROVENANCE.is_file() else {}
    files = {}
    for name in FILES:
        path = f"mcp-app/{name}"
        page = fetch(f"{RAW}/{tag}/{path}")
        (MCP_APP / name).write_bytes(page)
        files[name] = {
            "path": path,
            "commit": source_commit(tag, path),
            "sha256": hashlib.sha256(page).hexdigest(),
        }
    provenance = {"repository": REPOSITORY, "release": tag, "files": files}
    PROVENANCE.write_text(json.dumps(provenance, indent=2) + "\n", encoding="utf-8")
    print(f"server assets {previous.get('release', 'none')} -> {tag}")


def main(argv: list[str]) -> None:
    if argv[1:] == ["--check"]:
        check()
    elif len(argv) == 2 and TAG.fullmatch(argv[1]):
        update(argv[1])
    else:
        sys.exit("usage: update-server-assets.py vX.Y.Z | --check")


if __name__ == "__main__":
    main(sys.argv)
