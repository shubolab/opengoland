"""Native host controls; Windows CI also exercises its real msvcrt lock path."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import time
from types import SimpleNamespace
import unittest
from unittest.mock import patch

SCRIPTS = Path(__file__).resolve().parents[1]
ROOT = SCRIPTS.parent
sys.path.insert(0, str(SCRIPTS))
import platform_layout


class PlatformLayoutTest(unittest.TestCase):
    def test_macos_app_and_contents_resolve_to_same_sdk(self):
        with tempfile.TemporaryDirectory() as temporary:
            app = Path(temporary) / "IDE.app"
            info = app / "Contents/Resources/product-info.json"
            info.parent.mkdir(parents=True)
            info.write_text(json.dumps({"launch": [{"os": "macOS"}]}))
            first = platform_layout.detect_platform_layout(app)
            self.assertEqual(first, platform_layout.detect_platform_layout(app / "Contents"))
            self.assertEqual(app.resolve(), first.sdk_root)
            self.assertEqual(app.resolve() / "Contents/jbr/Contents/Home/bin/javac", first.javac)

    def test_flat_windows_sdk_has_native_executable_paths(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "product-info.json").write_text(json.dumps({"launch": [{"os": "Windows"}]}))
            layout = platform_layout.detect_platform_layout(root)
            self.assertEqual(root.resolve(), layout.contents)
            self.assertEqual(root.resolve() / "jbr/bin/java.exe", layout.java)
            self.assertEqual(root.resolve() / "jbr/bin/javac.exe", layout.javac)
            self.assertEqual(root.resolve() / "jbr/bin/jcmd.exe", layout.jcmd)

    def test_ambiguous_or_invalid_sdk_metadata_is_rejected(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            with self.assertRaises(ValueError): platform_layout.detect_platform_layout(root)
            (root / "product-info.json").write_text("not JSON")
            with self.assertRaises(ValueError): platform_layout.detect_platform_layout(root)
            (root / "product-info.json").write_text("{}")
            other = root / "Contents/Resources/product-info.json"
            other.parent.mkdir(parents=True)
            other.write_text("{}")
            with self.assertRaises(ValueError): platform_layout.detect_platform_layout(root)

    def test_cache_lock_serializes_processes_and_releases_after_owner_exit(self):
        worker = '''
from pathlib import Path
import sys
sys.path.insert(0, sys.argv[1])
from platform_layout import cache_lock
Path(sys.argv[3]).write_text('attempting')
with cache_lock(Path(sys.argv[2])):
    Path(sys.argv[4]).write_text('acquired')
    if sys.argv[5] == 'hold': sys.stdin.read(1)
'''
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            processes = []

            def launch(name, hold):
                attempt, entered = root / (name + ".attempt"), root / (name + ".entered")
                process = subprocess.Popen(
                    [sys.executable, "-c", worker, str(SCRIPTS), str(root / "cache.lock"),
                     str(attempt), str(entered), "hold" if hold else "once"],
                    stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
                processes.append(process)
                return process, attempt, entered

            def wait_for(path, process):
                deadline = time.monotonic() + 10
                while not path.exists():
                    if process.poll() is not None:
                        self.fail("Lock worker exited: " + process.communicate()[1].decode(errors="replace"))
                    if time.monotonic() >= deadline: self.fail("Lock worker startup timed out")
                    time.sleep(0.02)

            try:
                owner, unused, acquired = launch("owner", True)
                wait_for(acquired, owner)
                waiter, attempted, entered = launch("waiter", False)
                wait_for(attempted, waiter)
                time.sleep(0.15)
                self.assertFalse(entered.exists(), "Second process acquired the held cache lock")
                owner.terminate()
                owner.communicate(timeout=10)
                output, error = waiter.communicate(timeout=10)
                self.assertEqual(0, waiter.returncode, error.decode(errors="replace"))
                self.assertTrue(entered.exists(), "OS lock was not released after its owner exited")
            finally:
                for process in processes:
                    if process.poll() is None: process.terminate()
                    process.communicate(timeout=10)

    def test_windows_argfile_compiles_long_unicode_and_space_paths(self):
        selected = os.environ.get("OPENGOLAND_PLATFORM_PATH")
        candidates = [Path(selected)] if selected else [
            ROOT / ".tools/platform/idea-2026.1.4-windows",
            ROOT / ".tools/platform/idea-2026.1.4/IntelliJ IDEA.app",
        ]
        javac = None
        for candidate in candidates:
            try:
                compiler = platform_layout.detect_platform_layout(candidate).javac
                if compiler.is_file() and os.access(compiler, os.X_OK):
                    javac = compiler
                    break
            except ValueError:
                pass
        if javac is None:
            found = shutil.which("javac")
            self.assertIsNotNone(found, "A real JDK is required for the argfile control")
            javac = Path(found)
        with tempfile.TemporaryDirectory(prefix="open Goland 中文😀 ") as temporary:
            root = Path(temporary)
            source = root / "Example.java"
            source.write_text("class Example {}", encoding="utf-8")
            classes = root / "classes"
            classpath = os.pathsep.join(str(root / f"unused-{index:04d}.jar") for index in range(900))
            self.assertGreater(len(classpath), 32767)
            arguments = ["--release", "21", "-encoding", "UTF-8", "-proc:none", "-classpath",
                         classpath, "-d", str(classes), str(source)]
            with patch.object(platform_layout, "os", SimpleNamespace(name="nt")):
                invocation = platform_layout.java_compiler_invocation(javac, arguments, root / "compiler.args")
            self.assertLess(len(subprocess.list2cmdline(invocation.command)), 32767)
            self.assertTrue(invocation.command[-1].isascii(), "The native Windows launcher must not recode the Unicode @file path")
            self.assertEqual(root.resolve(), invocation.cwd)
            result = subprocess.run(invocation.command, cwd=invocation.cwd, capture_output=True, encoding="utf-8", errors="replace", timeout=45)
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertTrue((classes / "Example.class").is_file())

    def test_argfile_rejects_embedded_line_breaks(self):
        with tempfile.TemporaryDirectory() as temporary:
            with patch.object(platform_layout, "os", SimpleNamespace(name="nt")):
                with self.assertRaises(ValueError):
                    platform_layout.java_compiler_invocation(Path("javac.exe"), ["bad\nargument"], Path(temporary) / "args")

    def test_posix_compiler_keeps_argv_and_inherited_working_directory(self):
        with patch.object(platform_layout, "os", SimpleNamespace(name="posix")):
            invocation = platform_layout.java_compiler_invocation(Path("relative/javac"), ["-d", "relative/classes"], Path("unused.args"))
        self.assertEqual(["relative/javac", "-d", "relative/classes"], invocation.command)
        self.assertIsNone(invocation.cwd)

    def test_internal_windows_argfile_name_cannot_enter_native_ansi_path(self):
        with tempfile.TemporaryDirectory() as temporary:
            with patch.object(platform_layout, "os", SimpleNamespace(name="nt")):
                with self.assertRaisesRegex(ValueError, "ASCII"):
                    platform_layout.java_compiler_invocation(Path("javac.exe"), [], Path(temporary) / "编译.args")


if __name__ == "__main__":
    unittest.main()
