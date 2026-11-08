#!/usr/bin/env python3
"""Verify a live generated app's identity and plugin loading, not IDE feature parity."""

import argparse
import json
from pathlib import Path
import plistlib
import subprocess
import sys
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parent.parent


def verify(application: Path, pid: int):
    application = application.expanduser().resolve()
    contents = application / "Contents"
    metadata = json.loads((contents / "Resources/openidea-product.json").read_text())
    info = plistlib.loads((contents / "Info.plist").read_bytes())
    product_info = json.loads((contents / "Resources/product-info.json").read_text())
    if info.get("CFBundleDisplayName") != metadata["name"] or product_info.get("name") != metadata["name"]:
        raise ValueError("Product display names are inconsistent")
    if info.get("CFBundleIdentifier") != "dev.opengoland.ide":
        raise ValueError("Unexpected application bundle identifier")
    process = subprocess.check_output(["ps", "-p", str(pid), "-o", "comm="], text=True).strip()
    expected_process = str(contents / "MacOS/idea")
    if process != expected_process:
        raise ValueError(f"PID {pid} is not this generated application: {process}")
    jcmd = contents / "jbr/Contents/Home/bin/jcmd"
    result = subprocess.run([str(jcmd), str(pid), "VM.system_properties"], text=True, capture_output=True, check=True, timeout=20)
    properties = dict(line.split("=", 1) for line in result.stdout.splitlines() if "=" in line)
    for key, expected in [("idea.vendor.name", "OpenGoland"), ("idea.paths.selector", "OpenIDEAGo2026.1"),
                          ("intellij.platform.load.app.info.from.resources", "true")]:
        if properties.get(key) != expected:
            raise ValueError(f"Unexpected runtime {key}: {properties.get(key)!r}")
    plugin_paths = properties.get("plugin.path", "").split(",")
    expected_paths = [str(application / path) for path in metadata["pluginLoading"]["directories"]]
    if plugin_paths != expected_paths:
        raise ValueError(f"Plugin paths are missing or unresolved: {plugin_paths!r}")

    names = {}
    for directory in map(Path, plugin_paths):
        for archive in (directory / "lib").glob("*.jar"):
            with zipfile.ZipFile(archive) as bundle:
                if "META-INF/plugin.xml" not in bundle.namelist():
                    continue
                descriptor = ET.fromstring(bundle.read("META-INF/plugin.xml"))
                identifier = descriptor.findtext("id")
                if identifier in metadata["plugins"]:
                    names[identifier] = descriptor.findtext("name")
    if set(names) != set(metadata["plugins"]):
        raise ValueError("The runtime plugin paths do not contain all product plugin descriptors")

    log_path = Path.home() / "Library/Logs/OpenIDEA-Go/idea.log"
    log = log_path.read_text(errors="replace")
    marker = log.rfind("IDE STARTED")
    if marker < 0:
        raise ValueError("No startup marker in the generated app's log")
    startup = log[marker:]
    loaded_lines = [line for line in startup.splitlines() if "Loaded custom plugins:" in line or "Loaded bundled plugins:" in line]
    for name in names.values():
        if not any(name in line for line in loaded_lines):
            raise ValueError(f"The latest startup did not report loading {name}")
    return {
        "status": "pass",
        "scope": "live process identity, expanded plugin paths, startup plugin loading",
        "application": str(application), "pid": pid, "name": metadata["name"], "bundleId": info["CFBundleIdentifier"],
        "vendor": properties["idea.vendor.name"], "selector": properties["idea.paths.selector"],
        "pluginPaths": plugin_paths, "loadedPlugins": names, "log": str(log_path),
        "limitations": ["Does not prove editing, code intelligence, testing, debugging, or feature parity"],
    }


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("pid", type=int)
    parser.add_argument("--application", type=Path, default=ROOT / "build/product/open Goland.app")
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    try:
        report = verify(args.application, args.pid)
        text = json.dumps(report, ensure_ascii=False, indent=2) + "\n"
        if args.output:
            args.output.parent.mkdir(parents=True, exist_ok=True)
            args.output.write_text(text)
        print(text, end="")
    except (OSError, ValueError, KeyError, subprocess.SubprocessError) as error:
        print(json.dumps({"status": "fail", "reason": str(error)}, ensure_ascii=False), file=sys.stderr)
        sys.exit(1)
