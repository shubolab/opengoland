"""Apply authenticated source patches without host text or path translation."""
import hashlib
import os
from pathlib import Path
import subprocess


SOURCE_PATCH_RUNNER_SHA256 = hashlib.sha256(Path(__file__).read_bytes()).hexdigest()


def apply_source_patch(patch, source, expected_sha256, timeout=15):
    data = Path(patch).read_bytes()
    if hashlib.sha256(data).hexdigest() != expected_sha256:
        raise ValueError("Source patch digest mismatch: " + str(patch))
    command = ["patch", "--batch", "--fuzz=0", "-p1"]
    if os.name == "nt":
        # GNU patch otherwise translates significant CRLF bytes on Windows.
        # Apple's patch has no --binary option and already preserves bytes.
        command.append("--binary")
    # A native D:\\... -i argument also crosses the MSYS path boundary. Keep
    # patch contents as bytes on stdin; cwd is handled by native subprocess APIs.
    try:
        result = subprocess.run(command, cwd=source, input=data, capture_output=True, timeout=timeout)
    except subprocess.TimeoutExpired as error:
        raise ValueError(_failure("timed out", error.stdout, error.stderr)) from error
    if result.returncode:
        raise ValueError(_failure("exited " + str(result.returncode), result.stdout, result.stderr))
    return result


def _failure(status, stdout, stderr):
    def display(data):
        return (data or b"").decode("utf-8", errors="replace")
    return "Applying the authenticated source patch " + status + "\nstdout:\n" + display(stdout) + "\nstderr:\n" + display(stderr)
