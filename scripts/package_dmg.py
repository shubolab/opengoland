#!/usr/bin/env python3
"""Build, mount and exercise a real macOS drag-to-Applications installer."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import plistlib
import re
import shutil
import subprocess
import tempfile

from build_inputs import snapshot, verify
from installed_engine_smoke import engine_work, process_options, _stop_owned
from go_release_checks import fingerprint, record_success, reuse_evidence, RACE_ARGS

ROOT = Path(__file__).resolve().parent.parent


def sha(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        while chunk := stream.read(1024 * 1024):
            digest.update(chunk)
    return digest.hexdigest()


def run(command, log, environment=None, cwd=ROOT, timeout=None):
    with log.open("wb") as stream:
        process = subprocess.Popen(list(map(str, command)), cwd=cwd, env=environment, stdout=stream,
                                   stderr=subprocess.STDOUT, stdin=subprocess.DEVNULL, **process_options())
        try:
            process.wait(timeout=timeout)
            if process.returncode:
                raise subprocess.CalledProcessError(process.returncode, command)
        finally:
            _stop_owned(process)


def created_image_device(information, image):
    """Select only the newly created image's unambiguous primary device."""
    if not isinstance(information, dict) or not isinstance(information.get("images"), list):
        raise ValueError("Invalid disk image attachment inventory")
    image = Path(image).resolve()
    matches = [record for record in information["images"]
               if isinstance(record, dict) and isinstance(record.get("image-path"), str)
               and Path(record["image-path"]).is_absolute()
               and Path(record["image-path"]).resolve() == image]
    if not matches:
        return None
    if len(matches) != 1:
        raise ValueError("Multiple attachment records for the created disk image")
    entities = matches[0].get("system-entities")
    if not isinstance(entities, list) or not entities or any(not isinstance(item, dict) for item in entities):
        raise ValueError("Missing primary device for the created disk image")
    whole = [item for item in entities if isinstance(item.get("dev-entry"), str)
             and re.fullmatch(r"/dev/disk[0-9]+", item["dev-entry"])]
    if len({item["dev-entry"] for item in whole}) != len(whole):
        raise ValueError("Repeated primary device for the created disk image")
    maps = [item for item in whole if item.get("content-hint") in
            {"GUID_partition_scheme", "Apple_partition_scheme", "FDisk_partition_scheme"}]
    candidates = maps if maps else whole
    if len(candidates) != 1 or entities[0] is not candidates[0]:
        raise ValueError("Ambiguous primary device for the created disk image")
    return candidates[0]["dev-entry"]


def detach_created_image(image, proof):
    # Some macOS versions leave create's write-once device attached. That
    # exclusive image lock prevents verification until its normal detach.
    def inventory(name):
        result = subprocess.run(["hdiutil", "info", "-plist"], capture_output=True, check=True)
        (proof / name).write_bytes(result.stdout)
        return plistlib.loads(result.stdout)

    device = created_image_device(inventory("dmg-after-create-info.plist"), image)
    if device is not None:
        run(["hdiutil", "detach", device], proof / "dmg-after-create-detach.log")
        if created_image_device(inventory("dmg-after-detach-info.plist"), image) is not None:
            raise ValueError("The created disk image remains attached after normal detach")
    (proof / "dmg-create-cleanup.json").write_text(json.dumps({
        "image": str(Path(image).resolve()), "device": device,
        "status": "detached-owned-image" if device else "created-image-not-attached"
    }, indent=2) + "\n", encoding="utf-8")


def smoke_properties(profile):
    """Configure only this installer check's temporary native-launcher profile."""
    properties = profile / "idea.properties"
    properties.write_text("\n".join(f"idea.{name}.path={profile / name}"
                                    for name in ("config", "system", "plugins", "log")) +
                          "\nintellij.startup.wizard=false\nidea.initially.ask.config=never\n",
                          encoding="utf-8")
    return properties


def package(version, output, reuse_go=None):
    if platform.system() != "Darwin" or platform.machine().lower() != "arm64":
        raise ValueError("This installer must be built and checked on Apple silicon macOS")
    if not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+(?:-[0-9A-Za-z.-]+)?", version):
        raise ValueError("Use a concrete release version, such as 0.1.0-preview.1")
    output = output.resolve()
    if not output.is_relative_to((ROOT / "build").resolve()):
        raise ValueError("Release output must stay inside this repository's build directory")
    output.mkdir(parents=True, exist_ok=True)
    proof_root = output / "mac-provenance"
    proof_root.mkdir(exist_ok=True)
    proof = Path(tempfile.mkdtemp(prefix=version + "-", dir=proof_root))
    frozen = snapshot()
    (proof / "source-inputs.json").write_text(json.dumps(frozen, indent=2) + "\n")
    env = os.environ.copy()
    tools = ROOT / ".tools/release-tools/darwin-arm64"
    run([ROOT / "scripts/bootstrap-platform.sh"], proof / "platform-bootstrap.log", env)
    run(["python3", "-m", "unittest", "discover", "-s", "scripts/tests", "-v"], proof / "python-controls.log", env)
    print("Preparing authenticated Go SDK and tools…", flush=True)
    run(["python3", ROOT / "scripts/bundle-go-tools.py", "--target", "darwin-arm64", "--output", tools,
         "--report", proof / "tool-preparation.json"], proof / "tool-preparation.log", env)
    prepared = json.loads((proof / "tool-preparation.json").read_text())
    builder = Path(prepared["builderGoExecutable"])
    env.update({"OPENGOLAND_RELEASE_VERSION": version, "OPENGOLAND_GO_BUILDER": str(builder),
                "GOROOT": str(builder.parent.parent), "GOTOOLCHAIN": "local",
                "GOENV": "off", "GOFLAGS": "", "GOOS": "darwin", "GOARCH": "arm64", "CGO_ENABLED": "1",
                "PATH": str(builder.parent) + os.pathsep + env.get("PATH", ""),
                "OPENGOLAND_GO_TEST": str(tools / "go/bin/go"),
                "OPENGOLAND_GOPLS_TEST": str(tools / "bin/gopls")})
    env.pop("GOWORK", None)
    print("Running Go race/vet and native platform regression checks…", flush=True)
    if reuse_go is not None:
        go_checks = reuse_evidence(reuse_go, ROOT, builder, env, prepared, proof, frozen)
    else:
        before_go = fingerprint(ROOT, builder, env, prepared)
        # Recent Go SDKs build this official tool on first use. Prepare it before
        # the profile oracle's timed comparison, retaining all actual race tests.
        run([builder, "tool", "pprof", "-help"], proof / "go-pprof-bootstrap.log", env, timeout=300)
        run([builder, *RACE_ARGS], proof / "go-race.jsonl", env, ROOT / "engine")
        run([builder, "vet", "./..."], proof / "go-vet.log", env, ROOT / "engine")
        go_checks = record_success(ROOT, builder, env, prepared, before_go, proof, frozen,
                                   race_exit_code=0, vet_exit_code=0)
    try:
        run([ROOT / "scripts/package-product.sh", "--version", version], proof / "product-build.log", env)
    finally:
        test_results = ROOT / "build/test-results/test"
        if test_results.is_dir():
            shutil.copytree(test_results, proof / "kotlin-test-results")
    verify(frozen)
    app = ROOT / "build/product/open Goland.app"
    manifest_path = Path("Contents/Resources/openidea-product.json")
    manifest = json.loads((app / manifest_path).read_text())
    if manifest["version"] != version or manifest["buildInputs"] != frozen:
        raise ValueError("The compiled product does not match the release version/source snapshot")
    for record in manifest["archiveChecks"]["archives"]:
        if sha(app / "Contents" / record["path"]) != record["sha256"]:
            raise ValueError("Product archive changed after its native reader check")
    image = output / f"open-Goland-{version}-macos-arm64.dmg"
    if image.exists():
        raise ValueError("Choose a new release version or move the existing generated DMG first")
    print("Creating and mounting the DMG installer…", flush=True)
    with tempfile.TemporaryDirectory(prefix="dmg-stage-", dir=ROOT / "build") as temporary:
        stage = Path(temporary)
        run(["ditto", app, stage / app.name], proof / "stage-copy.log")
        (stage / "Applications").symlink_to("/Applications", target_is_directory=True)
        (stage / "Install.txt").write_text(
            "Drag open Goland.app to Applications, then open it.\n"
            "The app includes Java, the Go SDK, gopls and Delve. No IDE account or subscription is required.\n"
            "This preview uses ad-hoc signing and is not notarized. macOS may require per-app approval.\n"
            "Go debugging on macOS currently requires Apple's Command Line Tools (debugserver).\n"
            "Source and notices: https://github.com/shubolab/opengoland\n")
        run(["hdiutil", "create", "-volname", "open Goland", "-srcfolder", stage,
             "-format", "UDZO", "-imagekey", "zlib-level=9", image], proof / "dmg-create.log")
        detach_created_image(image, proof)
    run(["hdiutil", "verify", image], proof / "dmg-verify.log")
    install_root = Path(tempfile.mkdtemp(prefix="installed-macos-", dir=ROOT / "build"))
    installed = install_root / app.name
    with tempfile.TemporaryDirectory(prefix="dmg-mount-", dir=ROOT / "build") as temporary:
        mount = Path(temporary)
        result = subprocess.run(["hdiutil", "attach", "-readonly", "-nobrowse", "-noautoopen", "-mountpoint", str(mount), "-plist", str(image)],
                                capture_output=True, check=True)
        (proof / "dmg-mount.plist").write_bytes(result.stdout)
        plistlib.loads(result.stdout)
        try:
            mounted = mount / app.name
            if sha(mounted / manifest_path) != sha(app / manifest_path):
                raise ValueError("Mounted product manifest differs from the verified build")
            if not (mount / "Applications").is_symlink() or os.readlink(mount / "Applications") != "/Applications":
                raise ValueError("DMG has no drag-to-Applications installation target")
            run(["codesign", "--verify", "--deep", "--strict", mounted], proof / "mounted-signature.log")
            run(["ditto", mounted, installed], proof / "install-copy.log")
        finally:
            run(["hdiutil", "detach", mount], proof / "dmg-detach.log")
    # Verify the installed copy, not the source app or the build machine's tools.
    installed_manifest = json.loads((installed / manifest_path).read_text())
    for name, expected in installed_manifest["pluginArtifactSha256"].items():
        if sha(installed / "Contents" / name) != expected:
            raise ValueError("Installed plugin/tool file differs from the sealed product: " + name)
    for record in installed_manifest["localIdeDistribution"]["jars"]:
        if sha(installed / "Contents" / record["path"]) != record["afterSha256"]:
            raise ValueError("Installed local IDE resource archive differs from the sealed product")
    run(["codesign", "--verify", "--deep", "--strict", installed], proof / "installed-signature.log")
    selected = [installed / path for path in installed_manifest["pluginLoading"]["directories"]
                if (installed / path / "bin/opengoland-engine").is_file()]
    if len(selected) != 1:
        raise ValueError("Installed Go engine plugin is ambiguous")
    plugin = selected[0]
    smoke_env = {key: value for key, value in os.environ.items()
                 if key not in {"JAVA_HOME", "JDK_HOME", "IDEA_JDK", "IDEA_VM_OPTIONS", "IDEA_PROPERTIES", "GOFLAGS", "GOOS", "GOARCH", "CC", "CXX"}}
    smoke_env.update({"PATH": f"{plugin}/tools/go/bin:{plugin}/tools/bin:/usr/bin:/bin:/usr/sbin:/sbin",
                      "GOROOT": str(plugin / "tools/go"), "GOTOOLCHAIN": "local", "GOENV": "off",
                      "GOWORK": "off",
                      "GOPROXY": "off", "GOSUMDB": "off", "GOTELEMETRY": "off", "CGO_ENABLED": "0",
                      "GOCACHE": str(proof / "go-cache"), "GOMODCACHE": str(proof / "go-mod-cache")})
    profile = proof / "isolated-profile"
    profile.mkdir(exist_ok=True)
    properties = smoke_properties(profile)
    native_info = json.loads((installed / "Contents/Resources/product-info.json").read_text())
    prefix = native_info.get("envVarBaseName", "IDEA")
    if not re.fullmatch(r"[A-Z_][A-Z_0-9]*", prefix):
        raise ValueError("Unexpected launcher environment prefix")
    smoke_env[prefix + "_PROPERTIES"] = str(properties)
    smoke_env[prefix + "_VM_OPTIONS"] = str(installed / "Contents/bin/opengoland.vmoptions")
    print("Checking the installed launcher, bundled tools and real Go test…", flush=True)
    run([installed / "Contents/MacOS/idea", "-version"], proof / "installed-native-version.log", smoke_env, timeout=120)
    if manifest["platform"]["version"] not in (proof / "installed-native-version.log").read_text(errors="replace"):
        raise ValueError("Installed native launcher did not report the pinned platform version")
    for tool, command in [("go", [plugin / "tools/go/bin/go", "version"]),
                          ("gopls", [plugin / "tools/bin/gopls", "version"]),
                          ("dlv", [plugin / "tools/bin/dlv", "version"])]:
        run(command, proof / f"installed-{tool}-version.log", smoke_env, timeout=60)
    engine = engine_work(plugin / "bin/opengoland-engine", plugin / "tools/go/bin/go", smoke_env,
                         proof / "工程 空格 😀", proof)
    verify(frozen)
    report = {"schemaVersion": 1, "version": version, "target": "macos-arm64", "installer": image.name,
              "installerSha256": sha(image), "installerBytes": image.stat().st_size,
              "sourceCommit": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip(),
              "sourceDirty": bool(subprocess.check_output(["git", "status", "--porcelain"], cwd=ROOT, text=True).strip()),
              "sourceDigest": frozen["digest"], "productManifestSha256": sha(installed / manifest_path),
              "installedApplication": str(installed), "imageVerified": True, "mountedAndCopied": True,
              "signatureVerified": True, "signing": "ad-hoc; not notarized", "installedEngineSmoke": engine,
              "goChecks": go_checks,
              "scope": "Actual DMG mount/install copy, signatures, native launcher version, bundled SDK/tools and one uncached pure-Go test. Interactive GUI and clean-OS debugging require separate evidence.",
              "limitations": ["Apple silicon macOS build only; preview is not notarized.",
                              "macOS debugging currently needs Apple's Command Line Tools debugserver.",
                              "Complete GoLand feature parity remains open."]}
    (proof / "release.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
    (output / (image.name + ".sha256")).write_text(report["installerSha256"] + "  " + image.name + "\n")
    for name in ("go-cache", "go-mod-cache"):
        shutil.rmtree(proof / name, ignore_errors=True)
    print(json.dumps({"status": "pass", "installer": str(image), "sha256": report["installerSha256"],
                      "installedApplication": str(installed)}, ensure_ascii=False), flush=True)
    return report


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--version", required=True)
    parser.add_argument("--output", type=Path, default=ROOT / "build/release")
    parser.add_argument("--reuse-go-evidence", type=Path, help="Explicit prior fresh Go check record; all inputs/environment/logs are verified")
    arguments = parser.parse_args()
    package(arguments.version, arguments.output, arguments.reuse_go_evidence)
