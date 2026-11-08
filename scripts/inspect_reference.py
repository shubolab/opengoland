#!/usr/bin/env python3
"""Inventory selected installed IDE declarations without executing or copying code."""

from __future__ import annotations

import argparse
import collections
import hashlib
import json
import plistlib
import sys
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path


GO_PLUGINS = ("go-plugin", "go-ide", "go-linter", "go-template")
SHARED_JARS = (
    "intellij.platform.ide.impl.jar",
    "intellij.platform.editor.jar",
    "intellij.platform.editor.ui.jar",
    "intellij.platform.execution.jar",
    "intellij.platform.execution.impl.jar",
    "intellij.platform.lang.jar",
)
DECLARATIONS = {
    "action", "group", "applicationConfigurable", "projectConfigurable",
    "configurationType", "programRunner", "runConfigurationProducer",
    "runLineMarkerContributor", "toolWindow", "fileType", "lang.parserDefinition",
    "completion.contributor", "lang.refactoringSupport", "refactoring.moveHandler",
    "testFinder", "xdebugger.attachDebuggerProvider", "coverageEngine",
    "coverageRunner", "localInspection", "intentionAction",
    "profiler.profilerConfigurationType",
}
ACTION_IDS = {
    "SearchEverywhere", "GotoAction", "GotoFile", "GotoClass", "GotoSymbol",
    "GotoDeclaration", "GotoImplementation", "FindUsages", "ShowUsages",
    "RenameElement", "ChangeSignature", "ExtractMethod", "IntroduceVariable",
    "Inline", "ReformatCode", "OptimizeImports", "Run", "Debug",
    "RunClass", "DebugClass", "RerunTests", "RerunFailedTests",
    "CompileDirty", "Vcs.ShowTabbedFileHistory", "Compare.SameVersion",
    "ActivateProjectToolWindow", "ActivateTerminalToolWindow",
    "ActivateRunToolWindow", "ActivateDebugToolWindow",
}


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def file_record(path: Path, root: Path) -> dict:
    data = path.read_bytes()
    return {"path": path.relative_to(root).as_posix(), "size": len(data), "sha256": sha256(data)}


def local_name(tag: str) -> str:
    return tag.rsplit("}", 1)[-1]


def read_json(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


def descriptor_nodes(root: ET.Element, module: str | None = None):
    """JetBrains 261 packages some module descriptors as XML inside CDATA."""
    yield root, module
    for child in root.iter():
        if local_name(child.tag) != "module":
            continue
        value = (child.text or "").strip()
        if value.startswith("<idea-plugin"):
            yield from descriptor_nodes(ET.fromstring(value), child.get("name"))


def collect_descriptor(data: bytes, source: dict) -> dict:
    root = ET.fromstring(data)
    declarations, extensions, modules, dependencies = [], collections.Counter(), [], []
    for descriptor, module in descriptor_nodes(root):
        if module:
            modules.append(module)
        for e in descriptor.findall("./depends"):
            dependencies.append({"module": module, "id": (e.text or "").strip(), **e.attrib})
        for e in descriptor.findall("./dependencies/*"):
            dependencies.append({"module": module, "kind": local_name(e.tag), **e.attrib})
        for section in descriptor.findall("./extensions"):
            namespace = section.get("defaultExtensionNs", "")
            for e in section:
                tag = local_name(e.tag)
                extensions[namespace + "." + tag] += 1
        for e in descriptor.iter():
            tag = local_name(e.tag)
            if tag not in DECLARATIONS:
                continue
            # Embedded descriptors are text, so each declaration is visited once.
            record = {"kind": tag, "module": module, "attributes": dict(sorted(e.attrib.items()))}
            if tag == "intentionAction":
                record["class"] = e.findtext("className")
            if tag in {"action", "group"}:
                record["keybindings"] = [dict(x.attrib) for x in e.findall("keyboard-shortcut")]
                record["placements"] = [dict(x.attrib) for x in e.findall("add-to-group")]
            declarations.append(record)
    return {
        "source": source,
        "plugin": {k: root.findtext(k) for k in ("id", "name", "version", "vendor")},
        "descriptor_requires_ultimate": "only compatible IDE" in root.findtext("description", "") and "Ultimate" in root.findtext("description", ""),
        "compatibility": dict(root.find("idea-version").attrib) if root.find("idea-version") is not None else {},
        "modules": modules,
        "dependencies": dependencies,
        "extension_counts": dict(sorted(extensions.items())),
        "declarations": declarations,
    }


def inspect_app(app: Path, go_details: bool) -> dict:
    contents = app / "Contents"
    info_path = contents / "Info.plist"
    product_path = contents / "Resources/product-info.json"
    product = read_json(product_path)
    info = plistlib.loads(info_path.read_bytes())
    result = {
        "app_path": str(app),
        "identity": {
            **{k: product.get(k) for k in ("name", "version", "buildNumber", "productCode", "dataDirectoryName")},
            **{k: info.get(k) for k in ("CFBundleIdentifier", "CFBundleExecutable", "CFBundleShortVersionString")},
        },
        "identity_sources": [file_record(info_path, contents), file_record(product_path, contents)],
        "launcher": [
            {k: launch.get(k) for k in ("os", "arch", "launcherPath", "javaExecutablePath")}
            for launch in product.get("launch", [])
        ],
        "plugin_directories": sorted(p.name for p in (contents / "plugins").iterdir() if p.is_dir()),
        "selected_platform_jars": [],
        "keymaps": [],
        "plugin_descriptors": [],
        "failures": [],
    }
    for name in SHARED_JARS:
        jar = contents / "lib" / name
        if jar.is_file():
            result["selected_platform_jars"].append(file_record(jar, contents))
    platform = contents / "lib/intellij.platform.ide.impl.jar"
    if platform.is_file():
        with zipfile.ZipFile(platform) as archive:
            for member in ("keymaps/$default.xml", "keymaps/Mac OS X 10.5+.xml"):
                if member not in archive.namelist():
                    continue
                data = archive.read(member)
                root = ET.fromstring(data)
                result["keymaps"].append({
                    "source": {"jar": str(platform.relative_to(contents)), "member": member, "sha256": sha256(data)},
                    "name": root.get("name"),
                    "parent": root.get("parent"),
                    "selected_actions": [
                        {"id": a.get("id"), "shortcuts": [dict(x.attrib) for x in a]}
                        for a in root.findall("action") if a.get("id") in ACTION_IDS
                    ],
                    "coverage": "Selected literal mappings only; inherited and runtime remapping are not resolved.",
                })
    if go_details:
        for name in GO_PLUGINS:
            directory = contents / "plugins" / name
            if not directory.exists():
                result["failures"].append({"plugin": name, "reason": "directory absent"})
                continue
            for jar in sorted(directory.rglob("*.jar")):
                if jar.name not in {"go-plugin.jar", "go-plugin-frontend.jar", "go-ide.jar", "go-linter.jar", "go-template.jar"}:
                    continue
                jar_identity = file_record(jar, contents)
                with zipfile.ZipFile(jar) as archive:
                    for member in sorted(archive.namelist()):
                        if not (member.startswith("META-INF/") and member.endswith(".xml") and member.count("/") == 1):
                            continue
                        data = archive.read(member)
                        source = {"jar": jar_identity, "member": member, "size": len(data), "sha256": sha256(data)}
                        try:
                            result["plugin_descriptors"].append(collect_descriptor(data, source))
                        except ET.ParseError as error:
                            result["failures"].append({"source": source, "reason": str(error)})
    return result


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--goland", type=Path, default=Path.home() / "Applications/GoLand.app")
    parser.add_argument("--pycharm", type=Path, default=Path.home() / "Applications/PyCharm.app")
    parser.add_argument("--output", type=Path, default=Path("docs/reference/installed-ides.json"))
    args = parser.parse_args()
    for path in (args.goland, args.pycharm):
        if not (path / "Contents/Resources/product-info.json").is_file():
            parser.error(f"Not a selected JetBrains app bundle: {path}")
    apps = [inspect_app(args.goland.resolve(), True), inspect_app(args.pycharm.resolve(), False)]
    left = {r["path"]: r for r in apps[0]["selected_platform_jars"]}
    right = {r["path"]: r for r in apps[1]["selected_platform_jars"]}
    document = {
        "schema": "openidea.reference-inventory.v1",
        "authority": "shipped-artifact",
        "confidence": "observed-static-declarations",
        "rea_evidence": "Separate canonical REA records are supplied by the root investigation; this script does not manufacture REA Evidence IDs.",
        "scope": {
            "input": "Two explicit installed application bundles; no user configuration, credentials, caches or projects.",
            "collection": "Product identity, selected platform JAR identities, selected keymap entries and four Go plugin families' descriptor declarations.",
            "execution": "No target execution, class loading, reflection, decompilation, or application mutation.",
            "limitations": [
                "A declaration identifies a candidate feature and integration point, not successful runtime behavior.",
                "Embedded module descriptors are parsed from CDATA; dynamic extensions and code-created actions are outside coverage.",
                "Counts include literal declarations from included resources; they are not unique user feature counts.",
                "File digests identify observed bytes, not provenance, licensing, or permission to redistribute.",
                "PyCharm contributes platform comparison only; its Python feature descriptors are not inventoried.",
            ],
        },
        "apps": apps,
        "platform_comparison": [
            {"path": path, "left_sha256": left[path]["sha256"], "right_sha256": right[path]["sha256"], "byte_identical": left[path]["sha256"] == right[path]["sha256"]}
            for path in sorted(left.keys() & right.keys())
        ],
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(document, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    summary = {
        "output": str(args.output.resolve()),
        "apps": [a["identity"] for a in apps],
        "descriptors": len(apps[0]["plugin_descriptors"]),
        "declarations": sum(len(d["declarations"]) for d in apps[0]["plugin_descriptors"]),
        "failures": apps[0]["failures"] + apps[1]["failures"],
    }
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    return 0 if not summary["failures"] else 1


if __name__ == "__main__":
    sys.exit(main())
