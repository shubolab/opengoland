#!/usr/bin/env python3
"""Replay the saved independent fixtures with pinned gopls, never a user project."""
import argparse
import json
import os
import pathlib
import shutil
import signal
import subprocess
import tempfile
import urllib.parse

HERE = pathlib.Path(__file__).resolve().parent


def command(argv, cwd, env):
    child = subprocess.Popen(argv, cwd=cwd, env=env, stdout=subprocess.PIPE,
                             stderr=subprocess.PIPE, text=True, start_new_session=True)
    try:
        out, err = child.communicate(timeout=45)
    except subprocess.TimeoutExpired:
        os.killpg(child.pid, signal.SIGKILL)
        child.communicate()
        raise RuntimeError(f"Owned fixture command timed out: {argv[0]}")
    return {"code": child.returncode, "stdout": out, "stderr": err}


def uri_path(uri):
    parsed = urllib.parse.urlparse(uri)
    assert parsed.scheme == "file" and not parsed.netloc, uri
    return pathlib.Path(urllib.parse.unquote(parsed.path)).resolve()


def utf16_offset(text, position):
    lines = text.splitlines(keepends=True)
    prefix = "".join(lines[:position["line"]])
    line = lines[position["line"]] if position["line"] < len(lines) else ""
    units = 0
    for index, character in enumerate(line):
        if units == position["character"]:
            return len(prefix) + index
        units += 2 if ord(character) > 0xFFFF else 1
    assert units == position["character"], position
    return len(prefix) + len(line)


def apply_owned(edit, root, allowed):
    entries = edit.get("documentChanges", []) if isinstance(edit, dict) else []
    if isinstance(edit, dict):
        entries += [{"textDocument": {"uri": uri}, "edits": edits}
                    for uri, edits in edit.get("changes", {}).items()]
    for entry in entries:
        assert "textDocument" in entry, "Resource operations are outside these probes"
        path = uri_path(entry["textDocument"]["uri"])
        assert path.is_relative_to(root) and path.relative_to(root).as_posix() in allowed
        text = path.read_text()
        edits = [(utf16_offset(text, e["range"]["start"]),
                  utf16_offset(text, e["range"]["end"]), e["newText"])
                 for e in entry["edits"]]
        for start, end, replacement in sorted(edits, reverse=True):
            text = text[:start] + replacement + text[end:]
        path.write_text(text)


def run_source(go, source, env):
    with tempfile.TemporaryDirectory(prefix="opengoland-signature-lowering-") as temporary:
        root = pathlib.Path(temporary).resolve()
        (root / "go.mod").write_text("module example.com/lower\n\ngo 1.24.0\n")
        (root / "main.go").write_text(source)
        return command([go, "run", "."], root, env)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--go", default=shutil.which("go"))
    parser.add_argument("--gopls", default=shutil.which("gopls"))
    parser.add_argument("--output", type=pathlib.Path)
    args = parser.parse_args()
    assert args.go and args.gopls, "Choose installed Go/gopls with --go and --gopls"
    env = os.environ | {"GOTOOLCHAIN": "local", "GOTELEMETRY": "off", "GOPROXY": "off",
                        "GOFLAGS": "", "GOWORK": "off"}
    version = command([args.gopls, "version"], HERE, env)
    assert version["stdout"].strip() == "golang.org/x/tools/gopls v0.21.1", version
    result = {"gopls": version, "go": command([args.go, "version"], HERE, env),
              "goplsCases": [], "loweringCases": []}
    reference = json.loads((HERE / "gopls-probes.json").read_text())
    for case in reference["cases"]:
        with tempfile.TemporaryDirectory(prefix="opengoland-signature-replay-") as temporary:
            root = pathlib.Path(temporary).resolve()
            files = case["beforeFiles"]
            for name, text in files.items():
                path = root / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(text)
            current_env = env | ({"GOWORK": str(root / "go.work")} if "go.work" in files else {})
            patterns = ["./a/...", "./b/..."] if "go.work" in files else ["./..."]
            baseline = command([args.go, "test", *patterns], root, current_env)
            assert baseline["code"] == 0, (case["name"], baseline)
            request = json.loads(json.dumps(case["request"]))
            location = request["param"]["textDocument"] if "method" in request else request["Location"]
            old_path = uri_path(location["uri"]).as_posix()
            target = max((name for name in files if old_path.endswith("/" + name)), key=len)
            location["uri"] = (root / target).as_uri()
            method = "gopls.lsp" if "method" in request else "gopls.change_signature"
            response = command([args.gopls, "execute", method, json.dumps(request)], root, current_env)
            assert (response["code"] == 0) == (case["tool"]["code"] == 0), (case["name"], response)
            assert all((root / name).read_text() == text for name, text in files.items())
            if response["code"] == 0:
                apply_owned(json.loads(response["stdout"]), root, files)
            compiled = command([args.go, "test", *patterns], root, current_env)
            assert (compiled["code"] == 0) == (case["compileAfter"]["code"] == 0), (case["name"], compiled)
            item = {"name": case["name"], "tool": response, "compileAfter": compiled}
            if "runtimeAfter" in case:
                runtime = command([args.go, "run", "."], root, current_env)
                assert runtime["code"] == case["runtimeAfter"]["code"]
                assert runtime["stdout"] == case["runtimeAfter"]["stdout"], (case["name"], runtime)
                item["runtimeAfter"] = runtime
            if case["name"] in {"swap_results", "remove_result"}:
                assert " (int, error)" in (root / "main.go").read_text()
            if case["name"] == "workspace_utf16":
                assert "😀中文" in (root / "a/api.go").read_text()
                assert "😀中文" in (root / "b/main.go").read_text()
            result["goplsCases"].append(item)
            print("verified", case["name"], flush=True)
    for case in json.loads((HERE / "lowering-probes.json").read_text()):
        before = run_source(args.go, case["beforeSource"], env)
        after = run_source(args.go, case["afterSource"], env)
        assert (before["code"], before["stdout"]) == (case["before"]["code"], case["before"]["stdout"])
        assert (after["code"], after["stdout"]) == (case["after"]["code"], case["after"]["stdout"])
        result["loweringCases"].append({"name": case["name"], "before": before, "after": after})
        print("verified", case["name"], flush=True)
    if args.output:
        args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n")
    print("Verified 12 gopls cases and 9 independent lowering cases.")


if __name__ == "__main__":
    main()
