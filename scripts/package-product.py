#!/usr/bin/env python3
"""Assemble our IDE from the pinned OSS platform and our prepared plugins."""

import argparse
import hashlib
import json
from pathlib import Path
from pathlib import PurePosixPath
import plistlib
import re
import shutil
import stat
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET
import zipfile
from build_inputs import verify as verify_build_inputs

ROOT = Path(__file__).resolve().parent.parent
SDK_ROOT = ROOT / ".tools/platform/idea-2026.1.4"
REQUIRED_PLUGINS = {"dev.openidea.go", "com.redhat.devtools.lsp4ij"}
LSP4IJ_VERSION = "0.21.0"
# DisassemblyTextMateBundleProvider in this pinned OSS release copies these
# resources into its plugin directory on first use. Materialize their exact
# shipped bytes before sealing the app, rather than letting startup mutate it.
LSP4IJ_RUNTIME_RESOURCES = {
    "package.json": "2a654090b48f8bcb9d1e4831a3b52d23ee9207624092b08fcb1fd73d95f92323",
    "syntaxes/disassembly.json": "2dae8bb50879164cc61c0b35fa0bfdd38523766e6150448271e0d6c65c2a79fe",
}


def plugin_id(directory: Path):
    for archive in (directory / "lib").glob("*.jar"):
        with zipfile.ZipFile(archive) as bundle:
            if "META-INF/plugin.xml" in bundle.namelist():
                xml = ET.fromstring(bundle.read("META-INF/plugin.xml"))
                identifier = xml.findtext("id")
                if identifier:
                    return identifier
    return None


def prepare_lsp4ij_runtime_resources(directory: Path) -> list:
    """Preload only the pinned provider's two resources inside our staging app."""
    if directory.is_symlink() or not directory.is_dir():
        raise ValueError("The staged LSP4IJ plugin must be a regular directory")
    directory = directory.resolve()
    candidates = []
    for archive in sorted((directory / "lib").glob("*.jar")):
        with zipfile.ZipFile(archive) as bundle:
            descriptors = [item for item in bundle.infolist() if item.filename == "META-INF/plugin.xml"]
            if not descriptors:
                continue
            if len(descriptors) != 1:
                raise ValueError(f"Duplicate plugin descriptors in {archive.name}")
            descriptor = ET.fromstring(bundle.read(descriptors[0]))
            if descriptor.findtext("id") == "com.redhat.devtools.lsp4ij":
                if descriptor.findtext("version") != LSP4IJ_VERSION:
                    raise ValueError("The LSP4IJ runtime resource pin must match the prepared plugin version")
                candidates.append(archive)
    if len(candidates) != 1:
        raise ValueError("Expected exactly one pinned LSP4IJ plugin descriptor archive")

    archive = candidates[0]
    resources = []
    with zipfile.ZipFile(archive) as bundle:
        for relative, expected_digest in LSP4IJ_RUNTIME_RESOURCES.items():
            path = PurePosixPath(relative)
            if path.is_absolute() or any(part in (".", "..") for part in path.parts) or "\\" in relative:
                raise ValueError("Invalid pinned LSP4IJ runtime resource path")
            member = "textmate/" + relative
            entries = [item for item in bundle.infolist() if item.filename == member]
            if len(entries) != 1:
                raise ValueError(f"Expected one pinned LSP4IJ resource: {member}")
            entry = entries[0]
            mode = entry.external_attr >> 16
            if entry.is_dir() or (stat.S_IFMT(mode) not in (0, stat.S_IFREG)) or entry.file_size > 65536:
                raise ValueError(f"Invalid LSP4IJ runtime resource entry: {member}")
            data = bundle.read(entry)
            digest = hashlib.sha256(data).hexdigest()
            if digest != expected_digest:
                raise ValueError(f"The pinned LSP4IJ runtime resource changed: {member}")

            target = directory / "bundles" / LSP4IJ_VERSION / path
            current = directory
            for component in target.relative_to(directory).parts:
                current = current / component
                if current.is_symlink():
                    raise ValueError(f"Symbolic link in staged LSP4IJ bundle path: {current}")
            if not target.resolve().is_relative_to(directory):
                raise ValueError("The staged LSP4IJ bundle path escapes its plugin")
            target.parent.mkdir(parents=True, exist_ok=True)
            if target.exists():
                if not target.is_file() or target.read_bytes() != data:
                    raise ValueError(f"Unexpected existing LSP4IJ runtime resource: {relative}")
            else:
                with target.open("xb") as output:
                    output.write(data)
            resources.append({
                "plugin": "com.redhat.devtools.lsp4ij", "version": LSP4IJ_VERSION,
                "archive": archive.relative_to(directory).as_posix(),
                "resource": member, "path": target.relative_to(directory).as_posix(), "sha256": digest,
            })
    return resources


def brand_resources(contents: Path) -> list:
    """Change product names only in copied open-source application resources."""
    changed = []
    for archive in (contents / "lib").glob("app.jar"):
        with zipfile.ZipFile(archive) as source:
            names = [name for name in source.namelist() if re.fullmatch(r"idea/[^/]*ApplicationInfo\.xml", name)]
            if not names:
                continue
            replacements = {}
            for name in names:
                raw = source.read(name)
                parser = ET.XMLParser(target=ET.TreeBuilder(insert_comments=True))
                xml = ET.fromstring(raw, parser=parser)
                namespace = xml.tag.partition("}")[0].removeprefix("{") if "}" in xml.tag else ""
                tag = lambda local: f"{{{namespace}}}{local}" if namespace else local
                names_node = xml.find(tag("names"))
                if names_node is None:
                    continue
                names_node.set("product", "open Goland")
                names_node.set("fullname", "open Goland")
                names_node.set("script", "opengoland")
                names_node.set("motto", "An independent IDE for Go development")
                names_node.attrib.pop("edition", None)
                if namespace:
                    ET.register_namespace("", namespace)
                replacements[name] = ET.tostring(xml, encoding="utf-8", xml_declaration=True)
                changed.append(f"{archive.name}!/{name}")
            if not replacements:
                continue
            staging = archive.with_suffix(".jar.openidea")
            with zipfile.ZipFile(staging, "w") as target:
                target.comment = source.comment
                for item in source.infolist():
                    target.writestr(item, replacements.get(item.filename, source.read(item.filename)))
        staging.replace(archive)
    if not changed:
        raise RuntimeError("No open-source application-info resources found; product branding is unverified")
    return changed


def assemble(sdk: Path, plugins: Path, output: Path):
    sdk = sdk.expanduser().resolve()
    plugins = plugins.expanduser().resolve()
    output = output.expanduser().resolve()
    marker = sdk.parent / ".openidea-platform.json"
    if not marker.is_file():
        raise ValueError("Use scripts/bootstrap-platform.sh: packaging requires a verified OSS platform marker")
    pin = json.loads(marker.read_text())
    if pin != json.loads((ROOT / "product/platform.lock.json").read_text()):
        raise ValueError("The SDK marker does not match this project's pinned open-source platform")
    if (ROOT / "build").resolve() not in output.parents:
        raise ValueError("Generated products must be placed underneath this project's build directory")
    executable = output / "Contents/MacOS/idea"
    process_rows = subprocess.check_output(["ps", "-axo", "pid=,comm="], text=True).splitlines()
    for row in process_rows:
        fields = row.strip().split(maxsplit=1)
        if len(fields) == 2 and fields[1] == str(executable):
            raise ValueError(f"Quit this generated app before replacing it (running PID {fields[0]})")
    selected = {}
    for directory in plugins.iterdir():
        if directory.is_dir():
            identifier = plugin_id(directory)
            if identifier in REQUIRED_PLUGINS:
                selected[identifier] = directory
    missing = REQUIRED_PLUGINS - selected.keys()
    if missing:
        raise ValueError(f"Missing prepared product plugins: {sorted(missing)}")
    if not (selected["dev.openidea.go"] / "bin/opengoland-engine").is_file():
        raise ValueError("The prepared plugin does not contain the Go engine")
    build_inputs = None
    for archive in (selected["dev.openidea.go"] / "lib").glob("*.jar"):
        with zipfile.ZipFile(archive) as bundle:
            if "META-INF/openidea-build-inputs.json" in bundle.namelist():
                build_inputs = json.loads(bundle.read("META-INF/openidea-build-inputs.json"))
                break
    if build_inputs is None:
        raise ValueError("The compiled plugin has no embedded build-input provenance")
    verify_build_inputs(build_inputs)

    output.parent.mkdir(parents=True, exist_ok=True)
    temporary = Path(tempfile.mkdtemp(prefix="openidea-product-", dir=output.parent))
    app = temporary / "open Goland.app"
    try:
        subprocess.run(["ditto", str(sdk), str(app)], check=True)
        contents = app / "Contents"
        info_path = contents / "Info.plist"
        info = plistlib.loads(info_path.read_bytes())
        native_launcher = info["CFBundleExecutable"]
        info["CFBundleName"] = "open Goland"
        info["CFBundleDisplayName"] = "open Goland"
        info["CFBundleIdentifier"] = "dev.opengoland.ide"
        info["CFBundleGetInfoString"] = "open Goland 0.1.0, based on the open-source IntelliJ Platform; upstream notices retained"
        info["CFBundleURLTypes"] = [{"CFBundleTypeRole": "Editor", "CFBundleURLName": "open Goland", "CFBundleURLSchemes": ["opengoland"]}]
        info_path.write_bytes(plistlib.dumps(info, sort_keys=False))

        metadata_path = contents / "Resources/product-info.json"
        metadata = json.loads(metadata_path.read_text())
        env_base = metadata.get("envVarBaseName", "IDEA")
        if not re.fullmatch("[A-Z_][A-Z_0-9]*", env_base):
            raise ValueError("Unexpected platform launcher environment prefix")
        metadata["name"] = "open Goland"
        metadata["dataDirectoryName"] = "OpenIDEAGo2026.1"
        metadata["productVendor"] = "open Goland contributors"
        launches = [item for item in metadata["launch"] if item["os"] == "macOS"]
        if len(launches) != 1:
            raise ValueError("The pinned macOS product must have one launcher")
        launch = launches[0]
        vm_options = (metadata_path.parent / launch["vmOptionsFilePath"]).resolve()
        own_vm_options = contents / "bin/opengoland.vmoptions"
        inherited_vm_options = [line for line in vm_options.read_text().splitlines() if not line.startswith(("-Didea.paths.selector=", "-Didea.vendor.name=", "-Dplugin.path="))]
        own_vm_options.write_text("\n".join(inherited_vm_options) + "\n-Didea.no.platform.update=true\n")
        vm_options.write_text(own_vm_options.read_text())
        launch["vmOptionsFilePath"] = "../bin/opengoland.vmoptions"
        overrides = {
            "idea.vendor.name": "OpenGoland",
            "idea.paths.selector": "OpenIDEAGo2026.1",
            "intellij.platform.load.app.info.from.resources": "true",
            "plugin.path": ",".join(f"$APP_PACKAGE/Contents/plugins/{directory.name}" for directory in selected.values()),
        }
        arguments = [argument for argument in launch.get("additionalJvmArguments", []) if not any(argument.startswith(f"-D{key}=") for key in overrides)]
        arguments.extend(f"-D{key}={value}" for key, value in overrides.items())
        launch["additionalJvmArguments"] = arguments
        metadata_path.write_text(json.dumps(metadata, indent=2) + "\n")

        properties = contents / "bin/opengoland.properties"
        properties.write_text("\n".join([
            "idea.config.path=${user.home}/Library/Application Support/OpenIDEA-Go/config",
            "idea.system.path=${user.home}/Library/Caches/OpenIDEA-Go",
            "idea.plugins.path=${user.home}/Library/Application Support/OpenIDEA-Go/plugins",
            "idea.log.path=${user.home}/Library/Logs/OpenIDEA-Go",
            "idea.paths.selector=OpenIDEAGo2026.1",
            "",
        ]))
        default_properties = contents / "bin/idea.properties"
        default_properties.write_text(default_properties.read_text() + "\n# open Goland application paths\n" + properties.read_text())
        launcher = contents / "bin/opengoland"
        launcher.write_text("\n".join([
            "#!/bin/sh",
            "set -eu",
            'contents="$(cd "$(dirname "$0")/.." && pwd)"',
            f'export {env_base}_VM_OPTIONS="$contents/bin/opengoland.vmoptions"',
            f'export {env_base}_PROPERTIES="$contents/bin/opengoland.properties"',
            f'exec "$contents/MacOS/{native_launcher}" "$@"',
            "",
        ]))
        launcher.chmod(0o755)
        for identifier, directory in selected.items():
            destination = contents / "plugins" / directory.name
            if destination.exists():
                raise ValueError(f"Plugin directory collides with platform bundle: {identifier}")
            subprocess.run(["ditto", str(directory), str(destination)], check=True)

        lsp4ij = selected["com.redhat.devtools.lsp4ij"]
        runtime_resources = prepare_lsp4ij_runtime_resources(contents / "plugins" / lsp4ij.name)
        branding = brand_resources(contents)
        verify_build_inputs(build_inputs)
        artifacts = {}
        for directory in selected.values():
            copied = contents / "plugins" / directory.name
            for artifact in sorted(path for path in copied.rglob("*") if path.is_file()):
                artifacts[artifact.relative_to(contents).as_posix()] = hashlib.sha256(artifact.read_bytes()).hexdigest()
        manifest = {
            "name": "open Goland", "version": "0.1.0-SNAPSHOT", "platform": pin,
            "plugins": sorted(selected), "brandingResources": branding,
            "pluginLoading": {"property": "plugin.path", "directories": [f"Contents/plugins/{directory.name}" for directory in selected.values()]},
            "buildInputs": build_inputs,
            "pluginArtifactSha256": artifacts,
            "runtimePluginResources": [{**entry, "path": f"plugins/{lsp4ij.name}/{entry['path']}"} for entry in runtime_resources],
        }
        (contents / "Resources/openidea-product.json").write_text(json.dumps(manifest, indent=2) + "\n")
        subprocess.run(["codesign", "--force", "--sign", "-", "--preserve-metadata=entitlements", str(app)], check=True)
        subprocess.run(["codesign", "--verify", "--deep", "--strict", str(app)], check=True)
        if output.exists():
            if not (output / "Contents/Resources/openidea-product.json").is_file():
                raise ValueError("Existing output is not a generated open Goland product")
            shutil.rmtree(output)
        app.replace(output)
        print(json.dumps({"application": str(output), "manifest": manifest}, indent=2))
    finally:
        shutil.rmtree(temporary)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sdk", type=Path, default=SDK_ROOT / "IntelliJ IDEA.app")
    parser.add_argument("--plugins", type=Path, required=True)
    parser.add_argument("--output", type=Path, default=ROOT / "build/product/open Goland.app")
    args = parser.parse_args()
    try:
        assemble(args.sdk, args.plugins, args.output)
    except (OSError, ValueError, RuntimeError, subprocess.CalledProcessError) as error:
        print(str(error), file=sys.stderr)
        sys.exit(1)
