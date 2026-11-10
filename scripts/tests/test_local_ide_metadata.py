"""Exact unwanted registrations are omitted without changing development tools."""
import copy
import hashlib
from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from local_ide_metadata import filter_local_ide_metadata


class LocalConsentRegistrationTest(unittest.TestCase):
    def fixture(self):
        node = (b'<applicationConfigurable parentId="preferences.general" '
                b'instance="com.intellij.ide.gdpr.ConsentConfigurable" id="consents" '
                b'bundle="messages.IdeBundle" key="consent.configurable"/>')
        data = b'<idea-plugin><extensions>' + node + b'<applicationService serviceImplementation="KeepConsentApi"/></extensions></idea-plugin>'
        output = data.replace(node, b'')
        rule = {"kind": "xml", "path": "consent-control", "inputBytes": len(data),
                "inputSha256": hashlib.sha256(data).hexdigest(), "outputBytes": len(output),
                "outputSha256": hashlib.sha256(output).hexdigest(), "removals": [{
                    "tag": "applicationConfigurable", "parent": "extensions", "attributes": {
                        "parentId": "preferences.general", "instance": "com.intellij.ide.gdpr.ConsentConfigurable",
                        "id": "consents", "bundle": "messages.IdeBundle", "key": "consent.configurable",
                    }, "bytes": len(node), "sha256": hashlib.sha256(node).hexdigest()}],
                "absentTokens": ["com.intellij.ide.gdpr.ConsentConfigurable"]}
        return data, output, rule

    def test_exact_configurable_removed_without_changing_consent_service(self):
        data, expected, rule = self.fixture()
        result, record = filter_local_ide_metadata(data, rule)
        self.assertEqual(expected, result)
        self.assertIn(b'KeepConsentApi', result)
        self.assertEqual(1, len(record['removed']))
        self.assertTrue(record['otherRawBytesPreserved'])

    def test_other_configurable_or_additional_attributes_are_not_allowed(self):
        data, unused, rule = self.fixture()
        for name, value in (("instance", "example.OurConsents"), ("id", "other"), ("extra", "true")):
            changed = copy.deepcopy(rule)
            changed['removals'][0]['attributes'][name] = value
            with self.subTest(name=name), self.assertRaisesRegex(ValueError, 'Unsupported.*selector'):
                filter_local_ide_metadata(data, changed)

    def test_authenticated_changed_output_or_changed_span_is_rejected(self):
        data, unused, rule = self.fixture()
        for where in (rule, rule['removals'][0]):
            name = 'outputSha256' if where is rule else 'sha256'
            previous = where[name]
            where[name] = '0' * 64
            with self.assertRaises(ValueError):
                filter_local_ide_metadata(data, rule)
            where[name] = previous


class LocalPromotionRegistrationTest(unittest.TestCase):
    def fixture(self):
        # Bundled content descriptors are embedded as CDATA in the fast index.
        # Removing only an action would leave the settings/template providers.
        module = (b'<module name="intellij.java.promo"><![CDATA[<idea-plugin>'
                  b'<actions><action id="PromoEndpoints" class="PromoEndpointsAction"/></actions>'
                  b'<extensions><applicationConfigurable id="promo.database"/>'
                  b'<moduleBuilder builderClass="PromoSpringModuleBuilder"/></extensions>'
                  b'</idea-plugin>]]></module>')
        retained = (b'<module name="intellij.java"/><module name="ordinary.promo"/>'
                    b'<module name="intellij.vcs.github"/>')
        data = b'<idea-plugin><content>' + retained + module + b'</content></idea-plugin>'
        expected = data.replace(module, b'')
        rule = {"kind": "xml", "path": "java-promotion-control", "inputBytes": len(data),
                "inputSha256": hashlib.sha256(data).hexdigest(), "outputBytes": len(expected),
                "outputSha256": hashlib.sha256(expected).hexdigest(), "removals": [{
                    "tag": "module", "parent": "content", "attributes": {"name": "intellij.java.promo"},
                    "bytes": len(module), "sha256": hashlib.sha256(module).hexdigest()}],
                "absentTokens": ["intellij.java.promo", "PromoEndpoints", "promo.database", "PromoSpringModuleBuilder"]}
        return data, expected, rule

    def test_whole_optional_module_removed_with_other_content_bytes_preserved(self):
        data, expected, rule = self.fixture()
        result, record = filter_local_ide_metadata(data, rule)
        self.assertEqual(expected, result)
        self.assertTrue(record["otherRawBytesPreserved"])
        self.assertEqual(1, len(record["removed"]))

    def test_java_or_arbitrary_promo_modules_are_not_allowed(self):
        data, unused, rule = self.fixture()
        for name in ("intellij.java", "ordinary.promo", "intellij.vcs.github"):
            changed = copy.deepcopy(rule)
            changed["removals"][0]["attributes"]["name"] = name
            with self.subTest(name=name), self.assertRaisesRegex(ValueError, "Unsupported.*selector"):
                filter_local_ide_metadata(data, changed)

    def test_promotion_descriptor_mutation_is_not_silently_accepted(self):
        data, unused, rule = self.fixture()
        changed = data.replace(b"PromoEndpointsAction", b"NewEndpointsAction")
        # Even a caller that authenticates the outer document cannot change
        # the exact reviewed content span while retaining the removal pin.
        rule.update(inputBytes=len(changed), inputSha256=hashlib.sha256(changed).hexdigest())
        with self.assertRaisesRegex(ValueError, "removal target changed"):
            filter_local_ide_metadata(changed, rule)


if __name__ == '__main__':
    unittest.main()
