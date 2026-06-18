#!/usr/bin/env bash
# Sample-DB-Harness — Phase 1 Smoke (Pagila / PostgreSQL Round-Trip)
# Plan: docs/planning/next/sample-db-integration-harness.md
# ADR:  docs/adr/0014-sample-db-harness-fetch-and-compose.md
#
# Echter End-to-End-CLI-Lauf gegen das d-migrate:dev-Image:
#   Dump laden -> reverse --include-all -> validate (0 Errors)
#   -> generate --split pre-post -> Zielschema (pre) -> data transfer
#   -> Zielschema (post) -> schema compare gegen Expected-Baseline.
#
# Voraussetzung am Host: docker, docker compose, jq sowie das lokal
# gebaute d-migrate:dev-Image (`make docker-build IMAGE_TAG=dev`).
# Der Stack bleibt nach dem Lauf stehen — Cleanup via
# `make sample-db-down` (Volume bleibt) / `make sample-db-purge`.
#
# Green-Kriterien (hart): validate 0 Errors, generate-Notes == Baseline
# (E055+W123), Daten-Zeilenzahlen Quelle == Ziel je Tabelle, und
# `schema compare` == gepinnte Baseline (keine UNERWARTETEN Diffs).
# Die in der Baseline gepinnten Schema-Diffs sind in
# expected/pagila-smoke.md je Klasse erklärt.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
EXAMPLES_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
COMPOSE_FILE="$EXAMPLES_DIR/docker-compose.yml"
COMPOSE="docker compose -f $COMPOSE_FILE"
OUT_DIR="$EXAMPLES_DIR/out"
EXPECTED_DIR="$EXAMPLES_DIR/expected"
BASELINE="$EXPECTED_DIR/pagila-smoke.compare.txt"

log()  { printf '[smoke] %s\n' "$*"; }
fail() { printf '[smoke] FAIL: %s\n' "$*" >&2; exit 1; }

# --- 0. .env + Verzeichnisse + Dump-Fetch --------------------------
mkdir -p "$OUT_DIR" "$EXPECTED_DIR"
if [ ! -f "$EXAMPLES_DIR/.env" ]; then
    cp "$EXAMPLES_DIR/.env.example" "$EXAMPLES_DIR/.env"
    log "created examples/sample-db/.env from .env.example"
fi
# shellcheck disable=SC1091
set -a; . "$EXAMPLES_DIR/.env"; set +a
: "${POSTGRES_USER:?POSTGRES_USER not set}"

"$SCRIPT_DIR/fetch-dumps.sh"

# psql helper inside the postgres service (unix-socket -> trust)
psql_db() {  # psql_db <db> <on_error_stop:0|1> [extra psql args...]
    local db="$1" oes="$2"; shift 2
    $COMPOSE exec -T postgres psql -v ON_ERROR_STOP="$oes" -U "$POSTGRES_USER" -d "$db" "$@"
}
count_rows() { psql_db "$1" 0 -tAc "SELECT count(*) FROM \"$2\"" 2>/dev/null | tr -d '[:space:]'; }

# --- 1. Stack hoch + healthy ---------------------------------------
log "starting postgres..."
$COMPOSE up -d postgres
log "waiting for postgres healthy (timeout 120s)..."
deadline=$(($(date +%s) + 120)); pg_ok="no"
while [ "$(date +%s)" -lt "$deadline" ]; do
    st=$(docker inspect --format '{{.State.Health.Status}}' "$($COMPOSE ps -q postgres)" 2>/dev/null || echo "?")
    if [ "$st" = "healthy" ]; then pg_ok="yes"; break; fi
    sleep 2
done
[ "$pg_ok" = "yes" ] || fail "postgres did not reach healthy state"
log "postgres healthy"

# --- 2. Pagila-Dump in frische Quell-DB laden ----------------------
log "resetting + loading pagila source DB..."
psql_db postgres 1 -c "DROP DATABASE IF EXISTS pagila WITH (FORCE)" -c "CREATE DATABASE pagila" > /dev/null
psql_db pagila 1 < "$EXAMPLES_DIR/.cache/pagila.sql" > /dev/null || fail "pagila dump load failed"
src_tables=$(psql_db pagila 0 -tAc "SELECT count(*) FROM information_schema.tables WHERE table_schema='public' AND table_type='BASE TABLE'" | tr -d '[:space:]')
[ "$src_tables" = "22" ] || fail "expected 22 source tables, got $src_tables"
log "pagila loaded ($src_tables tables)"

# --- 3. reverse --include-all --------------------------------------
log "schema reverse --include-all..."
$COMPOSE run --rm dmigrate schema reverse --source pagila_pg --include-all \
    --output /work/out/pagila.reverse.yaml > /dev/null || fail "reverse failed"
[ -s "$OUT_DIR/pagila.reverse.yaml" ] || fail "empty reverse.yaml"

# --- 4. validate (0 Errors hart) -----------------------------------
log "schema validate (expect 0 errors)..."
val_out=$($COMPOSE run --rm dmigrate schema validate --source /work/out/pagila.reverse.yaml 2>&1) \
    || fail "validate exited non-zero:\n$val_out"
printf '%s' "$val_out" | grep -q "Validation passed" || fail "validate did not pass:\n$val_out"
log "validate passed"

# --- 5. generate --split pre-post + Notes gegen Baseline -----------
log "schema generate --split pre-post --deterministic..."
$COMPOSE run --rm dmigrate schema generate --source /work/out/pagila.reverse.yaml \
    --target postgresql --split pre-post --deterministic \
    --output /work/out/pagila.sql > /dev/null || fail "generate failed"
for f in pagila.pre-data.sql pagila.post-data.sql pagila.report.yaml; do
    [ -s "$OUT_DIR/$f" ] || fail "empty generate artifact: $f"
done
# Erwartete generate-Notes (Pagila/PG): genau E055 (leere RANGE-Partition
# payment) + W123 (gist auf tsvector->text). Abweichung = Regression.
grep -q "code: E055" "$OUT_DIR/pagila.report.yaml" || fail "expected note E055 missing"
grep -q "code: W123" "$OUT_DIR/pagila.report.yaml" || fail "expected note W123 missing"
note_count=$(grep -cE "^  - type:" "$OUT_DIR/pagila.report.yaml")
[ "$note_count" = "2" ] || fail "expected exactly 2 generate notes, got $note_count"
log "generate OK (notes E055+W123 as expected)"

# --- 6. Zielschema aufbauen + Daten transferieren ------------------
log "resetting target DB + applying pre-data DDL..."
psql_db postgres 1 -c "DROP DATABASE IF EXISTS pagila_target WITH (FORCE)" -c "CREATE DATABASE pagila_target" > /dev/null
psql_db pagila_target 1 < "$OUT_DIR/pagila.pre-data.sql" > /dev/null || fail "pre-data apply failed"

log "data transfer pagila_pg -> pagila_target..."
$COMPOSE run --rm dmigrate data transfer --source pagila_pg --target pagila_target --truncate \
    > /tmp/sample-db-xfer.log 2>&1 || { cat /tmp/sample-db-xfer.log; fail "transfer failed"; }
grep -q "Transfer complete" /tmp/sample-db-xfer.log || fail "transfer did not complete"

log "applying post-data DDL (FK/index/constraint/trigger/routine)..."
# Bewusst OHNE ON_ERROR_STOP: die 3 group_concat-Views scheitern
# einzeln (Ordering-Defekt, K2-Klasse — siehe expected/pagila-smoke.md);
# alles übrige muss anwendbar bleiben.
psql_db pagila_target 0 < "$OUT_DIR/pagila.post-data.sql" > /tmp/sample-db-post.log 2>&1 || true
post_errs=$(grep -c "^ERROR" /tmp/sample-db-post.log || true)
unexpected=$(grep "^ERROR" /tmp/sample-db-post.log | grep -vc "group_concat(text) does not exist" || true)
[ "$unexpected" = "0" ] || { grep "^ERROR" /tmp/sample-db-post.log; fail "unexpected post-data error(s)"; }
[ "$post_errs" = "3" ] || fail "expected exactly 3 known post-data errors (group_concat ordering), got $post_errs"
log "post-data applied (3 known group_concat ordering errors as expected)"

# --- 7. Daten-Zeilenzahlen Quelle == Ziel (alle Tabellen) ----------
log "verifying row-count parity (source == target) for all tables..."
mismatch=0
while IFS= read -r t; do
    [ -n "$t" ] || continue
    s=$(count_rows pagila "$t"); d=$(count_rows pagila_target "$t")
    if [ "$s" != "$d" ]; then printf '[smoke]   MISMATCH %s: src=%s dst=%s\n' "$t" "$s" "$d"; mismatch=1; fi
done < <(psql_db pagila 0 -tAc "SELECT tablename FROM pg_tables WHERE schemaname='public' ORDER BY 1")
[ "$mismatch" = "0" ] || fail "row-count parity violated"
log "row-count parity OK (all $src_tables tables)"

# --- 8. schema compare gegen gepinnte Baseline ---------------------
log "schema compare pagila_pg <-> pagila_target..."
$COMPOSE run --rm dmigrate schema compare --source db:pagila_pg --target db:pagila_target \
    --output /work/out/pagila.compare.yaml > /dev/null 2>&1 || true   # exit 1 bei DIFFERENT ist erwartet
[ -s "$OUT_DIR/pagila.compare.yaml" ] || fail "empty compare output"

if [ ! -f "$BASELINE" ]; then
    cp "$OUT_DIR/pagila.compare.yaml" "$BASELINE"
    log "BASELINE BOOTSTRAP: wrote $BASELINE — review + commit it, then re-run."
else
    if diff -u "$BASELINE" "$OUT_DIR/pagila.compare.yaml" > /tmp/sample-db-cmp.diff 2>&1; then
        log "schema compare == baseline (no unexpected diffs)"
    else
        cat /tmp/sample-db-cmp.diff
        fail "schema compare DEVIATES from baseline — review the diff above. If a real fix shrank the diff, update expected/pagila-smoke.compare.txt + the explanation in expected/pagila-smoke.md."
    fi
fi

log "SUCCESS — stack is up; clean up with 'make sample-db-down' or 'make sample-db-purge'"
