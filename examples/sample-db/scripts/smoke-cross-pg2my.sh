#!/usr/bin/env bash
# Sample-DB-Harness — Phase 2 Cross-Dialect-Smoke (Pagila PG → MySQL)
# Plan: docs/planning/done/sample-db-integration-harness.md
# ADR:  docs/adr/0014-sample-db-harness-fetch-and-compose.md
# Findings: docs/planning/in-progress/sample-db-phase2-findings.md (Flow B)
#
# Symmetrischer Cross-Dialect-Flow zu smoke-cross.sh (Sakila MySQL→PG):
#   Pagila in PG laden -> reverse pagila_pg --include-all -> validate
#   -> generate --target mysql --split pre-post -> pre-data auf MySQL-Ziel
#   -> data transfer PG->MySQL -> Zeilen-Parität + Wert-Stichproben.
#
# Gepinnt:
#   - generate-Notes == Baseline (PG-Features, die MySQL nicht 1:1 trägt)
#   - Per-Tabelle-Parität Quelle == Ziel (logische Tabellen; Partitionskinder
#     zählen nicht separat — payment ist EINE partitionierte MySQL-Tabelle)
#   - Typ-Konvertierungen datenbelegt (boolean→tinyint(1), text[]→json,
#     tsvector→text, timestamptz→datetime/W100)
#   - Partitions-Integrität: payment round-trippt als EINE RANGE-COLUMNS-Tabelle,
#     keine Kind-Duplikation, alle Zeilen vorhanden
#
# AP1/AP2 (PG-Partition-Reverse) + AP6 (Cross-Dialect-Generate) lösten das frühere
# Finding P2-pg2my (Partition-Daten-Duplikation): der Reverse modelliert payment als
# partitionierten Parent + Kind-Partitionen (nicht mehr als lose Tabellen), MySQL-
# Generate emittiert `PARTITION BY RANGE COLUMNS` mit UTC-normalisierten Grenzen
# (W112/W129) und überspringt die FKs der partitionierten Tabelle (E065, ADR 0020 §5).
#
# Voraussetzung am Host: docker, docker compose, lokal gebautes d-migrate:dev-Image.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
EXAMPLES_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
COMPOSE_FILE="$EXAMPLES_DIR/docker-compose.yml"
COMPOSE="docker compose -f $COMPOSE_FILE"
OUT_DIR="$EXAMPLES_DIR/out"
EXPECTED_DIR="$EXAMPLES_DIR/expected"
NOTES_BASELINE="$EXPECTED_DIR/pagila-cross.notes.txt"

log()  { printf '[cross-pg2my] %s\n' "$*"; }
note() { printf '[cross-pg2my] NOTE: %s\n' "$*"; }
fail() { printf '[cross-pg2my] FAIL: %s\n' "$*" >&2; exit 1; }

# --- 0. .env + Verzeichnisse + Dump-Fetch --------------------------
mkdir -p "$OUT_DIR" "$EXPECTED_DIR"
if [ ! -f "$EXAMPLES_DIR/.env" ]; then
    cp "$EXAMPLES_DIR/.env.example" "$EXAMPLES_DIR/.env"
    log "created examples/sample-db/.env from .env.example"
fi
# shellcheck disable=SC1091
set -a; . "$EXAMPLES_DIR/.env"; set +a
: "${POSTGRES_USER:?POSTGRES_USER not set}"
: "${MYSQL_ROOT_PASSWORD:?MYSQL_ROOT_PASSWORD not set}"

# Das d-migrate:dev-Image läuft als non-root (uid 10001); damit es in das
# gemountete out/ schreiben kann, läuft der dmigrate-Container als Host-User
# (vom compose `user:`-Feld konsumiert). Sonst: "Failed to write schema".
export SAMPLE_DB_DMIGRATE_USER="$(id -u):$(id -g)"

"$SCRIPT_DIR/fetch-dumps.sh"

mysql_root() { $COMPOSE exec -T mysql mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -N "$@" 2>/dev/null; }
psql_t()     { $COMPOSE exec -T postgres psql -v ON_ERROR_STOP="$2" -U "$POSTGRES_USER" -d "$1" "${@:3}"; }
pg_val()     { $COMPOSE exec -T postgres psql -U "$POSTGRES_USER" -d pagila -tAc "$1" 2>/dev/null | tr -d '[:space:]'; }
my_val()     { mysql_root -e "$1" | tr -d '[:space:]'; }

wait_healthy() {  # wait_healthy <service> <timeout_s>
    local svc="$1" to="$2" deadline st
    deadline=$(( $(date +%s) + to ))
    while [ "$(date +%s)" -lt "$deadline" ]; do
        st=$(docker inspect --format '{{.State.Health.Status}}' "$($COMPOSE ps -q "$svc")" 2>/dev/null || echo "?")
        [ "$st" = "healthy" ] && { log "$svc healthy"; return 0; }
        sleep 3
    done
    fail "$svc did not reach healthy state within ${to}s"
}

# --- 1. Stacks hoch (postgres-Quelle + mysql-Ziel) -----------------
log "starting postgres + mysql..."
$COMPOSE up -d postgres mysql
wait_healthy postgres 120
wait_healthy mysql 180

# --- 2. Pagila in eine frische PG-Quell-DB laden -------------------
log "resetting + loading pagila source DB..."
psql_t postgres 1 -c "DROP DATABASE IF EXISTS pagila WITH (FORCE)" -c "CREATE DATABASE pagila" > /dev/null
psql_t pagila 1 < "$EXAMPLES_DIR/.cache/pagila.sql" > /dev/null || fail "pagila dump load failed"
src_tables=$(pg_val "SELECT count(*) FROM information_schema.tables WHERE table_schema='public' AND table_type='BASE TABLE'")
[ "$src_tables" = "22" ] || fail "expected 22 source tables, got $src_tables"
# Logical tables = base tables that are NOT partition children. The partitioned
# `payment` parent counts once; its 7 children are partitions, not separate tables.
# AP2 reverse-filters the children and AP6 MySQL-generate emits payment as ONE
# partitioned table, so the MySQL target has this many base tables (not 22).
logical_tables=$(pg_val "SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='public' AND c.relkind IN ('r','p') AND NOT c.relispartition")
log "pagila loaded ($src_tables raw tables; $logical_tables logical, partition children excluded)"

# --- 3. reverse --include-all (PG-Quelle) --------------------------
log "schema reverse pagila_pg --include-all..."
$COMPOSE run --rm dmigrate schema reverse --source pagila_pg --include-all \
    --output /work/out/pagila.reverse.yaml > /dev/null || fail "reverse failed"
[ -s "$OUT_DIR/pagila.reverse.yaml" ] || fail "empty reverse.yaml"

# --- 4. validate (0 Errors hart) -----------------------------------
log "schema validate (expect 0 errors)..."
val_out=$($COMPOSE run --rm dmigrate schema validate --source /work/out/pagila.reverse.yaml 2>&1) \
    || fail "validate exited non-zero:\n$val_out"
printf '%s' "$val_out" | grep -q "Validation passed" || fail "validate did not pass:\n$val_out"
log "validate passed"

# --- 5. generate --target mysql + Notes gegen Baseline -------------
log "schema generate --target mysql --split pre-post --deterministic..."
$COMPOSE run --rm dmigrate schema generate --source /work/out/pagila.reverse.yaml \
    --target mysql --split pre-post --deterministic \
    --output /work/out/pagila.my.sql > /dev/null || fail "generate failed"
for f in pagila.my.pre-data.sql pagila.my.post-data.sql pagila.my.report.yaml; do
    [ -s "$OUT_DIR/$f" ] || fail "empty generate artifact: $f"
done
grep -oE 'code: [A-Z][0-9]+' "$OUT_DIR/pagila.my.report.yaml" | sort | uniq -c \
    | sed 's/^ *//' > "$OUT_DIR/pagila-cross.notes.txt"
if [ ! -f "$NOTES_BASELINE" ]; then
    cp "$OUT_DIR/pagila-cross.notes.txt" "$NOTES_BASELINE"
    log "BASELINE BOOTSTRAP: wrote $NOTES_BASELINE — review + commit it, then re-run."
else
    diff -u "$NOTES_BASELINE" "$OUT_DIR/pagila-cross.notes.txt" > /tmp/cross-pg2my-notes.diff 2>&1 \
        && log "generate notes == baseline" \
        || { cat /tmp/cross-pg2my-notes.diff; fail "generate notes DEVIATE from baseline (review; if a real fix changed them, re-pin pagila-cross.notes.txt)"; }
fi

# --- 6. MySQL-Ziel aufbauen + Daten transferieren ------------------
log "resetting target DB pagila_target (MySQL) + applying pre-data DDL..."
mysql_root -e "DROP DATABASE IF EXISTS pagila_target; CREATE DATABASE pagila_target CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci; GRANT ALL ON pagila_target.* TO '${MYSQL_USER}'@'%'; FLUSH PRIVILEGES;"
mysql_root pagila_target < "$OUT_DIR/pagila.my.pre-data.sql" || fail "pre-data apply failed"
tgt_tables=$(my_val "SELECT count(*) FROM information_schema.tables WHERE table_schema='pagila_target' AND table_type='BASE TABLE';")
[ "$tgt_tables" = "$logical_tables" ] || fail "expected $logical_tables target tables after pre-data (payment partitioned, children not separate), got $tgt_tables"

log "data transfer pagila_pg -> pagila_my_target..."
$COMPOSE run --rm dmigrate data transfer --source pagila_pg --target pagila_my_target --truncate \
    > /tmp/cross-pg2my-xfer.log 2>&1 || { cat /tmp/cross-pg2my-xfer.log; fail "transfer failed"; }
grep -q "Transfer complete" /tmp/cross-pg2my-xfer.log || fail "transfer did not complete"

# --- 7. Per-Tabelle-Zeilen-Parität ---------------------------------
log "verifying per-table row-count parity (source == target)..."
mismatch=0
compared=0
# F1: list tables into a variable FIRST — process substitution `< <(…)` does not
# propagate the generator's exit (even under pipefail), so a failed list query would
# run the loop 0× and falsely log "parity OK" without comparing anything. The bare
# assignment aborts on a generator error (|| fail); the compared-count assertion below
# is the second guard against the 0-iteration false-green.
src_table_list=$(psql_t pagila 0 -tAc "SELECT c.relname FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='public' AND c.relkind IN ('r','p') AND NOT c.relispartition ORDER BY 1") \
    || fail "could not list source tables for parity"
[ -n "$src_table_list" ] || fail "source table list for parity is empty"
while IFS= read -r t; do
    [ -n "$t" ] || continue
    s=$(pg_val "SELECT count(*) FROM \"$t\"")
    d=$(my_val "SELECT count(*) FROM pagila_target.\`$t\`;")
    if [ "$s" != "$d" ]; then printf '[cross-pg2my]   MISMATCH %s: src=%s dst=%s\n' "$t" "$s" "$d"; mismatch=1; fi
    compared=$((compared + 1))
done <<< "$src_table_list"
[ "$mismatch" = "0" ] || fail "per-table row-count parity violated"
[ "$compared" = "$logical_tables" ] || fail "parity compared $compared tables, expected $logical_tables (generator-swallow guard)"
log "per-table parity OK (all $compared logical tables; payment compared as a whole)"

# --- 8. Schlüssel-Typ-Konvertierungen datenbelegt ------------------
log "verifying critical cross-dialect type conversions..."
# boolean -> tinyint(1) (customer.activebool)
pg_act=$(pg_val "SELECT count(*) FILTER (WHERE activebool) FROM customer")
my_act=$(my_val "SELECT SUM(activebool) FROM pagila_target.customer;")
[ "$pg_act" = "$my_act" ] || fail "boolean->tinyint(1) mismatch: src=$pg_act dst=$my_act"
my_act_type=$(my_val "SELECT column_type FROM information_schema.columns WHERE table_schema='pagila_target' AND table_name='customer' AND column_name='activebool';")
[ "$my_act_type" = "tinyint(1)" ] || fail "customer.activebool expected tinyint(1), got $my_act_type"
log "  boolean->tinyint(1) OK (true-count $pg_act)"

# text[] -> json (film.special_features), film_id=1
my_sf_type=$(my_val "SELECT column_type FROM information_schema.columns WHERE table_schema='pagila_target' AND table_name='film' AND column_name='special_features';")
[ "$my_sf_type" = "json" ] || fail "film.special_features expected json, got $my_sf_type"
my_sf_valid=$(my_val "SELECT JSON_VALID(special_features) FROM pagila_target.film WHERE film_id=1;")
[ "$my_sf_valid" = "1" ] || fail "text[]->json: special_features is not valid JSON"
log "  text[]->json OK (valid JSON array)"

# tsvector -> text (film.fulltext): alle 1000 befüllt (fulltext = MySQL-Reserved → Backticks)
ft_empty=$(my_val "SELECT COUNT(*) FROM pagila_target.film WHERE \`fulltext\` IS NULL OR CHAR_LENGTH(\`fulltext\`)=0;")
[ "$ft_empty" = "0" ] || fail "tsvector->text: $ft_empty film rows have empty fulltext"
log "  tsvector->text OK (all film.fulltext populated)"

# --- 9. Partitions-Integrität (AP1/AP2 + AP6): payment round-trippt als EINE
#        partitionierte MySQL-Tabelle — alle Zeilen, keine Kind-Duplikation. -----
log "verifying partition integrity (payment as one partitioned MySQL table)..."
pay_src=$(pg_val "SELECT count(*) FROM payment")
pay_dst=$(my_val "SELECT COUNT(*) FROM pagila_target.payment;")
[ "$pay_src" = "$pay_dst" ] || fail "payment row count mismatch: src=$pay_src dst=$pay_dst (duplication regression?)"
pay_parts=$(my_val "SELECT COUNT(*) FROM information_schema.partitions WHERE table_schema='pagila_target' AND table_name='payment' AND partition_name IS NOT NULL;")
[ "${pay_parts:-0}" -ge 1 ] || fail "payment is not partitioned in MySQL target (expected RANGE COLUMNS partitions, got ${pay_parts:-0})"
# The former loose-table children must NOT exist as separate tables (no duplication).
child_tables=$(my_val "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='pagila_target' AND table_name LIKE 'payment\\_p2022\\_%';")
[ "${child_tables:-0}" = "0" ] || fail "partition children leaked as separate tables ($child_tables) — duplication regression"
# FKs on the partitioned table are skipped (E065, ADR 0020 §5) → none on payment.
pay_fks=$(my_val "SELECT COUNT(*) FROM information_schema.table_constraints WHERE table_schema='pagila_target' AND table_name='payment' AND constraint_type='FOREIGN KEY';")
[ "${pay_fks:-0}" = "0" ] || fail "partitioned payment has $pay_fks foreign keys — MySQL forbids FKs on partitioned tables (E065 carve-out broke)"
log "  partition integrity OK (payment: $pay_dst rows across $pay_parts MySQL partitions, 0 child tables, 0 FKs)"

log "SUCCESS — Pagila PG->MySQL cross-dialect smoke passed (per-table parity + conversions + partition integrity)."
log "stack is up; clean up with 'make sample-db-down' or 'make sample-db-purge'."
