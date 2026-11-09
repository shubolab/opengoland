#!/usr/bin/env python3
"""Compile a pinned public platform repair without changing the source SDK.

The optional installation target must be an owned app copy below this project's
build directory. No trust decisions or user configuration files are read/written.
"""
import argparse
import fcntl
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parent.parent
PIN = ROOT / "product/patches/platform-unicode-2026.1.4.json"
MARKER = "META-INF/open-goland-platform-unicode.json"


def sha(data):
    return hashlib.sha256(data).hexdigest()


def replaced(name, prefixes):
    return name.endswith(".class") and any(name == p + ".class" or name.startswith(p + "$") for p in prefixes)


def atomic_bytes(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(prefix=path.name + ".", dir=path.parent, delete=False) as stream:
        candidate = Path(stream.name)
        try:
            stream.write(data)
            stream.flush()
            candidate.chmod(path.stat().st_mode & 0o777 if path.exists() else 0o644)
            os.replace(candidate, path)
        finally:
            candidate.unlink(missing_ok=True)


def cache_name(value):
    if re.fullmatch(r"[a-z0-9-]+", value) is None:
        raise argparse.ArgumentTypeError("Platform repair cache name must use only a-z, 0-9 and hyphens")
    return value


def marker_name(value):
    if (not value or value.startswith("/") or "\\" in value or
            any(part in ("", ".", "..") for part in value.split("/")) or
            any(ord(character) < 32 or ord(character) == 127 for character in value)):
        raise argparse.ArgumentTypeError("Platform repair marker must be a clean relative JAR entry path")
    return value


def protect_sdk(path, sdk):
    if path.resolve().is_relative_to(sdk):
        raise ValueError("Platform repair outputs must not modify the source SDK")


def verify_jar(candidate, original, expected, prefixes, required, marker=MARKER):
    try:
        with zipfile.ZipFile(original) as before, zipfile.ZipFile(candidate) as after:
            names = after.namelist()
            if len(names) != len(set(names)):
                return None
            saved = json.loads(after.read(marker))
            if not isinstance(saved, dict):
                return None
            if any(saved.get(k) != value for k, value in expected.items()):
                return None
            records = saved.get("classSha256", {})
            if not isinstance(records, dict):
                return None
            actual = {name for name in names if replaced(name, prefixes)}
            if set(records) != actual or not set(required).issubset(actual):
                return None
            if any(sha(after.read(name)) != digest for name, digest in records.items()):
                return None
            untouched = {name for name in before.namelist() if not replaced(name, prefixes)}
            if set(names) - actual - {marker} != untouched or any(before.read(name) != after.read(name) for name in untouched):
                return None
            return saved
    except (OSError, ValueError, KeyError, zipfile.BadZipFile):
        return None


def build(args):
    pin_path = args.pin.resolve()
    pin = json.loads(pin_path.read_text())
    marker = args.marker
    sdk = args.sdk.resolve()
    info = json.loads((sdk / "Contents/Resources/product-info.json").read_text())
    if info["version"] != pin["platformVersion"]:
        raise ValueError("The platform repair requires the pinned SDK version")
    for output_path in (args.jar_output, args.metadata_output):
        if output_path is not None:
            protect_sdk(output_path, sdk)
    original = sdk / pin["upstreamJar"]["path"]
    if sha(original.read_bytes()) != pin["upstreamJar"]["sha256"]:
        raise ValueError("Pinned upstream platform JAR digest mismatch")
    patch = pin_path.parent / pin["patchFile"]
    if sha(patch.read_bytes()) != pin["patchSha256"]:
        raise ValueError("Platform repair digest mismatch")
    javac = sdk / "Contents/jbr/Contents/Home/bin/javac"
    if not javac.is_file():
        raise ValueError("The selected SDK must include javac")
    compiler = subprocess.check_output([str(javac), "-version"], text=True, stderr=subprocess.STDOUT, timeout=15).strip()
    expected = {
        "platformVersion": pin["platformVersion"], "upstreamCommit": pin["upstream"]["commit"],
        "upstreamJarSha256": pin["upstreamJar"]["sha256"], "patchSha256": pin["patchSha256"],
        "sources": pin["sources"], "release": 21,
    }
    cache = ROOT / ".tools" / args.cache_name
    identity = {**expected, "compiler": compiler, "sdkBuild": info["buildNumber"]}
    # Preserve the existing default artifact/cache identity byte for byte.
    if marker != MARKER:
        identity["marker"] = marker
    key = sha(json.dumps(identity, sort_keys=True).encode())
    work = cache / key
    protect_sdk(work, sdk)
    work.mkdir(parents=True, exist_ok=True)
    artifact = work / original.name
    metadata = verify_jar(artifact, original, expected, pin["classPrefixes"], pin["requiredClassNames"], marker)
    if metadata is None:
        with tempfile.TemporaryDirectory(prefix="compile-", dir=work) as directory:
            temporary = Path(directory)
            source = temporary / "source"
            for item in pin["sources"]:
                saved = cache / "source" / pin["upstream"]["commit"] / item["path"]
                if not saved.is_file() or sha(saved.read_bytes()) != item["upstreamSha256"]:
                    with urllib.request.urlopen(item["sourceUrl"], timeout=30) as response:
                        data = response.read()
                    if sha(data) != item["upstreamSha256"]:
                        raise ValueError("Pinned platform source digest mismatch: " + item["path"])
                    protect_sdk(saved, sdk)
                    atomic_bytes(saved, data)
                target = source / item["path"]
                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(saved, target)
            subprocess.run(["patch", "--batch", "--fuzz=0", "-p1", "-i", str(patch)], cwd=source,
                           check=True, capture_output=True, timeout=15)
            for item in pin["sources"]:
                if sha((source / item["path"]).read_bytes()) != item["patchedSha256"]:
                    raise ValueError("Patched platform source digest mismatch: " + item["path"])
            classes = temporary / "classes"
            classes.mkdir()
            classpath = sorted((sdk / "Contents/lib").rglob("*.jar"))
            command = [str(javac), "--release", "21", "-encoding", "UTF-8", "-proc:none", "-classpath",
                       os.pathsep.join(map(str, classpath)), "-d", str(classes)]
            command.extend(str(source / item["path"]) for item in pin["sources"])
            result = subprocess.run(command, capture_output=True, text=True, timeout=90)
            if result.returncode:
                raise ValueError("Compiling the public platform repair failed:\n" + result.stderr)
            records = {p.relative_to(classes).as_posix(): sha(p.read_bytes()) for p in sorted(classes.rglob("*.class"))}
            if not set(pin["requiredClassNames"]).issubset(records) or not all(replaced(n, pin["classPrefixes"]) for n in records):
                raise ValueError("The platform source rebuild produced an incomplete/unexpected class set")
            metadata = {**expected, "compiler": compiler, "classSha256": records}
            candidate = temporary / original.name
            with zipfile.ZipFile(original) as before, zipfile.ZipFile(candidate, "w", zipfile.ZIP_DEFLATED) as after:
                for entry in before.infolist():
                    if not replaced(entry.filename, pin["classPrefixes"]):
                        after.writestr(entry, before.read(entry.filename))
                for name in sorted(records):
                    after.writestr(name, (classes / name).read_bytes())
                after.writestr(marker, json.dumps(metadata, ensure_ascii=False, sort_keys=True, indent=2))
            if verify_jar(candidate, original, expected, pin["classPrefixes"], pin["requiredClassNames"], marker) is None:
                raise ValueError("Built platform patch failed complete class/entry verification")
            os.replace(candidate, artifact)
    published = artifact
    if args.jar_output is not None:
        published = args.jar_output.resolve()
        if published != artifact.resolve():
            atomic_bytes(published, artifact.read_bytes())
        if verify_jar(published, original, expected, pin["classPrefixes"], pin["requiredClassNames"], marker) is None:
            raise ValueError("Exported platform repair failed complete class/entry verification")
    output = {"patchJar": str(published), "sourceSdk": str(sdk), "marker": marker, "upstreamJarRelativePath": pin["upstreamJar"]["path"],
              "patchedJarSha256": sha(artifact.read_bytes()), **metadata}
    if args.target_app is not None:
        target_app = args.target_app.resolve()
        if target_app == sdk or not target_app.is_relative_to((ROOT / "build").resolve()):
            raise ValueError("Only a distinct owned app copy below this repository's build directory may be patched")
        target = target_app / pin["upstreamJar"]["path"]
        resolved_target = target.resolve()
        if not resolved_target.is_relative_to(target_app) or resolved_target == original.resolve():
            raise ValueError("Target app platform directory must not point outside the owned copy")
        if sha(target.read_bytes()) != pin["upstreamJar"]["sha256"] and verify_jar(
                target, original, expected, pin["classPrefixes"], pin["requiredClassNames"], marker) is None:
            raise ValueError("Target app platform JAR is neither pinned upstream nor the verified private patch")
        atomic_bytes(target, artifact.read_bytes())
        output["installedJar"] = str(target)
    if args.metadata_output is not None:
        atomic_bytes(args.metadata_output.resolve(), (json.dumps(output, ensure_ascii=False, indent=2) + "\n").encode())
    return output


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sdk", type=Path, required=True)
    parser.add_argument("--pin", type=Path, default=PIN)
    parser.add_argument("--marker", type=marker_name, default=MARKER)
    parser.add_argument("--cache-name", type=cache_name, default="platform-unicode")
    parser.add_argument("--jar-output", type=Path)
    parser.add_argument("--target-app", type=Path)
    parser.add_argument("--output", "--metadata-output", dest="metadata_output", type=Path)
    args = parser.parse_args()
    cache = ROOT / ".tools" / args.cache_name
    protect_sdk(cache / "build.lock", args.sdk.resolve())
    cache.mkdir(parents=True, exist_ok=True)
    with (cache / "build.lock").open("a+b") as guard:
        fcntl.flock(guard, fcntl.LOCK_EX)
        output = build(args)
    print(json.dumps(output, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
