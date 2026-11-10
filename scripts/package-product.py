#!/usr/bin/env python3
"""Assemble our IDE from the pinned OSS platform and our prepared plugins."""

import argparse
import hashlib
import json
import os
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
from product_metadata import filter_plugin_classpath
from local_ide_metadata import filter_local_ide_metadata
from platform_layout import detect_platform_layout
from product_archives import rewritten_zip_comment, verify_product_archives

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


def go_product_policy(contents: Path, pin_path: Path | None = None,
                      product_info_relative: str = "Resources/product-info.json") -> dict:
    """Authenticate the Java-specific product customization before excluding it."""
    if not contents.resolve().is_relative_to((ROOT / "build").resolve()):
        raise ValueError("Go product policy applies only to an owned build copy")
    policy_path = pin_path or ROOT / "product/patches/platform-go-product-2026.1.4.json"
    policy = json.loads(policy_path.read_text(encoding="utf-8"))
    version = json.loads((contents / product_info_relative).read_text(encoding="utf-8"))["version"]
    if version != policy["platformVersion"]:
        raise ValueError("The Go product policy requires its pinned platform version")
    excluded = policy["bundledPluginExclusions"]
    if [item["id"] for item in excluded] != ["com.intellij.java.ide"]:
        raise ValueError("Unexpected Go product plugin exclusion")
    for item in excluded:
        if item["relativeDirectory"] != "plugins/java-ide-customization":
            raise ValueError("Unexpected Java IDE customization directory")
        directory = contents / item["relativeDirectory"]
        if (not directory.resolve().is_relative_to(contents.resolve()) or directory.is_symlink()
                or not directory.is_dir() or any(p.is_symlink() for p in directory.rglob("*"))):
            raise ValueError("The copied customization plugin must contain regular pinned files")
        actual = {p.relative_to(contents).as_posix(): p for p in directory.rglob("*") if p.is_file()}
        expected = {entry["path"]: entry for entry in item["files"]}
        if actual.keys() != expected.keys():
            raise ValueError("The copied customization plugin has an unexpected file set")
        for name, path in actual.items():
            data = path.read_bytes()
            if len(data) != expected[name]["bytes"] or hashlib.sha256(data).hexdigest() != expected[name]["sha256"]:
                raise ValueError("Pinned Java IDE customization file changed: " + name)
        with zipfile.ZipFile(directory / item["descriptor"]["jar"]) as bundle:
            data = bundle.read(item["descriptor"]["entry"])
        if (hashlib.sha256(data).hexdigest() != item["descriptor"]["sha256"] or
                ET.fromstring(data).findtext("id") != item["id"]):
            raise ValueError("Java IDE customization descriptor identity changed")
    for name in policy["retainedBundledDirectories"]:
        if not (contents / name).is_dir():
            raise ValueError("A retained language plugin is missing: " + name)
    app_info = policy["applicationInfo"]
    archive = contents / app_info["jar"]
    if archive.is_symlink() or not archive.resolve().is_relative_to(contents.resolve()):
        raise ValueError("Application-info archive must remain in the owned copy")
    with zipfile.ZipFile(archive) as bundle:
        data = bundle.read(app_info["entry"])
    if hashlib.sha256(data).hexdigest() != app_info["upstreamSha256"]:
        raise ValueError("Pinned upstream application-info resource changed")
    return policy


def brand_resources(contents: Path, policy: dict, launcher_script: str = "opengoland") -> list:
    """Brand copied resources and retain only this Go product's essential IDs."""
    if not contents.resolve().is_relative_to((ROOT / "build").resolve()):
        raise ValueError("Product resources must belong to an owned build copy")
    if not re.fullmatch(r"[A-Za-z][A-Za-z0-9_-]*", launcher_script):
        raise ValueError("The internal launcher script must be a plain executable basename")
    changed = []
    for archive in (contents / "lib").glob("app.jar"):
        if archive.is_symlink() or not archive.resolve().is_relative_to(contents.resolve()):
            raise ValueError("Branding archive must remain in the owned copy")
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
                names_node.set("script", launcher_script)
                names_node.set("motto", "An independent IDE for Go development")
                names_node.attrib.pop("edition", None)
                company = xml.find(tag("company"))
                if company is None:
                    company = ET.SubElement(xml, tag("company"))
                company.set("name", "open Goland contributors")
                company.set("shortName", "open Goland")
                company.set("url", "https://github.com/shubolab/opengoland")
                if name == policy["applicationInfo"]["entry"]:
                    before = [item.text.strip() for item in xml.findall(tag("essential-plugin"))]
                    if before != policy["applicationInfo"]["essentialBefore"]:
                        raise ValueError("Unexpected upstream essential-plugin policy")
                    excluded = {item["id"] for item in policy["bundledPluginExclusions"]}
                    for item in list(xml.findall(tag("essential-plugin"))):
                        if item.text.strip() in excluded:
                            xml.remove(item)
                    after = [item.text.strip() for item in xml.findall(tag("essential-plugin"))]
                    if after != policy["applicationInfo"]["essentialAfter"]:
                        raise ValueError("Go product essential-plugin policy mismatch")
                if namespace:
                    ET.register_namespace("", namespace)
                replacements[name] = ET.tostring(xml, encoding="utf-8", xml_declaration=True)
                changed.append(f"{archive.name}!/{name}")
            if not replacements:
                continue
            staging = archive.with_suffix(".jar.openidea")
            with zipfile.ZipFile(staging, "w") as target:
                target.comment = rewritten_zip_comment(source)
                for item in source.infolist():
                    target.writestr(item, replacements.get(item.filename, source.read(item.filename)))
        staging.replace(archive)
    if not changed:
        raise RuntimeError("No open-source application-info resources found; product branding is unverified")
    return changed


def specialize_go_metadata(contents: Path, sdk_contents: Path, policy: dict,
                           product_info_relative: str = "Resources/product-info.json") -> dict:
    """Keep the copied distribution's activation index and inventories consistent."""
    def owned_file(relative):
        path = contents / relative
        if path.is_symlink() or not path.resolve().is_relative_to(contents.resolve()) or not path.is_file():
            raise ValueError("Product metadata must be a regular owned file: " + relative)
        return path

    cache = owned_file(policy["pluginClasspath"]["path"])
    filtered_cache, cache_record = filter_plugin_classpath(cache.read_bytes(), policy["pluginClasspath"])
    repository = policy["runtimeModuleRepository"]
    compact = owned_file(repository["compact"])
    xml_jar = owned_file(repository["jar"])
    for path, key in [(compact, "upstreamCompactSha256"), (xml_jar, "upstreamJarSha256")]:
        if hashlib.sha256(path.read_bytes()).hexdigest() != repository[key]:
            raise ValueError("Pinned runtime-module repository changed: " + path.name)
    helper = ROOT / repository["helper"]
    layout = detect_platform_layout(sdk_contents)
    java = layout.java
    classpath = os.pathsep.join(str(layout.lib / name) for name in ("platform-loader.jar", "annotations.jar"))
    output_dat = compact.with_name("module-descriptors.opengoland.dat")
    output_jar = xml_jar.with_name("module-descriptors.opengoland.jar")
    if output_dat.exists() or output_jar.exists():
        raise ValueError("Unexpected existing module-repository staging output")

    def run_helper(operation):
        command = [str(java), "--class-path", classpath, str(helper), operation,
                   "--input-dat", str(compact), "--input-jar", str(xml_jar),
                   "--output-dat", str(output_dat), "--output-jar", str(output_jar),
                   "--owned-root", str(contents)]
        result = subprocess.run(command, cwd=ROOT, check=True, text=True, capture_output=True, timeout=60)
        report = json.loads(result.stdout)
        report.pop("outputDat", None)
        report.pop("outputJar", None)
        return report

    filtered = run_helper("filter")
    verified = run_helper("verify")
    for path, kind in [(output_dat, "Compact"), (output_jar, "Jar")]:
        if (path.stat().st_size != repository["output" + kind + "Bytes"] or
                hashlib.sha256(path.read_bytes()).hexdigest() != repository["output" + kind + "Sha256"]):
            raise ValueError("Filtered runtime repository does not match its pinned output: " + kind)

    metadata_path = owned_file(product_info_relative)
    metadata = json.loads(metadata_path.read_text(encoding="utf-8"))
    inventory = policy["productInfoInventory"]
    before = {key: metadata[key] for key in ("bundledPlugins", "modules", "layout")}
    ids = set(inventory["bundledPluginIds"]) | set(inventory["moduleIds"])
    removed_layout = [entry for entry in before["layout"] if entry["name"] in ids]
    if removed_layout != inventory["excludedLayout"]:
        raise ValueError("Unexpected product-info customization layout")
    after = {
        "bundledPlugins": [name for name in before["bundledPlugins"] if name not in ids],
        "modules": [name for name in before["modules"] if name not in ids],
        "layout": [entry for entry in before["layout"] if entry["name"] not in ids],
    }
    for key in before:
        if (len(before[key]) != inventory[key + "BeforeCount"] or len(after[key]) != inventory[key + "AfterCount"]):
            raise ValueError("Unexpected product-info inventory size: " + key)
    if not set(inventory["preservedBundledIds"]).issubset(after["bundledPlugins"]):
        raise ValueError("An independent retained plugin was removed")
    metadata.update(after)
    cache.write_bytes(filtered_cache)
    output_dat.replace(compact)
    output_jar.replace(xml_jar)
    metadata_path.write_text(json.dumps(metadata, indent=2) + "\n", encoding="utf-8")
    return {"pluginClasspath": cache_record, "runtimeModuleRepository": {"filter": filtered, "verify": verified},
            "artifactSha256": {path.relative_to(contents).as_posix(): hashlib.sha256(path.read_bytes()).hexdigest()
                               for path in (cache, compact, xml_jar)},
            "productInfoInventory": {"removedLayout": removed_layout,
                                     "beforeCounts": {key: len(value) for key, value in before.items()},
                                     "afterCounts": {key: len(value) for key, value in after.items()},
                                     "preservedBundledIds": inventory["preservedBundledIds"]}}


def specialize_go_product(contents: Path, sdk_contents: Path, pin_path: Path | None = None,
                          product_info_relative: str = "Resources/product-info.json",
                          launcher_script: str = "opengoland") -> tuple:
    policy = go_product_policy(contents, pin_path, product_info_relative)
    metadata = specialize_go_metadata(contents, sdk_contents, policy, product_info_relative)
    branding = brand_resources(contents, policy, launcher_script)
    for item in policy["bundledPluginExclusions"]:
        shutil.rmtree(contents / item["relativeDirectory"])
    with zipfile.ZipFile(contents / policy["applicationInfo"]["jar"]) as bundle:
        branded_app_info = bundle.read(policy["applicationInfo"]["entry"])
    policy_path = pin_path or ROOT / "product/patches/platform-go-product-2026.1.4.json"
    record = {
        "pin": policy_path.relative_to(ROOT).as_posix(),
        "pinSha256": hashlib.sha256(policy_path.read_bytes()).hexdigest(),
        "bundledPluginExclusions": policy["bundledPluginExclusions"],
        "applicationInfo": {**policy["applicationInfo"],
                            "brandedSha256": hashlib.sha256(branded_app_info).hexdigest(),
                            "launcherScript": launcher_script,
                            "publisher": {"name": "open Goland contributors", "shortName": "open Goland",
                                          "url": "https://github.com/shubolab/opengoland"}},
        "retainedBundledDirectories": policy["retainedBundledDirectories"],
        "metadata": metadata,
    }
    return branding, record


def specialize_local_ide(contents: Path, sdk_contents: Path, product_policy: dict, platform_patches: list,
                         pin_path: Path | None = None,
                         product_info_relative: str = "Resources/product-info.json") -> dict:
    """Remove the pinned IDE-account/commercial registrations from our copy."""
    if not contents.resolve().is_relative_to((ROOT / "build").resolve()):
        raise ValueError("Local IDE changes apply only to an owned build copy")
    pin_path = pin_path or ROOT / "product/patches/platform-local-ide-2026.1.4.json"
    pin = json.loads(pin_path.read_text(encoding="utf-8"))

    def owned(relative):
        pure = PurePosixPath(relative)
        if pure.is_absolute() or "\\" in relative or any(part in ("", ".", "..") for part in pure.parts):
            raise ValueError("Invalid local IDE resource path")
        path = contents
        for part in pure.parts:
            path = path / part
            if path.is_symlink():
                raise ValueError("A local IDE resource must not be a symbolic link")
        if not path.is_file() or not path.resolve().is_relative_to(contents.resolve()):
            raise ValueError("Local IDE resource must be an owned regular file: " + relative)
        return path

    def authenticate(path, expected):
        data = path.read_bytes()
        if len(data) != expected["bytes"] or hashlib.sha256(data).hexdigest() != expected["sha256"]:
            raise ValueError("Pinned local IDE input changed: " + expected["path"])

    metadata_path = owned(product_info_relative)
    metadata = json.loads(metadata_path.read_text(encoding="utf-8"))
    if metadata["version"] != pin["platformVersion"]:
        raise ValueError("Local IDE cleanup requires the pinned platform version")
    patches_by_path = {item["upstreamJarRelativePath"].removeprefix("Contents/"): item for item in platform_patches}
    rules_by_jar = {}
    for rule in pin["xmlRules"]:
        rules_by_jar.setdefault(rule["jar"], []).append(rule)
    if set(rules_by_jar) != {item["path"] for item in pin["jarInputs"]}:
        raise ValueError("Local IDE XML rules and authenticated JARs disagree")
    for item in pin["jarInputs"]:
        source = sdk_contents / item["path"]
        authenticate(source, item)
        target = owned(item["path"])
        patch = patches_by_path.get(item["path"])
        if patch is not None:
            if hashlib.sha256(target.read_bytes()).hexdigest() != patch["patchedJarSha256"]:
                raise ValueError("A platform source rebuild changed before resource cleanup")
        elif item["path"] == product_policy["applicationInfo"]["jar"]:
            app_info = product_policy["applicationInfo"]
            with zipfile.ZipFile(source) as before, zipfile.ZipFile(target) as after:
                if before.namelist() != after.namelist():
                    raise ValueError("Unexpected entries in branded application-info JAR")
                for name in before.namelist():
                    data = after.read(name)
                    if name == app_info["entry"]:
                        if hashlib.sha256(data).hexdigest() != app_info["brandedSha256"]:
                            raise ValueError("The branded application-info resource changed")
                    elif data != before.read(name):
                        raise ValueError("Unexpected change before local IDE cleanup: " + name)
        else:
            authenticate(target, item)
    for item in pin["removedFiles"] + pin["retainedFiles"]:
        authenticate(owned(item["path"]), item)
    grazie_files = {path.relative_to(contents).as_posix() for path in (contents / "plugins/grazie").rglob("*") if path.is_file()}
    expected_grazie = {item["path"] for item in pin["removedFiles"] + pin["retainedFiles"] if item["path"].startswith("plugins/grazie/")}
    if grazie_files != expected_grazie or any(path.is_symlink() for path in (contents / "plugins/grazie").rglob("*")):
        raise ValueError("Unexpected files in the copied Grazie plugin")

    cache = owned(pin["classpathRule"]["path"])
    filtered_cache, cache_record = filter_local_ide_metadata(cache.read_bytes(), pin["classpathRule"])
    inventory = pin["productInfoInventory"]
    ids = set(inventory["excludedIds"])
    before_inventory = {key: metadata[key] for key in ("bundledPlugins", "modules", "layout")}
    removed_layout = [entry for entry in metadata["layout"] if entry["name"] in ids]
    if removed_layout != inventory["removedLayout"]:
        raise ValueError("Local IDE product-info removals changed")
    after_inventory = {key: [value for value in values if (value["name"] if isinstance(value, dict) else value) not in ids]
                       for key, values in before_inventory.items()}
    for key in before_inventory:
        if (len(before_inventory[key]) != inventory["beforeCounts"][key] or
                len(after_inventory[key]) != inventory["afterCounts"][key]):
            raise ValueError("Local IDE product-info count changed: " + key)
    if not set(inventory["preservedBundledIds"]).issubset(after_inventory["bundledPlugins"]):
        raise ValueError("A retained development plugin is missing")

    jar_records = []
    for relative, rules in rules_by_jar.items():
        target = owned(relative)
        before_digest = hashlib.sha256(target.read_bytes()).hexdigest()
        replacements, records = {}, []
        with zipfile.ZipFile(target) as source:
            if len(source.namelist()) != len(set(source.namelist())):
                raise ValueError("Duplicate JAR entries before local IDE cleanup")
            for rule in rules:
                data, record = filter_local_ide_metadata(source.read(rule["entry"]), rule)
                if rule["entry"] in replacements:
                    raise ValueError("Repeated local IDE resource transformation")
                replacements[rule["entry"]] = data
                records.append(record)
            staging = target.with_suffix(".jar.local-ide")
            if staging.exists():
                raise ValueError("Unexpected existing local IDE staging JAR")
            with zipfile.ZipFile(staging, "w") as output:
                output.comment = rewritten_zip_comment(source)
                for entry in source.infolist():
                    output.writestr(entry, replacements.get(entry.filename, source.read(entry.filename)))
        staging.replace(target)
        after_digest = hashlib.sha256(target.read_bytes()).hexdigest()
        jar_records.append({"path": relative, "beforeSha256": before_digest, "afterSha256": after_digest,
                            "resources": records, "otherEntryContentsPreserved": True})
        if relative in patches_by_path:
            patch = patches_by_path[relative]
            patch["rebuiltJarSha256"] = patch["patchedJarSha256"]
            patch["distributionResourceChanges"] = records
            patch["patchedJarSha256"] = after_digest
    for item in pin["removedFiles"]:
        owned(item["path"]).unlink()
    for item in pin["retainedFiles"]:
        authenticate(owned(item["path"]), item)
    cache.write_bytes(filtered_cache)
    metadata.update(after_inventory)
    metadata_path.write_text(json.dumps(metadata, indent=2) + "\n", encoding="utf-8")
    # This manifest map describes final on-disk bytes; the two index-stage
    # records independently retain their intermediate authenticated identities.
    product_policy["metadata"]["artifactSha256"][cache.relative_to(contents).as_posix()] = hashlib.sha256(filtered_cache).hexdigest()
    return {"pin": pin_path.relative_to(ROOT).as_posix(), "pinSha256": hashlib.sha256(pin_path.read_bytes()).hexdigest(),
            "pluginClasspath": cache_record, "jars": jar_records, "removedFiles": pin["removedFiles"],
            "retainedFiles": pin["retainedFiles"], "productInfoInventory": inventory,
            "pluginManagement": "upstream",
            "scope": "Bundled IDE-account, settings-sync, Grazie license/cloud and paid-promotion registrations; native compatible-plugin Marketplace, updates and local-disk installation retained. Development-service credentials remain available."}


def assemble(sdk: Path, plugins: Path, output: Path, version: str | None = None):
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
    compiled_version = None
    for archive in (selected["dev.openidea.go"] / "lib").glob("*.jar"):
        with zipfile.ZipFile(archive) as bundle:
            if "META-INF/plugin.xml" in bundle.namelist():
                descriptor = ET.fromstring(bundle.read("META-INF/plugin.xml"))
                if descriptor.findtext("id") == "dev.openidea.go":
                    compiled_version = descriptor.findtext("version")
            if "META-INF/openidea-build-inputs.json" in bundle.namelist():
                build_inputs = json.loads(bundle.read("META-INF/openidea-build-inputs.json"))
                break
    if build_inputs is None:
        raise ValueError("The compiled plugin has no embedded build-input provenance")
    if not compiled_version or (version is not None and version != compiled_version):
        raise ValueError("The requested product version must match the compiled Go plugin")
    version = compiled_version
    verify_build_inputs(build_inputs)

    output.parent.mkdir(parents=True, exist_ok=True)
    temporary = Path(tempfile.mkdtemp(prefix="openidea-product-", dir=output.parent))
    app = temporary / "open Goland.app"
    try:
        subprocess.run(["ditto", str(sdk), str(app)], check=True)
        contents = app / "Contents"
        unicode_patch = json.loads(subprocess.check_output([
            sys.executable, str(ROOT / "scripts/patch-platform-unicode.py"),
            "--sdk", str(sdk), "--target-app", str(app),
        ], text=True))
        # Keep the distributable record independent of local SDK/cache/staging paths.
        unicode_patch = {key: value for key, value in unicode_patch.items()
                         if key not in {"patchJar", "sourceSdk", "installedJar"}}
        psi_listener_patch = json.loads(subprocess.check_output([
            sys.executable, str(ROOT / "scripts/patch-platform-unicode.py"),
            "--sdk", str(sdk), "--target-app", str(app),
            "--pin", str(ROOT / "product/patches/platform-psi-listener-2026.1.4.json"),
            "--marker", "META-INF/open-goland-platform-psi-listener.json",
            "--cache-name", "platform-psi-listener",
        ], text=True))
        psi_listener_patch = {key: value for key, value in psi_listener_patch.items()
                              if key not in {"patchJar", "sourceSdk", "installedJar"}}
        info_path = contents / "Info.plist"
        info = plistlib.loads(info_path.read_bytes())
        native_launcher = info["CFBundleExecutable"]
        info["CFBundleName"] = "open Goland"
        info["CFBundleDisplayName"] = "open Goland"
        info["CFBundleIdentifier"] = "dev.opengoland.ide"
        info["CFBundleGetInfoString"] = f"open Goland {version}, based on the open-source IntelliJ Platform; upstream notices retained"
        info["CFBundleShortVersionString"] = version.split("-", 1)[0]
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
        own_vm_options.write_text("\n".join(inherited_vm_options) +
                                  "\n-Didea.no.platform.update=true\n")
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

        tools_report_path = temporary / "bundled-tools-report.json"
        subprocess.run([
            sys.executable, str(ROOT / "scripts/bundle-go-tools.py"), "--target", "darwin-arm64",
            "--output", str(contents / "plugins" / selected["dev.openidea.go"].name / "tools"),
            "--report", str(tools_report_path),
        ], check=True, stdout=subprocess.DEVNULL)
        tools_report = json.loads(tools_report_path.read_text())
        engine_relative = f"plugins/{selected['dev.openidea.go'].name}/bin/opengoland-engine"
        engine_info = subprocess.check_output([
            tools_report["builderGoExecutable"], "version", "-m", str(contents / engine_relative),
        ], text=True)
        expected_builder = json.loads((ROOT / "product/go-tools.lock.json").read_text())["builderVersion"]
        if not engine_info.splitlines()[0].endswith(expected_builder):
            raise ValueError("The engine must be compiled with the authenticated Go builder: " + expected_builder)
        engine_info = engine_relative + engine_info[engine_info.index(":"):]

        lsp4ij = selected["com.redhat.devtools.lsp4ij"]
        runtime_resources = prepare_lsp4ij_runtime_resources(contents / "plugins" / lsp4ij.name)
        if not (contents / "MacOS" / native_launcher).is_file():
            raise ValueError("The target Mac native launcher is missing")
        branding, product_policy = specialize_go_product(contents, sdk / "Contents", launcher_script=native_launcher)
        platform_patches = [unicode_patch, psi_listener_patch]
        local_ide = specialize_local_ide(contents, sdk / "Contents", product_policy, platform_patches)
        archive_paths = [record["path"] for record in local_ide["jars"]]
        archive_paths += [patch["upstreamJarRelativePath"].removeprefix("Contents/") for patch in platform_patches]
        archive_paths += [path for path in product_policy["metadata"]["artifactSha256"] if path.endswith(".jar")]
        archive_paths += [path.relative_to(contents).as_posix()
                          for identifier, directory in selected.items()
                          for path in (contents / "plugins" / directory.name / "lib").glob("*.jar")
                          if identifier == "dev.openidea.go" or path.name == "lsp4ij-0.21.0.jar"]
        archive_checks = verify_product_archives(sdk, contents, archive_paths)
        verify_build_inputs(build_inputs)
        artifacts = {}
        for directory in selected.values():
            copied = contents / "plugins" / directory.name
            for artifact in sorted(path for path in copied.rglob("*") if path.is_file()):
                artifacts[artifact.relative_to(contents).as_posix()] = hashlib.sha256(artifact.read_bytes()).hexdigest()
        manifest = {
            "name": "open Goland", "version": version, "platform": pin,
            "plugins": sorted(selected), "brandingResources": branding,
            "pluginLoading": {"property": "plugin.path", "directories": [f"Contents/plugins/{directory.name}" for directory in selected.values()]},
            "buildInputs": build_inputs,
            "pluginArtifactSha256": artifacts,
            "runtimePluginResources": [{**entry, "path": f"plugins/{lsp4ij.name}/{entry['path']}"} for entry in runtime_resources],
            "platformPatches": platform_patches,
            "goProductPolicy": product_policy,
            "localIdeDistribution": local_ide,
            "archiveChecks": archive_checks,
            "bundledTools": json.loads((contents / "plugins" / selected["dev.openidea.go"].name / "tools/manifest.json").read_text()),
            "engineBuildInfo": engine_info,
        }
        (contents / "Resources/openidea-product.json").write_text(json.dumps(manifest, indent=2) + "\n")
        subprocess.run(["codesign", "--force", "--sign", "-", "--preserve-metadata=entitlements", str(app)], check=True)
        subprocess.run(["codesign", "--verify", "--deep", "--strict", str(app)], check=True)
        for relative, expected in artifacts.items():
            if hashlib.sha256((contents / relative).read_bytes()).hexdigest() != expected:
                raise ValueError("Signing changed a sealed plugin or bundled-tool file: " + relative)
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
    parser.add_argument("--version", help="Must match the compiled Go plugin version")
    args = parser.parse_args()
    try:
        assemble(args.sdk, args.plugins, args.output, args.version)
    except (OSError, ValueError, RuntimeError, subprocess.CalledProcessError) as error:
        print(str(error), file=sys.stderr)
        sys.exit(1)
