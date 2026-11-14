#!/usr/bin/env python3
"""Owned C11 fixture preparation only. Nothing runs until an explicit subcommand.

This script never launches the IDE, changes its profile, or copies an SDK.
The root agent owns native screenshots, UI observations and actual Run/Test.
"""
import argparse
import datetime
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess

PLAN = Path(__file__).resolve().parent
BASE = PLAN / "native-c11-fixtures"
REPO = next(parent for parent in PLAN.parents if (parent / "settings.gradle.kts").is_file())
PUBLIC = REPO / "docs/reference/initial-absent-dependency-20261114-evidence/fixture"
CALLER = ("go.mod", "main.go", "main_test.go")


def stamp():
    return datetime.datetime.now(datetime.timezone.utc).isoformat()


def hashes(app):
    return {name: hashlib.sha256((app / name).read_bytes()).hexdigest() for name in CALLER}


def identity(path):
    if not path.exists():
        return None
    item = path.stat(follow_symlinks=False)
    return {"device": item.st_dev, "inode": item.st_ino, "mtimeNs": item.st_mtime_ns}


def write(path, text):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(text.encode("utf-8"))


def save(state, event):
    (BASE / "state.json").write_text(json.dumps(state, ensure_ascii=False, indent=2) + "\n")
    with (BASE / "operations.jsonl").open("a") as stream:
        stream.write(json.dumps({"atUtc": stamp(), **event}, ensure_ascii=False) + "\n")


def prepare():
    if BASE.exists():
        raise RuntimeError(f"Refusing to overwrite a fixture: {BASE}")
    BASE.mkdir(parents=True)
    state = {"createdAtUtc": stamp(), "flows": {}}
    for name in ("atomic", "slow", "steady"):
        app = BASE / name / "app"
        app.mkdir(parents=True)
        for leaf in CALLER:
            shutil.copyfile(PUBLIC / "app" / leaf, app / leaf)
        if name != "atomic":
            target = "deep/a/b" if name == "slow" else "fresh/a/b"
            imports = ' "fmt"\n dep "example.invalid/accept/dep/' + target + '"\n'
            prefix = ""
            if name == "steady":
                imports += ' anchor "example.invalid/accept/dep"\n'
                prefix = "anchor.Anchor() + "
            write(app / "main.go", "package main\n\nimport (\n" + imports + ")\n\n" +
                  "func Compose() string { return " + prefix + 'dep.Message() + "|中文😀" }\n\n' +
                  "func main() { fmt.Println(Compose()) }\n")
        if name == "steady":
            dep = BASE / name / "dep"
            dep.mkdir()
            shutil.copyfile(PUBLIC / "retained-created-dep/go.mod", dep / "go.mod")
            write(dep / "anchor.go", 'package dep\n\nfunc Anchor() string { return "" }\n')
        state["flows"][name] = {"phase": "missing", "callerSha256": hashes(app), "processBaseline": None}
    save(state, {"operation": "prepare", "base": str(BASE), "atomicCallerOriginalBytes": True})
    print(json.dumps({name: str(BASE / name / "app") for name in state["flows"]}, ensure_ascii=False, indent=2))


def checked_state(flow):
    state = json.loads((BASE / "state.json").read_text())
    if hashes(BASE / flow / "app") != state["flows"][flow]["callerSha256"]:
        raise RuntimeError("Caller bytes changed; do not continue or silently restore them")
    return state, state["flows"][flow]


def advance(flow, phase):
    state, item = checked_state(flow)
    allowed = {"atomic": {"greeting": "missing", "message": "greeting"},
               "slow": {"directory": "missing", "manifest": "directory", "greeting": "manifest", "message": "greeting"},
               "steady": {"greeting": "missing", "message": "greeting"}}
    if allowed.get(flow, {}).get(phase) != item["phase"]:
        raise RuntimeError(f"Invalid transition: {flow}/{item['phase']} -> {phase}")
    dep = BASE / flow / "dep"
    before = identity(dep)
    if flow == "atomic" and phase == "greeting":
        staging = dep.with_name("prepared-greeting")
        staging.mkdir()
        shutil.copyfile(PUBLIC / "retained-created-dep/go.mod", staging / "go.mod")
        original = (PUBLIC / "retained-created-dep/dep.go").read_text()
        write(staging / "dep.go", original.replace("func Message()", "func Greeting()"))
        os.rename(staging, dep)
    elif phase == "directory":
        dep.mkdir()
    elif phase == "manifest":
        shutil.copyfile(PUBLIC / "retained-created-dep/go.mod", dep / "go.mod")
    else:
        leaf = dep / ("dep.go" if flow == "atomic" else ("deep/a/b/leaf.go" if flow == "slow" else "fresh/a/b/leaf.go"))
        package = "dep" if flow == "atomic" else "leaf"
        function = "Greeting" if phase == "greeting" else "Message"
        write(leaf, f'package {package}\n\nfunc {function}() string {{ return "依赖-v1" }}\n')
    after = identity(dep)
    if before is not None and (before["device"], before["inode"]) != (after["device"], after["inode"]):
        raise RuntimeError("A supposedly stable dependency root changed identity")
    if hashes(BASE / flow / "app") != item["callerSha256"]:
        raise RuntimeError("Caller bytes changed during the fixture operation")
    item["phase"] = phase
    save(state, {"operation": "advance", "flow": flow, "phase": phase,
                 "rootBefore": before, "rootAfter": after, "callerSha256": item["callerSha256"]})
    print(json.dumps({"flow": flow, "phase": phase, "rootIdentity": after}, ensure_ascii=False))


def control(flow, go):
    """Run CLI in a tiny source clone, never in the live native fixture/profile."""
    state, item = checked_state(flow)
    go = Path(go).resolve(strict=True)
    goroot = go.parent.parent
    if not (goroot / "src/runtime").is_dir():
        raise RuntimeError("Supply the real matching bundled Go binary, not a wrapper")
    label = datetime.datetime.now(datetime.timezone.utc).strftime("%H%M%S%f")
    target = BASE / "cli-controls" / flow / (item["phase"] + "-" + label)
    app = target / "app"
    app.mkdir(parents=True)
    for name in CALLER:
        shutil.copyfile(BASE / flow / "app" / name, app / name)
    source_dep = BASE / flow / "dep"
    if source_dep.exists():
        (target / "dep").mkdir()
        # Only owned, deliberately created source files; no .idea/cache/profile.
        for source in source_dep.rglob("*"):
            if source.is_symlink():
                raise RuntimeError("Fixture source unexpectedly contains a symlink")
            if source.is_file() and (source.name == "go.mod" or source.suffix == ".go"):
                dest = target / "dep" / source.relative_to(source_dep)
                dest.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(source, dest)
    cache = REPO / ".tools/go-tools-cache"
    temp = BASE / "cli-only-tmp"
    temp.mkdir(parents=True, exist_ok=True)
    environment = {key: value for key, value in os.environ.items() if key in ("HOME", "USER", "LOGNAME", "LANG", "LC_ALL", "SystemRoot")}
    environment.update({"PATH": str(go.parent) + os.pathsep + "/usr/bin:/bin", "GOROOT": str(goroot),
                        "GOENV": "off", "GOTOOLCHAIN": "local", "GOFLAGS": "", "GOWORK": "off",
                        "GOPROXY": "off", "GOSUMDB": "off", "GOVCS": "*:off", "GOTELEMETRY": "off", "CGO_ENABLED": "0",
                        "GOCACHE": str(cache / "build-cache"), "GOMODCACHE": str(cache / "modules"),
                        "GOPATH": str(BASE / "cli-only-gopath"), "TMPDIR": str(temp)})
    results = []
    for args in (("run", "-mod=readonly", "."), ("test", "-mod=readonly", "-count=1", ".")):
        command = [str(go), *args]
        completed = subprocess.run(command, cwd=app, env=environment, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=90)
        tag = args[0]
        (target / (tag + ".stdout")).write_bytes(completed.stdout)
        (target / (tag + ".stderr")).write_bytes(completed.stderr)
        results.append({"command": command, "cwd": str(app), "exitCode": completed.returncode,
                        "stdoutSha256": hashlib.sha256(completed.stdout).hexdigest(), "stderrSha256": hashlib.sha256(completed.stderr).hexdigest()})
    checked_state(flow)
    record = {"atUtc": stamp(), "flow": flow, "phase": item["phase"], "cliOnly": True,
              "nativeRunOrTest": False, "environment": environment, "results": results}
    (target / "receipt.json").write_text(json.dumps(record, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps(record, ensure_ascii=False, indent=2))


def sample(flow, app_pid, gopls_pids):
    state, item = checked_state(flow)
    pids = sorted(set([app_pid, *gopls_pids]))
    raw = subprocess.check_output(["/bin/ps", "-p", ",".join(map(str, pids)), "-o", "pid=,ppid=,lstart=,command="]).decode()
    processes = {}
    for line in raw.splitlines():
        fields = line.split(None, 7)
        processes[int(fields[0])] = {"ppid": int(fields[1]), "started": " ".join(fields[2:7]), "command": fields[7]}
    if set(processes) != set(pids):
        raise RuntimeError("A sampled owned process disappeared")
    identities = {str(pid): processes[pid] for pid in pids}
    if item["processBaseline"] is not None and item["processBaseline"] != identities:
        raise RuntimeError("App/gopls process identity changed; this is not a same-process recovery pass")
    item["processBaseline"] = identities
    save(state, {"operation": "sample", "flow": flow, "phase": item["phase"], "processes": identities,
                 "rootIdentity": identity(BASE / flow / "dep"), "callerSha256": item["callerSha256"]})
    print(raw)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    commands = parser.add_subparsers(dest="command", required=True)
    commands.add_parser("prepare")
    for action in ("advance", "control", "sample"):
        child = commands.add_parser(action)
        child.add_argument("flow", choices=("atomic", "slow", "steady"))
        if action == "advance": child.add_argument("phase", choices=("directory", "manifest", "greeting", "message"))
        if action == "control": child.add_argument("--go", required=True)
        if action == "sample":
            child.add_argument("--app-pid", type=int, required=True)
            child.add_argument("--gopls-pid", type=int, action="append", required=True)
    args = parser.parse_args()
    if args.command == "prepare": prepare()
    elif args.command == "advance": advance(args.flow, args.phase)
    elif args.command == "control": control(args.flow, args.go)
    elif args.command == "sample": sample(args.flow, args.app_pid, args.gopls_pid)
