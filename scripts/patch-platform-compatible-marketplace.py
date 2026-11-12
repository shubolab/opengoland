#!/usr/bin/env python3
"""Apply source-correlated Marketplace and service-override repairs.

This does not recompile Kotlin. The complete original JAR/class, the exact
one-byte boolean output and the exact service-descriptor edit are pinned. All
other JAR entry contents stay byte-identical. The source SDK is read-only; an
installation target must be an owned build copy.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import tempfile
import urllib.request
import zipfile

from platform_layout import cache_lock, detect_platform_layout
from product_archives import rewritten_zip_comment
from source_patch import apply_source_patch, SOURCE_PATCH_RUNNER_SHA256

ROOT = Path(__file__).resolve().parent.parent
PIN = ROOT / "product/patches/platform-compatible-marketplace-2026.1.4.json"
MARKER = "META-INF/open-goland-compatible-marketplace.json"
CLASS_ENTRY = "com/intellij/ide/plugins/newui/PluginModelAsyncOperationsExecutor$performMarketplaceSearch$2.class"
SOURCE_PATH = "platform/platform-impl/src/com/intellij/ide/plugins/newui/PluginModelAsyncOperationsExecutor.kt"
MODE = "source-correlated-jvm-boolean-delta"
SERVICE_XML_ENTRY = "META-INF/PlatformExtensions.xml"
SERVICE_XML_BEFORE = (
    b'<applicationService serviceInterface="com.intellij.ide.plugins.PluginManagementPolicy"\n'
    b'                        serviceImplementation="com.intellij.ide.plugins.DefaultPluginManagementPolicy"/>'
)
SERVICE_XML_AFTER = (
    b'<applicationService open="true" serviceInterface="com.intellij.ide.plugins.PluginManagementPolicy"\n'
    b'                        serviceImplementation="com.intellij.ide.plugins.DefaultPluginManagementPolicy"/>'
)


def sha(data):
    return hashlib.sha256(data).hexdigest()


def atomic_bytes(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(dir=path.parent, prefix=path.name + ".", delete=False) as stream:
            temporary = Path(stream.name)
            stream.write(data)
        temporary.chmod(path.stat().st_mode & 0o777 if path.exists() else 0o644)
        os.replace(temporary, path)
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


def protect_sdk(path, sdk):
    if path.resolve().is_relative_to(sdk.resolve()):
        raise ValueError("Compatible Marketplace repair must not modify its source SDK")


def delta_class(data, pin):
    delta = pin["classDelta"]
    offset = delta["fileOffset"]
    if (delta["path"] != CLASS_ENTRY or not isinstance(offset, int) or isinstance(offset, bool)
            or offset < 0 or offset >= len(data) or delta["beforeHex"] != "04" or delta["afterHex"] != "03"
            or len(data) != delta["bytes"] or sha(data) != delta["upstreamSha256"] or data[offset] != 4):
        raise ValueError("Pinned Marketplace class or exact boolean opcode does not match")
    result = data[:offset] + b"\x03" + data[offset + 1:]
    if sha(result) != delta["patchedSha256"]:
        raise ValueError("Marketplace boolean output does not match its pinned complete class")
    return result


def delta_service_xml(data, pin):
    delta = pin["xmlDelta"]
    if (delta["path"] != SERVICE_XML_ENTRY or len(data) != delta["bytes"]
            or sha(data) != delta["upstreamSha256"] or data.count(SERVICE_XML_BEFORE) != 1):
        raise ValueError("Pinned PluginManagementPolicy service descriptor does not match")
    result = data.replace(SERVICE_XML_BEFORE, SERVICE_XML_AFTER, 1)
    if sha(result) != delta["patchedSha256"]:
        raise ValueError("PluginManagementPolicy service override output does not match its pin")
    return result


def verify_jar(candidate, original, expected):
    try:
        with zipfile.ZipFile(original) as before, zipfile.ZipFile(candidate) as after:
            names = after.namelist()
            if len(names) != len(set(names)) or set(names) != set(before.namelist()) | {MARKER}:
                return None
            marker = json.loads(after.read(MARKER))
            if marker != expected:
                return None
            if sha(after.read(CLASS_ENTRY)) != expected["classSha256"][CLASS_ENTRY]:
                return None
            if sha(after.read(SERVICE_XML_ENTRY)) != expected["xmlSha256"]:
                return None
            if any(before.read(name) != after.read(name)
                   for name in before.namelist() if name not in {CLASS_ENTRY, SERVICE_XML_ENTRY}):
                return None
            return marker
    except (OSError, ValueError, KeyError, zipfile.BadZipFile):
        return None


def verified_source(pin, pin_path, cache, sdk):
    source = pin["source"]
    patch = pin_path.parent / pin["patchFile"]
    if source["path"] != SOURCE_PATH or sha(patch.read_bytes()) != pin["patchSha256"]:
        raise ValueError("Marketplace source correlation pin does not match")
    expected_url = "https://raw.githubusercontent.com/JetBrains/intellij-community/" + pin["upstream"]["commit"] + "/" + SOURCE_PATH
    if source["url"] != expected_url:
        raise ValueError("Marketplace source must use its immutable public commit URL")
    saved = cache / "source" / (source["upstreamSha256"] + ".kt")
    protect_sdk(saved, sdk)
    if not saved.is_file() or sha(saved.read_bytes()) != source["upstreamSha256"]:
        with urllib.request.urlopen(expected_url, timeout=30) as response:
            data = response.read()
        if sha(data) != source["upstreamSha256"]:
            raise ValueError("Pinned public Marketplace source changed")
        atomic_bytes(saved, data)
    before = saved.read_bytes()
    call = b"pluginManager.executeMarketplaceQuery(query, 10000, true)"
    if before.count(call) != 1:
        raise ValueError("Marketplace source must contain exactly one correlated call")
    with tempfile.TemporaryDirectory(prefix="source-", dir=cache) as directory:
        target = Path(directory) / SOURCE_PATH
        target.parent.mkdir(parents=True)
        target.write_bytes(before)
        apply_source_patch(patch, Path(directory), pin["patchSha256"])
        result = target.read_bytes()
    if (result != before.replace(call, call.replace(b"true", b"false"))
            or sha(result) != source["patchedSha256"]):
        raise ValueError("Marketplace source delta must only disable alternate-host upgrade search")


def build(args):
    pin_path = args.pin.resolve()
    pin_bytes = pin_path.read_bytes()
    pin = json.loads(pin_bytes.decode("utf-8"))
    if pin["schemaVersion"] != 1 or pin["productionMode"] != MODE or args.marker != MARKER:
        raise ValueError("Unexpected compatible Marketplace repair profile")
    layout = detect_platform_layout(args.sdk)
    sdk = layout.sdk_root
    info = json.loads(layout.product_info.read_text(encoding="utf-8"))
    if info["version"] != pin["platformVersion"] or info["buildNumber"] != pin["platformBuild"]:
        raise ValueError("Compatible Marketplace repair requires the pinned platform build")
    original = sdk / pin["upstreamJar"]["path"]
    if original.resolve() != (layout.lib / "intellij.platform.ide.impl.jar").resolve():
        raise ValueError("Unexpected compatible Marketplace source JAR")
    if sha(original.read_bytes()) != pin["upstreamJar"]["sha256"]:
        raise ValueError("Pinned upstream Marketplace JAR digest mismatch")
    cache = ROOT / ".tools" / args.cache_name
    for path in (cache, args.jar_output, args.metadata_output):
        if path is not None:
            protect_sdk(path, sdk)
    cache.mkdir(parents=True, exist_ok=True)
    verified_source(pin, pin_path, cache, sdk)
    with zipfile.ZipFile(original) as source_jar:
        if len(source_jar.namelist()) != len(set(source_jar.namelist())) or MARKER in source_jar.namelist():
            raise ValueError("Ambiguous original Marketplace JAR")
        replacement = delta_class(source_jar.read(CLASS_ENTRY), pin)
        service_xml = delta_service_xml(source_jar.read(SERVICE_XML_ENTRY), pin)
    metadata = {
        "platformVersion": pin["platformVersion"], "upstreamCommit": pin["upstream"]["commit"],
        "upstreamJarSha256": pin["upstreamJar"]["sha256"], "patchSha256": pin["patchSha256"],
        "productionMode": MODE, "recompiled": False, "source": pin["source"], "classDelta": pin["classDelta"],
        "classSha256": {CLASS_ENTRY: sha(replacement)}, "xmlDelta": pin["xmlDelta"],
        "xmlSha256": sha(service_xml), "pinSha256": sha(pin_bytes),
        "producerSha256": sha(Path(__file__).read_bytes()), "sourcePatchRunnerSha256": SOURCE_PATCH_RUNNER_SHA256,
        "archiveWriterSha256": sha((ROOT / "scripts/product_archives.py").read_bytes()),
        "layoutHelperSha256": sha((ROOT / "scripts/platform_layout.py").read_bytes()),
    }
    identity = sha(json.dumps({**metadata, "sdkPlatform": layout.platform}, sort_keys=True).encode("utf-8"))
    work = cache / identity
    work.mkdir(parents=True, exist_ok=True)
    artifact = work / original.name
    if verify_jar(artifact, original, metadata) is None:
        with tempfile.TemporaryDirectory(prefix="binary-delta-", dir=work) as temporary:
            candidate = Path(temporary) / original.name
            with zipfile.ZipFile(original) as before, zipfile.ZipFile(candidate, "w", zipfile.ZIP_DEFLATED) as after:
                for entry in before.infolist():
                    payload = before.read(entry)
                    if entry.filename == CLASS_ENTRY:
                        payload = replacement
                    elif entry.filename == SERVICE_XML_ENTRY:
                        payload = service_xml
                    after.writestr(entry, payload)
                marker = zipfile.ZipInfo(MARKER, date_time=(1980, 1, 1, 0, 0, 0))
                marker.compress_type = zipfile.ZIP_DEFLATED
                marker.create_system = 3
                marker.external_attr = 0o100644 << 16
                after.writestr(marker, json.dumps(metadata, ensure_ascii=False, sort_keys=True, indent=2).encode("utf-8"))
                after.comment = rewritten_zip_comment(before)
            if verify_jar(candidate, original, metadata) is None:
                raise ValueError("Compatible Marketplace output changed an unpinned JAR entry")
            os.replace(candidate, artifact)
    published = artifact
    if args.jar_output is not None:
        published = args.jar_output.resolve()
        atomic_bytes(published, artifact.read_bytes())
        if verify_jar(published, original, metadata) is None:
            raise ValueError("Exported compatible Marketplace repair changed")
    output = {"patchJar": str(published), "sourceSdk": str(sdk), "marker": MARKER,
              "upstreamJarRelativePath": pin["upstreamJar"]["path"], "patchedJarSha256": sha(artifact.read_bytes()), **metadata}
    if args.target_app is not None:
        app = args.target_app.resolve()
        target = app / pin["upstreamJar"]["path"]
        if app == sdk.resolve() or not app.is_relative_to((ROOT / "build").resolve()):
            raise ValueError("Only a distinct owned build copy may receive the Marketplace repair")
        if target.is_symlink() or not target.resolve().is_relative_to(app) or target.resolve() == original.resolve():
            raise ValueError("Marketplace installation target escapes its owned copy")
        if sha(target.read_bytes()) != pin["upstreamJar"]["sha256"] and verify_jar(target, original, metadata) is None:
            raise ValueError("Marketplace target is neither pinned upstream nor this verified repair")
        atomic_bytes(target, artifact.read_bytes())
        output["installedJar"] = str(target)
    if args.metadata_output is not None:
        atomic_bytes(args.metadata_output.resolve(), (json.dumps(output, ensure_ascii=False, indent=2) + "\n").encode("utf-8"))
    return output


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sdk", type=Path, required=True)
    parser.add_argument("--pin", type=Path, default=PIN)
    parser.add_argument("--marker", default=MARKER)
    parser.add_argument("--cache-name", default="platform-compatible-marketplace")
    parser.add_argument("--jar-output", type=Path)
    parser.add_argument("--target-app", type=Path)
    parser.add_argument("--output", dest="metadata_output", type=Path)
    args = parser.parse_args()
    if re.fullmatch(r"[a-z0-9-]+", args.cache_name) is None:
        parser.error("Cache name must use only a-z, 0-9 and hyphens")
    cache = ROOT / ".tools" / args.cache_name
    protect_sdk(cache / "build.lock", detect_platform_layout(args.sdk).sdk_root)
    cache.mkdir(parents=True, exist_ok=True)
    with cache_lock(cache / "build.lock"):
        result = build(args)
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
