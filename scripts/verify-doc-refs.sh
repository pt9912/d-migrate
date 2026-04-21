#!/usr/bin/env bash
# Checks that markdown link targets ([text](path)) in docs/ exist.
# External HTTP(S) links are ignored.
#
# Usage: scripts/verify-doc-refs.sh [root-dir]
#   root-dir defaults to the repository root (parent of this script's dir).

set -euo pipefail

ROOT="${1:-$(cd "$(dirname "$0")/.." && pwd)}"
DOCS="$ROOT/docs"
broken=0

while IFS= read -r md; do
    rel="${md#"$ROOT"/}"
    while IFS= read -r target; do
        if [[ "$target" == /* ]]; then
            resolved="$target"
        else
            resolved="$(dirname "$md")/$target"
        fi
        if [[ ! -e "$resolved" ]]; then
            echo "BROKEN: $rel -> $target"
            ((++broken))
        fi
    done < <(grep -oP '\[.*?\]\(\K(?!https?://)[^)#\s]+' "$md" 2>/dev/null | sort -u)
done < <(find "$DOCS" -name '*.md' -type f | sort)

if [[ "$broken" -gt 0 ]]; then
    echo "$broken broken doc link(s) in docs/"
    exit 1
fi
echo "All doc links OK."
