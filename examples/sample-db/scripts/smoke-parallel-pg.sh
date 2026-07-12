#!/usr/bin/env bash
# LN-007/LN-008 — PostgreSQL `data transfer --parallel` Smoke (FK-Layer + Partitions-Fan-out).
#
# Beweist auf echtem PostgreSQL (kein Clamp wie SQLite):
#   - FK-sichere Topo-Ebenen: `customer` (Ebene 0) wird VOR `payment` (Ebene 1)
#     transferiert — bei aktiven FK-Checks kein Constraint-Fehler.
#   - Partitions-Fan-out: das RANGE-partitionierte `payment` wird pro Kind (3 Monats-
#     Partitionen) NEBENLÄUFIG transferiert (--parallel 4); Row-Counts je Kind UND
#     gesamt == Quelle (kein Doppelzählen, keine verlorenen Zeilen).
#
# Voraussetzung: docker compose postgres-Service + lokal gebautes d-migrate:dev-Image.
# ADR: docs/adr/0032-paralleler-datenpfad-tabellen-partitionen.md

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
EXAMPLES_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
COMPOSE_FILE="$EXAMPLES_DIR/docker-compose.yml"
COMPOSE="docker compose -f $COMPOSE_FILE"

log()  { printf '[parallel-pg] %s\n' "$*"; }
fail() { printf '[parallel-pg] FAIL: %s\n' "$*" >&2; exit 1; }

if [ ! -f "$EXAMPLES_DIR/.env" ]; then cp "$EXAMPLES_DIR/.env.example" "$EXAMPLES_DIR/.env"; fi
# shellcheck disable=SC1091
set -a; . "$EXAMPLES_DIR/.env"; set +a
: "${POSTGRES_USER:?POSTGRES_USER not set}"
: "${POSTGRES_PASSWORD:?POSTGRES_PASSWORD not set}"
export SAMPLE_DB_DMIGRATE_USER="$(id -u):$(id -g)"

psql_db() { $COMPOSE exec -T postgres psql -v ON_ERROR_STOP="$2" -U "$POSTGRES_USER" -d "$1" "${@:3}"; }
count() { psql_db "$1" 0 -tAc "SELECT count(*) FROM $2" </dev/null 2>/dev/null | tr -d '[:space:]'; }

SRC=dmig_par_src
TGT=dmig_par_tgt
SRC_URL="postgresql://${POSTGRES_USER}:${POSTGRES_PASSWORD}@postgres:5432/${SRC}"
TGT_URL="postgresql://${POSTGRES_USER}:${POSTGRES_PASSWORD}@postgres:5432/${TGT}"

SCHEMA="CREATE TABLE customer (id int PRIMARY KEY, name text);
CREATE TABLE payment (id int, customer_id int REFERENCES customer(id), amount numeric, pdate date, PRIMARY KEY (id, pdate)) PARTITION BY RANGE (pdate);
CREATE TABLE payment_2023_01 PARTITION OF payment FOR VALUES FROM ('2023-01-01') TO ('2023-02-01');
CREATE TABLE payment_2023_02 PARTITION OF payment FOR VALUES FROM ('2023-02-01') TO ('2023-03-01');
CREATE TABLE payment_2023_03 PARTITION OF payment FOR VALUES FROM ('2023-03-01') TO ('2023-04-01');"
DATA="INSERT INTO customer SELECT g, 'c'||g FROM generate_series(1,100) g;
INSERT INTO payment SELECT g, (g % 100)+1, g*1.5, DATE '2023-01-01' + (g % 89) FROM generate_series(1,900) g;"

log "compose up postgres..."
$COMPOSE up -d postgres >/dev/null
for _ in $(seq 1 30); do $COMPOSE exec -T postgres pg_isready -U "$POSTGRES_USER" >/dev/null 2>&1 && break; sleep 1; done

log "(re)create $SRC (with data) + $TGT (empty, same partitioned schema)..."
psql_db postgres 1 -c "DROP DATABASE IF EXISTS $SRC WITH (FORCE)" -c "CREATE DATABASE $SRC" </dev/null >/dev/null
psql_db postgres 1 -c "DROP DATABASE IF EXISTS $TGT WITH (FORCE)" -c "CREATE DATABASE $TGT" </dev/null >/dev/null
printf '%s\n%s' "$SCHEMA" "$DATA" | psql_db "$SRC" 1 >/dev/null
printf '%s' "$SCHEMA" | psql_db "$TGT" 1 >/dev/null

src_cust=$(count "$SRC" customer); src_pay=$(count "$SRC" payment)
src_p1=$(count "$SRC" payment_2023_01); src_p2=$(count "$SRC" payment_2023_02); src_p3=$(count "$SRC" payment_2023_03)
log "source: customer=$src_cust payment=$src_pay (p1=$src_p1 p2=$src_p2 p3=$src_p3)"
[ "$src_pay" = "900" ] || fail "source payment expected 900, got '$src_pay'"

log "data transfer --parallel 4 --truncate ($SRC -> $TGT)..."
$COMPOSE run --rm dmigrate data transfer --source "$SRC_URL" --target "$TGT_URL" --parallel 4 --truncate \
    || fail "parallel transfer failed (FK-layer ordering or partition fan-out broke)"

dst_cust=$(count "$TGT" customer); dst_pay=$(count "$TGT" payment)
dst_p1=$(count "$TGT" payment_2023_01); dst_p2=$(count "$TGT" payment_2023_02); dst_p3=$(count "$TGT" payment_2023_03)
log "target: customer=$dst_cust payment=$dst_pay (p1=$dst_p1 p2=$dst_p2 p3=$dst_p3)"

[ "$dst_cust" = "$src_cust" ] || fail "customer count mismatch: src=$src_cust dst=$dst_cust"
[ "$dst_pay" = "$src_pay" ] || fail "payment total mismatch: src=$src_pay dst=$dst_pay (double-count or lost rows)"
{ [ "$dst_p1" = "$src_p1" ] && [ "$dst_p2" = "$src_p2" ] && [ "$dst_p3" = "$src_p3" ]; } \
    || fail "per-child count mismatch — partition fan-out wrong (src p1/p2/p3=$src_p1/$src_p2/$src_p3 dst=$dst_p1/$dst_p2/$dst_p3)"

log "OK: FK-safe parallel transfer + per-child partition fan-out reproduced all rows exactly."
