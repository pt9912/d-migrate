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

# --- Sakila (MySQL) — Phase 2 ------------------------------------
# jOOQ/sakila liefert das MySQL-Sakila als Schema- + Daten-Datei
# getrennt (DELIMITER-Routinen im Schema, große INSERTs in den Daten).
# Beide auf denselben Commit-SHA gepinnt.
SAKILA_REPO="jOOQ/sakila"
SAKILA_COMMIT="e089a5b1ec9af0df7a9c6a5d47d49fa1736a4e84"
SAKILA_BASE="https://raw.githubusercontent.com/${SAKILA_REPO}/${SAKILA_COMMIT}/mysql-sakila-db"
SAKILA_SCHEMA_URL="$SAKILA_BASE/mysql-sakila-schema.sql"
SAKILA_SCHEMA_SHA256="f2c41c3bf6d6c239941b4f98fb37afad21f6be12b82bf586202529a793ccc2ee"
SAKILA_SCHEMA_DEST="$CACHE_DIR/sakila-schema.sql"
SAKILA_DATA_URL="$SAKILA_BASE/mysql-sakila-insert-data.sql"
SAKILA_DATA_SHA256="353ef858e4d2d1a60549969283434da15b905aef3ca7099d82b649b75f8de99f"
SAKILA_DATA_DEST="$CACHE_DIR/sakila-data.sql"

# --- Chinook (SQLite) — Phase 2b ----------------------------------
# lerocha/chinook-database liefert eine fertige SQLite-Binärdatei
# (Chinook_Sqlite.sqlite, Schema + Daten). Kein Server — die CLI
# arbeitet direkt gegen die bind-gemountete .db-Datei.
CHINOOK_REPO="lerocha/chinook-database"
CHINOOK_COMMIT="7f67772503d71ba90f19283c38e93923addb43fa"
CHINOOK_URL="https://raw.githubusercontent.com/${CHINOOK_REPO}/${CHINOOK_COMMIT}/ChinookDatabase/DataSources/Chinook_Sqlite.sqlite"
CHINOOK_SHA256="7651ba378ac2fcd0dfc3c66fb101f7a7eed3ba39a612ec642b96e20702061f15"
CHINOOK_DEST="$CACHE_DIR/chinook.db"

mkdir -p "$CACHE_DIR"
fetch_one "pagila"        "$PAGILA_URL"        "$PAGILA_DEST"        "$PAGILA_SHA256"
fetch_one "sakila-schema" "$SAKILA_SCHEMA_URL" "$SAKILA_SCHEMA_DEST" "$SAKILA_SCHEMA_SHA256"
fetch_one "sakila-data"   "$SAKILA_DATA_URL"   "$SAKILA_DATA_DEST"   "$SAKILA_DATA_SHA256"
fetch_one "chinook"       "$CHINOOK_URL"       "$CHINOOK_DEST"       "$CHINOOK_SHA256"
log "done."
