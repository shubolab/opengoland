import importlib.util
import io
import json
from pathlib import Path
import sys
import unittest
import warnings
import zipfile

SCRIPTS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SCRIPTS))


def module(name, filename):
    spec = importlib.util.spec_from_file_location(name, SCRIPTS / filename)
    value = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(value)
    return value


repair = module('compatible_marketplace_repair', 'patch-platform-compatible-marketplace.py')
windows = module('compatible_windows_dispatch', 'package_windows.py')


class CompatibleMarketplaceRepairTest(unittest.TestCase):
    def setUp(self):
        self.before = b'owned class bytes\x04\x04unchanged coroutine label'
        self.offset = len(b'owned class bytes')
        self.after = self.before[:self.offset] + b'\x03' + self.before[self.offset + 1:]
        self.xml_before = repair.SERVICE_XML_BEFORE
        self.xml_after = repair.SERVICE_XML_AFTER
        self.pin = {
            'classDelta': {'path': repair.CLASS_ENTRY, 'bytes': len(self.before),
                           'fileOffset': self.offset, 'beforeHex': '04', 'afterHex': '03',
                           'upstreamSha256': repair.sha(self.before), 'patchedSha256': repair.sha(self.after)},
            'xmlDelta': {'path': repair.SERVICE_XML_ENTRY, 'bytes': len(self.xml_before),
                         'upstreamSha256': repair.sha(self.xml_before), 'patchedSha256': repair.sha(self.xml_after)},
        }

    def test_only_the_authenticated_boolean_byte_changes(self):
        result = repair.delta_class(self.before, self.pin)
        self.assertEqual(self.after, result)
        self.assertEqual([self.offset], [i for i, (a, b) in enumerate(zip(self.before, result)) if a != b])
        self.assertEqual(4, result[self.offset + 1], 'The distinct coroutine label byte must remain untouched')

    def test_service_registration_opens_only_plugin_management_policy(self):
        result = repair.delta_service_xml(self.xml_before, self.pin)
        self.assertEqual(self.xml_after, result)
        self.assertEqual(1, result.count(b'<applicationService open="true" serviceInterface="com.intellij.ide.plugins.PluginManagementPolicy"'))
        self.assertEqual(1, result.count(repair.SERVICE_XML_BEFORE.split(b'\n', 1)[1]))

    def test_wrong_service_descriptor_and_output_identity_are_rejected(self):
        for data, change in [(self.xml_before + b' ', {}),
                             (self.xml_before, {'path': 'META-INF/other.xml'}),
                             (self.xml_before, {'patchedSha256': '0' * 64}),
                             (self.xml_before, {'bytes': 1})]:
            with self.subTest(change=change), self.assertRaises(ValueError):
                repair.delta_service_xml(data, {'xmlDelta': {**self.pin['xmlDelta'], **change}})

    def test_wrong_input_offset_and_output_identity_are_rejected(self):
        for data, change in [(self.before + b' ', {}), (self.before, {'fileOffset': self.offset + 1}),
                (self.before, {'patchedSha256': '0' * 64}), (self.before, {'bytes': 1}),
                (self.before, {'afterHex': '05'}), (self.before, {'path': 'other.class'})]:
            with self.subTest(change=change), self.assertRaises(ValueError):
                repair.delta_class(data, {'classDelta': {**self.pin['classDelta'], **change}})

    def test_jar_verification_rejects_other_entries_duplicate_and_marker_drift(self):
        expected = {'classSha256': {repair.CLASS_ENTRY: repair.sha(self.after)},
                    'xmlSha256': repair.sha(self.xml_after), 'productionMode': repair.MODE}
        before_entries = {repair.CLASS_ENTRY: self.before, repair.SERVICE_XML_ENTRY: self.xml_before,
                          'Other.class': b'unchanged ABI', 'NOTICE': b'upstream license'}
        after_entries = {**before_entries, repair.CLASS_ENTRY: self.after,
                         repair.SERVICE_XML_ENTRY: self.xml_after, repair.MARKER: json.dumps(expected).encode()}
        def jar(entries, duplicate=False):
            stream = io.BytesIO()
            with zipfile.ZipFile(stream, 'w') as output, warnings.catch_warnings():
                warnings.simplefilter('ignore', UserWarning)
                for name, data in entries.items(): output.writestr(name, data)
                if duplicate: output.writestr('NOTICE', b'upstream license')
            stream.seek(0)
            return stream
        for entries, duplicate, valid in [(after_entries, False, True),
                ({**after_entries, 'Other.class': b'altered ABI'}, False, False),
                ({**after_entries, 'NOTICE': b'altered license'}, False, False),
                ({**after_entries, repair.SERVICE_XML_ENTRY: repair.SERVICE_XML_BEFORE}, False, False),
                ({**after_entries, 'extra.xml': b'unpinned'}, False, False),
                ({**after_entries, repair.MARKER: b'{}'}, False, False),
                (after_entries, True, False)]:
            with self.subTest(duplicate=duplicate, valid=valid), jar(before_entries) as before, jar(entries, duplicate) as after:
                self.assertEqual(valid, repair.verify_jar(after, before, expected) == expected)

    def test_windows_dispatch_is_explicit_and_rejects_arbitrary_producers(self):
        self.assertEqual('patch-platform-unicode.py', windows.platform_patch_script({}).name)
        self.assertEqual('patch-platform-compatible-marketplace.py', windows.platform_patch_script({'producer': 'compatible-marketplace'}).name)
        with self.assertRaisesRegex(ValueError, 'Unknown authenticated'):
            windows.platform_patch_script({'producer': '../../arbitrary.py'})


if __name__ == '__main__': unittest.main()
