"""Exercise real owned app-info JAR rewrites and target-specific restart names."""

import importlib.util
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch
import xml.etree.ElementTree as ET
import zipfile

SCRIPTS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SCRIPTS))


def product_module():
    spec = importlib.util.spec_from_file_location("owned_branding_product", SCRIPTS / "package-product.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


XML = b'''<component xmlns="urn:owned-branding-test">
  <names product="Upstream" fullname="Upstream IDE" script="idea" edition="Community"/>
  <company name="Upstream" shortName="Upstream" url="https://example.invalid/"/>
  <essential-plugin>com.intellij.java</essential-plugin>
  <essential-plugin>com.intellij.java.ide</essential-plugin>
  <essential-plugin>com.intellij.modules.json</essential-plugin>
</component>'''
POLICY = {
    "applicationInfo": {
        "entry": "idea/IdeaApplicationInfo.xml",
        "essentialBefore": ["com.intellij.java", "com.intellij.java.ide", "com.intellij.modules.json"],
        "essentialAfter": ["com.intellij.java", "com.intellij.modules.json"],
    },
    "bundledPluginExclusions": [{"id": "com.intellij.java.ide"}],
}


class ProductRestartBrandingTest(unittest.TestCase):
    def check_target(self, script, launcher_relative):
        product = product_module()
        with tempfile.TemporaryDirectory(prefix="owned-branding-") as temporary:
            root = Path(temporary)
            contents = root / "build" / "open Goland 中文😀"
            archive = contents / "lib/app.jar"
            archive.parent.mkdir(parents=True)
            launcher = contents / launcher_relative
            launcher.parent.mkdir(parents=True, exist_ok=True)
            # Filename/metadata fixture only; never executed as a native binary.
            launcher.write_text("owned native-name sentinel", encoding="utf-8")
            with zipfile.ZipFile(archive, "w") as output:
                output.writestr("idea/IdeaApplicationInfo.xml", XML)
                output.writestr("unrelated.bin", b"unchanged\x00\xff")
                output.comment = b"ordinary-owned-comment"
            with patch.object(product, "ROOT", root):
                changed = product.brand_resources(contents, POLICY, launcher_script=script)
            self.assertEqual(["app.jar!/idea/IdeaApplicationInfo.xml"], changed)
            with zipfile.ZipFile(archive) as result:
                xml = ET.fromstring(result.read("idea/IdeaApplicationInfo.xml"))
                self.assertEqual(b"unchanged\x00\xff", result.read("unrelated.bin"))
                self.assertEqual(b"ordinary-owned-comment", result.comment)
            names = xml.find("{*}names")
            self.assertEqual("open Goland", names.get("product"))
            self.assertEqual("open Goland", names.get("fullname"))
            self.assertIsNone(names.get("edition"))
            self.assertEqual(script, names.get("script"))
            native_relative = ("MacOS/" + names.get("script") if launcher_relative.startswith("MacOS/")
                               else "bin/" + names.get("script") + "64.exe")
            self.assertTrue((contents / native_relative).is_file(), "Branded restart name must resolve to the actual owned launcher path")
            self.assertEqual("open Goland contributors", xml.find("{*}company").get("name"))
            self.assertEqual(POLICY["applicationInfo"]["essentialAfter"], [v.text for v in xml.findall("{*}essential-plugin")])
            return contents, names.get("script"), launcher_relative

    def test_mac_internal_script_matches_the_unchanged_native_idea_filename(self):
        contents, script, relative = self.check_target("idea", "MacOS/idea")
        self.assertEqual("MacOS/" + script, relative)
        self.assertNotEqual("opengoland", script)

    def test_windows_internal_script_keeps_the_existing_opengoland64_filename(self):
        contents, script, relative = self.check_target("opengoland", "bin/opengoland64.exe")
        self.assertEqual("bin/" + script + "64.exe", relative)


if __name__ == "__main__":
    unittest.main()
