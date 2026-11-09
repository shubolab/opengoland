#!/usr/bin/env python3
"""Exercise an actually installed Windows product with its bundled Go tools.

This checks native launcher startup, installed bytes and real engine/tool work.
It does not claim an interactive desktop acceptance pass or Kotlin default SDK
resolution: the protocol smoke supplies the installed bundled SDK explicitly.
"""

import argparse
import ctypes
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time


sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from installed_engine_smoke import engine_work


def sha(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def stop_owned(process):
    if process.poll() is None:
        subprocess.run(["taskkill.exe", "/PID", str(process.pid), "/T", "/F"], capture_output=True, timeout=20)
        process.wait(timeout=20)


def run(command, env, cwd, timeout=120):
    process = subprocess.Popen([str(value) for value in command], cwd=cwd, env=env,
                               stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                               creationflags=subprocess.CREATE_NEW_PROCESS_GROUP)
    try:
        output, error = process.communicate(timeout=timeout)
        result = {"argv": list(map(str, command)), "pid": process.pid, "code": process.returncode,
                  "stdout": output.decode("utf-8", errors="replace"), "stderr": error.decode("utf-8", errors="replace")}
        if process.returncode:
            raise AssertionError("Installed command failed: " + json.dumps(result, ensure_ascii=False))
        return result
    finally:
        stop_owned(process)


def owned_windows(pid):
    user32 = ctypes.WinDLL("user32", use_last_error=True)
    from ctypes import wintypes
    callback_type = ctypes.WINFUNCTYPE(wintypes.BOOL, wintypes.HWND, wintypes.LPARAM)
    user32.GetWindowThreadProcessId.argtypes = [wintypes.HWND, ctypes.POINTER(wintypes.DWORD)]
    user32.IsWindowVisible.argtypes = [wintypes.HWND]
    user32.GetWindowTextW.argtypes = [wintypes.HWND, wintypes.LPWSTR, ctypes.c_int]
    user32.GetClassNameW.argtypes = [wintypes.HWND, wintypes.LPWSTR, ctypes.c_int]
    result = []

    @callback_type
    def collect(handle, _):
        owner = wintypes.DWORD()
        user32.GetWindowThreadProcessId(handle, ctypes.byref(owner))
        if owner.value == pid and user32.IsWindowVisible(handle):
            title, name = ctypes.create_unicode_buffer(4096), ctypes.create_unicode_buffer(256)
            user32.GetWindowTextW(handle, title, len(title))
            user32.GetClassNameW(handle, name, len(name))
            result.append({"handle": int(handle), "title": title.value, "class": name.value})
        return True

    user32.EnumWindows.argtypes = [callback_type, wintypes.LPARAM]
    if not user32.EnumWindows(collect, 0):
        raise ctypes.WinError(ctypes.get_last_error())
    return result


def gui_startup(launcher, env, workspace, output):
    """Reach the actual project frame, then use the platform exit command."""
    stdout = (output / "native-gui-stdout.log").open("wb")
    stderr = (output / "native-gui-stderr.log").open("wb")
    process = subprocess.Popen([str(launcher), str(workspace)], env=env, cwd=output,
                               stdout=stdout, stderr=stderr,
                               creationflags=subprocess.CREATE_NEW_PROCESS_GROUP)
    windows = []
    try:
        deadline = time.monotonic() + 180
        while time.monotonic() < deadline:
            if process.poll() is not None:
                raise AssertionError("The installed native GUI exited before its project frame: " + str(process.returncode))
            windows = owned_windows(process.pid)
            logs = "\n".join(path.read_text(encoding="utf-8", errors="replace") for path in (output / "log").glob("idea*.log"))
            if re.search(r"\b(?:ERROR|SEVERE)\b", logs):
                raise AssertionError("Native GUI startup logged ERROR/SEVERE")
            frame = next((window for window in windows if workspace.name in window["title"] and window["class"] == "SunAwtFrame"), None)
            if frame is not None and "IDE STARTED" in logs and "Loaded custom plugins:" in logs and "Go Workbench" in logs and "LSP4IJ" in logs:
                # A visible license, consent or import dialog does not satisfy
                # this check. No agreement is accepted by this smoke script.
                time.sleep(3)
                if process.poll() is not None:
                    raise AssertionError("The installed project frame did not remain alive")
                exit_command = run([launcher, "exit"], env, output, timeout=45)
                process.wait(timeout=45)
                if process.returncode != 0:
                    raise AssertionError("Native GUI did not exit normally")
                return {"pid": process.pid, "projectFrame": frame, "exitCode": process.returncode,
                        "exitCommand": exit_command, "scope": "Actual visible owned project frame and plugin-loaded log; no interactive editor/menu parity claim."}
            time.sleep(0.25)
        raise TimeoutError("Installed native GUI did not reach its owned project frame; observed windows: " + repr(windows))
    finally:
        (output / "native-gui-windows.json").write_text(json.dumps(windows, indent=2, ensure_ascii=False), encoding="utf-8")
        stop_owned(process)
        stdout.close()
        stderr.close()



def smoke(installed, output):
    if os.name != "nt":
        raise ValueError("The installed Windows smoke must run on Windows")
    installed, output = installed.resolve(), output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    inventory = json.loads((installed / "openidea-installation-files.json").read_text(encoding="utf-8"))
    for relative, expected in inventory["files"].items():
        path = installed / relative
        if not path.resolve().is_relative_to(installed) or not path.is_file() or path.is_symlink():
            raise ValueError("Missing/unsafe installed file: " + relative)
        if path.stat().st_size != expected["bytes"] or sha(path) != expected["sha256"]:
            raise ValueError("Installed bytes differ from the sealed product: " + relative)
    manifest = json.loads((installed / "openidea-product.json").read_text(encoding="utf-8"))
    go_plugins = [directory for directory in manifest["pluginLoading"]["directories"]
                  if (installed / directory / "bin/opengoland-engine.exe").is_file()]
    if len(go_plugins) != 1:
        raise ValueError("Installed engine plugin is ambiguous")
    plugin = installed / go_plugins[0]
    tools = plugin / "tools"
    go = tools / "go/bin/go.exe"
    env = dict(os.environ)
    for key in ("CC", "CXX", "CGO_ENABLED", "GCCGO", "GOFLAGS", "GOTOOLDIR"):
        env.pop(key, None)
    # No developer Go/gopls/dlv is used. System32 remains for owned taskkill and
    # standard Windows utilities; the actual tool commands below are absolute.
    env["PATH"] = os.pathsep.join([str(tools / "go/bin"), str(tools / "bin"), str(Path(env["SYSTEMROOT"]) / "System32"), env["SYSTEMROOT"]])
    env.update(GOROOT=str(tools / "go"), GOTOOLCHAIN="local", GOWORK="off", GOENV="off")
    env["GOCACHE"] = str(output / "go-cache")
    env["GOMODCACHE"] = str(output / "go-mod-cache")
    versions = [run([go, "version"], env, output), run([tools / "bin/gopls.exe", "version"], env, output), run([tools / "bin/dlv.exe", "version"], env, output)]
    for result, version in zip(versions, ["go1.26.10", "v0.24.0", "1.27.2"], strict=True):
        if version not in result["stdout"] + result["stderr"]:
            raise AssertionError("Bundled tool version mismatch")
    properties = output / "isolated.properties"
    properties.write_text("\n".join("idea." + key + ".path=" + (output / key).as_posix()
                                     for key in ["config", "system", "plugins", "log"]) + "\n", encoding="utf-8")
    metadata = json.loads((installed / "product-info.json").read_text(encoding="utf-8"))
    env[metadata.get("envVarBaseName", "IDEA") + "_PROPERTIES"] = str(properties)
    # This flag trusts only the CI-owned fixture in this isolated process. It
    # is not written into the installed product or a user's configuration.
    gui_options = output / "smoke.vmoptions"
    gui_options.write_text((installed / "bin/opengoland64.exe.vmoptions").read_text(encoding="utf-8") +
                           "\n-Didea.trust.all.projects=true\n-Didea.initially.ask.config=false\n", encoding="utf-8")
    env[metadata.get("envVarBaseName", "IDEA") + "_VM_OPTIONS"] = str(gui_options)
    # Public PlatformExtensions.xml registers -version with ShowProductVersion.
    # This starts the real native launcher/JVM; it is not a GUI acceptance claim.
    launcher = run([installed / "bin/opengoland64.exe", "-version"], env, output, timeout=120)
    (output / "native-version.json").write_text(json.dumps(launcher, indent=2), encoding="utf-8")
    if not re.search(r"2026\.1", launcher["stdout"] + launcher["stderr"]):
        raise AssertionError("The real native launcher did not report the platform version")
    result = engine_work(plugin / "bin/opengoland-engine.exe", go, env, output / "工程 空格 😀", output)
    gui = gui_startup(installed / "bin/opengoland64.exe", env, output / "工程 空格 😀", output)
    severe = []
    for path in (output / "log").glob("idea*.log"):
        severe.extend(line for line in path.read_text(encoding="utf-8", errors="replace").splitlines()
                      if re.search(r"\b(?:ERROR|SEVERE)\b", line))
    if severe:
        raise AssertionError("Native startup logged errors: " + "\n".join(severe))
    return {"status": "pass", "version": manifest["version"], "installedFilesVerified": len(inventory["files"]),
            "nativeVersion": launcher, "nativeProjectStartup": gui, "toolVersions": versions, "engine": result,
            "scope": "Real installer bytes, native version and visible project-frame startup, explicit installed bundled tools and one uncached Go test via engine RPC; not interactive editor parity or Kotlin default-resolver acceptance."}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--installed", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    report = smoke(args.installed, args.output)
    (args.output / "smoke.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
    print(json.dumps(report, indent=2))
