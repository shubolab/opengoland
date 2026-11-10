#!/usr/bin/env python3
"""Assemble a Windows distribution from the authenticated OSS SDK and host build.

This creates an owned product tree. Inno Setup, invoked separately, creates the
installer. The source SDK, its launcher bytes and user configuration stay intact.
"""

import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET
import zipfile

from build_inputs import verify as verify_build_inputs
from platform_layout import detect_platform_layout
from product_archives import verify_product_archives

ROOT = Path(__file__).resolve().parent.parent
spec = importlib.util.spec_from_file_location("mac_product_shared", ROOT / "scripts/package-product.py")
shared = importlib.util.module_from_spec(spec)
spec.loader.exec_module(shared)


def digest(path):
    with Path(path).open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def files(root):
    result = {}
    for path in sorted(root.rglob("*")):
        if path.is_symlink():
            raise ValueError("Windows distribution inputs must not contain symbolic links: " + str(path))
        if path.is_file():
            result[path.relative_to(root).as_posix()] = {"bytes": path.stat().st_size, "sha256": digest(path)}
    return result


def repo_pin(value):
    path = (ROOT / value).resolve()
    if not path.is_relative_to(ROOT / "product") or not path.is_file():
        raise ValueError("A Windows policy pin is missing or outside product/: " + value)
    return path


def plugin_metadata(directory):
    matches = []
    for archive in sorted((directory / "lib").glob("*.jar")):
        with zipfile.ZipFile(archive) as bundle:
            if "META-INF/plugin.xml" in bundle.namelist():
                xml = ET.fromstring(bundle.read("META-INF/plugin.xml"))
                if xml.findtext("id") == "dev.openidea.go":
                    matches.append((xml.findtext("version"), json.loads(bundle.read("META-INF/openidea-build-inputs.json"))))
    if len(matches) != 1:
        raise ValueError("Exactly one compiled Go plugin with build provenance is required")
    return matches[0]


def assemble(sdk, plugins, output, version):
    if os.name != "nt":
        raise ValueError("Windows release assembly must run on Windows with its native JBR")
    if not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+(?:-[0-9A-Za-z.-]+)?", version):
        raise ValueError("A concrete release version is required")
    layout = detect_platform_layout(sdk)
    if layout.platform != "windows":
        raise ValueError("The Windows SDK layout is required")
    pin = json.loads((ROOT / "product/platform-windows.lock.json").read_text(encoding="utf-8"))
    if json.loads((layout.sdk_root / ".openidea-platform.json").read_text(encoding="utf-8")) != pin:
        raise ValueError("Bootstrap the authenticated Windows SDK before assembly")
    output = output.resolve()
    if not output.is_relative_to((ROOT / "build").resolve()) or output.exists():
        raise ValueError("Windows output must be a new directory inside this repository build/")
    selected = {}
    for directory in plugins.resolve().iterdir():
        if directory.is_dir():
            identifier = shared.plugin_id(directory)
            if identifier in shared.REQUIRED_PLUGINS:
                if identifier in selected:
                    raise ValueError("Duplicate prepared plugin: " + identifier)
                selected[identifier] = directory
    if selected.keys() != shared.REQUIRED_PLUGINS:
        raise ValueError("Prepared Go and LSP4IJ plugins are required")
    if not (selected["dev.openidea.go"] / "bin/opengoland-engine.exe").is_file():
        raise ValueError("Build the native Windows Go engine before assembling")
    compiled_version, build_inputs = plugin_metadata(selected["dev.openidea.go"])
    if compiled_version != version:
        raise ValueError("Installer version must equal the compiled Go plugin version")
    verify_build_inputs(build_inputs)
    source_files = files(layout.sdk_root)
    output.parent.mkdir(parents=True, exist_ok=True)
    temporary = Path(tempfile.mkdtemp(prefix="windows-product-", dir=output.parent))
    app = temporary / "open Goland"
    try:
        shutil.copytree(layout.sdk_root, app)
        (app / ".openidea-platform.json").unlink()
        patches = []
        for patch in pin["platformPatches"]:
            report = json.loads(subprocess.check_output([
                sys.executable, str(ROOT / "scripts/patch-platform-unicode.py"),
                "--sdk", str(layout.sdk_root), "--target-app", str(app),
                "--pin", str(repo_pin(patch["pin"])), "--marker", patch["marker"],
                "--cache-name", patch["cacheName"],
            ], text=True, encoding="utf-8"))
            patches.append({key: value for key, value in report.items()
                            if key not in {"patchJar", "sourceSdk", "installedJar"}})
        metadata_path = app / "product-info.json"
        metadata = json.loads(metadata_path.read_text(encoding="utf-8"))
        launches = [item for item in metadata["launch"] if item["os"] == "Windows" and item["arch"] == "amd64"]
        if len(launches) != 1:
            raise ValueError("Expected one authenticated Windows amd64 launch entry")
        launch = launches[0]
        original_launcher = app / launch["launcherPath"]
        original_vm_options = app / launch["vmOptionsFilePath"]
        if not original_launcher.is_file() or not original_vm_options.is_file():
            raise ValueError("The pinned native Windows launcher is incomplete")
        own_launcher = app / "bin/opengoland64.exe"
        if own_launcher.exists():
            raise ValueError("The SDK unexpectedly contains our launcher name")
        shutil.copy2(original_launcher, own_launcher)
        if digest(own_launcher) != digest(original_launcher):
            raise ValueError("The native launcher copy changed bytes")
        inherited = [line for line in original_vm_options.read_text(encoding="utf-8").splitlines()
                     if not line.startswith(("-Didea.paths.selector=", "-Didea.vendor.name=", "-Dplugin.path="))]
        own_options = app / "bin/opengoland64.exe.vmoptions"
        own_options.write_text("\n".join(inherited) + "\n-Didea.no.platform.update=true\n", encoding="utf-8")
        launch["launcherPath"] = "bin/opengoland64.exe"
        launch["vmOptionsFilePath"] = "bin/opengoland64.exe.vmoptions"
        overrides = {
            "idea.vendor.name": "OpenGoland", "idea.paths.selector": "OpenIDEAGo2026.1",
            "intellij.platform.load.app.info.from.resources": "true",
            "plugin.path": ",".join("%IDE_HOME%/plugins/" + directory.name for directory in selected.values()),
        }
        launch["additionalJvmArguments"] = [argument for argument in launch.get("additionalJvmArguments", [])
                                             if not any(argument.startswith("-D" + key + "=") for key in overrides)]
        launch["additionalJvmArguments"].extend("-D" + key + "=" + value for key, value in overrides.items())
        metadata.update(name="open Goland", dataDirectoryName="OpenIDEAGo2026.1", productVendor="open Goland contributors")
        metadata_path.write_text(json.dumps(metadata, indent=2) + "\n", encoding="utf-8")
        properties = "\n".join([
            "idea.config.path=${user.home}/AppData/Local/OpenIDEA-Go/config",
            "idea.system.path=${user.home}/AppData/Local/OpenIDEA-Go/system",
            "idea.plugins.path=${user.home}/AppData/Local/OpenIDEA-Go/plugins",
            "idea.log.path=${user.home}/AppData/Local/OpenIDEA-Go/log",
            "idea.paths.selector=OpenIDEAGo2026.1", "",
        ])
        (app / "bin/opengoland.properties").write_text(properties, encoding="utf-8")
        defaults = app / "bin/idea.properties"
        defaults.write_text(defaults.read_text(encoding="utf-8") + "\n# open Goland paths\n" + properties, encoding="utf-8")
        for identifier, directory in selected.items():
            destination = app / "plugins" / directory.name
            if destination.exists():
                raise ValueError("Prepared plugin collides with an SDK directory: " + identifier)
            shutil.copytree(directory, destination)
        lsp = selected["com.redhat.devtools.lsp4ij"]
        resources = shared.prepare_lsp4ij_runtime_resources(app / "plugins" / lsp.name)
        tool_report = temporary / "bundled-tools-report.json"
        subprocess.run([sys.executable, str(ROOT / "scripts/bundle-go-tools.py"),
                        "--target", "windows-amd64", "--output", str(app / "plugins" / selected["dev.openidea.go"].name / "tools"),
                        "--report", str(tool_report)], check=True, stdout=subprocess.DEVNULL)
        bundled_tools = json.loads(tool_report.read_text(encoding="utf-8"))
        builder = Path(bundled_tools["builderGoExecutable"])
        engine_build = subprocess.check_output([str(builder), "version", "-m", str(app / "plugins" / selected["dev.openidea.go"].name / "bin/opengoland-engine.exe")],
                                               text=True, encoding="utf-8")
        if "go1.27.3" not in engine_build.splitlines()[0]:
            raise ValueError("The release engine must be compiled by the pinned Go 1.27.3 builder")
        branding, go_policy = shared.specialize_go_product(
            app, layout.contents, repo_pin(pin["productPolicies"]["goProduct"]), "product-info.json", launcher_script="opengoland")
        local_ide = shared.specialize_local_ide(
            app, layout.contents, go_policy, patches,
            repo_pin(pin["productPolicies"]["localIde"]), "product-info.json")
        archives = [record["path"] for record in local_ide["jars"]]
        archives += [record["upstreamJarRelativePath"] for record in patches]
        archives += [path.relative_to(app).as_posix() for identifier, directory in selected.items()
                     for path in (app / "plugins" / directory.name / "lib").glob("*.jar")
                     if identifier == "dev.openidea.go" or path.name == "lsp4ij-0.21.0.jar"]
        archive_checks = verify_product_archives(layout.sdk_root, app, archives)
        verify_build_inputs(build_inputs)
        if files(layout.sdk_root) != source_files:
            raise ValueError("The source SDK changed during assembly")
        manifest = {
            "name": "open Goland", "version": version, "platform": pin, "target": "windows-x64",
            "plugins": sorted(selected), "brandingResources": branding, "buildInputs": build_inputs,
            "pluginLoading": {"property": "plugin.path", "directories": ["plugins/" + value.name for value in selected.values()]},
            "pluginArtifactSha256": {path.relative_to(app).as_posix(): digest(path)
                                     for value in selected.values() for path in sorted((app / "plugins" / value.name).rglob("*")) if path.is_file()},
            "runtimePluginResources": [{**entry, "path": "plugins/" + lsp.name + "/" + entry["path"]} for entry in resources],
            "platformPatches": patches, "goProductPolicy": go_policy, "localIdeDistribution": local_ide,
            "archiveChecks": archive_checks, "sourceSdkUnchanged": True,
            "bundledTools": json.loads((app / "plugins" / selected["dev.openidea.go"].name / "tools/manifest.json").read_text(encoding="utf-8")),
            "engineBuildInformation": engine_build,
            "launcher": {"path": "bin/opengoland64.exe", "sha256": digest(own_launcher), "upstreamByteIdentical": True},
            "signing": {"installer": "unsigned unless separately Authenticode-signed by the release owner", "upstreamLauncherBytesPreserved": True},
        }
        (app / "openidea-product.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
        (app / "openidea-installation-files.json").write_text(json.dumps({"schemaVersion": 1, "files": files(app)}, indent=2) + "\n", encoding="utf-8")
        app.replace(output)
        return {"application": str(output), "manifest": manifest, "installationInventorySha256": digest(output / "openidea-installation-files.json")}
    finally:
        shutil.rmtree(temporary)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sdk", type=Path, required=True)
    parser.add_argument("--plugins", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--version", required=True)
    args = parser.parse_args()
    try:
        print(json.dumps(assemble(args.sdk, args.plugins, args.output, args.version), indent=2))
    except (OSError, ValueError, KeyError, subprocess.SubprocessError) as error:
        parser.exit(1, str(error) + "\n")
