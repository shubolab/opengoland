import difflib
import importlib.util
import io
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch
import warnings
import zipfile

SCRIPTS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SCRIPTS))
spec = importlib.util.spec_from_file_location("lsp_metadata_builder", SCRIPTS / "patch-lsp4ij.py")
builder = importlib.util.module_from_spec(spec)
spec.loader.exec_module(builder)


class IntentionMetadataGuardsTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.pin_path = self.root / "product/patches/metadata.json"
        self.pin_path.parent.mkdir(parents=True)
        ids = ["com.redhat.devtools.lsp4ij.features.codeAction.intention.LSPIntentionAction" + str(i) for i in range(20)]
        self.before = ("<idea-plugin><extensions>\r\n" + "".join(
            "<intentionAction><className>" + name + "</className></intentionAction>\r\n" for name in ids)
            + "</extensions></idea-plugin>\r\n").encode()
        self.after = self.before.replace(b"</className>", b"</className><descriptionDirectoryName>Empty</descriptionDirectoryName>")
        self.html = b"<html><body>Owned metadata fixture</body></html>"
        patch_bytes = "".join(difflib.unified_diff(self.before.decode().splitlines(keepends=True),
            self.after.decode().splitlines(keepends=True), fromfile="a/META-INF/plugin.xml", tofile="b/META-INF/plugin.xml")).encode()
        (self.pin_path.parent / "metadata.patch").write_bytes(patch_bytes)
        self.pin = {"schemaVersion": 1, "upstreamJarSha256": builder.UPSTREAM_JAR_SHA,
            "resourcePath": "META-INF/plugin.xml", "upstreamSha256": builder.sha(self.before),
            "patchedSha256": builder.sha(self.after), "patchFile": "metadata.patch",
            "patchSha256": builder.sha(patch_bytes), "intentionClassNames": ids, "descriptionDirectoryName": "Empty",
            "descriptionResource": {"path": "intentionDescriptions/Empty/description.html", "sha256": builder.sha(self.html)}}
        self.save_pin()
        for name, value in [("ROOT", self.root), ("DESCRIPTION_PIN", self.pin_path)]:
            replacement = patch.object(builder, name, value)
            replacement.start()
            self.addCleanup(replacement.stop)

    def save_pin(self):
        self.pin_path.write_text(json.dumps(self.pin), encoding="utf-8")

    def original(self, descriptor=None, html=None):
        path = self.root / "original.jar"
        with zipfile.ZipFile(path, "w") as archive:
            archive.writestr("META-INF/plugin.xml", self.before if descriptor is None else descriptor)
            archive.writestr("intentionDescriptions/Empty/description.html", self.html if html is None else html)
        return path

    def testAuthenticatedTwentyTargetsApplyWithoutChangingOtherMetadata(self):
        actual, record = builder.prepare_description_metadata(self.original())
        self.assertEqual(self.after, actual)
        self.assertEqual(builder.sha(self.after), record["patchedSha256"])
        self.assertEqual(20, len(record["intentionClassNames"]))

    def testChangedUpstreamDescriptorOrExistingDescriptionFailsBeforePatching(self):
        for args in [{"descriptor": self.before + b" "}, {"html": self.html + b" "}]:
            with self.subTest(args=list(args)), self.assertRaisesRegex(ValueError, "Upstream intention"):
                builder.prepare_description_metadata(self.original(**args))

    def testPatchDigestAndFixedIdSetCannotBeSubstituted(self):
        patch_path = self.pin_path.parent / "metadata.patch"
        patch_path.write_bytes(patch_path.read_bytes() + b" ")
        with self.assertRaisesRegex(ValueError, "pin mismatch"):
            builder.prepare_description_metadata(self.original())
        self.pin["patchSha256"] = builder.sha(patch_path.read_bytes())
        self.pin["intentionClassNames"][-1] += "Unexpected"
        self.save_pin()
        with self.assertRaisesRegex(ValueError, "targets"):
            builder.prepare_description_metadata(self.original())

    def testWarmAndRepackGuardsRejectUnrelatedTamperingAndDuplicateEntries(self):
        classes = {prefix + ".class": ("Owned class " + prefix).encode() for prefix in builder.PREFIXES}
        before_entries = {"META-INF/plugin.xml": self.before, "NOTICE.txt": b"retained notice", **classes}
        after_entries = {**before_entries, "META-INF/plugin.xml": self.after, builder.MARKER: b"owned marker"}
        saved = {"classSha256": {name: builder.sha(data) for name, data in classes.items()},
            "intentionDescriptions": {"upstreamSha256": builder.sha(self.before), "patchedSha256": builder.sha(self.after)}}

        def archive(entries, duplicate=False):
            result = io.BytesIO()
            with zipfile.ZipFile(result, "w") as output, warnings.catch_warnings():
                warnings.simplefilter("ignore", UserWarning)
                for name, data in entries.items():
                    output.writestr(name, data)
                if duplicate:
                    output.writestr("NOTICE.txt", b"retained notice")
            result.seek(0)
            return zipfile.ZipFile(result)

        candidates = [(after_entries, False, True),
            ({**after_entries, "NOTICE.txt": b"tampered notice"}, False, False),
            ({**after_entries, "META-INF/plugin.xml": self.after + b" "}, False, False),
            ({**after_entries, next(iter(classes)): b"tampered class"}, False, False),
            ({**after_entries, "unexpected-resource.xml": b"extra"}, False, False),
            (after_entries, True, False)]
        for entries, duplicate, expected in candidates:
            with self.subTest(entries=len(entries), duplicate=duplicate, expected=expected), \
                    archive(before_entries) as before, archive(entries, duplicate) as after:
                self.assertEqual(expected, builder.verified_contents(before, after, saved))


if __name__ == "__main__":
    unittest.main()
