#!/usr/bin/env bash
# Sample-DB-Harness — Phase 4 Smoke (TPC-DS / PostgreSQL Round-Trip, 4e)
# Slice: docs/planning/done/tpc-4e-tpcds-slice.md
# ADR:   docs/adr/0017-tpc-benchmark-workload-sourcing.md (Sourcing; tpcds-Ext)
#        docs/adr/0014-sample-db-harness-fetch-and-compose.md (Harness)
#
# 4e = die zweite, komplexere Benchmark-Workload (TPC-DS, 24 Tabellen) als
# Schema-Round-Trip-KORREKTHEIT — wie 4b (TPC-H), aber breiteres Typ-/Tabellen-
# Spektrum. Echter End-to-End-CLI-Lauf gegen d-migrate:dev:
#   4e-Generierung (gepinnter DuckDB-tpcds, offline) -> schema.sql + 24 CSVs laden
#   -> reverse -> validate (0 Errors) -> generate PG -> Zielschema -> data transfer
#   -> Zeilen-Paritaet je Tabelle + DECIMAL-Wert-Checksumme (store_sales).
#
# Quelle ist die RUNTIME-generierte DuckDB-schema.sql (PG-kompatibel: BIGINT/
# VARCHAR/DECIMAL(p,s)/DATE) + die CSVs — KEIN TPC-Artefakt im Repo (ADR 0017).
# Bewusst FK-/PK-frei (wie 4b): das TPC-DS-Schluesselgefuege waere ein eingechecktes
# TPC-Artefakt; constraint-reiche Round-Trips deckt Phase 1/2 (Pagila/Sakila) ab.
# 4e prueft die 24-Tabellen-Form + die TPC-DS-Typen + Datentransfer.
#
# Opt-in (kein PR-Gate): `make sample-db-tpcds-smoke` (SF=0.01 default).
# Voraussetzung: docker, docker compose, lokales d-migrate:dev
# (`make docker-build IMAGE_TAG=dev`).

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
EXAMPLES_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
COMPOSE="docker compose -f $EXAMPLES_DIR/docker-compose.yml"
OUT_DIR="$EXAMPLES_DIR/out"
TPCDS="$EXAMPLES_DIR/.cache/tpcds"
SF="${SF:-0.01}"

log()  { printf '[tpcds-rt] %s\n' "$*"; }
fail() { printf '[tpcds-rt] FAIL: %s\n' "$*" >&2; exit 1; }

# --- 0. .env + Host-User + dataset (4e generator) ------------------
mkdir -p "$OUT_DIR"
if [ ! -f "$EXAMPLES_DIR/.env" ]; then
    cp "$EXAMPLES_DIR/.env.example" "$EXAMPLES_DIR/.env"
    log "created examples/sample-db/.env from .env.example"
fi
# shellcheck disable=SC1091
set -a; . "$EXAMPLES_DIR/.env"; set +a
: "${POSTGRES_USER:?POSTGRES_USER not set}"
export SAMPLE_DB_DMIGRATE_USER="$(id -u):$(id -g)"

log "ensuring TPC-DS dataset (4e generator, SF=$SF)..."
SF="$SF" "$SCRIPT_DIR/tpcds-generate.sh" > /tmp/tpcds-rt-gen.log 2>&1 \
    || { cat /tmp/tpcds-rt-gen.log; fail "4e generation failed"; }
[ -f "$TPCDS/schema.sql" ] || fail "schema.sql missing after generation"

psql_db() {  # psql_db <db> <on_error_stop:0|1> [extra psql args...]
    local db="$1" oes="$2"; shift 2
    $COMPOSE exec -T postgres psql -v ON_ERROR_STOP="$oes" -U "$POSTGRES_USER" -d "$db" "$@"
}
count_rows() { psql_db "$1" 0 -tAc "SELECT count(*) FROM \"$2\"" 2>/dev/null | tr -d '[:space:]'; }

# --- 1. Stack hoch + healthy ---------------------------------------
log "starting postgres..."
$COMPOSE up -d postgres
deadline=$(($(date +%s) + 120)); pg_ok="no"
while [ "$(date +%s)" -lt "$deadline" ]; do
    st=$(docker inspect --format '{{.State.Health.Status}}' "$($COMPOSE ps -q postgres)" 2>/dev/null || echo "?")
    if [ "$st" = "healthy" ]; then pg_ok="yes"; break; fi
    sleep 2
done
[ "$pg_ok" = "yes" ] || fail "postgres did not reach healthy state"
log "postgres healthy"

# --- 2. TPC-DS in frische Quell-DB laden ---------------------------
# DuckDB-schema.sql ist PG-kompatibel (CREATE TABLE ...;; -> leeres Folgestatement
# toleriert psql). CSV-Load via `\copy FROM STDIN` (CSVs vom Host in psql-stdin gepiped,
# kein Mount noetig). DuckDB schreibt NULL als leeres Feld -> PG CSV liest leer als NULL.
log "resetting + loading tpcds source DB..."
psql_db postgres 1 -c "DROP DATABASE IF EXISTS tpcds WITH (FORCE)" -c "CREATE DATABASE tpcds" > /dev/null
psql_db tpcds 1 < "$TPCDS/schema.sql" > /tmp/tpcds-rt-schema.log 2>&1 \
    || { cat /tmp/tpcds-rt-schema.log; fail "schema.sql load failed (DuckDB DDL not PG-compatible?)"; }

tables=""
for csv in "$TPCDS"/*.csv; do
    t=$(basename "$csv" .csv)
    tables="$tables $t"
    psql_db tpcds 1 -c "\copy $t FROM STDIN WITH (FORMAT csv, HEADER true)" < "$csv" > /dev/null \
        || fail "copy $t failed"
done
src_tables=$(psql_db tpcds 0 -tAc "SELECT count(*) FROM information_schema.tables WHERE table_schema='public' AND table_type='BASE TABLE'" | tr -d '[:space:]')
[ "$src_tables" = "24" ] || fail "expected 24 TPC-DS source tables, got $src_tables"
ss_src=$(count_rows tpcds store_sales)
[ "$ss_src" -gt 0 ] || fail "store_sales empty after load"
log "tpcds loaded (24 tables, store_sales=$ss_src rows)"

# --- 3. reverse ----------------------------------------------------
log "schema reverse..."
$COMPOSE run --rm dmigrate schema reverse --source tpcds_pg_src \
    --output /work/out/tpcds.reverse.yaml > /dev/null || fail "reverse failed"
[ -s "$OUT_DIR/tpcds.reverse.yaml" ] || fail "empty reverse.yaml"
# Form-Beleg: alle 24 Tabellennamen + die kennzeichnenden TPC-DS-Typen erfasst.
for t in $tables; do
    grep -qE "^  $t:$" "$OUT_DIR/tpcds.reverse.yaml" || fail "reverse missing table $t"
done
grep -qiE "decimal|numeric" "$OUT_DIR/tpcds.reverse.yaml" || fail "reverse lost DECIMAL type"
grep -qiE "\bdate\b"        "$OUT_DIR/tpcds.reverse.yaml" || fail "reverse lost DATE type"
log "reverse OK (24 tables, DECIMAL + DATE preserved)"

# --- 4. validate (0 Errors hart) -----------------------------------
log "schema validate (expect 0 errors)..."
val_out=$($COMPOSE run --rm dmigrate schema validate --source /work/out/tpcds.reverse.yaml 2>&1) \
    || fail "validate exited non-zero:\n$val_out"
printf '%s' "$val_out" | grep -q "Validation passed" || fail "validate did not pass:\n$val_out"
log "validate passed"

# --- 5. generate PG (deterministisch) + Notes pinnen ---------------
log "schema generate --target postgresql --deterministic..."
$COMPOSE run --rm dmigrate schema generate --source /work/out/tpcds.reverse.yaml \
    --target postgresql --deterministic --output /work/out/tpcds.sql > /dev/null || fail "generate failed"
[ -s "$OUT_DIR/tpcds.sql" ] || fail "empty generated DDL"
gen_creates=$(grep -ciE "CREATE TABLE" "$OUT_DIR/tpcds.sql" || true)
[ "$gen_creates" = "24" ] || fail "generated DDL has $gen_creates CREATE TABLE (expected 24)"
# Erwartete Notes: TPC-DS ist (wie generiert) constraint-/programmability-frei -> 0 Notes.
# Eine Note waere eine Regression (oder eine neue Typ-Degradierung) und muss bewusst
# hier + in expected/tpcds.md gepinnt werden.
if [ -f "$OUT_DIR/tpcds.report.yaml" ]; then
    note_count=$(grep -cE "^  - type:" "$OUT_DIR/tpcds.report.yaml" || true)
    [ "$note_count" = "0" ] || { cat "$OUT_DIR/tpcds.report.yaml"; fail "expected 0 generate notes for TPC-DS, got $note_count"; }
fi
log "generate OK (24 CREATE TABLE, 0 notes)"

# --- 6. Zielschema aufbauen + Daten transferieren ------------------
log "resetting target DB + applying generated DDL..."
psql_db postgres 1 -c "DROP DATABASE IF EXISTS tpcds_target WITH (FORCE)" -c "CREATE DATABASE tpcds_target" > /dev/null
psql_db tpcds_target 1 < "$OUT_DIR/tpcds.sql" > /tmp/tpcds-rt-apply.log 2>&1 \
    || { cat /tmp/tpcds-rt-apply.log; fail "generated DDL apply failed"; }

log "data transfer tpcds_pg_src -> tpcds_pg_target..."
$COMPOSE run --rm dmigrate data transfer --source tpcds_pg_src --target tpcds_pg_target --truncate \
    > /tmp/tpcds-rt-xfer.log 2>&1 || { cat /tmp/tpcds-rt-xfer.log; fail "transfer failed"; }
grep -q "Transfer complete" /tmp/tpcds-rt-xfer.log || fail "transfer did not complete"

# --- 7. Paritaet: Zeilen je Tabelle + DECIMAL-Wert-Checksumme ------
log "verifying row-count parity (source == target) for all 24 tables..."
mismatch=0
compared=0
for t in $tables; do
    s=$(count_rows tpcds "$t"); d=$(count_rows tpcds_target "$t")
    # Guard gegen False-Green: count_rows schluckt Query-Fehler (2>/dev/null) und
    # liefert dann "" — ohne diesen Check waere ""=="" eine bestandene Paritaet.
    case "$s" in ''|*[!0-9]*) fail "source count for '$t' not numeric ('$s') — query failed?";; esac
    if [ "$s" != "$d" ]; then printf '[tpcds-rt]   MISMATCH %s: src=%s dst=%s\n' "$t" "$s" "$d"; mismatch=1; fi
    compared=$((compared + 1))
done
[ "$mismatch" = "0" ] || fail "row-count parity violated"
[ "$compared" = "24" ] || fail "parity compared $compared tables, expected 24 (generator-swallow guard)"

# DECIMAL(7,2)-Fidelity: Summe store_sales.ss_net_paid muss exakt gleich sein
# (faengt stille Praezisions-/Rundungsverluste im Transfer; NULLs werden auf beiden
# Seiten von SUM uebersprungen).
sum_src=$(psql_db tpcds        0 -tAc "SELECT sum(ss_net_paid) FROM store_sales" | tr -d '[:space:]')
sum_dst=$(psql_db tpcds_target 0 -tAc "SELECT sum(ss_net_paid) FROM store_sales" | tr -d '[:space:]')
case "$sum_src" in ''|*[!0-9.]*) fail "source checksum not numeric ('$sum_src') — query failed?";; esac
case "$sum_dst" in ''|*[!0-9.]*) fail "target checksum not numeric ('$sum_dst') — query failed?";; esac
[ "$sum_src" = "$sum_dst" ] || fail "DECIMAL checksum mismatch: src=$sum_src dst=$sum_dst"
log "parity OK (24 tables row-identical; store_sales sum(ss_net_paid)=$sum_dst identical)"

log "SUCCESS — TPC-DS schema round-trips correctly PG->PG (4e): 24 tables reverse/validate/generate/transfer, types + DECIMAL values preserved."
log "stack is up; clean up with 'make sample-db-down' or 'make sample-db-purge'."
