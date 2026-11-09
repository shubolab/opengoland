"""Pinned, in-memory transformations of our copied OSS product metadata.

The v2 format is defined by IntelliJ's public ``classPath/classpath.kt`` writer
and ``PluginDescriptorLoader.kt`` reader at commit
456919a9624bae72ac12efafc35d2b916cb0c5c5. This module performs no filesystem work.
"""

from dataclasses import dataclass
import hashlib
import re
import struct
import xml.etree.ElementTree as ET


@dataclass(frozen=True)
class _PluginRecord:
    start: int
    end: int
    directory: str
    plugin_id: str | None
    descriptor: bytes
    files: tuple[str, ...]
    modules: tuple[str, ...]


@dataclass(frozen=True)
class _PluginClasspath:
    version: int
    jar_only: int
    core: bytes
    count_offset: int
    records: tuple[_PluginRecord, ...]


def _sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def _xml(data: bytes) -> ET.Element:
    # This pinned cache contains expanded XML, with no DTD/entity declarations.
    if b"<!DOCTYPE" in data or b"<!ENTITY" in data:
        raise ValueError("Plugin classpath XML must not declare DTDs or entities")
    try:
        root = ET.fromstring(data)
    except ET.ParseError as error:
        raise ValueError("Invalid plugin classpath XML") from error
    if root.tag != "idea-plugin":
        raise ValueError("Expected an idea-plugin root in the classpath cache")
    return root


def _parse_plugin_classpath(data: bytes) -> _PluginClasspath:
    if len(data) < 8 or data[0] != 2 or data[1] != 1:
        raise ValueError("Expected the pinned jar-only plugin classpath v2 format")
    cursor = 2

    def take(size: int) -> bytes:
        nonlocal cursor
        if size < 0 or size > len(data) - cursor:
            raise ValueError("Truncated or invalid plugin classpath field length")
        result = data[cursor:cursor + size]
        cursor += size
        return result

    def integer(fmt: str) -> int:
        return struct.unpack(fmt, take(struct.calcsize(fmt)))[0]

    def utf() -> str:
        value = take(integer(">H"))
        # All paths in the authenticated SDK input are ASCII, whose encoding is
        # identical under Java DataInputStream.readUTF's modified UTF-8. Keep
        # every original field slice; do not re-encode arbitrary UTF strings.
        if not value or not value.isascii() or b"\0" in value:
            raise ValueError("Unexpected non-ASCII or empty path in pinned classpath cache")
        return value.decode("ascii")

    core = take(integer(">i"))
    _xml(core)
    count_offset = cursor
    count = integer(">H")
    records = []
    for _ in range(count):
        start = cursor
        file_count = integer(">H")
        if not file_count:
            raise ValueError("Plugin classpath record must contain a file")
        directory = utf()
        descriptor = take(integer(">i"))
        root = _xml(descriptor)
        files = tuple(utf() for _ in range(file_count))
        records.append(_PluginRecord(
            start, cursor, directory, root.findtext("id"), descriptor, files,
            tuple(module.get("name", "") for module in root.findall("content/module")),
        ))
    if cursor != len(data):
        raise ValueError("Unexpected trailing bytes in plugin classpath cache")
    return _PluginClasspath(data[0], data[1], core, count_offset, tuple(records))


def _policy_value(policy: dict, key: str, expected_type: type):
    value = policy.get(key)
    if type(value) is not expected_type:
        raise ValueError(f"Invalid or missing plugin classpath policy field: {key}")
    return value


def _policy_sha(policy: dict, key: str) -> str:
    value = _policy_value(policy, key, str)
    if re.fullmatch(r"[0-9a-f]{64}", value) is None:
        raise ValueError(f"Invalid SHA-256 in plugin classpath policy: {key}")
    return value


def filter_plugin_classpath(data: bytes, policy: dict) -> tuple[bytes, dict]:
    """Remove only the authenticated Java IDE customization record.

    ``policy`` is the ``pluginClasspath`` section of the Go product pin. Unknown
    bytes, layouts, targets or output hashes fail closed with ``ValueError``.
    The caller alone is responsible for writing the result inside its owned
    product copy. This function never modifies the input or deletes the index.
    """
    if type(data) is not bytes or type(policy) is not dict:
        raise ValueError("Plugin classpath data and policy must be bytes and a dictionary")
    integers = {key: _policy_value(policy, key, int) for key in (
        "formatVersion", "jarOnly", "upstreamBytes", "pluginCountBefore",
        "pluginCountAfter", "outputBytes",
    )}
    digests = {key: _policy_sha(policy, key) for key in (
        "upstreamSha256", "coreXmlSha256", "excludedRecordSha256",
        "excludedXmlSha256", "outputSha256",
    )}
    path = _policy_value(policy, "path", str)
    directory = _policy_value(policy, "excludedDirectory", str)
    plugin_id = _policy_value(policy, "excludedPluginId", str)
    if (path != "plugins/plugin-classpath.txt" or directory != "java-ide-customization"
            or plugin_id != "com.intellij.java.ide"):
        raise ValueError("Unsupported plugin classpath exclusion target")
    if (integers["formatVersion"] != 2 or integers["jarOnly"] != 1
            or integers["pluginCountBefore"] < 1
            or integers["pluginCountAfter"] != integers["pluginCountBefore"] - 1):
        raise ValueError("Unsupported plugin classpath policy format or counts")
    if (len(data) != integers["upstreamBytes"]
            or _sha256(data) != digests["upstreamSha256"]):
        raise ValueError("The pinned upstream plugin classpath cache changed")

    original = _parse_plugin_classpath(data)
    if (len(original.records) != integers["pluginCountBefore"]
            or _sha256(original.core) != digests["coreXmlSha256"]):
        raise ValueError("The pinned core descriptor or plugin count changed")
    matches = [(index, record) for index, record in enumerate(original.records)
               if record.directory == directory or record.plugin_id == plugin_id]
    if (len(matches) != 1 or matches[0][1].directory != directory
            or matches[0][1].plugin_id != plugin_id):
        raise ValueError("Expected exactly one matching Java IDE customization record")
    ordinal, excluded = matches[0]
    excluded_raw = data[excluded.start:excluded.end]
    if (_sha256(excluded_raw) != digests["excludedRecordSha256"]
            or _sha256(excluded.descriptor) != digests["excludedXmlSha256"]):
        raise ValueError("The pinned excluded plugin classpath record changed")

    offset = original.count_offset
    result = (data[:offset] + struct.pack(">H", len(original.records) - 1)
              + data[offset + 2:excluded.start] + data[excluded.end:])
    if (len(result) != integers["outputBytes"]
            or _sha256(result) != digests["outputSha256"]):
        raise ValueError("The filtered plugin classpath cache does not match its output pin")
    filtered = _parse_plugin_classpath(result)
    kept = original.records[:ordinal] + original.records[ordinal + 1:]
    if (len(filtered.records) != integers["pluginCountAfter"]
            or filtered.count_offset != offset or result[:offset] != data[:offset]
            or filtered.core != original.core):
        raise ValueError("Filtering changed the classpath header, core or record count")
    retained_hashes = []
    for before, after in zip(kept, filtered.records, strict=True):
        raw_before = data[before.start:before.end]
        raw_after = result[after.start:after.end]
        digest = _sha256(raw_before)
        if raw_before != raw_after or _sha256(raw_after) != digest:
            raise ValueError("Filtering changed a retained plugin classpath record")
        retained_hashes.append({"directory": before.directory, "sha256": digest})
    for token in (plugin_id, directory, "intellij.java.ide.customization"):
        if token.encode("ascii") in result:
            raise ValueError("Excluded plugin identity remains in the filtered classpath cache")

    record = {
        "path": path, "formatVersion": original.version, "jarOnly": original.jar_only,
        "before": {"bytes": len(data), "sha256": digests["upstreamSha256"],
                   "pluginCount": len(original.records)},
        "after": {"bytes": len(result), "sha256": digests["outputSha256"],
                  "pluginCount": len(filtered.records)},
        "excluded": {"id": plugin_id, "directory": directory, "ordinal": ordinal,
                     "bytes": len(excluded_raw), "recordSha256": digests["excludedRecordSha256"],
                     "descriptorBytes": len(excluded.descriptor),
                     "descriptorSha256": digests["excludedXmlSha256"],
                     "files": list(excluded.files), "moduleIds": list(excluded.modules)},
        "preserved": {"headerAndCoreByteEqual": True,
                      "coreXmlBytes": len(original.core), "coreXmlSha256": digests["coreXmlSha256"],
                      "retainedRawRecordsByteEqual": True, "retainedRecords": retained_hashes},
    }
    return result, record
