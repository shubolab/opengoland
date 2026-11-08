#!/usr/bin/env python3
"""Compile the pinned public JDOMUtil Unicode fix without changing the source SDK.

The optional installation target must be an owned app copy below this project's
build directory. No trust decisions or user configuration files are read/written.
"""
import argparse
import fcntl
import hashlib
import json
import os
from pathlib import Path
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


def verify_jar(candidate, original, expected, prefixes, required):
    try:
        with zipfile.ZipFile(original) as before, zipfile.ZipFile(candidate) as after:
            names = after.namelist()
            if len(names) != len(set(names)):
                return None
            saved = json.loads(after.read(MARKER))
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
            if set(names) - actual - {MARKER} != untouched or any(before.read(name) != after.read(name) for name in untouched):
                return None
            return saved
    except (OSError, ValueError, KeyError, zipfile.BadZipFile):
        return None


def build(args):
    pin = json.loads(PIN.read_text())
    sdk = args.sdk.resolve()
    info = json.loads((sdk / "Contents/Resources/product-info.json").read_text())
    if info["version"] != pin["platformVersion"]:
        raise ValueError("The platform Unicode patch requires the pinned SDK version")
    original = sdk / pin["upstreamJar"]["path"]
    if sha(original.read_bytes()) != pin["upstreamJar"]["sha256"]:
        raise ValueError("Pinned upstream platform JAR digest mismatch")
    patch = PIN.parent / pin["patchFile"]
    if sha(patch.read_bytes()) != pin["patchSha256"]:
        raise ValueError("Platform Unicode patch digest mismatch")
    javac = sdk / "Contents/jbr/Contents/Home/bin/javac"
    if not javac.is_file():
        raise ValueError("The selected SDK must include javac")
    compiler = subprocess.check_output([str(javac), "-version"], text=True, stderr=subprocess.STDOUT, timeout=15).strip()
    expected = {
        "platformVersion": pin["platformVersion"], "upstreamCommit": pin["upstream"]["commit"],
        "upstreamJarSha256": pin["upstreamJar"]["sha256"], "patchSha256": pin["patchSha256"],
        "sources": pin["sources"], "release": 21,
    }
    cache = ROOT / ".tools/platform-unicode"
    key = sha(json.dumps({**expected, "compiler": compiler, "sdkBuild": info["buildNumber"]}, sort_keys=True).encode())
    work = cache / key
    work.mkdir(parents=True, exist_ok=True)
    artifact = work / original.name
    metadata = verify_jar(artifact, original, expected, pin["classPrefixes"], pin["requiredClassNames"])
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
                raise ValueError("Compiling the public platform Unicode fix failed:\n" + result.stderr)
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
                after.writestr(MARKER, json.dumps(metadata, ensure_ascii=False, sort_keys=True, indent=2))
            if verify_jar(candidate, original, expected, pin["classPrefixes"], pin["requiredClassNames"]) is None:
                raise ValueError("Built platform patch failed complete class/entry verification")
            os.replace(candidate, artifact)
    output = {"patchJar": str(artifact), "sourceSdk": str(sdk), "marker": MARKER, "upstreamJarRelativePath": pin["upstreamJar"]["path"],
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
                target, original, expected, pin["classPrefixes"], pin["requiredClassNames"]) is None:
            raise ValueError("Target app platform JAR is neither pinned upstream nor the verified private patch")
        atomic_bytes(target, artifact.read_bytes())
        output["installedJar"] = str(target)
    if args.metadata_output is not None:
        atomic_bytes(args.metadata_output.resolve(), (json.dumps(output, ensure_ascii=False, indent=2) + "\n").encode())
    return output


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sdk", type=Path, required=True)
    parser.add_argument("--target-app", type=Path)
    parser.add_argument("--output", "--metadata-output", dest="metadata_output", type=Path)
    args = parser.parse_args()
    cache = ROOT / ".tools/platform-unicode"
    cache.mkdir(parents=True, exist_ok=True)
    with (cache / "build.lock").open("a+b") as guard:
        fcntl.flock(guard, fcntl.LOCK_EX)
        output = build(args)
    print(json.dumps(output, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
