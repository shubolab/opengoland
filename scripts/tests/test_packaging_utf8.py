"""Exercise Windows metadata encoding and atomic-publication contracts."""

from contextlib import contextmanager
import hashlib
import importlib.util
import json
from pathlib import Path
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch
import zipfile

SCRIPTS = Path(__file__).resolve().parents[1]
ROOT = SCRIPTS.parent
sys.path.insert(0, str(SCRIPTS))


def load_script(name):
    spec = importlib.util.spec_from_file_location("utf8_control_" + name.replace("-", "_"), SCRIPTS / (name + ".py"))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


@contextmanager
def cp1252_default():
    """Change only omitted file encodings; explicit UTF-8 must remain effective."""
    read = Path.read_text
    write = Path.write_text

    def read_ansi(path, encoding=None, errors=None):
        return read(path, encoding=encoding or "cp1252", errors=errors)

    def write_ansi(path, data, encoding=None, errors=None, **kwargs):
        return write(path, data, encoding=encoding or "cp1252", errors=errors, **kwargs)

    with patch.object(Path, "read_text", read_ansi), patch.object(Path, "write_text", write_ansi):
        yield


def sha(data):
    return hashlib.sha256(data).hexdigest()


class PackagingUtf8Test(unittest.TestCase):
    def test_real_lsp_source_pin_authenticates_before_plugin_guard_under_cp1252(self):
        builder = load_script("patch-lsp4ij")
        # This is the actual published pin whose UTF-8 author/reference text
        # failed to decode on the first real Windows Gradle invocation.
        with self.assertRaises(UnicodeDecodeError):
            builder.PIN.read_bytes().decode("cp1252")
        with tempfile.TemporaryDirectory() as temporary, cp1252_default():
            with self.assertRaisesRegex(ValueError, "Expected one private sandbox LSP4IJ"):
                builder.build(SimpleNamespace(plugins=Path(temporary)))

    def test_platform_pin_and_sdk_metadata_reach_authentication_under_cp1252(self):
        builder = load_script("patch-platform-unicode")
        pin = json.loads((ROOT / "product/patches/platform-unicode-windows-2026.1.4.json").read_text(encoding="utf-8"))
        pin["utf8Control"] = "前😀"
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            pin_path = directory / "pin.json"
            pin_path.write_text(json.dumps(pin, ensure_ascii=False), encoding="utf-8")
            sdk = directory / "sdk"
            sdk.mkdir()
            (sdk / "product-info.json").write_text(json.dumps({"version": pin["platformVersion"], "name": "前😀", "launch": [{"os": "Windows"}]}, ensure_ascii=False), encoding="utf-8")
            original = sdk / pin["upstreamJar"]["path"]
            original.parent.mkdir(parents=True)
            original.write_bytes(b"not the authenticated SDK jar")
            with cp1252_default(), self.assertRaisesRegex(ValueError, "Pinned upstream platform JAR digest mismatch"):
                builder.build(SimpleNamespace(pin=pin_path, marker="META-INF/test.json", sdk=sdk, jar_output=None, metadata_output=None))

    def test_owned_go_policy_authenticates_unicode_descriptor_paths_under_cp1252(self):
        product = load_script("package-product")
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            contents = root / "build/product"
            plugin = contents / "plugins/java-ide-customization/lib/custom前😀.jar"
            plugin.parent.mkdir(parents=True)
            descriptor = b"<idea-plugin><id>com.intellij.java.ide</id></idea-plugin>"
            with zipfile.ZipFile(plugin, "w") as archive:
                archive.writestr("META-INF/plugin.xml", descriptor)
            app = contents / "lib/app.jar"
            app.parent.mkdir()
            app_info = b"<application/>"
            with zipfile.ZipFile(app, "w") as archive:
                archive.writestr("idea/ApplicationInfo.xml", app_info)
            (contents / "product-info.json").write_text(json.dumps({"version": "2026.1.4", "name": "前😀"}, ensure_ascii=False), encoding="utf-8")
            policy = {
                "platformVersion": "2026.1.4", "utf8Control": "前😀",
                "bundledPluginExclusions": [{
                    "id": "com.intellij.java.ide", "relativeDirectory": "plugins/java-ide-customization",
                    "files": [{"path": plugin.relative_to(contents).as_posix(), "bytes": plugin.stat().st_size, "sha256": sha(plugin.read_bytes())}],
                    "descriptor": {"jar": plugin.relative_to(contents / "plugins/java-ide-customization").as_posix(), "entry": "META-INF/plugin.xml", "sha256": sha(descriptor)},
                }],
                "retainedBundledDirectories": [],
                "applicationInfo": {"jar": "lib/app.jar", "entry": "idea/ApplicationInfo.xml", "upstreamSha256": sha(app_info)},
            }
            pin = root / "go-policy.json"
            pin.write_text(json.dumps(policy, ensure_ascii=False), encoding="utf-8")
            with patch.object(product, "ROOT", root), cp1252_default():
                actual = product.go_product_policy(contents, pin, "product-info.json")
            self.assertEqual(policy, actual)
            self.assertEqual("前😀", actual["utf8Control"])


class PackagingPublicationTest(unittest.TestCase):
    def test_actual_temporary_writer_is_closed_before_native_replace(self):
        builder = load_script("patch-platform-unicode")
        create = builder.tempfile.NamedTemporaryFile
        replace = builder.os.replace
        handles = []

        def tracked_writer(*args, **kwargs):
            stream = create(*args, **kwargs)
            handles.append(stream)
            return stream

        def windows_delete_lease(source, destination):
            # Enforce Windows' real NamedTemporaryFile sharing contract even on
            # a POSIX host, then execute the real native replacement operation.
            self.assertTrue(handles)
            if any(not stream.closed for stream in handles):
                raise PermissionError("The real temporary writer still holds the Windows delete lease")
            replace(source, destination)

        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            output = directory / "owned.bin"
            output.write_bytes(b"previous verified bytes")
            data = b"\x00\xff" + "前😀".encode("utf-8")
            with patch.object(builder.tempfile, "NamedTemporaryFile", tracked_writer), patch.object(builder.os, "replace", windows_delete_lease):
                builder.atomic_bytes(output, data)
            self.assertEqual(data, output.read_bytes())
            self.assertTrue(all(stream.closed for stream in handles))
            self.assertEqual([output], list(directory.iterdir()))

    def test_failed_publication_preserves_previous_bytes_and_removes_temporary_file(self):
        builder = load_script("patch-platform-unicode")
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            output = directory / "owned.bin"
            output.write_bytes(b"previous verified bytes")
            with patch.object(builder.os, "replace", side_effect=PermissionError("controlled destination sharing violation")):
                with self.assertRaisesRegex(PermissionError, "controlled destination"):
                    builder.atomic_bytes(output, b"new candidate")
            self.assertEqual(b"previous verified bytes", output.read_bytes())
            self.assertEqual([output], list(directory.iterdir()))


if __name__ == "__main__":
    unittest.main()
