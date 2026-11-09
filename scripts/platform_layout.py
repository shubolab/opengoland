"""SDK layout and process-owned cache locks for native build scripts."""

from contextlib import contextmanager
from dataclasses import dataclass
import errno
import json
import os
from pathlib import Path
import time


@dataclass(frozen=True)
class PlatformLayout:
    sdk_root: Path
    contents: Path
    product_info: Path
    java_home: Path
    executable_suffix: str
    platform: str

    @property
    def lib(self) -> Path:
        return self.contents / "lib"

    def java_tool(self, name: str) -> Path:
        if name not in {"java", "javac", "jcmd"}:
            raise ValueError("Unsupported JBR tool name")
        return self.java_home / "bin" / (name + self.executable_suffix)

    @property
    def java(self) -> Path:
        return self.java_tool("java")

    @property
    def javac(self) -> Path:
        return self.java_tool("javac")

    @property
    def jcmd(self) -> Path:
        return self.java_tool("jcmd")


def detect_platform_layout(sdk: Path) -> PlatformLayout:
    """Accept a macOS .app/Contents or a flat Windows/Linux SDK root."""
    root = Path(sdk).resolve()
    candidates = [
        (root, root / "Contents", root / "Contents/Resources/product-info.json", True),
        (root.parent, root, root / "Resources/product-info.json", True),
        (root, root, root / "product-info.json", False),
    ]
    matches = [candidate for candidate in candidates if candidate[2].is_file()]
    if len(matches) != 1:
        raise ValueError("Expected one product-info.json in a native SDK layout")
    sdk_root, contents, info_path, macos = matches[0]
    try:
        info = json.loads(info_path.read_text(encoding="utf-8"))
    except (UnicodeError, json.JSONDecodeError) as error:
        raise ValueError("Invalid SDK product-info.json") from error
    if not isinstance(info, dict):
        raise ValueError("SDK product-info.json must be an object")
    if macos:
        return PlatformLayout(sdk_root, contents, info_path,
                              contents / "jbr/Contents/Home", "", "macos")
    launches = info.get("launch", [])
    windows = ((isinstance(launches, list) and any(
        isinstance(item, dict) and str(item.get("os", "")).casefold() == "windows"
        for item in launches)) or (contents / "jbr/bin/java.exe").is_file())
    return PlatformLayout(sdk_root, contents, info_path, contents / "jbr",
                          ".exe" if windows else "", "windows" if windows else "linux")


@contextmanager
def cache_lock(path: Path):
    """Hold an OS lock released on close/process exit; never use stale markers."""
    with Path(path).open("a+b") as guard:
        if os.name == "nt":
            import msvcrt

            guard.seek(0, os.SEEK_END)
            if guard.tell() == 0:
                guard.write(b"\0")
                guard.flush()
            while True:
                guard.seek(0)
                try:
                    msvcrt.locking(guard.fileno(), msvcrt.LK_NBLCK, 1)
                    break
                except OSError as error:
                    if error.errno not in {errno.EACCES, errno.EAGAIN, errno.EDEADLK}:
                        raise
                    time.sleep(0.1)
            try:
                yield
            finally:
                guard.seek(0)
                msvcrt.locking(guard.fileno(), msvcrt.LK_UNLCK, 1)
        else:
            import fcntl

            fcntl.flock(guard, fcntl.LOCK_EX)
            try:
                yield
            finally:
                fcntl.flock(guard, fcntl.LOCK_UN)


def java_compiler_command(javac: Path, arguments: list[str], argument_file: Path) -> list[str]:
    """Avoid Windows' command-line limit while keeping the macOS argv unchanged."""
    if os.name != "nt":
        return [str(javac), *arguments]
    rendered = []
    for argument in arguments:
        if any(char in argument for char in "\r\n\0"):
            raise ValueError("Compiler argument contains a line break or NUL")
        rendered.append('"' + argument.replace("\\", "\\\\").replace('"', '\\"') + '"')
    Path(argument_file).write_text("\n".join(rendered) + "\n", encoding="utf-8")
    return [str(javac), "-J-Dfile.encoding=UTF-8", "@" + str(argument_file)]
