"""Attachment selection and normal cleanup without operating on real disks."""
import copy
import json
import os
from pathlib import Path
import plistlib
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import package_dmg as dmg


class DmgImageCleanupTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.directory = Path(temporary.name).resolve()
        self.image = self.directory / "open Goland 中文😀.dmg"
        self.image.write_bytes(b"owned test fixture; not a disk image")
        self.owned = {"image-path": str(self.image), "system-entities": [
            {"dev-entry": "/dev/disk4", "content-hint": "GUID_partition_scheme"},
            {"dev-entry": "/dev/disk4s1", "content-hint": "EFI"},
            {"dev-entry": "/dev/disk5", "content-hint": "EF57347C-0000-11AA-AA11-00306543ECAC"},
            {"dev-entry": "/dev/disk5s1", "content-hint": "41504653-0000-11AA-AA11-00306543ECAC"},
        ]}
        self.foreign = {"image-path": str(self.directory / "foreign.dmg"),
                        "system-entities": [{"dev-entry": "/dev/disk9"}]}

    def test_exact_path_selects_primary_not_apfs_synthesized_or_foreign_device(self):
        inventory = {"images": [self.foreign, self.owned]}
        original = copy.deepcopy(inventory)
        self.assertEqual("/dev/disk4", dmg.created_image_device(inventory, self.image))
        self.assertEqual(original, inventory)
        self.assertIsNone(dmg.created_image_device({"images": [self.foreign]}, self.image))
        self.assertIsNone(dmg.created_image_device({"images": []}, self.image))
        if os.name != "nt":  # Windows symlink creation may require privileges.
            alias = self.directory / "alias.dmg"
            alias.symlink_to(self.image)
            self.assertEqual("/dev/disk4", dmg.created_image_device(inventory, alias))

    def test_duplicate_or_ambiguous_or_malformed_target_is_rejected(self):
        two_maps = copy.deepcopy(self.owned)
        two_maps["system-entities"][2]["content-hint"] = "GUID_partition_scheme"
        no_maps = copy.deepcopy(self.owned)
        del no_maps["system-entities"][0]["content-hint"]
        partition_first = copy.deepcopy(self.owned)
        partition_first["system-entities"].reverse()
        repeated_device = copy.deepcopy(self.owned)
        repeated_device["system-entities"].append({"dev-entry": "/dev/disk4"})
        for inventory in ({"images": [self.owned, copy.deepcopy(self.owned)]},
                          {"images": [two_maps]}, {"images": [no_maps]},
                          {"images": [partition_first]}, {"images": [repeated_device]},
                          {"images": [{"image-path": str(self.image), "system-entities": []}]}, {}):
            with self.subTest(inventory=inventory), self.assertRaises(ValueError):
                dmg.created_image_device(inventory, self.image)

    def result(self, images):
        return subprocess.CompletedProcess(["hdiutil", "info", "-plist"], 0,
                                           stdout=plistlib.dumps({"images": images}), stderr=b"")

    def test_normal_detach_only_selected_owned_device_and_rechecks_inventory(self):
        with patch.object(dmg.subprocess, "run", side_effect=[self.result([self.foreign, self.owned]), self.result([self.foreign])]) as info, \
                patch.object(dmg, "run") as detach:
            dmg.detach_created_image(self.image, self.directory)
        detach.assert_called_once_with(["hdiutil", "detach", "/dev/disk4"], self.directory / "dmg-after-create-detach.log")
        self.assertEqual(2, info.call_count)
        self.assertEqual("detached-owned-image", json.loads((self.directory / "dmg-create-cleanup.json").read_text(encoding="utf-8"))["status"])
        self.assertEqual([self.foreign], plistlib.loads((self.directory / "dmg-after-detach-info.plist").read_bytes())["images"])

    def test_no_target_never_detaches_foreign_and_failed_cleanup_is_not_accepted(self):
        with patch.object(dmg.subprocess, "run", return_value=self.result([self.foreign])), patch.object(dmg, "run") as detach:
            dmg.detach_created_image(self.image, self.directory)
        detach.assert_not_called()
        (self.directory / "dmg-create-cleanup.json").unlink()
        with patch.object(dmg.subprocess, "run", side_effect=[self.result([self.owned]), self.result([self.owned])]), \
                patch.object(dmg, "run"), self.assertRaisesRegex(ValueError, "remains attached"):
            dmg.detach_created_image(self.image, self.directory)
        self.assertFalse((self.directory / "dmg-create-cleanup.json").exists())

    def test_smoke_properties_disable_only_temporary_profile_import_ui(self):
        profile = self.directory / "isolated 中文😀"
        profile.mkdir()
        properties = dmg.smoke_properties(profile)
        actual = dict(line.split("=", 1) for line in properties.read_text(encoding="utf-8").splitlines())
        expected = {f"idea.{name}.path": str(profile / name) for name in ("config", "system", "plugins", "log")}
        expected.update({"intellij.startup.wizard": "false", "idea.initially.ask.config": "never"})
        self.assertEqual(expected, actual)
        self.assertEqual([properties], list(profile.iterdir()))


if __name__ == "__main__":
    unittest.main()
