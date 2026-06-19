#!/usr/bin/env bash
# Fetch the pinned semgrep rule set into a gitignored cache (config/semgrep/*.yml).
#
# github.com/semgrep/semgrep-rules is licensed LGPL-2.1 + Commons Clause (GitHub
# reports spdx NOASSERTION). To keep this MIT repo clean we do NOT vendor those
# files — we cache them on demand, pinned to a commit and SHA256-verified, exactly
# like the sample-db dumps (ADR 0014). The cached *.yml are gitignored.
#
# Re-vendor/refresh: bump PIN_SHA + the expected sha256 below, delete the cache,
# re-run. The scan itself (`make semgrep`) runs offline against this cache.
set -euo pipefail

REPO="semgrep/semgrep-rules"
PIN_SHA="311ca4e9ba59d700624539bf658e3d29b134ee77"
DEST="$(cd "$(dirname "$0")/.." && pwd)/config/semgrep"

# repo-path | local-filename | expected-sha256
RULES=(
  "dockerfile/security/missing-user.yaml|dockerfile-security.yml|15d87423a39349f2c5f35534c981ff490c782be84de7aaf106dbc6a35dc3eb49"
  "python/lang/security/use-defused-xml.yaml|python-security.yml|dd676b0bf976948915170a3cc8804e0f4ece9b7d1e89ff84e7b983dd3c2ca871"
)

verify() { printf '%s  %s\n' "$2" "$1" | sha256sum -c --status 2>/dev/null; }

mkdir -p "$DEST"
for entry in "${RULES[@]}"; do
  IFS='|' read -r path name sha <<<"$entry"
  out="$DEST/$name"
  if [ -f "$out" ] && verify "$out" "$sha"; then
    echo "[semgrep-rules] cached + verified: $name"
    continue
  fi
  url="https://raw.githubusercontent.com/$REPO/$PIN_SHA/$path"
  echo "[semgrep-rules] fetching $name from $REPO@${PIN_SHA:0:12}"
  tmp="$(mktemp)"
  curl -fsSL "$url" -o "$tmp"
  if ! verify "$tmp" "$sha"; then
    echo "[semgrep-rules] FAIL: sha256 mismatch for $name (pin drift or tampering)" >&2
    echo "  expected $sha" >&2
    echo "  got      $(sha256sum "$tmp" | cut -d' ' -f1)" >&2
    rm -f "$tmp"
    exit 1
  fi
  mv "$tmp" "$out"
  echo "[semgrep-rules] cached: $name"
done
