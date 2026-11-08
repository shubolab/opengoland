#!/usr/bin/env bash
set -euo pipefail

project_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$project_root"

if [[ "$(uname -s)" != Darwin || "$(uname -m)" != arm64 ]]; then
    echo "The pinned bootstrap currently supports macOS Apple silicon only." >&2
    exit 1
fi

platform_root="$project_root/.tools/platform/idea-2026.1.4"
if [[ -f "$platform_root/.openidea-platform.json" ]]; then
    python3 scripts/platform-info.py "$platform_root/IntelliJ IDEA.app"
    exit 0
fi

mkdir -p "$project_root/.tools/downloads" "$project_root/.tools/platform"
artifact="$project_root/.tools/downloads/idea-2026.1.4-aarch64.dmg"
if [[ ! -f "$artifact" || "$(stat -f%z "$artifact")" != 792341526 ]]; then
    curl --fail --location --retry 3 --connect-timeout 15 --max-time 1800 \
        --continue-at - --output "$artifact" \
        'https://github.com/JetBrains/intellij-community/releases/download/idea/2026.1.4/idea-2026.1.4-aarch64.dmg'
fi

python3 - "$artifact" <<'PY'
import hashlib, json, pathlib, sys
artifact = pathlib.Path(sys.argv[1])
pin = json.loads(pathlib.Path("product/platform.lock.json").read_text())["macOS-aarch64"]
if artifact.stat().st_size != pin["size"]:
    raise SystemExit("Platform download has an unexpected size")
with artifact.open("rb") as source:
    hasher = hashlib.sha256()
    for block in iter(lambda: source.read(1024 * 1024), b""):
        hasher.update(block)
    digest = hasher.hexdigest()
if digest != pin["sha256"]:
    raise SystemExit(f"Platform SHA-256 mismatch: {digest}")
print(f"Verified official open-source platform: {digest}")
PY

mount_root="$(mktemp -d "$project_root/.tools/platform-mount.XXXXXX")"
cleanup() {
    hdiutil detach "$mount_root" -quiet >/dev/null 2>&1 || true
    rmdir "$mount_root" >/dev/null 2>&1 || true
}
trap cleanup EXIT
hdiutil attach "$artifact" -readonly -nobrowse -mountpoint "$mount_root" -quiet
source_app="$(find "$mount_root" -maxdepth 1 -type d -name '*.app' -print -quit)"
if [[ -z "$source_app" ]]; then
    echo "Verified installer does not contain an application bundle." >&2
    exit 1
fi

staging="$(mktemp -d "$project_root/.tools/platform/idea-2026.1.4.XXXXXX")"
ditto "$source_app" "$staging/IntelliJ IDEA.app"
cp product/platform.lock.json "$staging/.openidea-platform.json"
mv "$staging" "$platform_root"
python3 scripts/platform-info.py "$platform_root/IntelliJ IDEA.app"
