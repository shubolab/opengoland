#!/usr/bin/env python3
"""Build pinned public-source LSP4IJ fixes into an owned sandbox plugin.

Never edits the downloaded dependency cache or an installed IDE. The upstream
JAR, source files and patch are independently SHA-256 pinned before compilation.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import urllib.request
import zipfile
import xml.etree.ElementTree as ET
from platform_layout import cache_lock, detect_platform_layout, java_compiler_invocation
from source_patch import apply_source_patch, SOURCE_PATCH_RUNNER_SHA256

ROOT = Path(__file__).resolve().parent.parent
PIN = ROOT / "product/patches/lsp4ij-0.21.0-document-sync.json"
DESCRIPTION_PIN = ROOT / "product/patches/lsp4ij-0.21.0-intention-descriptions.json"
DESCRIPTION_ENTRY = "META-INF/plugin.xml"
UPSTREAM_JAR_SHA = "c874e03849aeab974483913ea72161bca0c315c7800c77cbf530d82da0001900"
MARKER = "META-INF/open-goland-document-sync.json"
# Dependencies can ship .java alongside their .class entries. Compile only the
# explicitly pinned source list, never implicitly rebuild third-party DTOs.
COMPILER_OPTIONS = ("--release", "17", "-encoding", "UTF-8", "-implicit:none", "-sourcepath", "")
PREFIXES = ("com/redhat/devtools/lsp4ij/LanguageServerWrapper", "com/redhat/devtools/lsp4ij/DocumentContentSynchronizer", "com/redhat/devtools/lsp4ij/LSPFileListener", "com/redhat/devtools/lsp4ij/usages/LSPUsageSearcher", "com/redhat/devtools/lsp4ij/client/features/LSPUsageFeature", "com/redhat/devtools/lsp4ij/usages/LSPUsageSupport", "com/redhat/devtools/lsp4ij/features/AbstractLSPDocumentFeatureSupport", "com/redhat/devtools/lsp4ij/features/declaration/LSPDeclarationSupport", "com/redhat/devtools/lsp4ij/features/navigation/LSPDefinitionSupport", "com/redhat/devtools/lsp4ij/features/typeDefinition/LSPTypeDefinitionSupport", "com/redhat/devtools/lsp4ij/features/implementation/LSPImplementationSupport", "com/redhat/devtools/lsp4ij/features/highlight/LSPHighlightSupport", "com/redhat/devtools/lsp4ij/LanguageServiceAccessor", "com/redhat/devtools/lsp4ij/dap/DAPDebugProcess", "com/redhat/devtools/lsp4ij/dap/client/DAPClient", "com/redhat/devtools/lsp4ij/dap/client/DAPExecutionStack", "com/redhat/devtools/lsp4ij/dap/client/DAPSuspendContext", "com/redhat/devtools/lsp4ij/dap/client/files/DeferredSourcePositionBase")


def sha(data):
    return hashlib.sha256(data).hexdigest()


def patched_class(name):
    return name.endswith(".class") and any(name == p + ".class" or name.startswith(p + "$") for p in PREFIXES)


def write_patched_entry(archive, name, data):
    # The compiler and runtime consume separate owned copies. Stable ZIP headers
    # make identical pinned classes/metadata produce identical dependency bytes.
    entry = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
    entry.compress_type = zipfile.ZIP_DEFLATED
    entry.create_system = 3
    entry.external_attr = 0o100644 << 16
    archive.writestr(entry, data)


def complete_class_set(records, actual):
    return (isinstance(records, dict) and records and set(records) == set(actual)
            and all(p + ".class" in records for p in PREFIXES)
            and all(patched_class(name) for name in records))


def prepare_description_metadata(original):
    """Change one authenticated descriptor; translated family names are not paths."""
    pin_bytes = DESCRIPTION_PIN.read_bytes()
    pin = json.loads(pin_bytes.decode("utf-8"))
    patch = DESCRIPTION_PIN.parent / pin["patchFile"]
    if (pin["schemaVersion"] != 1 or pin["upstreamJarSha256"] != UPSTREAM_JAR_SHA
            or pin["resourcePath"] != DESCRIPTION_ENTRY
            or sha(patch.read_bytes()) != pin["patchSha256"]):
        raise ValueError("Intention description metadata pin mismatch")
    fixed_ids = ["com.redhat.devtools.lsp4ij.features.codeAction.intention.LSPIntentionAction" + str(i) for i in range(20)]
    if pin["intentionClassNames"] != fixed_ids or pin["descriptionDirectoryName"] != "Empty":
        raise ValueError("Unexpected intention description targets")
    with zipfile.ZipFile(original) as archive:
        source = archive.read(DESCRIPTION_ENTRY)
        resource = pin["descriptionResource"]
        if (sha(source) != pin["upstreamSha256"]
                or resource["path"] != "intentionDescriptions/Empty/description.html"
                or sha(archive.read(resource["path"])) != resource["sha256"]):
            raise ValueError("Upstream intention description resource mismatch")
    with tempfile.TemporaryDirectory(prefix="lsp4ij-description-") as temporary:
        directory = Path(temporary)
        descriptor = directory / DESCRIPTION_ENTRY
        descriptor.parent.mkdir()
        descriptor.write_bytes(source)
        apply_source_patch(patch, directory, pin["patchSha256"])
        result = descriptor.read_bytes()
    if sha(result) != pin["patchedSha256"]:
        raise ValueError("Patched intention descriptor digest mismatch")
    targets = [item for item in ET.fromstring(result).iter("intentionAction")
               if item.findtext("className") in fixed_ids]
    if (len(targets) != len(fixed_ids) or {item.findtext("className") for item in targets} != set(fixed_ids)
            or any([element.text for element in item.findall("descriptionDirectoryName")] != ["Empty"] for item in targets)):
        raise ValueError("Patched intention description target mismatch")
    record = {"pinFile": DESCRIPTION_PIN.relative_to(ROOT).as_posix(), "pinSha256": sha(pin_bytes),
              "patchSha256": pin["patchSha256"], "resourcePath": DESCRIPTION_ENTRY,
              "upstreamSha256": pin["upstreamSha256"], "patchedSha256": pin["patchedSha256"],
              "intentionClassNames": fixed_ids, "descriptionDirectoryName": "Empty",
              "descriptionResource": pin["descriptionResource"]}
    return result, record


def verified_contents(before, after, saved):
    """No arbitrary XML/resource exceptions: only the pinned descriptor may differ."""
    expected = set(before.namelist()) - {n for n in before.namelist() if patched_class(n)} - {DESCRIPTION_ENTRY}
    actual = set(after.namelist()) - {n for n in after.namelist() if patched_class(n)} - {MARKER, DESCRIPTION_ENTRY}
    classes = {n for n in after.namelist() if patched_class(n)}
    return (len(after.namelist()) == len(set(after.namelist()))
            and complete_class_set(saved.get("classSha256"), classes)
            and expected == actual and all(before.read(n) == after.read(n) for n in expected)
            and sha(before.read(DESCRIPTION_ENTRY)) == saved["intentionDescriptions"]["upstreamSha256"]
            and sha(after.read(DESCRIPTION_ENTRY)) == saved["intentionDescriptions"]["patchedSha256"]
            and all(sha(after.read(n)) == digest for n, digest in saved["classSha256"].items()))


def build(args):
    pin = json.loads(PIN.read_text(encoding="utf-8"))
    patch = PIN.parent / pin["patchFile"]
    assert sha(patch.read_bytes()) == pin["patchSha256"], "LSP4IJ patch digest mismatch"
    jars = list(args.plugins.resolve().glob("*/lib/lsp4ij-0.21.0.jar"))
    if len(jars) != 1:
        raise ValueError(f"Expected one private sandbox LSP4IJ 0.21.0 JAR, found {len(jars)}")
    target = jars[0]
    # Keep a verified original for idempotent checks and compilation, never mutate
    # Gradle's downloaded/transformed plugin or any application installation.
    cache = ROOT / ".tools/lsp4ij-document-sync"
    cache.mkdir(parents=True, exist_ok=True)
    original = cache / "upstream-0.21.0.jar"
    target_sha = sha(target.read_bytes())
    if target_sha == UPSTREAM_JAR_SHA:
        if not original.exists() or sha(original.read_bytes()) != UPSTREAM_JAR_SHA:
            shutil.copyfile(target, original)
    elif not original.exists() or sha(original.read_bytes()) != UPSTREAM_JAR_SHA:
        raise ValueError("Sandbox plugin is neither the pinned upstream JAR nor verifiable from its original")

    description_bytes, description_record = prepare_description_metadata(original)
    metadata = {"upstreamVersion": "0.21.0", "upstreamCommit": pin["upstream"]["refObject"]["sha"],
                "upstreamJarSha256": UPSTREAM_JAR_SHA, "patchSha256": pin["patchSha256"],
                "intentionDescriptions": description_record,
                "sources": [{k: v for k, v in source.items() if k != "sourceUrl"} for source in pin["sources"]],
                "compilerOptions": list(COMPILER_OPTIONS)}
    if target_sha != UPSTREAM_JAR_SHA:
        with zipfile.ZipFile(original) as before, zipfile.ZipFile(target) as after:
            saved = json.loads(after.read(MARKER))
            if all(saved.get(k) == v for k, v in metadata.items()):
                if verified_contents(before, after, saved):
                    print("Verified private LSP4IJ document-sync patch")
                    return
        raise ValueError("Existing private LSP4IJ patch does not match its pinned inputs")

    layout = detect_platform_layout(args.sdk)
    javac_candidates = [layout.javac]
    if os.environ.get("JAVA_HOME"):
        javac_candidates.append(Path(os.environ["JAVA_HOME"]) / "bin" / ("javac.exe" if os.name == "nt" else "javac"))
    if shutil.which("javac"):
        javac_candidates.append(Path(shutil.which("javac")))
    javac = next((p for p in javac_candidates if p.is_file() and os.access(p, os.X_OK)), None)
    if javac is None:
        raise ValueError("A JDK with javac is required to compile the pinned LSP4IJ source patch")
    compiler = subprocess.check_output([str(javac), "-version"], text=True, stderr=subprocess.STDOUT).strip()
    sdk_lib = layout.lib
    classpath = [original] + sorted(target.parent.glob("*.jar")) + sorted(sdk_lib.rglob("*.jar"))
    classpath = [p for p in classpath if p != target]
    identity = {**metadata, "compiler": compiler, "sourcePatchRunnerSha256": SOURCE_PATCH_RUNNER_SHA256,
                "sdk": json.loads(layout.product_info.read_text(encoding="utf-8"))["buildNumber"]}
    if layout.platform != "macos":
        identity["sdkPlatform"] = layout.platform
    key = sha(json.dumps(identity, sort_keys=True).encode())
    work = cache / key
    built = work / "classes"
    class_manifest = work / "classes.json"
    valid = class_manifest.exists() and built.is_dir()
    if valid:
        try:
            records = json.loads(class_manifest.read_text(encoding="utf-8"))
            actual = {p.relative_to(built).as_posix() for p in built.rglob("*.class")}
            valid = complete_class_set(records, actual) and all((built / n).is_file() and sha((built / n).read_bytes()) == d for n, d in records.items())
        except (OSError, ValueError):
            valid = False
    if not valid:
        work.mkdir(parents=True, exist_ok=True)
        upstream = work / "source"
        shutil.rmtree(upstream, ignore_errors=True)
        shutil.rmtree(built, ignore_errors=True)
        built.mkdir()
        for source in pin["sources"]:
            path = upstream / source["path"]
            path.parent.mkdir(parents=True, exist_ok=True)
            # Pin immutable commit URLs as well as bytes; a moved tag cannot
            # silently change the source used to produce this build.
            url = source["sourceUrl"].replace("/0.21.0/", "/" + metadata["upstreamCommit"] + "/")
            with urllib.request.urlopen(url, timeout=60) as response:
                data = response.read()
            if sha(data) != source["upstreamSha256"]:
                raise ValueError(f"Upstream source digest mismatch: {source['path']}")
            path.write_bytes(data)
        apply_source_patch(patch, upstream, pin["patchSha256"])
        for source in pin["sources"]:
            if sha((upstream / source["path"]).read_bytes()) != source["patchedSha256"]:
                raise ValueError(f"Patched source digest mismatch: {source['path']}")
        compiler_arguments = [*COMPILER_OPTIONS, "-classpath", os.pathsep.join(map(str, classpath)), "-d", str(built)]
        compiler_arguments += [str(upstream / source["path"]) for source in pin["sources"]]
        invocation = java_compiler_invocation(javac, compiler_arguments, work / "javac.args")
        result = subprocess.run(invocation.command, cwd=invocation.cwd, text=True, capture_output=True)
        if result.returncode:
            raise ValueError("Compiling the pinned LSP4IJ patch failed:\n" + result.stderr)
        records = {p.relative_to(built).as_posix(): sha(p.read_bytes()) for p in sorted(built.rglob("*.class"))}
        if not complete_class_set(records, records):
            raise ValueError("Unexpected classes produced by LSP4IJ source patch")
        candidate_manifest = work / "classes.json.pending"
        candidate_manifest.write_text(json.dumps(records, indent=2) + "\n", encoding="utf-8")
        os.replace(candidate_manifest, class_manifest)
    metadata["compiler"] = compiler
    metadata["sourcePatchRunnerSha256"] = SOURCE_PATCH_RUNNER_SHA256
    metadata["classSha256"] = records
    with tempfile.TemporaryDirectory(prefix="lsp4ij-patch-", dir=target.parent) as temporary:
        candidate = Path(temporary) / target.name
        with zipfile.ZipFile(original) as before, zipfile.ZipFile(candidate, "w", zipfile.ZIP_DEFLATED) as after:
            for entry in before.infolist():
                if not patched_class(entry.filename):
                    after.writestr(entry, description_bytes if entry.filename == DESCRIPTION_ENTRY else before.read(entry.filename))
            for name in sorted(records):
                write_patched_entry(after, name, (built / name).read_bytes())
            write_patched_entry(after, MARKER, json.dumps(metadata, sort_keys=True, indent=2))
        with zipfile.ZipFile(original) as before, zipfile.ZipFile(candidate) as after:
            if not verified_contents(before, after, metadata):
                raise ValueError("Built LSP4IJ patch changed an unpinned entry")
        os.replace(candidate, target)
    print(f"Built private LSP4IJ document-sync patch: {len(records)} classes, patch {pin['patchSha256']}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sdk", required=True, type=Path)
    parser.add_argument("--plugins", required=True, type=Path)
    args = parser.parse_args()
    cache = ROOT / ".tools/lsp4ij-document-sync"
    cache.mkdir(parents=True, exist_ok=True)
    # Development/test sandbox preparation (or separate Gradle invocations)
    # can share a cold compiler cache. Lock its entire verify/build/install
    # transaction; a process exit releases this OS lock without stale markers.
    with cache_lock(cache / "build.lock"):
        build(args)


if __name__ == "__main__":
    main()
