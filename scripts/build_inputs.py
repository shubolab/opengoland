#!/usr/bin/env python3
"""Capture and verify the repository inputs used by a compiled product."""

import argparse
import hashlib
import json
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parent.parent


def ordered_sources(sources, root):
    # Native WindowsPath comparison ignores case; provenance must have the same
    # byte ordering on every build host.
    return sorted(sources, key=lambda path: path.relative_to(root).as_posix())


def snapshot(root: Path = ROOT) -> dict:
    sources = set()
    for folder in ["src/main", "src/test"]:
        sources.update(path for path in (root / folder).rglob("*") if path.is_file())
    for path in (root / "engine").rglob("*"):
        if path.is_file() and (path.suffix == ".go" or path.name in {"go.mod", "go.sum"} or "licenses" in path.relative_to(root / "engine").parts):
            sources.add(path)
    sources.update(path for path in (root / "scripts").rglob("*") if path.is_file() and path.suffix in {".py", ".sh", ".ps1", ".iss"})
    sources.update(path for path in (root / ".github/workflows").rglob("*") if path.is_file() and path.suffix in {".yml", ".yaml"})
    sources.update(path for path in (root / "product").rglob("*windows*.json") if path.is_file())
    sources.update(path for path in (root / "product/patches").rglob("*") if path.is_file())
    for name in ["build.gradle.kts", "settings.gradle.kts", "gradle.properties", "product/platform.lock.json"]:
        sources.add(root / name)
    if (root / "product/go-tools.lock.json").is_file():
        sources.add(root / "product/go-tools.lock.json")
    if (root / ".gitattributes").is_file():
        sources.add(root / ".gitattributes")
    entries = [{"path": path.relative_to(root).as_posix(), "sha256": hashlib.sha256(path.read_bytes()).hexdigest()} for path in ordered_sources(sources, root)]
    canonical = "".join(f"{entry['sha256']}  {entry['path']}\n" for entry in entries)
    return {
        "schemaVersion": 1, "algorithm": "sha256",
        "scope": "Kotlin main/test sources and resources, Go engine sources/tests/licenses, Gradle configuration, packaging scripts and pinned public-source patches",
        "files": entries, "digest": hashlib.sha256(canonical.encode()).hexdigest(),
    }


def verify(expected: dict, root: Path = ROOT):
    actual = snapshot(root)
    if expected != actual:
        previous = {entry["path"]: entry["sha256"] for entry in expected["files"]}
        current = {entry["path"]: entry["sha256"] for entry in actual["files"]}
        changed = sorted(path for path in previous.keys() | current.keys() if previous.get(path) != current.get(path))
        raise ValueError("Build inputs changed during or after compilation: " + ", ".join(changed))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("operation", choices=["capture", "verify"])
    parser.add_argument("manifest", type=Path)
    args = parser.parse_args()
    try:
        if args.operation == "capture":
            result = snapshot()
            args.manifest.parent.mkdir(parents=True, exist_ok=True)
            args.manifest.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
        else:
            result = json.loads(args.manifest.read_text(encoding="utf-8"))
            verify(result)
        print(f"{args.operation}: {len(result['files'])} files, SHA-256 {result['digest']}")
    except (OSError, ValueError, KeyError) as error:
        print(str(error), file=sys.stderr)
        sys.exit(1)
