#!/usr/bin/env bash
set -euo pipefail

project_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$project_root"

./scripts/bootstrap-platform.sh
platform_path="$project_root/.tools/platform/idea-2026.1.4/IntelliJ IDEA.app"
export JAVA_HOME="$platform_path/Contents/jbr/Contents/Home"
tools_directory="$project_root/.tools/release-tools/darwin-arm64"
tools_report="$project_root/.tools/package-tools-darwin-arm64.json"
python3 scripts/bundle-go-tools.py --target darwin-arm64 --output "$tools_directory" --report "$tools_report"
pinned_builder="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["builderGoExecutable"])' "$tools_report")"
if [[ -n "${OPENGOLAND_GO_BUILDER:-}" && "$OPENGOLAND_GO_BUILDER" != "$pinned_builder" ]]; then
    echo "The product must use the authenticated Go builder from product/go-tools.lock.json." >&2
    exit 1
fi
export OPENGOLAND_GO_BUILDER="$pinned_builder"
export GOROOT="$(dirname "$(dirname "$pinned_builder")")"
export GOTOOLCHAIN=local
export PATH="$(dirname "$pinned_builder"):$PATH"
export OPENGOLAND_GO_TEST="${OPENGOLAND_GO_TEST:-$tools_directory/go/bin/go}"
export OPENGOLAND_GOPLS_TEST="${OPENGOLAND_GOPLS_TEST:-$tools_directory/bin/gopls}"
gradle_arguments=("-PplatformPath=$platform_path")
if [[ -n "${OPENGOLAND_RELEASE_VERSION:-}" ]]; then
    gradle_arguments+=("-PreleaseVersion=$OPENGOLAND_RELEASE_VERSION")
fi
if [[ -n "${OPENGOLAND_GO_BUILDER:-}" ]]; then
    gradle_arguments+=("-PgoBuilder=$OPENGOLAND_GO_BUILDER")
fi
./gradlew "${gradle_arguments[@]}" test buildPlugin prepareSandbox verifyPluginStructure
exec python3 scripts/package-product.py \
    --sdk "$platform_path" \
    --plugins "$project_root/build/idea-sandbox/opengoland/IC-2026.1.4/plugins" \
    "$@"
