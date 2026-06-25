#!/usr/bin/env bash
# Sample-DB-Harness — TPC-DS-Generierung (Phase 4, optionaler Sub-Slice 4e)
# Slice: docs/planning/done/tpc-4e-tpcds-slice.md
# ADR:   docs/adr/0017-tpc-benchmark-workload-sourcing.md (Tool A DuckDB; tpcds-Ext)
#        docs/adr/0014-sample-db-harness-fetch-and-compose.md (Pin/Compose)
#
# Erzeugt die TPC-DS-Workload (24 Tabellen) reproduzierbar + OFFLINE aus dem
# gepinnten DuckDB-CLI v1.4.5 + gepinnter tpcds-Extension (fetch-dumps.sh
# FETCH_TPCDS=1). EXPORT DATABASE schreibt schema.sql + load.sql + 24 CSVs nach
# .cache/tpcds/ (gitignored — KEIN Dump im Repo). Sourcing-Beleg für 4e; das
# Laden in eine Quell-DB + der Reverse/Generate/Transfer-Round-Trip sind smoke-tpcds.sh.
#
# Wie tpch ist die tpcds-Extension NICHT im CLI gebündelt — sie ist mitgepinnt + wird
# aus Datei `LOAD`-ed, daher läuft der Loader unter `network_mode: none` (hermetisch).
#
# Opt-in (kein PR-Gate): `make sample-db-tpcds-gen` (Default SF=0.01) oder
# `SF=0.1 make sample-db-tpcds-gen`. Voraussetzung am Host: docker, docker compose.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
EXAMPLES_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
COMPOSE="docker compose -f $EXAMPLES_DIR/docker-compose.yml"
CACHE="$EXAMPLES_DIR/.cache"
TOOL="$CACHE/tpcds-tool"
OUT="$CACHE/tpcds"
SF="${SF:-0.01}"

log()  { printf '[tpcds] %s\n' "$*"; }
fail() { printf '[tpcds] FAIL: %s\n' "$*" >&2; exit 1; }

# --- 0. .env + Host-User (Schreibrechte im gemounteten .cache) ------
if [ ! -f "$EXAMPLES_DIR/.env" ]; then
    cp "$EXAMPLES_DIR/.env.example" "$EXAMPLES_DIR/.env"
    log "created examples/sample-db/.env from .env.example"
fi
export SAMPLE_DB_DMIGRATE_USER="$(id -u):$(id -g)"

# --- 1. gepinntes Generator-Tool holen (idempotent, SHA256) ---------
log "fetching pinned DuckDB CLI v1.4.5 + tpcds extension (FETCH_TPCDS=1)..."
FETCH_TPCDS=1 "$SCRIPT_DIR/fetch-dumps.sh" > /tmp/tpcds-fetch.log 2>&1 \
    || { cat /tmp/tpcds-fetch.log; fail "generator tool fetch failed"; }
[ -x "$TOOL/duckdb" ]                  || fail "duckdb binary missing after fetch"
[ -f "$TOOL/tpcds.duckdb_extension" ]  || fail "tpcds extension missing after fetch"

# --- 2. Generierung OFFLINE im digest-gepinnten Loader --------------
log "generating TPC-DS at SF=$SF (offline, network_mode none)..."
rm -rf "$OUT"
$COMPOSE run --rm duckdb /work/.cache/tpcds-tool/duckdb :memory: \
    "SET autoinstall_known_extensions=false; SET autoload_known_extensions=false;" \
    "LOAD '/work/.cache/tpcds-tool/tpcds.duckdb_extension';" \
    "CALL dsdgen(sf=$SF);" \
    "EXPORT DATABASE '/work/.cache/tpcds' (FORMAT CSV, DELIMITER ',');" \
    > /tmp/tpcds-gen.log 2>&1 || { cat /tmp/tpcds-gen.log; fail "generation failed (extension pin incomplete? offline?)"; }

# --- 3. Form-Assertion (24 Tabellen + store_sales-Volumen) ---------
csv_count=$(find "$OUT" -maxdepth 1 -name '*.csv' | wc -l | tr -d ' ')
[ "$csv_count" = "24" ] || { ls -1 "$OUT"; fail "expected 24 CSV tables, got $csv_count"; }
ddl_count=$(grep -ic "CREATE TABLE" "$OUT/schema.sql" 2>/dev/null || true)
[ "$ddl_count" = "24" ] || { cat "$OUT/schema.sql"; fail "expected 24 CREATE TABLE in schema.sql, got $ddl_count"; }
[ -f "$OUT/load.sql" ] || fail "load.sql missing"

[ -f "$OUT/store_sales.csv" ] || fail "store_sales.csv missing after generation"
ss_rows=$(( $(wc -l < "$OUT/store_sales.csv") - 1 ))   # minus header row
[ "$ss_rows" -gt 0 ] || fail "store_sales has no data rows"
# Bei der Default-SF 0.01 ist dsdgen deterministisch -> harte Zeilen-Pin als
# Form-Check (fängt eine veränderte Generierung / falschen Extension-Pin).
if [ "$SF" = "0.01" ]; then
    [ "$ss_rows" = "28810" ] \
        || fail "store_sales rows $ss_rows != 28810 (SF=0.01 pinned form-check — generation drift?)"
fi

log "OK — 24 TPC-DS tables generated OFFLINE (SF=$SF, store_sales=$ss_rows rows); schema.sql + load.sql + 24 CSVs in .cache/tpcds/."
log "Sourcing (4e) verified — generator pinned, nothing checked in. Loading into a source DB + round-trip is smoke-tpcds.sh."
