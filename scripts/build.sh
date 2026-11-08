#!/usr/bin/env bash
set -euo pipefail

project_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$project_root"

platform_path="${OPENIDEA_PLATFORM_PATH:-$project_root/.tools/platform/idea-2026.1.4/IntelliJ IDEA.app}"
if [[ -z "${OPENIDEA_PLATFORM_PATH:-}" && ! -d "$platform_path" ]]; then
    platform_path="$HOME/Applications/PyCharm.app"
fi
python3 scripts/platform-info.py "$platform_path"
if [[ -x "$platform_path/Contents/jbr/Contents/Home/bin/javac" ]]; then
    export JAVA_HOME="$platform_path/Contents/jbr/Contents/Home"
fi
exec ./gradlew -PplatformPath="$platform_path" test buildPlugin verifyPluginStructure "$@"
