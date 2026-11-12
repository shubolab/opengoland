import sys
from pathlib import Path, PurePosixPath, PureWindowsPath
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from build_inputs import ROOT, ordered_sources, snapshot


class BuildInputOrderingTest(unittest.TestCase):
    def test_same_canonical_order_for_native_windows_and_posix_paths(self):
        names = ["product/patches/platform.json", "product/patches/VerifyProductArchives.java",
                 "product/patches/lsp4ij.patch", "product/patches/GoProductModuleRepository.java",
                 "scripts/build_inputs.py"]
        expected = ["product/patches/GoProductModuleRepository.java", "product/patches/VerifyProductArchives.java",
                    "product/patches/lsp4ij.patch", "product/patches/platform.json", "scripts/build_inputs.py"]
        for kind in (PurePosixPath, PureWindowsPath):
            root = kind("project")
            ordered = ordered_sources([root / name for name in reversed(names)], root)
            self.assertEqual(expected, [path.relative_to(root).as_posix() for path in ordered])


if __name__ == "__main__":
    unittest.main()


class BuildInputFileAssociationTest(unittest.TestCase):
    def test_cross_platform_file_association_inventory_is_authenticated(self):
        manifest = snapshot(ROOT)
        paths = {entry["path"] for entry in manifest["files"]}
        self.assertIn("product/file-associations.json", paths)
