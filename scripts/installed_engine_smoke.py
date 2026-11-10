#!/usr/bin/env python3
"""Run an installed engine and its explicit bundled Go SDK through real stdio RPC.

The same probe is used by both executable installer lanes. It verifies engine
and SDK execution; it does not prove the Kotlin default resolver or interactive
editor behavior. Only the subprocess/session started here is terminated.
"""

import json
import os
from pathlib import Path
import queue
import signal
import subprocess
import threading
import time
from go_tool_receipts import validate_bundle


def process_options():
    if os.name == "nt":
        return {"creationflags": subprocess.CREATE_NEW_PROCESS_GROUP}
    return {"start_new_session": True}


def _stop_owned(process):
    # EOF lets the real engine cancel its workers and close its manager first.
    if process.stdin is not None and not process.stdin.closed:
        try:
            process.stdin.close()
        except OSError:
            pass
    if process.poll() is not None:
        return
    try:
        process.wait(timeout=2)
        return
    except subprocess.TimeoutExpired:
        pass
    if os.name == "nt":
        subprocess.run(["taskkill.exe", "/PID", str(process.pid), "/T", "/F"], capture_output=True, timeout=20)
        process.wait(timeout=20)
        return
    # start_new_session made this process the owner of this unique process group.
    try:
        os.killpg(process.pid, signal.SIGTERM)
    except ProcessLookupError:
        pass
    try:
        process.wait(timeout=5)
    except subprocess.TimeoutExpired:
        try:
            os.killpg(process.pid, signal.SIGKILL)
        except ProcessLookupError:
            pass
        process.wait(timeout=20)


def engine_work(engine, go, env, workspace, output):
    """Create a new scratch project and retain wire/stderr under output."""
    receipt_checks = validate_bundle(Path(go).parent.parent.parent, Path(__file__).resolve().parent.parent)
    output.mkdir(parents=True, exist_ok=True)
    workspace.mkdir(parents=True)
    (workspace / "go.mod").write_text("module example.org/installedsmoke\n\ngo 1.26.0\n", encoding="utf-8")
    (workspace / "sample_test.go").write_text('package installedsmoke\nimport "testing"\nfunc TestBundledInstalledRuntime(t *testing.T) { if "前😀" == "" {t.Fatal("Unicode")} }\n', encoding="utf-8")
    process = subprocess.Popen([str(engine)], stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                               stderr=subprocess.PIPE, env=env, cwd=workspace,
                               **process_options())
    messages = queue.Queue()
    transcript = []
    errors = []

    def read():
        try:
            while True:
                headers = {}
                while True:
                    line = process.stdout.readline()
                    if not line:
                        messages.put(EOFError("Engine transport closed"))
                        return
                    if line in (b"\r\n", b"\n"):
                        break
                    key, value = line.decode("ascii").strip().split(":", 1)
                    headers[key.lower()] = value.strip()
                size = int(headers["content-length"])
                if not 0 < size <= 16 * 1024 * 1024:
                    raise ValueError("Invalid engine frame length")
                data = bytearray()
                while len(data) < size:
                    chunk = process.stdout.read(size - len(data))
                    if not chunk:
                        raise EOFError("Truncated engine frame")
                    data.extend(chunk)
                message = json.loads(data)
                transcript.append({"direction": "receive", "message": message})
                messages.put(message)
        except Exception as error:
            messages.put(error)

    reader = threading.Thread(target=read, daemon=True)
    reader.start()
    stderr_reader = threading.Thread(target=lambda: errors.append(process.stderr.read().decode("utf-8", errors="replace")), daemon=True)
    stderr_reader.start()

    def send(identifier, method, params):
        message = {"jsonrpc": "2.0", "id": identifier, "method": method, "params": params}
        data = json.dumps(message, ensure_ascii=False).encode("utf-8")
        transcript.append({"direction": "send", "message": message})
        process.stdin.write(("Content-Length: " + str(len(data)) + "\r\n\r\n").encode("ascii") + data)
        process.stdin.flush()

    def receive_until(predicate, timeout=120):
        deadline = time.monotonic() + timeout
        while True:
            try:
                message = messages.get(timeout=max(0.01, deadline - time.monotonic()))
            except queue.Empty as error:
                raise TimeoutError("Engine request timed out") from error
            if isinstance(message, Exception):
                raise message
            if predicate(message):
                if "error" in message:
                    raise AssertionError(message)
                return message
            if time.monotonic() >= deadline:
                raise TimeoutError("Engine request timed out")

    try:
        send(1, "initialize", {"root": str(workspace), "goExecutable": str(go)})
        initialized = receive_until(lambda message: message.get("id") == 1)["result"]
        if initialized["protocolVersion"] != 1 or not initialized["features"].get("taskTargets"):
            raise AssertionError("Installed engine did not expose the expected protocol")
        send(2, "task/start", {"id": "installed-test", "command": "test", "args": ["-count=1", "./..."]})
        receive_until(lambda message: message.get("id") == 2)
        exited = receive_until(lambda message: message.get("method") == "task/exit")["params"]
        passed = [entry for entry in transcript if entry.get("message", {}).get("method") == "test/event"
                  and entry["message"]["params"].get("event", {}).get("Action") == "pass"
                  and entry["message"]["params"]["event"].get("Test") == "TestBundledInstalledRuntime"]
        if exited["code"] != 0 or not passed:
            raise AssertionError("Installed bundled Go test did not pass")
        send(3, "shutdown", {})
        receive_until(lambda message: message.get("id") == 3)
        process.wait(timeout=20)
        if process.returncode != 0:
            raise AssertionError("Engine did not shut down normally")
        return {"pid": process.pid, "exitCode": process.returncode, "test": "TestBundledInstalledRuntime", "testCount": 1, "cached": False,
                "bundledToolReceiptChecks": receipt_checks}
    finally:
        _stop_owned(process)
        reader.join(timeout=5)
        stderr_reader.join(timeout=5)
        (output / "engine-wire.json").write_text(json.dumps(transcript, indent=2, ensure_ascii=False), encoding="utf-8")
        (output / "engine-stderr.log").write_text("".join(errors), encoding="utf-8")
        if reader.is_alive() or stderr_reader.is_alive():
            raise RuntimeError("Owned engine pipe readers did not finish after process cleanup")
        process.stdout.close()
        process.stderr.close()
