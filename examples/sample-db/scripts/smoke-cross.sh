#!/usr/bin/env bash
# Sample-DB-Harness — Phase 2 Cross-Dialect-Smoke (Sakila MySQL → PostgreSQL)
# Plan: docs/planning/in-progress/sample-db-integration-harness.md
# ADR:  docs/adr/0014-sample-db-harness-fetch-and-compose.md
# Findings: docs/planning/in-progress/sample-db-phase2-findings.md
#
# Echter Cross-Dialect-End-to-End-Lauf gegen das d-migrate:dev-Image:
#   Sakila in MySQL laden -> reverse sakila_my --include-all -> validate
#   -> generate --target postgresql --split pre-post -> pre-data auf PG-Ziel
#   -> data transfer MySQL->PG -> Zeilen-Parität + Wert-Stichproben.
#
# Anders als Phase 1 (smoke.sh, PG->PG-Round-Trip) ist das ein DIALEKT-WECHSEL:
# `schema compare` zwischen MySQL-Quelle und PG-Ziel ist nicht die Erwartung
# (Dialektunterschiede sind legitim). Stattdessen wird gepinnt:
#   - generate-Notes == Baseline (38: 32x E053 + 6x W127, alle erwartet)
#   - Zeilen-Parität Quelle == Ziel über alle Tabellen
#   - Schlüssel-Typ-Konvertierungen datenbelegt (TINYINT(1)->bool, ENUM, SET)
#
# Bekanntes Finding Y1 (YEAR-Wert-Korruption) wird als NOTE gemeldet, nicht als
# Fehler — getrackt in sample-db-phase2-findings.md (Fix = eigener Slice).
#
# Voraussetzung am Host: docker, docker compose sowie das lokal gebaute
# d-migrate:dev-Image (`make docker-build IMAGE_TAG=dev`).

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
EXAMPLES_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
COMPOSE_FILE="$EXAMPLES_DIR/docker-compose.yml"
COMPOSE="docker compose -f $COMPOSE_FILE"
OUT_DIR="$EXAMPLES_DIR/out"
EXPECTED_DIR="$EXAMPLES_DIR/expected"
NOTES_BASELINE="$EXPECTED_DIR/sakila-cross.notes.txt"

log()  { printf '[cross] %s\n' "$*"; }
note() { printf '[cross] NOTE: %s\n' "$*"; }
fail() { printf '[cross] FAIL: %s\n' "$*" >&2; exit 1; }

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

"$SCRIPT_DIR/fetch-dumps.sh"

mysql_root() { $COMPOSE exec -T mysql mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -N "$@" 2>/dev/null; }
psql_t()     { $COMPOSE exec -T postgres psql -v ON_ERROR_STOP="$2" -U "$POSTGRES_USER" -d "$1" "${@:3}"; }
pg_val()     { $COMPOSE exec -T postgres psql -U "$POSTGRES_USER" -d sakila_target -tAc "$1" 2>/dev/null | tr -d '[:space:]'; }
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

# --- 1. Stacks hoch (mysql-Quelle + postgres-Ziel) -----------------
log "starting mysql + postgres..."
$COMPOSE up -d mysql postgres
wait_healthy mysql 180
wait_healthy postgres 120

# --- 2. Sakila in eine frische MySQL-Quell-DB laden ----------------
log "resetting + loading sakila source DB (schema + data)..."
mysql_root -e "DROP DATABASE IF EXISTS sakila; CREATE DATABASE sakila CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
mysql_root sakila < "$EXAMPLES_DIR/.cache/sakila-schema.sql" || fail "sakila schema load failed"
mysql_root sakila < "$EXAMPLES_DIR/.cache/sakila-data.sql"   || fail "sakila data load failed"
src_tables=$(my_val "SELECT count(*) FROM information_schema.tables WHERE table_schema='sakila' AND table_type='BASE TABLE';")
[ "$src_tables" = "16" ] || fail "expected 16 source tables, got $src_tables"
log "sakila loaded ($src_tables tables)"

# --- 3. reverse --include-all (MySQL-Quelle) -----------------------
log "schema reverse sakila_my --include-all..."
$COMPOSE run --rm dmigrate schema reverse --source sakila_my --include-all \
    --output /work/out/sakila.reverse.yaml > /dev/null || fail "reverse failed"
[ -s "$OUT_DIR/sakila.reverse.yaml" ] || fail "empty reverse.yaml"

# --- 4. validate (0 Errors hart) -----------------------------------
log "schema validate (expect 0 errors)..."
val_out=$($COMPOSE run --rm dmigrate schema validate --source /work/out/sakila.reverse.yaml 2>&1) \
    || fail "validate exited non-zero:\n$val_out"
printf '%s' "$val_out" | grep -q "Validation passed" || fail "validate did not pass:\n$val_out"
log "validate passed"

# --- 5. generate --target postgresql + Notes gegen Baseline --------
log "schema generate --target postgresql --split pre-post --deterministic..."
$COMPOSE run --rm dmigrate schema generate --source /work/out/sakila.reverse.yaml \
    --target postgresql --split pre-post --deterministic \
    --output /work/out/sakila.pg.sql > /dev/null || fail "generate failed"
for f in sakila.pg.pre-data.sql sakila.pg.post-data.sql sakila.pg.report.yaml; do
    [ -s "$OUT_DIR/$f" ] || fail "empty generate artifact: $f"
done
# Notes-Signatur: sortierte Code-Häufigkeit (stabil, reihenfolge-unabhängig).
grep -oE 'code: [A-Z][0-9]+' "$OUT_DIR/sakila.pg.report.yaml" | sort | uniq -c \
    | sed 's/^ *//' > "$OUT_DIR/sakila-cross.notes.txt"
if [ ! -f "$NOTES_BASELINE" ]; then
    cp "$OUT_DIR/sakila-cross.notes.txt" "$NOTES_BASELINE"
    log "BASELINE BOOTSTRAP: wrote $NOTES_BASELINE — review + commit it, then re-run."
else
    diff -u "$NOTES_BASELINE" "$OUT_DIR/sakila-cross.notes.txt" > /tmp/cross-notes.diff 2>&1 \
        && log "generate notes == baseline" \
        || { cat /tmp/cross-notes.diff; fail "generate notes DEVIATE from baseline (review; if a real fix changed them, re-pin sakila-cross.notes.txt)"; }
fi

# --- 6. PG-Ziel aufbauen + Daten transferieren ---------------------
log "resetting target DB sakila_target (PG) + applying pre-data DDL..."
psql_t postgres 1 -c "DROP DATABASE IF EXISTS sakila_target WITH (FORCE)" -c "CREATE DATABASE sakila_target" > /dev/null
psql_t sakila_target 1 < "$OUT_DIR/sakila.pg.pre-data.sql" > /dev/null || fail "pre-data apply failed"

log "data transfer sakila_my -> sakila_pg_target..."
$COMPOSE run --rm dmigrate data transfer --source sakila_my --target sakila_pg_target --truncate \
    > /tmp/cross-xfer.log 2>&1 || { cat /tmp/cross-xfer.log; fail "transfer failed"; }
grep -q "Transfer complete" /tmp/cross-xfer.log || fail "transfer did not complete"

# --- 7. Zeilen-Parität Quelle == Ziel (alle Tabellen) --------------
log "verifying row-count parity (source == target) for all tables..."
mismatch=0
while IFS= read -r t; do
    [ -n "$t" ] || continue
    s=$(my_val "SELECT count(*) FROM sakila.\`$t\`;")
    d=$(pg_val "SELECT count(*) FROM \"$t\"")
    if [ "$s" != "$d" ]; then printf '[cross]   MISMATCH %s: src=%s dst=%s\n' "$t" "$s" "$d"; mismatch=1; fi
done < <(mysql_root -e "SELECT table_name FROM information_schema.tables WHERE table_schema='sakila' AND table_type='BASE TABLE' ORDER BY table_name;")
[ "$mismatch" = "0" ] || fail "row-count parity violated"
log "row-count parity OK (all $src_tables tables)"

# --- 8. Schlüssel-Typ-Konvertierungen datenbelegt ------------------
log "verifying critical cross-dialect type conversions..."
# TINYINT(1) -> boolean (customer.active): Summe muss gleich sein
my_active=$(my_val "SELECT SUM(active) FROM sakila.customer;")
pg_active=$(pg_val "SELECT SUM(active::int) FROM customer")
[ "$my_active" = "$pg_active" ] || fail "TINYINT(1)->bool mismatch: customer.active sum src=$my_active dst=$pg_active"
pg_active_type=$(pg_val "SELECT data_type FROM information_schema.columns WHERE table_name='customer' AND column_name='active'")
[ "$pg_active_type" = "boolean" ] || fail "customer.active expected boolean, got $pg_active_type"
log "  TINYINT(1)->boolean OK (sum $my_active, type boolean)"

# ENUM -> text (film.rating): Verteilung identisch. ACHTUNG: order-unabhängig
# als rating=count-Paare vergleichen — MySQL-ENUM sortiert nach Deklarations-
# Ordinal, PG-text lexikalisch; beidseitig CAST auf CHAR + lexikalisch ordnen.
my_rating=$(my_val "SELECT GROUP_CONCAT(p ORDER BY p) FROM (SELECT CONCAT(CAST(rating AS CHAR),'=',COUNT(*)) p FROM sakila.film GROUP BY rating) x;")
pg_rating=$(pg_val "SELECT string_agg(p, ',' ORDER BY p) FROM (SELECT rating||'='||COUNT(*) p FROM film GROUP BY rating) x")
[ "$my_rating" = "$pg_rating" ] || fail "ENUM->text rating distribution mismatch: src=$my_rating dst=$pg_rating"
log "  ENUM->text OK (rating distribution identical: $pg_rating)"

# SET -> text (film.special_features): Stichprobe film_id=1
my_sf=$(my_val "SELECT special_features FROM sakila.film WHERE film_id=1;")
pg_sf=$(pg_val "SELECT special_features FROM film WHERE film_id=1")
[ "$my_sf" = "$pg_sf" ] || fail "SET->text mismatch: special_features src=$my_sf dst=$pg_sf"
log "  SET->text OK (special_features identical)"

# Y1 (bekanntes Finding): YEAR-Wert-Korruption — NOTE, kein Fehler.
my_year=$(my_val "SELECT release_year FROM sakila.film WHERE film_id=1;")
pg_year=$(pg_val "SELECT release_year FROM film WHERE film_id=1")
if [ "$my_year" != "$pg_year" ]; then
    note "Y1 (known finding): YEAR value src='$my_year' -> dst='$pg_year' (yearIsDateType). See sample-db-phase2-findings.md."
else
    note "Y1 appears FIXED (YEAR value '$my_year' round-trips) — update sample-db-phase2-findings.md + this check."
fi

log "SUCCESS — Sakila MySQL->PG cross-dialect smoke passed (parity + conversions; Y1 tracked)."
log "stack is up; clean up with 'make sample-db-down' or 'make sample-db-purge'."
