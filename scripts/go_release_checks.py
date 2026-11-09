"""Strict, explicit reuse of a completed Go race/vet release check.

This module never executes Go. The release driver captures ``fingerprint``
before its two blocking commands, then calls ``record_success`` only after both
return zero. A historical observation without that fingerprint is not reusable.
Environment values are represented only by SHA-256, including credential URLs.
"""

from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import platform
import shutil


SCHEMA = "open-goland-go-release-checks-v1"
RACE_ARGS = ["test", "-race", "-count=1", "-p=1", "-timeout=30m", "-json", "./..."]
VET_ARGS = ["vet", "./..."]
_ENV_KEYS = {
    "PATH", "HOME", "USERPROFILE", "APPDATA", "LOCALAPPDATA", "XDG_CONFIG_HOME",
    "TMPDIR", "TMP", "TEMP", "LANG", "TZ", "CC", "CXX", "AR", "PKG_CONFIG",
    "SDKROOT", "DEVELOPER_DIR", "MACOSX_DEPLOYMENT_TARGET", "SYSTEMROOT", "WINDIR",
    "COMSPEC", "PATHEXT", "NUMBER_OF_PROCESSORS", "GIT_CONFIG_GLOBAL",
    "GIT_CONFIG_SYSTEM", "GIT_CONFIG_NOSYSTEM", "GIT_SSH", "GIT_SSH_COMMAND", "NETRC",
}


def _sha(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def _canonical(value) -> str:
    return _sha(json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=True).encode())


def _file(path: Path) -> dict:
    if path.is_symlink() or not path.is_file():
        raise ValueError("Release evidence requires a regular file: " + str(path))
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        while chunk := stream.read(1024 * 1024):
            digest.update(chunk)
    return {"bytes": path.stat().st_size, "sha256": digest.hexdigest()}


def _tree(root: Path, omit: set[str] | None = None) -> dict:
    if root.is_symlink() or not root.is_dir():
        raise ValueError("Release input must be a regular directory: " + str(root))
    result = {}
    for path in sorted(root.rglob("*")):
        relative = path.relative_to(root).as_posix()
        if omit and relative in omit:
            continue
        if path.is_symlink():
            raise ValueError("Symbolic link in release inputs: " + relative)
        if path.is_file():
            result[relative] = _file(path)
    return result


def capture_go_inputs(root: Path) -> dict:
    """Cover engine source/tests/fixtures/mod files/licenses, excluding outputs."""
    root = Path(root).resolve()
    engine = root / "engine"
    if engine.is_symlink() or not engine.is_dir():
        raise ValueError("Go source inputs require a regular engine directory")
    files = {}
    for directory, children, names in os.walk(engine, followlinks=False):
        directory = Path(directory)
        children[:] = sorted(name for name in children if name not in {"bin", "build", ".git", ".tools", "__pycache__"})
        for name in children:
            if (directory / name).is_symlink():
                raise ValueError("Symbolic link in Go source inputs")
        for name in sorted(names):
            path = directory / name
            relative = path.relative_to(root).as_posix()
            if path.parent == engine and name == "README.md":
                continue
            files[relative] = _file(path)
    if "engine/go.mod" not in files or not any(name.endswith("_test.go") for name in files):
        raise ValueError("Go release input tree is incomplete")
    return {"scope": "Entire engine source/test/fixture/license tree excluding generated output directories and its README",
            "files": files, "digest": _canonical(files)}


def _host() -> str:
    system = {"Darwin": "darwin", "Windows": "windows", "Linux": "linux"}.get(platform.system())
    machine = platform.machine().lower()
    architecture = {"arm64": "arm64", "aarch64": "arm64", "amd64": "amd64", "x86_64": "amd64"}.get(machine)
    if not system or not architecture:
        raise ValueError("Unsupported Go release builder host")
    return system + "-" + architecture


def _environment(environment: dict, cwd: Path) -> dict:
    names = set(_ENV_KEYS)
    names.update(key for key in environment if key.startswith(("GO", "CGO", "LC_", "OPENIDEA", "OPENGOLAND")))
    # Release labels do not participate in Go source/tests; each caller still
    # binds its complete release source digest outside this reusable subset.
    names.discard("OPENGOLAND_RELEASE_VERSION")
    variables = {name: {"present": name in environment,
                        "sha256": _sha(str(environment[name]).encode("utf-8", errors="surrogatepass")) if name in environment else None}
                 for name in sorted(names)}
    goenv = environment.get("GOENV", "")
    config = None
    if goenv != "off":
        if goenv:
            config = Path(goenv)
            if not config.is_absolute(): config = cwd / config
        elif platform.system() == "Darwin" and environment.get("HOME"):
            config = Path(environment["HOME"]) / "Library/Application Support/go/env"
        elif platform.system() == "Windows" and environment.get("APPDATA"):
            config = Path(environment["APPDATA"]) / "go/env"
        elif environment.get("XDG_CONFIG_HOME"):
            config = Path(environment["XDG_CONFIG_HOME"]) / "go/env"
        elif environment.get("HOME"):
            config = Path(environment["HOME"]) / ".config/go/env"
    configuration = None
    if config is not None:
        configuration = {"pathSha256": _sha(str(config.absolute()).encode()), "exists": config.exists()}
        if config.exists():
            configuration.update(_file(config))
    return {"variables": variables, "goEnvironmentFile": configuration}


def fingerprint(root: Path, builder: Path, environment: dict, prepared: dict) -> dict:
    """Verify full builder SDK/tool payload bytes without invoking executables."""
    root, builder = Path(root).resolve(), Path(builder).resolve()
    lock_path = root / "product/go-tools.lock.json"
    lock = json.loads(lock_path.read_text(encoding="utf-8"))
    lock_sha = _file(lock_path)["sha256"]
    host = _host()
    if (not isinstance(prepared, dict) or not isinstance(prepared.get("files"), dict)
            or not isinstance(prepared.get("output"), str) or not prepared["output"]
            or prepared.get("target") != host
            or prepared.get("runtimeSdkVersion") != lock["runtimeSdkVersion"]):
        raise ValueError("A native prepared Go runtime/tool manifest is required")
    if prepared.get("lockSha256") != lock_sha or prepared.get("builderVersion") != lock["builderVersion"]:
        raise ValueError("Go tool preparation does not match the current authenticated lock")
    if Path(prepared.get("builderGoExecutable", "")).resolve() != builder:
        raise ValueError("The Go command differs from its prepared builder")
    sdk = builder.parent.parent
    if not any(sdk.is_relative_to(root / name) for name in (".tools", "build")):
        raise ValueError("The authenticated builder SDK must be in the owned build/cache tree")
    expected_name = "go.exe" if host.startswith("windows-") else "go"
    if builder != sdk / "bin" / expected_name:
        raise ValueError("Unexpected Go builder executable location")
    marker_path = sdk.parent / "sdk-files.json"
    marker = json.loads(marker_path.read_text(encoding="utf-8"))
    if marker.get("archiveSha256") != lock["builders"][host]["sha256"]:
        raise ValueError("Go builder SDK archive does not match the authenticated lock")
    sdk_files = _tree(sdk)
    if marker.get("files") != sdk_files or not (sdk / "VERSION").read_text(encoding="utf-8").startswith(lock["builderVersion"] + "\n"):
        raise ValueError("Go builder SDK files changed")
    found = shutil.which(expected_name, path=environment.get("PATH", ""))
    if found is None or Path(found).resolve() != builder:
        raise ValueError("Go test subprocesses must resolve the same authenticated builder from PATH")
    tool_root = Path(prepared.get("output", "")).resolve()
    if not any(tool_root.is_relative_to(root / name) for name in (".tools", "build")):
        raise ValueError("Prepared Go tools must be in the owned build/cache tree")
    tool_files = _tree(tool_root, {"manifest.json"})
    if prepared.get("files") != tool_files:
        raise ValueError("Prepared Go runtime SDK/tool payload files changed")
    stable_toolset = {key: prepared[key] for key in ("lockSha256", "target", "runtimeSdkVersion", "builderVersion", "files")}
    inputs = capture_go_inputs(root)
    return {"schemaVersion": 1, "goInputs": inputs,
            "host": {"target": host, "release": platform.release(), "version": platform.version()},
            "builder": {"version": lock["builderVersion"], "executable": _file(builder),
                        "sdkMarker": _file(marker_path), "sdkArchiveSha256": marker["archiveSha256"],
                        "sdkFilesCount": len(sdk_files), "sdkFilesDigest": _canonical(sdk_files)},
            "toolLockSha256": lock_sha, "toolPayloadDigest": _canonical(stable_toolset),
            "environment": _environment(environment, root / "engine")}


def _race_summary(data: bytes) -> dict:
    starts, completed, test_passes, non_json = set(), {}, 0, 0
    for line in data.decode("utf-8", errors="strict").splitlines():
        if not line.strip(): continue
        try:
            event = json.loads(line)
        except json.JSONDecodeError:
            non_json += 1
            continue
        if not isinstance(event, dict):
            raise ValueError("Invalid Go JSON event")
        action, package = event.get("Action"), event.get("Package")
        if action in {"fail", "build-fail"}:
            raise ValueError("Go race evidence contains a failure event")
        if action == "start" and package: starts.add(package)
        if event.get("Test"):
            if action == "pass": test_passes += 1
        elif action in {"pass", "skip"} and package:
            if package in completed: raise ValueError("Repeated Go package terminal event")
            completed[package] = action
        if (not event.get("Test") and action == "output"
                and str(event.get("Output", "")).lstrip().startswith("ok ")
                and "(cached)" in str(event.get("Output", ""))):
            raise ValueError("Go release evidence unexpectedly contains cached packages")
    if not starts or starts != set(completed) or not test_passes or not any(v == "pass" for v in completed.values()):
        raise ValueError("Go race evidence is incomplete or contains no passing tests")
    return {"packages": dict(sorted(completed.items())), "testPassEvents": test_passes,
            "nonJsonLines": non_json, "cachedPackages": 0}


def _attempt(root: Path, attempt: Path) -> Path:
    root, attempt = Path(root).resolve(), Path(attempt).resolve()
    if not attempt.is_relative_to(root / "build") or not attempt.is_dir():
        raise ValueError("Go release evidence must use an existing owned build attempt directory")
    return attempt


def _logs(attempt: Path) -> tuple[list[dict], dict, dict]:
    facts, data = [], {}
    for name in ("go-race.jsonl", "go-vet.log"):
        path = attempt / name
        fact = _file(path)
        content = path.read_bytes()
        if len(content) != fact["bytes"] or _sha(content) != fact["sha256"]:
            raise ValueError("Go evidence log changed while reading")
        facts.append({"path": name, **fact})
        data[name] = content
    return facts, _race_summary(data["go-race.jsonl"]), data


def _commands(builder: Path) -> list:
    return [{"argv": [str(Path(builder).resolve()), *args], "cwd": "engine", "exitCode": 0}
            for args in (RACE_ARGS, VET_ARGS)]


def _write(attempt: Path, record: dict) -> dict:
    with (attempt / "go-checks.json").open("x", encoding="utf-8") as output:
        json.dump(record, output, ensure_ascii=True, indent=2)
        output.write("\n")
    return record


def record_success(root: Path, builder: Path, environment: dict, prepared: dict,
                   before: dict, attempt: Path, source_manifest: dict,
                   *, race_exit_code: int, vet_exit_code: int) -> dict:
    """Called by the driver after both commands succeeded; no commands run here."""
    if type(race_exit_code) is not int or type(vet_exit_code) is not int or race_exit_code != 0 or vet_exit_code != 0:
        raise ValueError("Both Go release commands must have exited zero")
    attempt = _attempt(root, attempt)
    after = fingerprint(root, builder, environment, prepared)
    if before != after:
        raise ValueError("Go release inputs, authenticated tools or environment changed during checks")
    logs, summary, unused = _logs(attempt)
    return _write(attempt, {"schema": SCHEMA, "kind": "fresh", "createdAtUtc": datetime.now(timezone.utc).isoformat(),
                            "sourceDigest": source_manifest["digest"], "fingerprint": after,
                            "commands": _commands(builder), "logs": logs, "raceSummary": summary,
                            "authority": "Fresh driver captured fingerprint before both zero-exit commands and checked it afterward"})


def reuse_evidence(path: Path, root: Path, builder: Path, environment: dict, prepared: dict,
                   attempt: Path, source_manifest: dict) -> dict:
    """Read/validate a fresh proof, copy its exact logs, and label this reuse."""
    path = Path(path).resolve()
    evidence_fact = _file(path)
    evidence = json.loads(path.read_text(encoding="utf-8"))
    if evidence.get("schema") != SCHEMA or evidence.get("kind") != "fresh":
        raise ValueError("Only a fresh fingerprinted Go execution proof may be reused; historical observations are insufficient")
    current = fingerprint(root, builder, environment, prepared)
    if current != evidence.get("fingerprint") or evidence.get("commands") != _commands(builder):
        raise ValueError("Go evidence does not match current inputs, builder, host, environment or commands")
    logs, summary, content = _logs(path.parent)
    if evidence.get("logs") != logs or evidence.get("raceSummary") != summary:
        raise ValueError("Go evidence logs or summary changed")
    if _file(path) != evidence_fact or fingerprint(root, builder, environment, prepared) != current:
        raise ValueError("Go evidence or release inputs changed during reuse verification")
    attempt = _attempt(root, attempt)
    for name in content:
        if (attempt / name).exists(): raise ValueError("Go evidence destination already contains logs")
    if (attempt / "go-checks.json").exists(): raise ValueError("Go evidence destination already contains a record")
    for name, data in content.items():
        with (attempt / name).open("xb") as output: output.write(data)
    return _write(attempt, {"schema": SCHEMA, "kind": "reused", "createdAtUtc": datetime.now(timezone.utc).isoformat(),
                            "sourceDigest": source_manifest["digest"], "fingerprint": current,
                            "origin": {"path": str(path), **evidence_fact, "sourceDigest": evidence["sourceDigest"]},
                            "commandsNotExecuted": _commands(builder), "logs": logs, "raceSummary": summary,
                            "authority": "Existing fresh Go commands/logs reused after exact source, full tool SDK, host and environment validation; no new Go execution"})
