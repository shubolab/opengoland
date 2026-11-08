#!/usr/bin/env python3
"""Read an explicitly selected IntelliJ SDK without changing the installation."""

import argparse
import json
import os
from pathlib import Path
import sys


def inspect(path: Path) -> dict:
    path = path.expanduser().resolve()
    candidates = [path / "Resources/product-info.json", path / "product-info.json", path / "Contents/Resources/product-info.json"]
    metadata = next((candidate for candidate in candidates if candidate.is_file()), None)
    if metadata is None:
        raise ValueError(f"No product-info.json under {path}")
    data = json.loads(metadata.read_text())
    launches = [
        {key: item.get(key) for key in ("os", "arch", "launcherPath", "javaExecutablePath")}
        for item in data.get("launch", [])
    ]
    return {
        "path": str(path),
        "metadata": str(metadata),
        "name": data.get("name"),
        "version": data.get("version"),
        "buildNumber": data.get("buildNumber"),
        "productCode": data.get("productCode"),
        "launch": launches,
        "officialGoPluginBundled": "org.jetbrains.plugins.go" in data.get("bundledPlugins", []),
    }


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("path", nargs="?", default=os.environ.get("OPENIDEA_PLATFORM_PATH", str(Path.home() / "Applications/PyCharm.app")))
    args = parser.parse_args()
    try:
        print(json.dumps(inspect(Path(args.path)), ensure_ascii=False, indent=2))
    except (OSError, ValueError) as error:
        print(str(error), file=sys.stderr)
        sys.exit(1)
