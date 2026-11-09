"""Keep rewritten product JARs readable by the platform's native ZIP loader."""

import hashlib
import json
import os
from pathlib import Path
import subprocess
import zipfile

from platform_layout import detect_platform_layout


def rewritten_zip_comment(source: zipfile.ZipFile) -> bytes:
    """Drop the pinned optimized-index trailer after changing ZIP entry offsets.

    ImmutableZipFile in public platform commit
    456919a9624bae72ac12efafc35d2b916cb0c5c5 recognizes a five-byte comment:
    version 4 followed by an absolute little-endian IKV offset. Copying it into a
    newly written ZIP leaves a stale offset. An absent trailer selects the
    platform's supported central-directory reader. The inert __index__ entry and
    all ordinary comments/notice entries are preserved.
    """
    comment = source.comment
    if len(comment) == 5 and comment[0] == 4:
        if "__index__" not in source.namelist():
            raise ValueError("Optimized ZIP trailer has no authenticated index entry")
        return b""
    return comment


def verify_product_archives(sdk: Path, contents: Path, relative_paths: list[str]) -> dict:
    """Read rewritten JARs with the actual platform loader before sealing them."""
    layout = detect_platform_layout(sdk)
    root = contents.resolve()
    paths = []
    for relative in sorted(set(relative_paths)):
        path = contents / relative
        if path.is_symlink() or not path.is_file() or not path.resolve().is_relative_to(root):
            raise ValueError("Archive verification requires an owned regular JAR: " + relative)
        paths.append(path.resolve())
    helper = Path(__file__).resolve().parent.parent / "product/patches/VerifyProductArchives.java"
    command = [str(layout.java), "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED",
               "--add-opens=java.base/jdk.internal.ref=ALL-UNNAMED", "--class-path",
               os.pathsep.join(str(layout.lib / name) for name in ("platform-loader.jar", "annotations.jar")),
               str(helper), *map(str, paths)]
    result = subprocess.run(command, text=True, capture_output=True, timeout=60)
    if result.returncode:
        raise ValueError("The actual IntelliJ archive reader rejected this product: " + result.stdout + result.stderr)
    report = json.loads(result.stdout)
    if report.get("status") != "pass" or len(report.get("archives", [])) != len(paths):
        raise ValueError("Incomplete IntelliJ archive verification report")
    for record, path in zip(report["archives"], paths, strict=True):
        if Path(record["path"]) != path or record["sha256"] != hashlib.sha256(path.read_bytes()).hexdigest():
            raise ValueError("An archive changed during IntelliJ reader verification")
        record["path"] = path.relative_to(root).as_posix()
    return {**report, "helper": helper.relative_to(helper.parent.parent.parent).as_posix(),
            "helperSha256": hashlib.sha256(helper.read_bytes()).hexdigest()}
