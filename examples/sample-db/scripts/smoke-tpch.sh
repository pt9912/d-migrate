#!/usr/bin/env bash
# Sample-DB-Harness — Phase 4 Smoke (TPC-H / PostgreSQL Round-Trip, 4b)
# Slice: docs/planning/in-progress/tpc-4b-roundtrip-slice.md
# ADR:   docs/adr/0017-tpc-benchmark-workload-sourcing.md (Sourcing, 4a)
#        docs/adr/0014-sample-db-harness-fetch-and-compose.md (Harness)
#
# 4b = Schema-Round-Trip-KORREKTHEIT der TPC-H-Workload (Korrektheit vor der
# Messung in 4c/4d). Echter End-to-End-CLI-Lauf gegen d-migrate:dev:
#   4a-Generierung (gepinnter DuckDB-tpch, offline) -> schema.sql + CSVs laden
#   -> reverse -> validate (0 Errors) -> generate PG -> Zielschema -> data transfer
#   -> Zeilen-Paritaet je Tabelle + DECIMAL-Wert-Checksumme (lineitem).
#
# Quelle ist die RUNTIME-generierte DuckDB-schema.sql (PG-kompatibel: BIGINT/
# INTEGER/VARCHAR/DECIMAL(15,2)/DATE) + die CSVs — KEIN TPC-Artefakt im Repo
# (ADR 0017: nichts eingecheckt). Bewusst FK-/PK-frei: das TPC-H-Schluesselgefuege
# waere ein eingechecktes TPC-Schema-Artefakt; constraint-reiche Round-Trips deckt
# Phase 1/2 (Pagila/Sakila) ab. 4b prueft die TPC-H-Typen + 8-Tabellen-Form + Daten.
#
# Opt-in (kein PR-Gate): `make sample-db-tpch-smoke` (SF=0.01 default).
# Voraussetzung: docker, docker compose, lokales d-migrate:dev
# (`make docker-build IMAGE_TAG=dev`).

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
EXAMPLES_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
COMPOSE="docker compose -f $EXAMPLES_DIR/docker-compose.yml"
OUT_DIR="$EXAMPLES_DIR/out"
TPCH="$EXAMPLES_DIR/.cache/tpch"
SF="${SF:-0.01}"

log()  { printf '[tpch-rt] %s\n' "$*"; }
fail() { printf '[tpch-rt] FAIL: %s\n' "$*" >&2; exit 1; }

# --- 0. .env + Host-User + dataset (4a) ----------------------------
mkdir -p "$OUT_DIR"
if [ ! -f "$EXAMPLES_DIR/.env" ]; then
    cp "$EXAMPLES_DIR/.env.example" "$EXAMPLES_DIR/.env"
    log "created examples/sample-db/.env from .env.example"
fi
# shellcheck disable=SC1091
set -a; . "$EXAMPLES_DIR/.env"; set +a
: "${POSTGRES_USER:?POSTGRES_USER not set}"
export SAMPLE_DB_DMIGRATE_USER="$(id -u):$(id -g)"

log "ensuring TPC-H dataset (4a generator, SF=$SF)..."
SF="$SF" "$SCRIPT_DIR/tpch-generate.sh" > /tmp/tpch-rt-gen.log 2>&1 \
    || { cat /tmp/tpch-rt-gen.log; fail "4a generation failed"; }
[ -f "$TPCH/schema.sql" ] || fail "schema.sql missing after generation"

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

# --- 2. TPC-H in frische Quell-DB laden ----------------------------
# DuckDB-schema.sql ist PG-kompatibel (CREATE TABLE ...;; -> leeres Folgestatement
# toleriert psql). CSV-Load via `\copy FROM STDIN` (load.sql ist DuckDB-COPY-Dialekt;
# CSVs werden vom Host in den psql-stdin gepiped, kein Mount noetig).
log "resetting + loading tpch source DB..."
psql_db postgres 1 -c "DROP DATABASE IF EXISTS tpch WITH (FORCE)" -c "CREATE DATABASE tpch" > /dev/null
psql_db tpch 1 < "$TPCH/schema.sql" > /tmp/tpch-rt-schema.log 2>&1 \
    || { cat /tmp/tpch-rt-schema.log; fail "schema.sql load failed (DuckDB DDL not PG-compatible?)"; }

tables=""
for csv in "$TPCH"/*.csv; do
    t=$(basename "$csv" .csv)
    tables="$tables $t"
    psql_db tpch 1 -c "\copy $t FROM STDIN WITH (FORMAT csv, HEADER true)" < "$csv" > /dev/null \
        || fail "copy $t failed"
done
src_tables=$(psql_db tpch 0 -tAc "SELECT count(*) FROM information_schema.tables WHERE table_schema='public' AND table_type='BASE TABLE'" | tr -d '[:space:]')
[ "$src_tables" = "8" ] || fail "expected 8 TPC-H source tables, got $src_tables"
li_src=$(count_rows tpch lineitem)
[ "$li_src" -gt 0 ] || fail "lineitem empty after load"
log "tpch loaded (8 tables, lineitem=$li_src rows)"

# --- 3. reverse ----------------------------------------------------
log "schema reverse..."
$COMPOSE run --rm dmigrate schema reverse --source tpch_pg_src \
    --output /work/out/tpch.reverse.yaml > /dev/null || fail "reverse failed"
[ -s "$OUT_DIR/tpch.reverse.yaml" ] || fail "empty reverse.yaml"
rev_tables=$(grep -cE "^  [a-z_]+:$" "$OUT_DIR/tpch.reverse.yaml" || true)
# Form-Beleg: alle 8 Tabellennamen + die kennzeichnenden TPC-H-Typen erfasst.
for t in $tables; do
    grep -qE "^  $t:$" "$OUT_DIR/tpch.reverse.yaml" || fail "reverse missing table $t"
done
grep -qiE "decimal|numeric" "$OUT_DIR/tpch.reverse.yaml" || fail "reverse lost DECIMAL type"
grep -qiE "\bdate\b"        "$OUT_DIR/tpch.reverse.yaml" || fail "reverse lost DATE type"
log "reverse OK (8 tables, DECIMAL + DATE preserved)"

# --- 4. validate (0 Errors hart) -----------------------------------
log "schema validate (expect 0 errors)..."
val_out=$($COMPOSE run --rm dmigrate schema validate --source /work/out/tpch.reverse.yaml 2>&1) \
    || fail "validate exited non-zero:\n$val_out"
printf '%s' "$val_out" | grep -q "Validation passed" || fail "validate did not pass:\n$val_out"
log "validate passed"

# --- 5. generate PG (deterministisch) + Notes pinnen ---------------
log "schema generate --target postgresql --deterministic..."
$COMPOSE run --rm dmigrate schema generate --source /work/out/tpch.reverse.yaml \
    --target postgresql --deterministic --output /work/out/tpch.sql > /dev/null || fail "generate failed"
[ -s "$OUT_DIR/tpch.sql" ] || fail "empty generated DDL"
gen_creates=$(grep -ciE "CREATE TABLE" "$OUT_DIR/tpch.sql" || true)
[ "$gen_creates" = "8" ] || fail "generated DDL has $gen_creates CREATE TABLE (expected 8)"
# Erwartete Notes: TPC-H ist constraint-/programmability-frei -> 0 Notes.
# Eine Note waere eine Regression (oder eine neue Typ-Degradierung) und muss
# bewusst hier + in expected/tpch.md gepinnt werden.
if [ -f "$OUT_DIR/tpch.report.yaml" ]; then
    note_count=$(grep -cE "^  - type:" "$OUT_DIR/tpch.report.yaml" || true)
    [ "$note_count" = "0" ] || { cat "$OUT_DIR/tpch.report.yaml"; fail "expected 0 generate notes for TPC-H, got $note_count"; }
fi
log "generate OK (8 CREATE TABLE, 0 notes)"

# --- 6. Zielschema aufbauen + Daten transferieren ------------------
log "resetting target DB + applying generated DDL..."
psql_db postgres 1 -c "DROP DATABASE IF EXISTS tpch_target WITH (FORCE)" -c "CREATE DATABASE tpch_target" > /dev/null
psql_db tpch_target 1 < "$OUT_DIR/tpch.sql" > /tmp/tpch-rt-apply.log 2>&1 \
    || { cat /tmp/tpch-rt-apply.log; fail "generated DDL apply failed"; }

log "data transfer tpch_pg_src -> tpch_pg_target..."
$COMPOSE run --rm dmigrate data transfer --source tpch_pg_src --target tpch_pg_target --truncate \
    > /tmp/tpch-rt-xfer.log 2>&1 || { cat /tmp/tpch-rt-xfer.log; fail "transfer failed"; }
grep -q "Transfer complete" /tmp/tpch-rt-xfer.log || fail "transfer did not complete"

# --- 7. Paritaet: Zeilen je Tabelle + DECIMAL-Wert-Checksumme ------
log "verifying row-count parity (source == target) for all 8 tables..."
mismatch=0
for t in $tables; do
    s=$(count_rows tpch "$t"); d=$(count_rows tpch_target "$t")
    if [ "$s" != "$d" ]; then printf '[tpch-rt]   MISMATCH %s: src=%s dst=%s\n' "$t" "$s" "$d"; mismatch=1; fi
done
[ "$mismatch" = "0" ] || fail "row-count parity violated"

# DECIMAL(15,2)-Fidelity: Summe der lineitem.l_extendedprice muss exakt gleich sein
# (faengt stille Praezisions-/Rundungsverluste im Transfer).
sum_src=$(psql_db tpch        0 -tAc "SELECT sum(l_extendedprice) FROM lineitem" | tr -d '[:space:]')
sum_dst=$(psql_db tpch_target 0 -tAc "SELECT sum(l_extendedprice) FROM lineitem" | tr -d '[:space:]')
[ -n "$sum_dst" ] && [ "$sum_src" = "$sum_dst" ] \
    || fail "DECIMAL checksum mismatch: src=$sum_src dst=$sum_dst"
log "parity OK (8 tables row-identical; lineitem sum(l_extendedprice)=$sum_dst identical)"

log "SUCCESS — TPC-H schema round-trips correctly PG->PG (4b): 8 tables reverse/validate/generate/transfer, types + DECIMAL values preserved. Volume measurement is 4c/4d."
log "stack is up; clean up with 'make sample-db-down' or 'make sample-db-purge'."
