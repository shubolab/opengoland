"""Pure, pinned removal of unwanted account and promotion registrations.

Only our product assembler writes these returned bytes. XML edits preserve all
unselected bytes, including CDATA descriptors. The distribution index keeps the
v2 fast-loader format and all unmodified plugin records. No runtime service is
replaced and no license or authentication result is fabricated.
"""

from dataclasses import dataclass
import hashlib
import re
import struct
from xml.parsers import expat

from product_metadata import _parse_plugin_classpath


_MODULES = {
    "intellij.settingsSync.core", "intellij.vcs.github.ultimatePromo",
    "intellij.toml.grazie", "intellij.kotlin.grazie",
}
_TRY_ULTIMATE = "com.intellij.openapi.updateSettings.impl.upgradeToUltimate.installation.install.TryUltimateActionHandler"
_ADVERTISER = "com.intellij.openapi.updateSettings.impl.pluginsAdvertisement."
_CONSENT_CONFIGURABLE = {
    "parentId": "preferences.general", "instance": "com.intellij.ide.gdpr.ConsentConfigurable",
    "id": "consents", "bundle": "messages.IdeBundle", "key": "consent.configurable",
}
_PATCHED_PLUGINS = {
    "toml": "org.toml.lang", "maven": "org.jetbrains.idea.maven",
    "vcs-github": "org.jetbrains.plugins.github",
}


def _sha(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def _value(rule: dict, key: str, kind: type):
    if type(rule) is not dict:
        raise ValueError("Local IDE metadata rule must be a dictionary")
    value = rule.get(key)
    if type(value) is not kind:
        raise ValueError(f"Invalid or missing local IDE metadata rule: {key}")
    return value


def _digest(rule: dict, key: str) -> str:
    value = _value(rule, key, str)
    if re.fullmatch(r"[0-9a-f]{64}", value) is None:
        raise ValueError(f"Invalid local IDE metadata SHA-256: {key}")
    return value


def _authenticate(data: bytes, rule: dict, prefix: str) -> None:
    if (len(data) != _value(rule, prefix + "Bytes", int)
            or _sha(data) != _digest(rule, prefix + "Sha256")):
        raise ValueError(f"Local IDE metadata {prefix} bytes changed: {rule.get('path')}")


@dataclass(frozen=True)
class _Element:
    tag: str
    attributes: dict
    parent: str | None
    text: str
    start: int
    end: int


def _elements(data: bytes) -> list[_Element]:
    parser = expat.ParserCreate()
    stack = []
    elements = []

    def reject_declaration(*unused):
        raise ValueError("Local IDE XML must not contain DTD or entity declarations")

    def start(tag, attributes):
        at = parser.CurrentByteIndex
        quote = None
        while at < len(data):
            char = data[at]
            if quote is not None:
                if char == quote:
                    quote = None
            elif char in (ord('"'), ord("'")):
                quote = char
            elif char == ord(">"):
                break
            at += 1
        if at >= len(data):
            raise ValueError("Unclosed local IDE XML start tag")
        empty = data[parser.CurrentByteIndex:at].rstrip().endswith(b"/")
        stack.append((tag, attributes, stack[-1][0] if stack else None,
                      parser.CurrentByteIndex, [] if tag == "depends" else None, empty))

    def text(value):
        if stack and stack[-1][4] is not None:
            stack[-1][4].append(value)

    def end(tag):
        name, attributes, parent, begin, fragments, empty = stack.pop()
        if name != tag:
            raise ValueError("Invalid local IDE XML element nesting")
        finish = parser.CurrentByteIndex
        # Expat reports the closing tag start for normal elements, but the
        # position after '/>' for a self-closing element. CDATA contents are
        # character data, so inner descriptor tags cannot terminate this node.
        if not empty:
            if data[finish:finish + 2] != b"</":
                raise ValueError("Invalid local IDE XML closing tag")
            close = data.find(b">", finish)
            if close < 0:
                raise ValueError("Unclosed local IDE XML element")
            finish = close + 1
        elements.append(_Element(name, attributes, parent,
                                 "".join(fragments).strip() if fragments is not None else "",
                                 begin, finish))

    parser.StartElementHandler = start
    parser.CharacterDataHandler = text
    parser.EndElementHandler = end
    parser.StartDoctypeDeclHandler = reject_declaration
    parser.EntityDeclHandler = reject_declaration
    parser.ExternalEntityRefHandler = reject_declaration
    try:
        parser.Parse(data, True)
    except expat.ExpatError as error:
        raise ValueError("Invalid local IDE metadata XML") from error
    return elements


def _allowed_selector(selector: dict) -> None:
    tag = _value(selector, "tag", str)
    attributes = _value(selector, "attributes", dict)
    parent = _value(selector, "parent", str)
    if (tag == "module" and parent == "content"
            and attributes.get("name") in _MODULES):
        return
    if (tag == "openAnotherToolHandler" and parent == "extensions"
            and attributes == {"implementation": _TRY_ULTIMATE}):
        return
    if tag == "applicationConfigurable" and parent == "extensions" and attributes == _CONSENT_CONFIGURABLE:
        return
    if (parent == "extensions" and (tag, attributes.get("implementation")) in {
        ("editorNotificationProvider", _ADVERTISER + "PluginAdvertiserEditorNotificationProvider"),
        ("backgroundPostStartupActivity", _ADVERTISER + "PluginsAdvertiserStartupActivity"),
    } and set(attributes) == {"implementation"}):
        return
    if (tag == "action" and parent == "actions" and attributes == {
        "id": "CheckSuggestedPlugins", "class": _ADVERTISER + "CheckSuggestedPluginsAction",
    }):
        return
    if (tag == "depends" and parent == "idea-plugin"
            and attributes == {"optional": "true", "config-file": "grazie-support.xml"}
            and selector.get("text") == "tanvd.grazi"):
        return
    raise ValueError("Unsupported local IDE XML removal selector")


def _selected_spans(data: bytes, removals: list) -> list[tuple[_Element, dict]]:
    elements = _elements(data)
    selected = []
    for removal in removals:
        if type(removal) is not dict:
            raise ValueError("Invalid local IDE XML removal rule")
        _allowed_selector(removal)
        matches = [item for item in elements
                   if item.tag == removal["tag"] and item.parent == removal["parent"]
                   and item.attributes == removal["attributes"]
                   and ("text" not in removal or item.text == removal["text"])]
        if len(matches) != 1:
            raise ValueError("Expected exactly one local IDE XML removal target")
        item = matches[0]
        raw = data[item.start:item.end]
        if (len(raw) != _value(removal, "bytes", int)
                or _sha(raw) != _digest(removal, "sha256")):
            raise ValueError("Local IDE XML removal target changed")
        selected.append((item, removal))
    selected.sort(key=lambda item: item[0].start)
    if any(left[0].end > right[0].start for left, right in zip(selected, selected[1:])):
        raise ValueError("Overlapping or repeated local IDE XML removal targets")
    return selected


def _absent(data: bytes, rule: dict) -> None:
    for token in _value(rule, "absentTokens", list):
        if type(token) is not str or not token:
            raise ValueError("Invalid local IDE metadata absence token")
        if token.encode("utf-8") in data:
            raise ValueError(f"Removed registration remains in local IDE metadata: {token}")


def _filter_xml(data: bytes, rule: dict) -> tuple[bytes, dict]:
    removals = _value(rule, "removals", list)
    if not removals:
        raise ValueError("Local IDE XML transformation must remove a registration")
    selected = _selected_spans(data, removals)
    chunks = []
    cursor = 0
    for item, unused in selected:
        chunks.append(data[cursor:item.start])
        cursor = item.end
    chunks.append(data[cursor:])
    result = b"".join(chunks)
    _authenticate(result, rule, "output")
    _elements(result)
    _absent(result, rule)
    return result, {
        "path": rule["path"], "kind": "xml",
        "before": {"bytes": len(data), "sha256": _sha(data)},
        "after": {"bytes": len(result), "sha256": _sha(result)},
        "removed": [{"tag": item.tag, "attributes": item.attributes,
                     "parent": item.parent, "bytes": item.end - item.start,
                     "sha256": _sha(data[item.start:item.end])} for item, unused in selected],
        "otherRawBytesPreserved": True,
    }


def _filter_classpath(data: bytes, rule: dict) -> tuple[bytes, dict]:
    if rule["path"] != "plugins/plugin-classpath.txt":
        raise ValueError("Unexpected local IDE classpath index path")
    original = _parse_plugin_classpath(data)
    if any(item.directory == "java-ide-customization" or item.plugin_id == "com.intellij.java.ide"
           for item in original.records):
        raise ValueError("Local IDE filtering requires the earlier Java IDE classpath filter")
    if len(original.records) != _value(rule, "pluginCountBefore", int):
        raise ValueError("Local IDE classpath input plugin count changed")
    core, core_record = filter_local_ide_metadata(original.core, _value(rule, "core", dict))
    dropped = _value(rule, "dropPlugins", list)
    if (len(dropped) != 1 or type(dropped[0]) is not dict
            or dropped[0].get("directory") != "grazie"
            or dropped[0].get("id") != "tanvd.grazi"):
        raise ValueError("Only the pinned Grazie plugin may be removed by this rule")
    patches = _value(rule, "pluginDescriptors", list)
    patch_by_directory = {}
    for patch in patches:
        directory = _value(patch, "directory", str)
        if (directory in patch_by_directory
                or _PATCHED_PLUGINS.get(directory) != _value(patch, "id", str)):
            raise ValueError("Unsupported or repeated local IDE plugin descriptor patch")
        patch_by_directory[directory] = patch
    if set(patch_by_directory) != set(_PATCHED_PLUGINS):
        raise ValueError("Missing local IDE plugin descriptor patch")
    chunks = []
    expected = []
    dropped_records = []
    patched_records = []
    unchanged = []
    seen = set()
    for item in original.records:
        raw = data[item.start:item.end]
        if item.directory in seen:
            raise ValueError("Repeated plugin directory in local IDE classpath index")
        seen.add(item.directory)
        if item.directory == "grazie" or item.plugin_id == "tanvd.grazi":
            drop = dropped[0]
            if (item.directory != drop["directory"] or item.plugin_id != drop["id"]
                    or _sha(raw) != _digest(drop, "recordSha256")
                    or _sha(item.descriptor) != _digest(drop, "descriptorSha256")):
                raise ValueError("Pinned Grazie classpath record changed")
            dropped_records.append({"directory": item.directory, "id": item.plugin_id,
                                    "bytes": len(raw), "sha256": _sha(raw)})
            continue
        patch = patch_by_directory.get(item.directory)
        if patch is not None:
            if (item.plugin_id != patch["id"]
                    or _sha(raw) != _digest(patch, "inputRecordSha256")):
                raise ValueError("Local IDE plugin classpath record changed")
            descriptor, descriptor_record = filter_local_ide_metadata(
                item.descriptor, _value(patch, "xml", dict))
            # The pinned parser accepts only ASCII directory strings, so Java's
            # modified UTF-8 directory length is exactly this byte length.
            length_at = item.start + 4 + len(item.directory.encode("ascii"))
            tail_at = length_at + 4 + len(item.descriptor)
            if data[length_at:length_at + 4] != struct.pack(">i", len(item.descriptor)):
                raise ValueError("Invalid plugin descriptor framing")
            raw = (data[item.start:length_at] + struct.pack(">i", len(descriptor))
                   + descriptor + data[tail_at:item.end])
            if _sha(raw) != _digest(patch, "outputRecordSha256"):
                raise ValueError("Filtered plugin classpath record output changed")
            patched_records.append({"directory": item.directory, "id": item.plugin_id,
                                    "sha256": _sha(raw), "fileListRawBytesPreserved": True,
                                    "descriptor": descriptor_record})
        else:
            unchanged.append({"directory": item.directory, "sha256": _sha(raw)})
        chunks.append(raw)
        expected.append(item)
    if (len(dropped_records) != 1 or len(patched_records) != len(patches)
            or len(expected) != _value(rule, "pluginCountAfter", int)):
        raise ValueError("Local IDE classpath removal/patch count mismatch")
    result = (data[:2] + struct.pack(">i", len(core)) + core
              + struct.pack(">H", len(expected)) + b"".join(chunks))
    _authenticate(result, rule, "output")
    filtered = _parse_plugin_classpath(result)
    if filtered.core != core or len(filtered.records) != len(expected):
        raise ValueError("Filtered local IDE classpath framing changed")
    for before, after, chunk in zip(expected, filtered.records, chunks, strict=True):
        if (before.directory != after.directory or before.plugin_id != after.plugin_id
                or before.files != after.files
                or result[after.start:after.end] != chunk
                or _sha(result[after.start:after.end]) != _sha(chunk)):
            raise ValueError("Filtered classpath record or file list changed")
    _absent(result, rule)
    return result, {
        "path": rule["path"], "kind": "plugin-classpath",
        "before": {"bytes": len(data), "sha256": _sha(data), "pluginCount": len(original.records)},
        "after": {"bytes": len(result), "sha256": _sha(result), "pluginCount": len(filtered.records)},
        "core": core_record, "droppedPlugins": dropped_records,
        "patchedPlugins": patched_records, "unchangedRawRecords": unchanged,
        "otherRawRecordsPreserved": True, "retainedFileListsPreserved": True,
    }


def filter_local_ide_metadata(data: bytes, rule: dict) -> tuple[bytes, dict]:
    """Apply one authenticated XML/index rule and return bytes plus manifest facts.

    Classpath input must be the result of the earlier Java IDE customization
    filter. The caller must separately validate and remove the physical plugin
    files/module repository entries specified by the distribution pin.
    """
    if type(data) is not bytes or type(rule) is not dict:
        raise ValueError("Local IDE metadata requires immutable bytes and a rule dictionary")
    _value(rule, "path", str)
    kind = _value(rule, "kind", str)
    _authenticate(data, rule, "input")
    if kind == "xml":
        return _filter_xml(data, rule)
    if kind == "plugin-classpath":
        return _filter_classpath(data, rule)
    raise ValueError("Unsupported local IDE metadata rule kind")
