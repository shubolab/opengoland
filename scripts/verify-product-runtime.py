#!/usr/bin/env python3
"""Verify a live generated app's identity and plugin loading, not IDE feature parity."""

import argparse
import hashlib
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

    platform_artifacts = []
    for patch in metadata.get("platformPatches", []):
        artifact = (application / patch["upstreamJarRelativePath"]).resolve()
        if not artifact.is_relative_to(contents):
            raise ValueError("Platform patch artifact escapes this application")
        digest = hashlib.sha256(artifact.read_bytes()).hexdigest()
        if digest != patch["patchedJarSha256"]:
            raise ValueError("The packaged platform patch artifact changed")
        with zipfile.ZipFile(artifact) as bundle:
            marker = json.loads(bundle.read(patch["marker"]))
            for key in ("upstreamCommit", "patchSha256", "classSha256"):
                if marker[key] != patch[key]:
                    raise ValueError("The packaged platform patch marker is inconsistent")
            for member, expected in marker["classSha256"].items():
                if hashlib.sha256(bundle.read(member)).hexdigest() != expected:
                    raise ValueError("The packaged platform patch class changed: " + member)
        platform_artifacts.append({"path": str(artifact), "sha256": digest, "patchSha256": patch["patchSha256"]})

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
    product_policy = metadata.get("goProductPolicy")
    local_ide = metadata.get("localIdeDistribution")
    policy_checks = None
    if product_policy:
        for item in product_policy["bundledPluginExclusions"]:
            if (contents / item["relativeDirectory"]).exists():
                raise ValueError("An excluded IDE customization plugin remains in this app")
        for directory in product_policy["retainedBundledDirectories"]:
            if not (contents / directory).is_dir():
                raise ValueError("A retained language plugin is missing")
        app_info = product_policy["applicationInfo"]
        with zipfile.ZipFile(contents / app_info["jar"]) as bundle:
            data = bundle.read(app_info["entry"])
        if hashlib.sha256(data).hexdigest() != app_info["brandedSha256"]:
            raise ValueError("The product application-info policy resource changed")
        xml = ET.fromstring(data)
        essential = [node.text.strip() for node in xml if node.tag.rsplit("}", 1)[-1] == "essential-plugin"]
        if essential != app_info["essentialAfter"]:
            raise ValueError("The product essential-plugin list is inconsistent")
        if app_info.get("publisher"):
            company = next((node for node in xml if node.tag.rsplit("}", 1)[-1] == "company"), None)
            if company is None or any(company.get(key) != value for key, value in app_info["publisher"].items()):
                raise ValueError("The product publisher identity is inconsistent")
        if "Override failed for com.intellij.lang.IdeLanguageCustomization" in startup:
            raise ValueError("The current startup still reports a primary-language service conflict")
        distribution = product_policy["metadata"]
        artifact_checks = {}
        for relative, expected_digest in distribution["artifactSha256"].items():
            artifact = contents / relative
            if artifact.is_symlink() or not artifact.resolve().is_relative_to(contents):
                raise ValueError("Product metadata artifact escapes this application")
            actual_digest = hashlib.sha256(artifact.read_bytes()).hexdigest()
            if actual_digest != expected_digest:
                raise ValueError("Product metadata artifact changed: " + relative)
            artifact_checks[relative] = actual_digest
        inventory = distribution["productInfoInventory"]
        final_inventory = local_ide["productInfoInventory"] if local_ide else inventory
        for key, expected_count in final_inventory["afterCounts"].items():
            if len(product_info[key]) != expected_count:
                raise ValueError("Product-info inventory size changed: " + key)
        excluded_ids = {item["id"] for item in product_policy["bundledPluginExclusions"]}
        excluded_modules = {name for item in product_policy["bundledPluginExclusions"] for name in item["moduleIds"]}
        if (excluded_ids.intersection(product_info["bundledPlugins"]) or
                excluded_modules.intersection(product_info["modules"]) or
                any(entry["name"] in excluded_ids | excluded_modules for entry in product_info["layout"])):
            raise ValueError("Excluded customization remains in the product-info inventory")
        if not set(inventory["preservedBundledIds"]).issubset(product_info["bundledPlugins"]):
            raise ValueError("A retained independent bundled plugin is missing from product-info")
        policy_checks = {"excludedBundledIds": [item["id"] for item in product_policy["bundledPluginExclusions"]],
                         "remainingEssentialIds": essential, "applicationInfoSha256": app_info["brandedSha256"],
                         "metadataArtifactSha256": artifact_checks, "productInfoCounts": final_inventory["afterCounts"],
                         "startupPrimaryLanguageConflictAbsent": True}
    local_checks = None
    if local_ide:
        if properties.get(local_ide["localPluginManagerProperty"]) != "true":
            raise ValueError("The local-only plugin manager is not enabled in this process")
        for entry in local_ide["removedFiles"]:
            if (contents / entry["path"]).exists():
                raise ValueError("An excluded account/commercial component remains in this app")
        for entry in local_ide["retainedFiles"]:
            path = contents / entry["path"]
            if path.is_symlink() or not path.resolve().is_relative_to(contents):
                raise ValueError("Retained shared library escapes this application")
            if path.stat().st_size != entry["bytes"] or hashlib.sha256(path.read_bytes()).hexdigest() != entry["sha256"]:
                raise ValueError("A retained shared development library changed")
        actual_grazie = {path.relative_to(contents).as_posix() for path in (contents / "plugins/grazie").rglob("*") if path.is_file()}
        expected_grazie = {entry["path"] for entry in local_ide["retainedFiles"] if entry["path"].startswith("plugins/grazie/")}
        if actual_grazie != expected_grazie:
            raise ValueError("Unrecognized files remain in the excluded Grazie plugin directory")
        resources_checked = []
        for record in local_ide["jars"]:
            path = contents / record["path"]
            if path.is_symlink() or not path.resolve().is_relative_to(contents):
                raise ValueError("A local IDE resource archive escapes this application")
            if hashlib.sha256(path.read_bytes()).hexdigest() != record["afterSha256"]:
                raise ValueError("A local IDE resource archive changed")
            with zipfile.ZipFile(path) as bundle:
                for resource in record["resources"]:
                    member = resource["path"].split("!/", 1)[1]
                    if hashlib.sha256(bundle.read(member)).hexdigest() != resource["after"]["sha256"]:
                        raise ValueError("A removed account/commercial registration resource changed")
                    resources_checked.append(resource["path"])
        excluded = set(local_ide["productInfoInventory"]["excludedIds"])
        if (excluded.intersection(product_info["bundledPlugins"]) or
                excluded.intersection(product_info["modules"]) or
                any(entry["name"] in excluded for entry in product_info["layout"])):
            raise ValueError("A removed account/commercial component remains in product-info")
        if not set(local_ide["productInfoInventory"]["preservedBundledIds"]).issubset(product_info["bundledPlugins"]):
            raise ValueError("A retained development-service plugin is missing")
        local_checks = {"localPluginManagerEnabled": True, "removedFilesAbsent": len(local_ide["removedFiles"]),
                        "resourcesChecked": resources_checked, "retainedSharedLibraries": local_ide["retainedFiles"],
                        "excludedInventoryIds": sorted(excluded), "scope": "Runtime property and packaged registration/resource identity; native UI and feature behavior require their separate observations."}
    archive_checks = metadata.get("archiveChecks")
    if archive_checks:
        if archive_checks["status"] != "pass":
            raise ValueError("The product did not pass its native archive-reader guard")
        for record in archive_checks["archives"]:
            path = contents / record["path"]
            if (path.is_symlink() or not path.resolve().is_relative_to(contents) or
                    hashlib.sha256(path.read_bytes()).hexdigest() != record["sha256"]):
                raise ValueError("An archive changed after native reader verification")
    return {
        "status": "pass",
        "scope": "live process identity, expanded plugin paths, startup plugin loading and on-disk platform patch identity",
        "application": str(application), "pid": pid, "name": metadata["name"], "bundleId": info["CFBundleIdentifier"],
        "vendor": properties["idea.vendor.name"], "selector": properties["idea.paths.selector"],
        "pluginPaths": plugin_paths, "loadedPlugins": names, "log": str(log_path),
        "platformArtifactChecks": platform_artifacts,
        "goProductPolicyChecks": policy_checks,
        "localIdeDistributionChecks": local_checks,
        "archiveReaderChecks": archive_checks,
        "limitations": ["Does not prove editing, code intelligence, testing, debugging, feature parity or live class resolution"],
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
