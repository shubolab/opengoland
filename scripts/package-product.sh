#!/usr/bin/env bash
set -euo pipefail

project_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$project_root"

./scripts/bootstrap-platform.sh
platform_path="$project_root/.tools/platform/idea-2026.1.4/IntelliJ IDEA.app"
export JAVA_HOME="$platform_path/Contents/jbr/Contents/Home"
./gradlew -PplatformPath="$platform_path" test buildPlugin prepareSandbox verifyPluginStructure
exec python3 scripts/package-product.py \
    --sdk "$platform_path" \
    --plugins "$project_root/build/idea-sandbox/opengoland/IC-2026.1.4/plugins" \
    "$@"
