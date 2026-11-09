"""Actual patch-process controls; Windows CI exercises GNU's binary mode."""
import difflib
import hashlib
from pathlib import Path
import subprocess
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import source_patch


def difference(name, before, after):
    return b"".join(difflib.diff_bytes(difflib.unified_diff, before.splitlines(keepends=True),
                                     after.splitlines(keepends=True),
                                     fromfile=("a/" + name).encode(), tofile=("b/" + name).encode()))


class SourcePatchTest(unittest.TestCase):
    def test_real_mixed_line_endings_and_unicode_paths_are_preserved_exactly(self):
        with tempfile.TemporaryDirectory(prefix="open Goland 前😀 ") as temporary:
            root = Path(temporary)
            source = root / "source 中文 space"
            source.mkdir()
            originals = {"lf.java": b"first\nold\nlast\n", "crlf.java": b"first\r\nold\r\nlast\r\n"}
            expected = {name: data.replace(b"old", b"new") for name, data in originals.items()}
            data = b"".join(difference(name, before, expected[name]) for name, before in originals.items())
            self.assertIn(b"\r\n", data)
            self.assertIn(b"\n", data.replace(b"\r\n", b""))
            for name, before in originals.items():
                (source / name).write_bytes(before)
            file = root / "patch 前😀.diff"
            file.write_bytes(data)
            result = source_patch.apply_source_patch(file, source, hashlib.sha256(data).hexdigest())
            self.assertEqual(0, result.returncode)
            self.assertEqual(data, file.read_bytes())
            self.assertEqual(expected, {name: (source / name).read_bytes() for name in expected})

    def test_real_failed_hunk_is_reported_with_captured_process_output(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "item.java").write_bytes(b"unrelated source\n")
            data = difference("item.java", b"old\n", b"new\n")
            file = root / "fix.patch"
            file.write_bytes(data)
            with self.assertRaises(ValueError) as failure:
                source_patch.apply_source_patch(file, root, hashlib.sha256(data).hexdigest())
            message = str(failure.exception)
            self.assertIn("exited 1", message)
            self.assertIn("stdout:", message)
            self.assertIn("stderr:", message)
            self.assertIn("item.java", message)
            self.assertIn("failed", message.lower())
            self.assertEqual(b"unrelated source\n", (root / "item.java").read_bytes())

    def test_digest_change_rejects_before_process_or_source_edit(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "item.java").write_bytes(b"old\n")
            file = root / "fix.patch"
            file.write_bytes(difference("item.java", b"old\n", b"new\n"))
            with patch.object(source_patch.subprocess, "run") as run:
                with self.assertRaisesRegex(ValueError, "digest mismatch"):
                    source_patch.apply_source_patch(file, root, "0" * 64)
            run.assert_not_called()
            self.assertEqual(b"old\n", (root / "item.java").read_bytes())

    def test_windows_command_uses_binary_stdin_without_patch_path_argument(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            file = root / "fix.patch"
            data = b"authenticated bytes\r\n"
            file.write_bytes(data)
            with patch.object(source_patch, "os", SimpleNamespace(name="nt")), \
                    patch.object(source_patch.subprocess, "run", return_value=subprocess.CompletedProcess([], 0, b"", b"")) as run:
                source_patch.apply_source_patch(file, root, hashlib.sha256(data).hexdigest())
            arguments, keywords = run.call_args
            self.assertEqual(["patch", "--batch", "--fuzz=0", "-p1", "--binary"], arguments[0])
            self.assertEqual(data, keywords["input"])
            self.assertEqual(root, keywords["cwd"])
            self.assertNotIn("text", keywords)
            self.assertNotIn("encoding", keywords)


if __name__ == "__main__":
    unittest.main()
