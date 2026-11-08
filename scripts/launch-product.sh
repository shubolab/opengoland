#!/usr/bin/env bash
set -euo pipefail

project_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
application="$project_root/build/product/open Goland.app"
if [[ ! -f "$application/Contents/Resources/openidea-product.json" ]]; then
    echo "Build the independent app first with scripts/package-product.sh." >&2
    exit 1
fi
exec /usr/bin/open -a "$application" "$@"
