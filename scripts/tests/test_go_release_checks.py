"""Real byte/file guards; these controls do not execute Go or pretend tests ran."""
import copy
import hashlib
import json
import os
from pathlib import Path
import sys
import tempfile
import unittest

SCRIPTS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SCRIPTS))
import go_release_checks as checks


class GoReleaseEvidenceTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name).resolve()
        for relative, text in {
            "engine/go.mod": "module example.org/checks\ngo 1.24.0\n",
            "engine/go.sum": "sum\n", "engine/main.go": "package main\n",
            "engine/main_test.go": "package main\n", "engine/testdata/go.work": "go 1.24\n",
            "engine/testdata/fixture.json": "{}", "engine/licenses/NOTICE.txt": "notice",
        }.items():
            path = self.root / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(text)
        self.sdk = self.root / ".tools/builder/go"
        self.sdk.mkdir(parents=True)
        (self.sdk / "bin").mkdir()
        self.builder = self.sdk / "bin" / ("go.exe" if os.name == "nt" else "go")
        self.builder.write_bytes(b"fixture builder; never executed")
        self.builder.chmod(0o755)
        (self.sdk / "VERSION").write_text("go1.27.3\nfixture\n")
        (self.sdk / "src/runtime").mkdir(parents=True)
        (self.sdk / "src/runtime/runtime.go").write_text("package runtime\n")
        lock = {"builderVersion": "go1.27.3", "runtimeSdkVersion": "go1.26.10",
                "builders": {checks._host(): {"sha256": "1" * 64}}}
        (self.root / "product").mkdir()
        self.lock = self.root / "product/go-tools.lock.json"
        self.lock.write_text(json.dumps(lock))
        self.marker = self.sdk.parent / "sdk-files.json"
        self.marker.write_text(json.dumps({"archiveSha256": "1" * 64, "files": checks._tree(self.sdk)}))
        self.tools = self.root / ".tools/prepared"
        self.tools.mkdir(parents=True)
        (self.tools / "go").mkdir()
        (self.tools / "go/VERSION").write_text("go1.26.10\n")
        (self.tools / "gopls").write_bytes(b"fixture language tool")
        self.prepared = {"lockSha256": checks._file(self.lock)["sha256"], "target": checks._host(),
                         "builderVersion": "go1.27.3", "runtimeSdkVersion": "go1.26.10",
                         "builderGoExecutable": str(self.builder), "output": str(self.tools),
                         "files": checks._tree(self.tools), "cacheHit": False}
        self.environment = {"PATH": str(self.builder.parent), "GOROOT": str(self.sdk),
                            "GOENV": "off", "GOFLAGS": "", "GOPROXY": "https://reader:private-token@example.test"}
        self.source = {"digest": "recorded-full-source"}

    def attempt(self, name):
        path = self.root / "build" / name
        path.mkdir(parents=True)
        return path

    def logs(self, path):
        events = [{"Action": "start", "Package": "example.org/checks"},
                  {"Action": "run", "Package": "example.org/checks", "Test": "TestOne"},
                  {"Action": "pass", "Package": "example.org/checks", "Test": "TestOne"},
                  {"Action": "pass", "Package": "example.org/checks"}]
        (path / "go-race.jsonl").write_text("".join(json.dumps(e) + "\n" for e in events))
        (path / "go-vet.log").write_bytes(b"")

    def fresh(self):
        path = self.attempt("fresh")
        self.logs(path)
        before = checks.fingerprint(self.root, self.builder, self.environment, self.prepared)
        record = checks.record_success(self.root, self.builder, self.environment, self.prepared,
                                       before, path, self.source, race_exit_code=0, vet_exit_code=0)
        return path, record

    def reuse(self, path, name="reuse"):
        return checks.reuse_evidence(path / "go-checks.json", self.root, self.builder,
                                     self.environment, self.prepared, self.attempt(name), {"digest": "new-full-source"})

    def test_fresh_and_explicit_reuse_preserve_exact_logs_and_provenance(self):
        path, first = self.fresh()
        original = {p.name: p.read_bytes() for p in path.iterdir()}
        reused = self.reuse(path)
        self.assertEqual("fresh", first["kind"])
        self.assertEqual("reused", reused["kind"])
        self.assertEqual("recorded-full-source", reused["origin"]["sourceDigest"])
        self.assertEqual("new-full-source", reused["sourceDigest"])
        self.assertNotIn("commands", reused)
        self.assertIn("commandsNotExecuted", reused)
        for name in ("go-race.jsonl", "go-vet.log"):
            self.assertEqual(original[name], (self.root / "build/reuse" / name).read_bytes())
        self.assertEqual(original, {p.name: p.read_bytes() for p in path.iterdir()})
        self.assertNotIn("private-token", json.dumps(first))
        self.assertNotIn("reader:", json.dumps(first))

    def test_frontend_script_and_release_label_changes_allow_reuse(self):
        path, unused = self.fresh()
        (self.root / "src").mkdir()
        (self.root / "src/Foo.kt").write_text("changed frontend")
        (self.root / "scripts").mkdir()
        (self.root / "scripts/driver.py").write_text("changed driver")
        self.prepared["cacheHit"] = True
        self.environment["OPENGOLAND_RELEASE_VERSION"] = "different-release"
        self.assertEqual("reused", self.reuse(path)["kind"])

    def test_source_fixture_license_and_module_changes_reject(self):
        path, unused = self.fresh()
        for index, name in enumerate(["engine/main.go", "engine/testdata/go.work", "engine/testdata/fixture.json", "engine/licenses/NOTICE.txt", "engine/go.sum"]):
            target = self.root / name
            previous = target.read_bytes()
            target.write_bytes(previous + b"changed")
            with self.assertRaises(ValueError): self.reuse(path, "change" + str(index))
            target.write_bytes(previous)

    def test_entire_builder_sdk_and_runtime_tool_payload_are_checked(self):
        path, unused = self.fresh()
        for index, target in enumerate([self.builder, self.sdk / "src/runtime/runtime.go", self.tools / "gopls"]):
            previous = target.read_bytes()
            target.write_bytes(previous + b"changed")
            with self.assertRaises(ValueError): self.reuse(path, "sdk" + str(index))
            target.write_bytes(previous)

    def test_environment_secret_change_and_absent_vs_off_are_distinct(self):
        path, unused = self.fresh()
        self.environment["GOWORK"] = "off"
        with self.assertRaises(ValueError): self.reuse(path, "work")
        del self.environment["GOWORK"]
        self.environment["GOPROXY"] = "https://reader:changed-secret@example.test"
        with self.assertRaises(ValueError): self.reuse(path, "secret")

    def test_explicit_goenv_file_is_hashed_without_plaintext(self):
        config = self.root / "private-go-env"
        config.write_text("GOPROXY=https://user:config-secret@example.test\n")
        self.environment["GOENV"] = str(config)
        path, record = self.fresh()
        self.assertNotIn("config-secret", json.dumps(record))
        config.write_text("GOPROXY=https://user:other-secret@example.test\n")
        with self.assertRaises(ValueError): self.reuse(path)

    def test_log_tampering_including_empty_vet_file_rejects(self):
        path, unused = self.fresh()
        for index, name in enumerate(["go-race.jsonl", "go-vet.log"]):
            target = path / name
            previous = target.read_bytes()
            target.write_bytes(previous + b"tampered\n")
            with self.assertRaises(ValueError): self.reuse(path, "log" + str(index))
            target.write_bytes(previous)

    def test_historical_or_reused_record_cannot_become_a_fresh_execution(self):
        path = self.attempt("historical")
        (path / "go-checks.json").write_text(json.dumps({"authority": "historical-observed-control-flow"}))
        with self.assertRaisesRegex(ValueError, "Only a fresh"): self.reuse(path)
        fresh, unused = self.fresh()
        self.reuse(fresh, "once")
        with self.assertRaisesRegex(ValueError, "Only a fresh"):
            checks.reuse_evidence(self.root / "build/once/go-checks.json", self.root, self.builder,
                                 self.environment, self.prepared, self.attempt("twice"), self.source)

    def test_failed_command_or_changed_during_check_never_writes_record(self):
        path = self.attempt("failed")
        self.logs(path)
        before = checks.fingerprint(self.root, self.builder, self.environment, self.prepared)
        with self.assertRaises(ValueError):
            checks.record_success(self.root, self.builder, self.environment, self.prepared, before,
                                  path, self.source, race_exit_code=1, vet_exit_code=0)
        self.assertFalse((path / "go-checks.json").exists())
        (self.root / "engine/main.go").write_text("changed while checking")
        with self.assertRaises(ValueError):
            checks.record_success(self.root, self.builder, self.environment, self.prepared, before,
                                  path, self.source, race_exit_code=0, vet_exit_code=0)
        self.assertFalse((path / "go-checks.json").exists())

    def test_command_edit_or_truncated_package_result_rejects(self):
        path, record = self.fresh()
        record["commands"][0]["argv"].append("-run=OnlyOne")
        (path / "go-checks.json").write_text(json.dumps(record))
        with self.assertRaises(ValueError): self.reuse(path)
        with self.assertRaises(ValueError): checks._race_summary(b'{"Action":"start","Package":"x"}\n')


if __name__ == "__main__":
    unittest.main()
