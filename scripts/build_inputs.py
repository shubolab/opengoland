#!/usr/bin/env python3
"""Capture and verify the repository inputs used by a compiled product."""

import argparse
import hashlib
import json
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parent.parent


def snapshot(root: Path = ROOT) -> dict:
    sources = set()
    for folder in ["src/main", "src/test"]:
        sources.update(path for path in (root / folder).rglob("*") if path.is_file())
    for path in (root / "engine").rglob("*"):
        if path.is_file() and (path.suffix == ".go" or path.name in {"go.mod", "go.sum"} or "licenses" in path.relative_to(root / "engine").parts):
            sources.add(path)
    sources.update(path for path in (root / "scripts").iterdir() if path.is_file() and path.suffix in {".py", ".sh"})
    for name in ["build.gradle.kts", "settings.gradle.kts", "gradle.properties", "product/platform.lock.json"]:
        sources.add(root / name)
    entries = [{"path": path.relative_to(root).as_posix(), "sha256": hashlib.sha256(path.read_bytes()).hexdigest()} for path in sorted(sources)]
    canonical = "".join(f"{entry['sha256']}  {entry['path']}\n" for entry in entries)
    return {
        "schemaVersion": 1, "algorithm": "sha256",
        "scope": "Kotlin main/test sources and resources, Go engine sources/tests/licenses, Gradle configuration, packaging scripts",
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
            args.manifest.write_text(json.dumps(result, indent=2) + "\n")
        else:
            result = json.loads(args.manifest.read_text())
            verify(result)
        print(f"{args.operation}: {len(result['files'])} files, SHA-256 {result['digest']}")
    except (OSError, ValueError, KeyError) as error:
        print(str(error), file=sys.stderr)
        sys.exit(1)
