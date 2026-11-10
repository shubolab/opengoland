"""Use real app/JAR/log files to distinguish the live profile from stale logs."""

import importlib.util
import json
from pathlib import Path
import plistlib
import subprocess
import tempfile
import unittest
from unittest.mock import patch
import xml.etree.ElementTree as ET
import zipfile


SCRIPTS = Path(__file__).resolve().parents[1]


class ProductRuntimeProfileLogTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix="owned-runtime-profile-")
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name).resolve()
        self.home = self.root / "owned-home"
        self.home.mkdir()
        # Stub only the home-location boundary; log files and reads stay real.
        home = patch.object(Path, "home", return_value=self.home)
        home.start()
        self.addCleanup(home.stop)
        self.assertEqual(self.home, Path.home())

        self.app = self.root / "open Goland.app"
        self.contents = self.app / "Contents"
        resources = self.contents / "Resources"
        resources.mkdir(parents=True)
        self.launcher = self.contents / "MacOS/idea"
        self.launcher.parent.mkdir()
        self.launcher.write_bytes(b"owned filename sentinel; never executed")
        (self.contents / "Info.plist").write_bytes(plistlib.dumps({
            "CFBundleDisplayName": "open Goland",
            "CFBundleIdentifier": "dev.opengoland.ide",
        }))
        (resources / "product-info.json").write_text(json.dumps({"name": "open Goland"}), encoding="utf-8")
        self.plugins = {"dev.openidea.go": "Go Workbench", "com.redhat.devtools.lsp4ij": "LSP4IJ"}
        directories = []
        for index, (identifier, name) in enumerate(self.plugins.items()):
            relative = f"Contents/plugins/owned-{index}"
            directories.append(relative)
            library = self.app / relative / "lib"
            library.mkdir(parents=True)
            descriptor = ET.Element("idea-plugin")
            ET.SubElement(descriptor, "id").text = identifier
            ET.SubElement(descriptor, "name").text = name
            with zipfile.ZipFile(library / "owned-plugin.jar", "w") as archive:
                archive.writestr("META-INF/plugin.xml", ET.tostring(descriptor))
        metadata = {"name": "open Goland", "plugins": list(self.plugins),
                    "pluginLoading": {"directories": directories}}
        (resources / "openidea-product.json").write_text(json.dumps(metadata), encoding="utf-8")

        self.global_log = self.home / "Library/Logs/OpenIDEA-Go/idea.log"
        self.current_directory = self.root / "profile 中文 😀=current" / "log"
        self.current_log = self.current_directory / "idea.log"
        for log in [self.global_log, self.current_log]:
            log.parent.mkdir(parents=True)
        self.properties = {
            "idea.vendor.name": "OpenGoland",
            "idea.paths.selector": "OpenIDEAGo2026.1",
            "intellij.platform.load.app.info.from.resources": "true",
            "plugin.path": ",".join(str(self.app / relative) for relative in directories),
            "idea.log.path": str(self.current_directory),
        }
        self.pid = 12345
        spec = importlib.util.spec_from_file_location("owned_runtime_profile_verifier", SCRIPTS / "verify-product-runtime.py")
        self.verifier = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.verifier)

        def ps(argv, **kwargs):
            self.assertEqual(["ps", "-p", str(self.pid), "-o", "comm="], argv)
            self.assertTrue(kwargs["text"])
            return str(self.launcher) + "\n"

        def jcmd(argv, **kwargs):
            self.assertEqual([str(self.contents / "jbr/Contents/Home/bin/jcmd"), str(self.pid), "VM.system_properties"], argv)
            self.assertTrue(kwargs["text"])
            self.assertTrue(kwargs["check"])
            return subprocess.CompletedProcess(argv, 0,
                "\n".join(key + "=" + value for key, value in self.properties.items()) + "\n", "")

        for attribute, callback in [("check_output", ps), ("run", jcmd)]:
            boundary = patch.object(self.verifier.subprocess, attribute, side_effect=callback)
            boundary.start()
            self.addCleanup(boundary.stop)

    @staticmethod
    def valid_log():
        return "IDE STARTED\nLoaded custom plugins: Go Workbench, LSP4IJ\n"

    def test_stale_default_startup_cannot_mask_missing_current_startup(self):
        self.global_log.write_text(self.valid_log(), encoding="utf-8")
        self.current_log.write_text("Current profile has not started yet\n", encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "No startup marker"):
            self.verifier.verify(self.app, self.pid)

    def test_stale_default_plugins_cannot_mask_missing_current_plugin(self):
        self.global_log.write_text(self.valid_log(), encoding="utf-8")
        self.current_log.write_text("IDE STARTED\nLoaded custom plugins: Go Workbench\n", encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "latest startup did not report loading LSP4IJ"):
            self.verifier.verify(self.app, self.pid)

    def test_explicit_current_profile_passes_while_default_log_is_invalid(self):
        self.global_log.write_text("Other profile has no valid startup or plugins\n", encoding="utf-8")
        self.current_log.write_text(self.valid_log(), encoding="utf-8")
        result = self.verifier.verify(self.app, self.pid)
        self.assertEqual("pass", result["status"])
        self.assertEqual(str(self.current_log), result["log"])
        self.assertEqual("live-jvm-property", result["logPathSource"])
        self.assertEqual(self.plugins, result["loadedPlugins"])

    def test_relative_explicit_path_rejects_instead_of_using_valid_default(self):
        self.global_log.write_text(self.valid_log(), encoding="utf-8")
        self.current_log.write_text(self.valid_log(), encoding="utf-8")
        self.properties["idea.log.path"] = "relative/profile/log"
        with self.assertRaisesRegex(ValueError, "live runtime log path must be absolute"):
            self.verifier.verify(self.app, self.pid)


if __name__ == "__main__":
    unittest.main()
