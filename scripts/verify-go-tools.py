#!/usr/bin/env python3
"""Exercise real gopls and Delve against a disposable Go workspace.

This is a tool-protocol baseline. It does not prove native IDE integration.
Only Python's standard library and the installed Go tools are used.
"""

from __future__ import annotations

import argparse
import collections
import contextlib
import hashlib
import json
import os
import pathlib
import queue
import shutil
import signal
import socket
import subprocess
import sys
import tempfile
import threading
import time
import urllib.parse


REPO = pathlib.Path(__file__).resolve().parents[1]
MAX_BODY = 16 * 1024 * 1024


class ProbeFailure(RuntimeError):
    pass


def require(condition, message):
    if not condition:
        raise ProbeFailure(message)


def command(args, cwd=None, timeout=45):
    env = dict(os.environ, GOTOOLCHAIN="local", GOWORK="")
    result = subprocess.run(args, cwd=cwd, env=env, text=True,
                            stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                            timeout=timeout)
    require(result.returncode == 0,
            f"command failed ({result.returncode}): {' '.join(map(str, args))}\n{result.stderr[-4000:]}")
    return result.stdout.strip()


def source_digests(root):
    return {str(path.relative_to(root)): hashlib.sha256(path.read_bytes()).hexdigest()
            for path in sorted(root.rglob("*")) if path.is_file()}


def read_frame(stream):
    headers = {}
    size = 0
    while True:
        line = stream.readline(8193)
        if not line:
            raise EOFError("protocol stream closed")
        size += len(line)
        require(size <= 8192, "protocol headers exceed limit")
        if line in (b"\r\n", b"\n"):
            break
        key, sep, value = line.partition(b":")
        require(bool(sep), "invalid protocol header")
        key = key.strip().lower()
        require(key not in headers, "duplicate protocol header")
        headers[key] = value.strip()
    length = int(headers.get(b"content-length", b"-1"))
    require(0 <= length <= MAX_BODY, "invalid protocol length")
    body = bytearray()
    while len(body) < length:
        part = stream.read(length - len(body))
        if not part:
            raise EOFError("truncated protocol body")
        body.extend(part)
    return json.loads(body)


class Peer:
    def __init__(self, reader, writer, kind="lsp", handler=None):
        self.reader, self.writer, self.kind = reader, writer, kind
        self.handler = handler
        self.condition = threading.Condition()
        self.write_lock = threading.Lock()
        self.responses = {}
        self.events = []
        self.sequence = 0
        self.failure = None
        self.closed = False
        self.thread = threading.Thread(target=self._read, daemon=True)
        self.thread.start()

    def _send(self, body):
        payload = json.dumps(body, ensure_ascii=False, separators=(",", ":")).encode()
        with self.write_lock:
            self.writer.write(f"Content-Length: {len(payload)}\r\n\r\n".encode() + payload)
            self.writer.flush()

    def _read(self):
        try:
            while True:
                body = read_frame(self.reader)
                if self.kind == "lsp" and "method" in body and "id" in body:
                    try:
                        result = self.handler(body["method"], body.get("params", {})) if self.handler else None
                        self._send({"jsonrpc": "2.0", "id": body["id"], "result": result})
                    except Exception as error:
                        self._send({"jsonrpc": "2.0", "id": body["id"],
                                    "error": {"code": -32603, "message": str(error)}})
                    continue
                with self.condition:
                    response_id = body.get("id") if self.kind == "lsp" else body.get("request_seq")
                    is_response = "method" not in body and "id" in body if self.kind == "lsp" else body.get("type") == "response"
                    if is_response:
                        self.responses[response_id] = body
                    else:
                        self.events.append(body)
                    self.condition.notify_all()
        except Exception as error:
            with self.condition:
                self.failure = error
                self.closed = True
                self.condition.notify_all()

    def send_request(self, method, params=None):
        with self.condition:
            self.sequence += 1
            seq = self.sequence
        if self.kind == "lsp":
            self._send({"jsonrpc": "2.0", "id": seq, "method": method, "params": params or {}})
        else:
            self._send({"seq": seq, "type": "request", "command": method, "arguments": params or {}})
        return seq

    def response(self, seq, timeout=45):
        deadline = time.monotonic() + timeout
        with self.condition:
            while seq not in self.responses:
                if self.closed:
                    raise ProbeFailure(f"protocol closed waiting for request {seq}: {self.failure}")
                left = deadline - time.monotonic()
                require(left > 0, f"timed out waiting for request {seq}")
                self.condition.wait(left)
            message = self.responses.pop(seq)
        require("error" not in message, f"protocol request {seq}: {message.get('error')}")
        if self.kind == "dap":
            require(message.get("success"), f"DAP {message.get('command')}: {message.get('message')}")
            return message.get("body", {})
        return message.get("result")

    def request(self, method, params=None, timeout=45):
        return self.response(self.send_request(method, params), timeout)

    def notify(self, method, params=None):
        self._send({"jsonrpc": "2.0", "method": method, "params": params or {}})

    def mark(self):
        with self.condition:
            return len(self.events)

    def event(self, predicate, after=0, timeout=45):
        deadline = time.monotonic() + timeout
        with self.condition:
            while True:
                for index, body in enumerate(self.events[after:], after):
                    if predicate(body):
                        return index, body
                if self.closed:
                    raise ProbeFailure(f"protocol closed while waiting for event: {self.failure}")
                left = deadline - time.monotonic()
                require(left > 0, "timed out waiting for protocol event")
                self.condition.wait(left)


def collect_lines(stream, storage, events=None):
    try:
        for raw in iter(stream.readline, b""):
            line = raw.decode("utf-8", "replace").rstrip()
            storage.append(line)
            if events is not None:
                events.put(line)
    except (OSError, ValueError):
        pass


def stop_owned_process(process):
    if process is None or process.poll() is not None:
        return
    try:
        if os.name != "nt":
            os.killpg(process.pid, signal.SIGTERM)
        else:
            process.terminate()
        process.wait(timeout=4)
    except (ProcessLookupError, subprocess.TimeoutExpired):
        if process.poll() is None:
            if os.name != "nt":
                os.killpg(process.pid, signal.SIGKILL)
            else:
                process.kill()
            process.wait(timeout=4)


def position(text, needle, within=0):
    offset = text.index(needle) + within
    prefix = text[:offset]
    return {"line": prefix.count("\n"),
            "character": len(prefix.rsplit("\n", 1)[-1].encode("utf-16-le")) // 2}


def offset_of(text, pos):
    lines = text.splitlines(keepends=True)
    line = pos["line"]
    require(0 <= line <= len(lines), "edit line outside document")
    if line == len(lines):
        require(pos["character"] == 0, "edit character beyond document end")
        return len(text)
    encoded = lines[line].encode("utf-16-le")
    length = pos["character"] * 2
    require(0 <= length <= len(encoded), "UTF-16 edit position outside line")
    prefix = encoded[:length].decode("utf-16-le")
    return sum(map(len, lines[:line])) + len(prefix)


def apply_text_edits(text, edits):
    replacements = [(offset_of(text, edit["range"]["start"]),
                     offset_of(text, edit["range"]["end"]), edit["newText"])
                    for edit in edits]
    replacements.sort(reverse=True)
    previous = len(text) + 1
    for start, end, replacement in replacements:
        require(start <= end <= previous, "overlapping or reversed text edits")
        text = text[:start] + replacement + text[end:]
        previous = start
    return text


def workspace_changes(edit):
    changes = dict(edit.get("changes", {}))
    for change in edit.get("documentChanges", []):
        require("textDocument" in change, "unexpected resource operation in workspace edit")
        uri = change["textDocument"]["uri"]
        changes.setdefault(uri, []).extend(change["edits"])
    return changes


class Documents:
    def __init__(self, root):
        self.root = root.resolve()
        self.texts, self.versions, self.opened = {}, {}, set()
        self.peer = None
        self.lock = threading.RLock()

    def path(self, uri):
        parsed = urllib.parse.urlparse(uri)
        require(parsed.scheme == "file", "non-file workspace edit")
        path = pathlib.Path(urllib.parse.unquote(parsed.path)).resolve()
        require(path.is_relative_to(self.root), "workspace edit escaped disposable fixture")
        return path

    def get(self, uri):
        with self.lock:
            return self.texts.get(uri, self.path(uri).read_text())

    def open(self, path):
        uri = path.resolve().as_uri()
        with self.lock:
            text = path.read_text()
            self.texts[uri], self.versions[uri] = text, 1
            self.opened.add(uri)
        self.peer.notify("textDocument/didOpen", {"textDocument": {
            "uri": uri, "languageId": "go", "version": 1, "text": text}})
        return uri

    def change(self, uri, text):
        with self.lock:
            require(uri in self.opened, "changing unopened document")
            self.versions[uri] += 1
            version = self.versions[uri]
            self.texts[uri] = text
        self.peer.notify("textDocument/didChange", {
            "textDocument": {"uri": uri, "version": version},
            "contentChanges": [{"text": text}]})
        return version

    def apply(self, edit, save=False):
        changes = workspace_changes(edit)
        for uri, edits in changes.items():
            new = apply_text_edits(self.get(uri), edits)
            if uri in self.opened:
                self.change(uri, new)
            else:
                self.texts[uri] = new
            if save:
                self.path(uri).write_text(new)
        if save:
            self.peer.notify("workspace/didChangeWatchedFiles", {"changes": [
                {"uri": uri, "type": 2} for uri in changes]})
        return changes


class Checks:
    def __init__(self):
        self.items = []

    @contextlib.contextmanager
    def check(self, name):
        started = time.monotonic()
        detail = {}
        try:
            yield detail
        except Exception as error:
            self.items.append({"name": name, "status": "failed", "seconds": round(time.monotonic() - started, 3), "error": str(error)})
            raise
        else:
            self.items.append({"name": name, "status": "passed", "seconds": round(time.monotonic() - started, 3), **detail})


def verify_gopls(root, executable):
    checks = Checks()
    process, peer = None, None
    stderr = collections.deque(maxlen=150)
    result = {"status": "failed", "checks": checks.items}
    documents = Documents(root)
    workspace_folders = [{"uri": root.as_uri(), "name": "openidea-tool-acceptance"}]

    def server_request(method, params):
        if method == "workspace/configuration":
            return [{} for _ in params.get("items", [])]
        if method == "workspace/workspaceFolders":
            return workspace_folders
        if method == "workspace/applyEdit":
            documents.apply(params["edit"])
            return {"applied": True}
        if method in ("client/registerCapability", "client/unregisterCapability", "window/workDoneProgress/create"):
            return None
        raise ProbeFailure(f"unhandled gopls server request: {method}")

    try:
        process = subprocess.Popen([executable], cwd=root,
                                   env=dict(os.environ, GOTOOLCHAIN="local", GOWORK=""),
                                   stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                   start_new_session=os.name != "nt")
        threading.Thread(target=collect_lines, args=(process.stderr, stderr), daemon=True).start()
        peer = Peer(process.stdout, process.stdin, handler=server_request)
        documents.peer = peer
        with checks.check("initialize_utf16_workspace") as detail:
            initialized = peer.request("initialize", {
                "processId": os.getpid(), "rootUri": root.as_uri(),
                "workspaceFolders": workspace_folders,
                "capabilities": {
                    "general": {"positionEncodings": ["utf-16"]},
                    "workspace": {"configuration": True, "workspaceFolders": True, "applyEdit": True},
                    "textDocument": {
                        "publishDiagnostics": {"versionSupport": True},
                        "completion": {"completionItem": {"snippetSupport": True}},
                        "definition": {"linkSupport": True},
                        "codeAction": {"dataSupport": True, "resolveSupport": {"properties": ["edit"]},
                                       "codeActionLiteralSupport": {"codeActionKind": {"valueSet": ["source.organizeImports"]}}}}},
                "initializationOptions": {"diagnosticsDelay": "100ms"}})
            capabilities = initialized.get("capabilities", {})
            require(capabilities.get("positionEncoding", "utf-16") == "utf-16", "gopls did not select UTF-16")
            peer.notify("initialized")
            detail["positionEncoding"] = capabilities.get("positionEncoding", "utf-16")
            detail["workspaceFolders"] = len(workspace_folders)
        app = documents.open(root / "app/main.go")
        catalog = documents.open(root / "catalog/catalog.go")

        def at(uri, needle, within=0):
            return {"textDocument": {"uri": uri}, "position": position(documents.get(uri), needle, within)}

        with checks.check("completion_catalog_members") as detail:
            completion = peer.request("textDocument/completion", at(app, "catalog.Cart", len("catalog.")))
            items = completion.get("items", []) if isinstance(completion, dict) else completion or []
            labels = [item["label"] for item in items]
            require(any(label == "Cart" or label.startswith("Cart ") for label in labels), f"Cart completion missing: {labels}")
            detail["matchingLabels"] = [label for label in labels if "Cart" in label]
        with checks.check("completion_interface_and_generic_contexts") as detail:
            interface_completion = peer.request("textDocument/completion", at(app, "price.Total()", len("price.")))
            interface_items = interface_completion.get("items", []) if isinstance(interface_completion, dict) else interface_completion or []
            interface_labels = [item["label"] for item in interface_items]
            require(any(label == "Total" or label.startswith("Total ") for label in interface_labels),
                    f"interface method completion missing: {interface_labels}")
            generic_completion = peer.request("textDocument/completion", at(app, "catalog.Greeting", len("catalog.")))
            generic_items = generic_completion.get("items", []) if isinstance(generic_completion, dict) else generic_completion or []
            generic_labels = [item["label"] for item in generic_items]
            require(any(label == "Map" or label.startswith("Map[") for label in generic_labels),
                    f"generic function completion missing: {generic_labels}")
            detail.update({"interfaceMatches": [label for label in interface_labels if label.startswith("Total")],
                           "genericMatches": [label for label in generic_labels if label.startswith("Map")]})
        with checks.check("completion_incomplete_unsaved_document") as detail:
            original = documents.get(app)
            probe = original + "\nfunc IncompleteCompletionProbe() { catalog. }\n"
            version = documents.change(app, probe)
            pos = position(probe, "catalog. }", len("catalog."))
            completion = peer.request("textDocument/completion", {"textDocument": {"uri": app}, "position": pos})
            items = completion.get("items", []) if isinstance(completion, dict) else completion or []
            labels = [item["label"] for item in items]
            require(any(label == "Cart" or label.startswith("Cart ") for label in labels),
                    f"incomplete document completion missing Cart: {labels}")
            require("IncompleteCompletionProbe" not in documents.path(app).read_text(),
                    "incomplete completion fixture was accidentally saved")
            documents.change(app, original)
            detail.update({"documentVersion": version, "matchingLabels": [label for label in labels if "Cart" in label],
                           "diskWasUnchanged": True})
        with checks.check("hover_cart_type") as detail:
            hover = peer.request("textDocument/hover", at(app, "catalog.Cart", len("catalog.") + 1))
            require("Cart" in json.dumps(hover), "Cart hover content missing")
            detail["containsCart"] = True
        with checks.check("definition_cross_module_utf16_unsaved") as detail:
            probe = '\nfunc UTF16Probe() string { _ = "😀"; return catalog.Greeting("Go") }\n'
            documents.change(app, documents.get(app) + probe)
            pos = position(documents.get(app), 'catalog.Greeting("Go")', len("catalog.") + 1)
            definitions = peer.request("textDocument/definition", {"textDocument": {"uri": app}, "position": pos})
            definitions = definitions if isinstance(definitions, list) else [definitions]
            require(any(item and item.get("targetUri", item.get("uri")) == catalog for item in definitions), "UTF-16 cross-module definition did not resolve to catalog.go")
            line = documents.get(app).splitlines()[pos["line"]]
            require(len(line[:line.index("Greeting")].encode("utf-16-le")) // 2 != len(line[:line.index("Greeting")]), "UTF-16 fixture did not include non-BMP prefix")
            detail.update({"position": pos, "resolvedPath": "catalog/catalog.go", "unsaved": True})
        with checks.check("references_cross_module") as detail:
            refs = peer.request("textDocument/references", {
                **at(catalog, "type Cart", len("type ") + 1), "context": {"includeDeclaration": True}})
            uris = {item["uri"] for item in refs or []}
            require(app in uris and catalog in uris, f"references did not span modules: {uris}")
            detail["paths"] = sorted(str(documents.path(uri).relative_to(root)) for uri in uris)
        with checks.check("rename_workspace_edit_and_compile") as detail:
            edit = peer.request("textDocument/rename", {**at(catalog, "type Cart", len("type ") + 1), "newName": "Basket"})
            changes = workspace_changes(edit)
            require(app in changes and catalog in changes, "rename did not edit both modules")
            documents.apply(edit, save=True)
            require("type Basket struct" in documents.get(catalog) and "catalog.Basket" in documents.get(app), "rename edit did not produce expected source")
            outputs = {}
            for member in ("catalog", "app"):
                outputs[member] = command(["go", "test", "./..."], cwd=root / member)
            detail.update({"changedPaths": sorted(str(documents.path(uri).relative_to(root)) for uri in changes), "goTest": outputs})
        with checks.check("organize_imports_unsaved") as detail:
            text = documents.get(app).replace('import (\n', 'import (\n\t"strings"\n', 1)
            require(text != documents.get(app), "could not inject unused import")
            documents.change(app, text)
            actions = peer.request("textDocument/codeAction", {
                "textDocument": {"uri": app}, "range": {"start": {"line": 0, "character": 0}, "end": {"line": 0, "character": 0}},
                "context": {"diagnostics": [], "only": ["source.organizeImports"]}})
            require(bool(actions), "organize imports action missing")
            action = next((item for item in actions if item.get("kind") == "source.organizeImports"), actions[0])
            if not action.get("edit") and not action.get("command") and action.get("data"):
                action = peer.request("codeAction/resolve", action)
            if action.get("edit"):
                documents.apply(action["edit"])
            if action.get("command"):
                action_command = action["command"]
                if isinstance(action_command, str):
                    action_command = {"command": action_command, "arguments": action.get("arguments", [])}
                peer.request("workspace/executeCommand", action_command)
            require('"strings"' not in documents.get(app), "unused import survived organize imports")
            require('"example.com/openidea/catalog"' in documents.get(app), "organize imports removed needed cross-module import")
            detail["removedUnusedImport"] = "strings"
        with checks.check("format_unsaved_document") as detail:
            documents.change(app, documents.get(app) + "\nfunc Ugly( )int{return 1}\n")
            edits = peer.request("textDocument/formatting", {"textDocument": {"uri": app}, "options": {"tabSize": 4, "insertSpaces": False}})
            require(bool(edits), "formatting returned no edits for malformed spacing")
            documents.change(app, apply_text_edits(documents.get(app), edits))
            require("func Ugly() int { return 1 }" in documents.get(app), "formatting did not produce gofmt spacing")
            detail["editCount"] = len(edits)
        with checks.check("unsaved_diagnostics_version_invalidation") as detail:
            valid = documents.get(app)
            before = peer.mark()
            bad = documents.change(app, valid + "\nfunc InvalidOverlay() { undefinedOpenIDEAName() }\n")
            _, diagnostic = peer.event(lambda event: event.get("method") == "textDocument/publishDiagnostics"
                                      and event.get("params", {}).get("uri") == app
                                      and event["params"].get("version") == bad
                                      and "undefinedOpenIDEAName" in json.dumps(event["params"].get("diagnostics", [])), after=before)
            require("undefinedOpenIDEAName" not in documents.path(app).read_text(), "diagnostic fixture was accidentally saved to disk")
            after = peer.mark()
            good = documents.change(app, valid)
            _, cleared = peer.event(lambda event: event.get("method") == "textDocument/publishDiagnostics"
                                   and event.get("params", {}).get("uri") == app
                                   and event["params"].get("version") == good
                                   and "undefinedOpenIDEAName" not in json.dumps(event["params"].get("diagnostics", [])), after=after)
            require(not cleared["params"].get("diagnostics"), "fixed overlay still has diagnostics")
            detail.update({"badVersion": bad, "clearedVersion": good,
                           "badDiagnosticCount": len(diagnostic["params"]["diagnostics"]), "clearedDiagnosticCount": 0,
                           "diskWasUnchanged": True})
        result["status"] = "passed"
    except Exception as error:
        result["error"] = str(error)
    finally:
        if peer and process and process.poll() is None:
            try:
                peer.request("shutdown", timeout=8)
                peer.notify("exit")
                process.wait(timeout=8)
            except Exception:
                pass
        stop_owned_process(process)
        result["processExited"] = process is None or process.poll() is not None
        result["stderrTail"] = list(stderr)[-12:]
    return result


def owned_descendants(process):
    """Inspect only the child tree of this exact adapter PID, not unrelated apps."""
    pending = [process.pid]
    found = {}
    while pending:
        parent = pending.pop()
        children = subprocess.run(["pgrep", "-P", str(parent)], text=True,
                                  stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
        for raw in children.stdout.split():
            if not raw.isdigit() or int(raw) in found:
                continue
            pid = int(raw)
            row = subprocess.run(["ps", "-p", str(pid), "-o", "stat=", "-o", "command="],
                                 text=True, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL).stdout.strip()
            if row:
                state, executable = row.split(maxsplit=1)
                found[pid] = {"state": state, "command": executable}
                pending.append(pid)
    return found


def await_descendants_exit(descendants, timeout=8):
    deadline = time.monotonic() + timeout
    remaining = {}
    while time.monotonic() < deadline:
        remaining = {}
        for pid, previous in descendants.items():
            row = subprocess.run(["ps", "-p", str(pid), "-o", "stat=", "-o", "command="],
                                 text=True, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL).stdout.strip()
            if row:
                state, executable = row.split(maxsplit=1)
                if "Z" not in state and executable == previous["command"]:
                    remaining[pid] = state
        if not remaining:
            return
        time.sleep(0.03)
    raise ProbeFailure(f"owned debuggee descendants survived disconnect: {remaining}")


def verify_delve(root, executable, active_termination=False):
    checks = Checks()
    result = {"status": "failed", "checks": checks.items}
    process, peer, connection = None, None, None
    lines = collections.deque(maxlen=300)
    endpoints = queue.Queue()
    try:
        process = subprocess.Popen([executable, "dap", "--listen=127.0.0.1:0"], cwd=root / "app",
                                   env=dict(os.environ, GOTOOLCHAIN="local", GOWORK=""),
                                   stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                   start_new_session=os.name != "nt")
        for stream in (process.stdout, process.stderr):
            threading.Thread(target=collect_lines, args=(stream, lines, endpoints), daemon=True).start()
        with checks.check("listen_loopback_ephemeral") as detail:
            deadline = time.monotonic() + 15
            address = None
            while address is None:
                require(time.monotonic() < deadline, "Delve did not announce DAP endpoint")
                try:
                    line = endpoints.get(timeout=0.2)
                except queue.Empty:
                    require(process.poll() is None, "Delve exited before listening: " + "\n".join(lines))
                    continue
                if "DAP server listening at:" in line:
                    address = line.split("DAP server listening at:", 1)[1].strip()
            host, port = address.rsplit(":", 1)
            require(host == "127.0.0.1" and int(port) > 0, "Delve listened outside loopback")
            connection = socket.create_connection((host, int(port)), timeout=10)
            connection.settimeout(None)
            peer = Peer(connection.makefile("rb"), connection.makefile("wb"), kind="dap")
            detail.update({"host": host, "ephemeralPort": int(port)})
        with checks.check("initialize_dap_capabilities") as detail:
            capabilities = peer.request("initialize", {
                "clientID": "openidea-tool-acceptance", "adapterID": "go",
                "linesStartAt1": True, "columnsStartAt1": True, "pathFormat": "path",
                "supportsVariableType": True, "supportsEvaluateForHovers": True,
                "supportsRunInTerminalRequest": False})
            require(capabilities.get("supportsConfigurationDoneRequest"), "Delve lacks configurationDone")
            detail["supportsConfigurationDoneRequest"] = True
        main = root / "app/main.go"
        breakpoint_line = main.read_text().splitlines().index('\tfmt.Println(catalog.Greeting("Go 开发者"))') + 1
        with checks.check("launch_and_verified_breakpoint") as detail:
            before = peer.mark()
            launch = peer.send_request("launch", {"mode": "debug", "program": str(root / "app"),
                                      "cwd": str(root / "app"), "stopOnEntry": False,
                                      "env": {"GOTOOLCHAIN": "local"}})
            # Launch failure can precede initialized; surface it rather than waiting 45s.
            deadline = time.monotonic() + 45
            while True:
                with peer.condition:
                    if launch in peer.responses and not peer.responses[launch].get("success"):
                        peer.response(launch)
                    if any(event.get("event") == "initialized" for event in peer.events[before:]):
                        break
                    require(time.monotonic() < deadline, "DAP launch did not initialize")
                    require(not peer.closed, f"DAP closed during launch: {peer.failure}")
                    peer.condition.wait(0.1)
            points = peer.request("setBreakpoints", {"source": {"name": "main.go", "path": str(main)},
                                  "breakpoints": [{"line": breakpoint_line}]})
            require(points.get("breakpoints") and points["breakpoints"][0].get("verified"), "breakpoint not verified")
            peer.request("configurationDone")
            peer.response(launch)
            stopped_index, stopped = peer.event(lambda event: event.get("event") == "stopped", after=before)
            require(stopped["body"].get("reason") == "breakpoint", f"unexpected stop: {stopped}")
            thread_id = stopped["body"]["threadId"]
            detail.update({"line": breakpoint_line, "reason": "breakpoint", "threadId": thread_id})
        if active_termination:
            with checks.check("disconnect_terminates_paused_debuggee") as detail:
                descendants = owned_descendants(process)
                require(bool(descendants), "could not observe adapter-owned debuggee process tree")
                peer.request("disconnect", {"terminateDebuggee": True}, timeout=10)
                await_descendants_exit(descendants)
                process.wait(timeout=8)
                detail.update({"observedOwnedDescendantCount": len(descendants),
                               "allObservedDescendantsExited": True,
                               "adapterExited": process.poll() is not None,
                               "debuggeeWasPausedAtBreakpoint": True})
            result["status"] = "passed"
            return result
        with checks.check("threads_stack_scopes_variables") as detail:
            threads = peer.request("threads").get("threads", [])
            require(any(thread["id"] == thread_id for thread in threads), "stopped goroutine missing from threads")
            frames = peer.request("stackTrace", {"threadId": thread_id, "startFrame": 0, "levels": 20}).get("stackFrames", [])
            require(bool(frames), "empty stack trace")
            frame = next((frame for frame in frames if frame.get("source", {}).get("path") == str(main)), None)
            require(frame is not None and frame["line"] == breakpoint_line, "main.go breakpoint frame missing")
            scopes = peer.request("scopes", {"frameId": frame["id"]}).get("scopes", [])
            variables = []
            for scope in scopes:
                if scope.get("variablesReference"):
                    variables.extend(peer.request("variables", {"variablesReference": scope["variablesReference"]}).get("variables", []))
            names = {variable["name"] for variable in variables}
            require("cart" in names and "price" in names, f"expected locals missing: {names}")
            detail.update({"threadCount": len(threads), "stackDepth": len(frames), "locals": sorted(names)})
        with checks.check("evaluate_cart_field") as detail:
            value = peer.request("evaluate", {"expression": "cart.Items[0].Price", "frameId": frame["id"], "context": "watch"})
            require(value.get("result") == "25", f"unexpected evaluated cart field: {value}")
            detail.update({"expression": "cart.Items[0].Price", "value": value["result"]})
        with checks.check("step_resume_and_exit") as detail:
            mark = peer.mark()
            peer.request("next", {"threadId": thread_id})
            _, step = peer.event(lambda event: event.get("event") == "stopped", after=mark)
            require(step["body"].get("reason") == "step", f"unexpected step stop: {step}")
            peer.request("continue", {"threadId": step["body"].get("threadId", thread_id)})
            _, exited = peer.event(lambda event: event.get("event") == "terminated", after=mark)
            detail.update({"stepReason": "step", "terminated": exited.get("event") == "terminated"})
        result["status"] = "passed"
    except Exception as error:
        result["error"] = str(error)
        permission_markers = ("operation not permitted", "could not launch process", "could not attach", "not allowed", "developer mode", "debugserver", "authorization")
        if sys.platform == "darwin" and any(marker in (str(error) + "\n".join(lines)).lower() for marker in permission_markers):
            result["status"] = "blocked"
            result["blocker"] = "macOS debug permissions or debugserver launch rejected; no system security setting was changed"
    finally:
        if peer and process and process.poll() is None:
            try:
                peer.request("disconnect", {"terminateDebuggee": True}, timeout=8)
            except Exception:
                pass
        if connection:
            try:
                connection.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass
            connection.close()
        stop_owned_process(process)
        result["adapterProcessExited"] = process is None or process.poll() is not None
        result["adapterOutputTail"] = list(lines)[-12:]
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--fixture", type=pathlib.Path, default=REPO / "fixtures/go-workspace")
    parser.add_argument("--output", type=pathlib.Path, default=REPO / "docs/verification/go-tools.json")
    parser.add_argument("--only", choices=("all", "gopls", "delve"), default="all")
    args = parser.parse_args()
    fixture = args.fixture.resolve()
    before = source_digests(fixture)
    require(bool(before), "fixture is empty")
    report = {"schemaVersion": 1, "scope": "direct-tool-protocol-baseline",
              "notProven": ["native IDE integration", "UI behavior", "complete GoLand feature equivalence"],
              "startedAt": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
              "platform": sys.platform, "fixtureSource": str(fixture), "fixtureSHA256": before,
              "tools": {}, "results": {}}
    started = time.monotonic()
    for name, arguments in (("go", ["version"]), ("gopls", ["version"]), ("dlv", ["version"])):
        executable = shutil.which(name)
        require(executable is not None, f"required tool is not installed: {name}")
        report["tools"][name] = {"path": executable, "version": command([executable, *arguments], timeout=15)}
    with tempfile.TemporaryDirectory(prefix="openidea-go-tools-") as temporary:
        base = pathlib.Path(temporary).resolve()
        if args.only in ("all", "gopls"):
            root = base / "gopls-workspace"
            shutil.copytree(fixture, root)
            report["results"]["gopls"] = verify_gopls(root, report["tools"]["gopls"]["path"])
        if args.only in ("all", "delve"):
            root = base / "delve-workspace"
            shutil.copytree(fixture, root)
            report["results"]["delve"] = verify_delve(root, report["tools"]["dlv"]["path"])
            termination_root = base / "delve-termination-workspace"
            shutil.copytree(fixture, termination_root)
            termination = verify_delve(termination_root, report["tools"]["dlv"]["path"], active_termination=True)
            report["results"]["delve"]["activeTermination"] = termination
            if termination["status"] != "passed":
                report["results"]["delve"]["status"] = termination["status"]
    report["fixtureSourceUnchanged"] = before == source_digests(fixture)
    require(report["fixtureSourceUnchanged"], "source fixture was modified")
    report["temporaryWorkspacesRemoved"] = True
    report["seconds"] = round(time.monotonic() - started, 3)
    statuses = [value["status"] for value in report["results"].values()]
    report["status"] = "passed" if all(value == "passed" for value in statuses) else "blocked" if "blocked" in statuses and "failed" not in statuses else "failed"
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps({"status": report["status"], "results": {name: value["status"] for name, value in report["results"].items()}, "report": str(args.output), "seconds": report["seconds"]}, ensure_ascii=False))
    return 0 if report["status"] == "passed" else 2 if report["status"] == "blocked" else 1


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (ProbeFailure, subprocess.TimeoutExpired) as error:
        print(f"Go tool verification failed: {error}", file=sys.stderr)
        raise SystemExit(1)
