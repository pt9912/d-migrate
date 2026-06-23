#!/usr/bin/env bash
# Sample-DB-Harness — TPC-H-Generierung (Phase 4, Sub-Slice 4a)
# Slice: docs/planning/in-progress/tpc-4a-sourcing-slice.md
# ADR:   docs/adr/0017-tpc-benchmark-workload-sourcing.md (Tool A DuckDB-tpch)
#        docs/adr/0014-sample-db-harness-fetch-and-compose.md (Pin/Compose)
#
# Erzeugt die TPC-H-Workload (8 Tabellen) reproduzierbar + OFFLINE aus dem
# gepinnten DuckDB-CLI v1.4.5 + gepinnter tpch-Extension (fetch-dumps.sh
# FETCH_TPCH=1). EXPORT DATABASE schreibt schema.sql + load.sql + 8 CSVs nach
# .cache/tpch/ (gitignored — KEIN Dump im Repo). Sourcing-Beleg für 4a; das
# Laden in eine Quell-DB + der Reverse/Generate/Transfer-Round-Trip sind 4b.
#
# Die tpch-Extension ist NICHT im CLI-Binary gebündelt (4a-Befund); ohne ihren
# Pin lädt DuckDB sie zur Laufzeit von extensions.duckdb.org. Sie ist
# mitgepinnt + wird aus Datei `LOAD`-ed, daher läuft der Loader unter
# `network_mode: none` (Hermetizitäts-Beleg).
#
# Opt-in (kein PR-Gate): `make sample-db-tpch-gen` (Default SF=0.01) oder
# `SF=0.1 make sample-db-tpch-gen`. Voraussetzung am Host: docker, docker compose.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
EXAMPLES_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
COMPOSE="docker compose -f $EXAMPLES_DIR/docker-compose.yml"
CACHE="$EXAMPLES_DIR/.cache"
TOOL="$CACHE/tpch-tool"
OUT="$CACHE/tpch"
SF="${SF:-0.01}"

log()  { printf '[tpch] %s\n' "$*"; }
fail() { printf '[tpch] FAIL: %s\n' "$*" >&2; exit 1; }

# --- 0. .env + Host-User (Schreibrechte im gemounteten .cache) ------
if [ ! -f "$EXAMPLES_DIR/.env" ]; then
    cp "$EXAMPLES_DIR/.env.example" "$EXAMPLES_DIR/.env"
    log "created examples/sample-db/.env from .env.example"
fi
export SAMPLE_DB_DMIGRATE_USER="$(id -u):$(id -g)"

# --- 1. gepinntes Generator-Tool holen (idempotent, SHA256) ---------
log "fetching pinned DuckDB CLI v1.4.5 + tpch extension (FETCH_TPCH=1)..."
FETCH_TPCH=1 "$SCRIPT_DIR/fetch-dumps.sh" > /tmp/tpch-fetch.log 2>&1 \
    || { cat /tmp/tpch-fetch.log; fail "generator tool fetch failed"; }
[ -x "$TOOL/duckdb" ]                 || fail "duckdb binary missing after fetch"
[ -f "$TOOL/tpch.duckdb_extension" ]  || fail "tpch extension missing after fetch"

# --- 2. Generierung OFFLINE im digest-gepinnten Loader --------------
log "generating TPC-H at SF=$SF (offline, network_mode none)..."
rm -rf "$OUT"
$COMPOSE run --rm duckdb /work/.cache/tpch-tool/duckdb :memory: \
    "SET autoinstall_known_extensions=false; SET autoload_known_extensions=false;" \
    "LOAD '/work/.cache/tpch-tool/tpch.duckdb_extension';" \
    "CALL dbgen(sf=$SF);" \
    "EXPORT DATABASE '/work/.cache/tpch' (FORMAT CSV, DELIMITER ',');" \
    > /tmp/tpch-gen.log 2>&1 || { cat /tmp/tpch-gen.log; fail "generation failed (extension pin incomplete? offline?)"; }

# --- 3. Form-Assertion (8 Tabellen + lineitem-Volumen) -------------
csv_count=$(find "$OUT" -maxdepth 1 -name '*.csv' | wc -l | tr -d ' ')
[ "$csv_count" = "8" ] || { ls -1 "$OUT"; fail "expected 8 CSV tables, got $csv_count"; }
ddl_count=$(grep -ic "CREATE TABLE" "$OUT/schema.sql" 2>/dev/null || true)
[ "$ddl_count" = "8" ] || { cat "$OUT/schema.sql"; fail "expected 8 CREATE TABLE in schema.sql, got $ddl_count"; }
[ -f "$OUT/load.sql" ] || fail "load.sql missing"

li_rows=$(( $(wc -l < "$OUT/lineitem.csv") - 1 ))   # minus header row
[ "$li_rows" -gt 0 ] || fail "lineitem has no data rows"
# Bei der Default-SF 0.01 ist dbgen deterministisch -> harte Zeilen-Pin als
# Form-Check (fängt eine veränderte Generierung / falschen Extension-Pin).
if [ "$SF" = "0.01" ]; then
    [ "$li_rows" = "60175" ] \
        || fail "lineitem rows $li_rows != 60175 (SF=0.01 pinned form-check — generation drift?)"
fi

log "OK — 8 TPC-H tables generated OFFLINE (SF=$SF, lineitem=$li_rows rows); schema.sql + load.sql + 8 CSVs in .cache/tpch/."
log "Sourcing (4a) verified — generator pinned, nothing checked in. Loading into a source DB + round-trip is 4b."
