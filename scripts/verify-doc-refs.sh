#!/usr/bin/env bash
# Checks that local markdown link targets ([text](path)) in documentation
# files exist. External links and fragment-only anchors are ignored.
#
# Usage:
#   scripts/verify-doc-refs.sh [root-dir]
#
# Exit codes:
#   0  passed
#   1  broken local link target detected
#   2  environment error
set -euo pipefail

script_dir="$(cd "$(dirname "$0")" && pwd)"
repo_root="$(cd "$script_dir/.." && pwd)"
root="${1:-$repo_root}"

if [[ ! -d "$root" ]]; then
    echo "ERROR: root directory not found: $root" >&2
    exit 2
fi

extract_local_markdown_links() {
    awk '
        {
            line = $0
            while (match(line, /!?\[[^]]*\]\([^)]*\)/)) {
                link = substr(line, RSTART, RLENGTH)
                line = substr(line, RSTART + RLENGTH)

                if (substr(link, 1, 1) == "!") {
                    continue
                }

                sub(/^!?\[[^]]*\]\(/, "", link)
                sub(/\)$/, "", link)

                if (link ~ /^</) {
                    sub(/^</, "", link)
                    sub(/>.*/, "", link)
                } else {
                    sub(/[[:space:]].*/, "", link)
                }

                sub(/#.*/, "", link)

                if (link == "" ||
                    link ~ /^[a-zA-Z][a-zA-Z0-9+.-]*:/) {
                    continue
                }

                print link
            }
        }
    ' "$1" | sort -u
}

broken=0

while IFS= read -r md; do
    rel="${md#"$root"/}"
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
    done < <(extract_local_markdown_links "$md")
done < <(
    {
        for docs_dir in "$root/docs" "$root/spec"; do
            if [[ -d "$docs_dir" ]]; then
                find "$docs_dir" -name '*.md' -type f
            fi
        done
        for top_level_doc in "$root/README.md" "$root/CHANGELOG.md"; do
            if [[ -f "$top_level_doc" ]]; then
                printf '%s\n' "$top_level_doc"
            fi
        done
    } | sort
)

if [[ "$broken" -gt 0 ]]; then
    echo "$broken broken documentation link(s)"
    exit 1
fi
echo "All documentation links OK."
