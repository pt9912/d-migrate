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

# --- Employees (MySQL) — Phase 3 (Scale) --------------------------
# datacharmer/test_db: das klassische große Employees-Beispiel (~4 Mio
# Zeilen, 6 Tabellen). employees.sql enthält das DDL UND `source
# load_*.dump`-Direktiven mit RELATIVEN Pfaden — deshalb landen alle
# Dateien zusammen in .cache/employees/, und der Loader im Smoke-Skript
# führt mysql mit cwd=.cache/employees aus. Jede Datei auf denselben
# Commit-SHA gepinnt + SHA256-verifiziert.
EMP_REPO="datacharmer/test_db"
EMP_COMMIT="e324b56193ca506ab7cc1ab143a9153d8c4535d7"
EMP_BASE="https://raw.githubusercontent.com/${EMP_REPO}/${EMP_COMMIT}"
EMP_DIR="$CACHE_DIR/employees"
# Datei -> SHA256 (am Pin-Commit berechnet). load_salaries* sind die
# Volumen-Treiber für den Scale-/Resume-Test.
EMP_FILES="\
employees.sql:cfe3f89f7b21326c516ba65d253e35e795877e9bb60c388520d915f348403a9a
load_departments.dump:2271cfef20852e395ec72ce269a119b2c799a973a9277c971409ea53d5a17cfa
load_dept_emp.dump:52cc6dbc1b139254533264bd5d44a6012377f34ecf1eef693ddfb349aeb40ed6
load_dept_manager.dump:d9cff691f09f2399f5490e435deb8c932946246aaf483b8f1cbef0bc556aa1dc
load_employees.dump:ba004ebc5fcdad59544fd8ced262d1793ca02c7936c5d7668a355c6a683d6fa8
load_salaries1.dump:aa485ea7b1553f1660d6db5a93e9ede0a0c182cb923f9471a39594f7ca967c5b
load_salaries2.dump:cad589bff736cb575358d7806e4e4a13e28a2e9c714c2fb51fbe4db74a5706fa
load_salaries3.dump:75fc473d2472341fbfd635d6f4853c63645051829a9c7a1a18ee819fe5816f45
load_titles.dump:dcd382989c46719e1e216ffef4919483f0f71d52da517c37c22d39cdaf9bc044"

mkdir -p "$CACHE_DIR"
fetch_one "pagila"        "$PAGILA_URL"        "$PAGILA_DEST"        "$PAGILA_SHA256"
fetch_one "sakila-schema" "$SAKILA_SCHEMA_URL" "$SAKILA_SCHEMA_DEST" "$SAKILA_SCHEMA_SHA256"
fetch_one "sakila-data"   "$SAKILA_DATA_URL"   "$SAKILA_DATA_DEST"   "$SAKILA_DATA_SHA256"
fetch_one "chinook"       "$CHINOOK_URL"       "$CHINOOK_DEST"       "$CHINOOK_SHA256"

# Employees nur fetchen, wenn angefordert (Phase 3 ist opt-in/nightly —
# kein PR-Gate). `FETCH_EMPLOYEES=1 ./fetch-dumps.sh` oder der
# Scale-Smoke setzt die Variable selbst.
if [ "${FETCH_EMPLOYEES:-0}" = "1" ]; then
    mkdir -p "$EMP_DIR"
    while IFS=: read -r f sha; do
        [ -n "$f" ] || continue
        fetch_one "employees/$f" "$EMP_BASE/$f" "$EMP_DIR/$f" "$sha"
    done <<EOF
$EMP_FILES
EOF
fi
log "done."
