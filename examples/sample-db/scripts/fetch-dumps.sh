#!/usr/bin/env bash
# Sample-DB-Harness — On-Demand-Dump-Fetch
# Plan: docs/planning/in-progress/sample-db-integration-harness.md
# ADR:  docs/adr/0014-sample-db-harness-fetch-and-compose.md
#
# Lädt die GEPINNTEN, SHA256-verifizierten Sample-DB-Dumps in einen
# gitignored .cache/-Ordner (kein Dump im Repo). Idempotent: ein
# Cache-Hit mit passender SHA256 wird nicht erneut geladen.
#
# Pin-Disziplin (ADR 0014): Quelle ist ein Commit-SHA, NICHT ein
# Branch — sonst ist auch der Download nicht reproduzierbar.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
EXAMPLES_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
CACHE_DIR="$EXAMPLES_DIR/.cache"

log()  { printf '[fetch] %s\n' "$*"; }
fail() { printf '[fetch] FAIL: %s\n' "$*" >&2; exit 1; }

# --- Pagila (PostgreSQL) — Phase 1 ---------------------------------
# neondatabase-labs/postgres-sample-dbs/pagila.sql ist ein
# kombinierter pg_dump (Schema + Daten), lädt in einem psql-Lauf.
PAGILA_REPO="neondatabase-labs/postgres-sample-dbs"
PAGILA_COMMIT="ff2ccb50d16ca6309f93cc2682fb901fb9141331"
PAGILA_URL="https://raw.githubusercontent.com/${PAGILA_REPO}/${PAGILA_COMMIT}/pagila.sql"
PAGILA_SHA256="5f76fa094bca43ebcdc43c49b2314a130e374486c7b9432f1974763a81dc5809"
PAGILA_DEST="$CACHE_DIR/pagila.sql"

fetch_one() {
    local name="$1" url="$2" dest="$3" sha="$4"
    if [ -f "$dest" ] && printf '%s  %s' "$sha" "$dest" | sha256sum -c --status 2>/dev/null; then
        log "$name: cache hit (SHA256 OK) -> $dest"
        return 0
    fi
    command -v curl >/dev/null 2>&1 || fail "curl not found"
    log "$name: fetching pinned dump..."
    curl -fsSL --max-time 180 "$url" -o "$dest.tmp" || fail "$name: download failed"
    printf '%s  %s' "$sha" "$dest.tmp" | sha256sum -c --status 2>/dev/null \
        || { rm -f "$dest.tmp"; fail "$name: SHA256 mismatch (pin moved or corrupt download)"; }
    mv "$dest.tmp" "$dest"
    log "$name: fetched + SHA256-verified -> $dest"
}

mkdir -p "$CACHE_DIR"
fetch_one "pagila" "$PAGILA_URL" "$PAGILA_DEST" "$PAGILA_SHA256"
log "done."
