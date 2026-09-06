#!/usr/bin/env bash
# Sample-DB-Harness — Oracle-Leg, Gegenrichtung: Pagila Oracle → PostgreSQL
# Plan: docs/planning/next/oracle-sample-db-leg.md (Slice 4b)
# ADR:  docs/adr/0052-oracle-fuenfter-dialekt-scoping.md,
#       docs/adr/0014-sample-db-harness-fetch-and-compose.md
#
# smoke-cross-pg2ora.sh (Slice 3b) faehrt Oracle als ZIEL. Dieses Leg faehrt
# ihn als QUELLE — der Pfad, den `schema reverse` + `data transfer` gegen ein
# gewachsenes Oracle-Schema nehmen, und der einzige, der die Rueckrichtung der
# Typtabelle belegt (NUMBER→numerisch, CLOB→text, TIMESTAMP WITH TIME ZONE→
# timestamptz, IDENTITY→identifier).
#
#   Hop 0 (Saat, Mechanik aus smoke-cross-pg2ora.sh): Pagila in PG laden ->
#     reverse -> generate --target oracle -> pre-data per sqlplus ->
#     transfer PG->Oracle.
#   Hop 1 (das Leg unter Test): reverse pagila_ora_source --include-all ->
#     validate -> generate --target postgresql --split pre-post -> pre-data
#     per psql -> transfer Oracle->PG --verify -> Paritaet + Typ-Stichproben.
#
# Gepinnt:
#   - generate-Notes == Baseline (was PG aus einem Oracle-Modell nicht 1:1 traegt)
#   - DREIFACHE Zeilen-Paritaet: PG-Original == Oracle == PG-Rueckziel.
#     Der Vergleich laeuft gegen die Original-Pagila, nicht gegen sich selbst.
#   - Rueckwaerts-Typkonvertierungen datenbelegt
#
# WAS DIESES LEG BEWUSST NICHT PRUEFT: Wert-Identitaet Original == Rueckziel.
# Oracle setzt den leeren String mit NULL gleich; ein `''` aus der Original-
# Pagila kommt als NULL zurueck (nullbare Spalten) bzw. als der in
# `write.oracle.empty_string` erklaerte Ersatztext (NOT-NULL-Spalten). Dieser
# Verlust ist Oracles Semantik, nicht ein Fehler des Werkzeugs -- die
# Zeilen-Paritaet und die Typ-Stichproben bleiben davon unberuehrt.
#
# Voraussetzung am Host: docker, docker compose, lokal gebautes d-migrate:dev-Image.
# Das Oracle-Image braucht ~2 GB RAM und ~2-3 Minuten Kaltstart.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
EXAMPLES_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
COMPOSE_FILE="$EXAMPLES_DIR/docker-compose.yml"
COMPOSE="docker compose -f $COMPOSE_FILE"
OUT_DIR="$EXAMPLES_DIR/out"
EXPECTED_DIR="$EXAMPLES_DIR/expected"
NOTES_BASELINE="$EXPECTED_DIR/pagila-cross-ora2pg.notes.txt"
EXPECTED_VERIFY_EXCLUSIONS_FILE="$EXPECTED_DIR/pagila-cross-ora2pg.verify-exclusions.txt"

log()  { printf '[cross-ora2pg] %s\n' "$*"; }
fail() { printf '[cross-ora2pg] FAIL: %s\n' "$*" >&2; exit 1; }

# --- 0. .env + Verzeichnisse + Dump-Fetch --------------------------
mkdir -p "$OUT_DIR" "$EXPECTED_DIR"
if [ ! -f "$EXAMPLES_DIR/.env" ]; then
    cp "$EXAMPLES_DIR/.env.example" "$EXAMPLES_DIR/.env"
    log "created examples/sample-db/.env from .env.example"
else
    if [ -s "$EXAMPLES_DIR/.env" ] && [ -n "$(tail -c1 "$EXAMPLES_DIR/.env")" ]; then
        printf '\n' >> "$EXAMPLES_DIR/.env"
    fi
    added=""
    while IFS= read -r line; do
        case "$line" in
            [A-Z]*=*)
                key="${line%%=*}"
                grep -qE "^[[:space:]]*${key}=" "$EXAMPLES_DIR/.env" || {
                    printf '%s\n' "$line" >> "$EXAMPLES_DIR/.env"
                    added="$added $key"
                }
                ;;
        esac
    done < "$EXAMPLES_DIR/.env.example"
    [ -z "$added" ] || log "added missing key(s) to existing .env:$added"
fi
# shellcheck disable=SC1091
set -a; . "$EXAMPLES_DIR/.env"; set +a
: "${POSTGRES_USER:?POSTGRES_USER not set}"
: "${APP_USER:?APP_USER not set (add it to examples/sample-db/.env)}"
: "${APP_USER_PASSWORD:?APP_USER_PASSWORD not set (add it to examples/sample-db/.env)}"

export SAMPLE_DB_DMIGRATE_USER="$(id -u):$(id -g)"

"$SCRIPT_DIR/fetch-dumps.sh"

psql_t()   { $COMPOSE exec -T postgres psql -v ON_ERROR_STOP="$2" -U "$POSTGRES_USER" -d "$1" "${@:3}"; }
# `</dev/null`: die Value-Helfer laufen in `while read … done <<< "$list"`-Schleifen;
# `docker compose exec` schluckt sonst das Here-String (False-Green nach der 1. Iteration).
pg_val()   { $COMPOSE exec -T postgres psql -U "$POSTGRES_USER" -d pagila -tAc "$1" </dev/null 2>/dev/null | tr -d '[:space:]'; }
back_val() { $COMPOSE exec -T postgres psql -U "$POSTGRES_USER" -d pagila_back -tAc "$1" </dev/null 2>/dev/null | tr -d '[:space:]'; }
# Wie back_val, aber ohne Whitespace-Strippen -- fuer Typnamen wie
# "timestamp with time zone", die sonst zusammenkleben.
back_text() { $COMPOSE exec -T postgres psql -U "$POSTGRES_USER" -d pagila_back -tAc "$1" </dev/null 2>/dev/null | sed 's/^ *//;s/ *$//'; }

ora_run() {  # ora_run <sql-text>
    $COMPOSE exec -T oracle sqlplus -S -L "${APP_USER}/${APP_USER_PASSWORD}@//localhost:1521/FREEPDB1" <<SQL
WHENEVER SQLERROR EXIT SQL.SQLCODE
SET HEADING OFF FEEDBACK OFF PAGESIZE 0 LINESIZE 32767 TRIMSPOOL ON
$1
EXIT
SQL
}
ora_val()  { ora_run "$1" </dev/null 2>/dev/null | tr -d '[:space:]'; }
ora_exec() { ora_run "$1" </dev/null | tr -d '[:space:]'; }

wait_healthy() {  # wait_healthy <service> <timeout_s>
    local svc="$1" to="$2" deadline st
    deadline=$(( $(date +%s) + to ))
    while [ "$(date +%s)" -lt "$deadline" ]; do
        st=$(docker inspect --format '{{.State.Health.Status}}' "$($COMPOSE ps -q "$svc")" 2>/dev/null || echo "?")
        [ "$st" = "healthy" ] && { log "$svc healthy"; return 0; }
        sleep 5
    done
    fail "$svc did not reach healthy state within ${to}s"
}

# ══ HOP 0 — Oracle-Quelle saeen (Mechanik aus smoke-cross-pg2ora.sh) ══
log "starting postgres + oracle (Oracle cold start takes ~2-3 min)..."
$COMPOSE up -d postgres oracle
wait_healthy postgres 120
wait_healthy oracle 600

log "hop 0: resetting + loading pagila reference DB (PostgreSQL)..."
psql_t postgres 1 -c "DROP DATABASE IF EXISTS pagila WITH (FORCE)" -c "CREATE DATABASE pagila" > /dev/null
psql_t pagila 1 < "$EXAMPLES_DIR/.cache/pagila.sql" > /dev/null || fail "pagila dump load failed"
logical_tables=$(pg_val "SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='public' AND c.relkind IN ('r','p') AND NOT c.relispartition")
log "  pagila loaded ($logical_tables logical tables)"

log "hop 0: reverse PG + generate --target oracle..."
$COMPOSE run --rm dmigrate schema reverse --source pagila_pg --include-all \
    --output /work/out/ora2pg.hop0.reverse.yaml > /dev/null || fail "hop 0 reverse failed"
$COMPOSE run --rm dmigrate schema generate --source /work/out/ora2pg.hop0.reverse.yaml \
    --target oracle --split pre-post --deterministic \
    --output /work/out/ora2pg.hop0.ora.sql > /dev/null || fail "hop 0 generate failed"

log "hop 0: clearing the ${APP_USER} schema + applying pre-data via sqlplus..."
ora_exec "BEGIN
  FOR r IN (SELECT table_name FROM user_tables) LOOP
    EXECUTE IMMEDIATE 'DROP TABLE \"' || r.table_name || '\" CASCADE CONSTRAINTS PURGE';
  END LOOP;
  FOR r IN (SELECT view_name FROM user_views) LOOP
    EXECUTE IMMEDIATE 'DROP VIEW \"' || r.view_name || '\"';
  END LOOP;
  FOR r IN (SELECT sequence_name FROM user_sequences) LOOP
    EXECUTE IMMEDIATE 'DROP SEQUENCE \"' || r.sequence_name || '\"';
  END LOOP;
END;
/" > /dev/null || fail "could not clear the ${APP_USER} schema"
$COMPOSE exec -T oracle sqlplus -S -L "${APP_USER}/${APP_USER_PASSWORD}@//localhost:1521/FREEPDB1" \
    < "$OUT_DIR/ora2pg.hop0.ora.pre-data.sql" > /tmp/cross-ora2pg-hop0.log 2>&1 \
    || { tail -40 /tmp/cross-ora2pg-hop0.log; fail "hop 0 pre-data apply failed"; }
if grep -qE '^ORA-[0-9]+|^SP2-[0-9]+' /tmp/cross-ora2pg-hop0.log; then
    grep -E '^ORA-[0-9]+|^SP2-[0-9]+' /tmp/cross-ora2pg-hop0.log | sort -u | head -20
    fail "hop 0 pre-data apply reported Oracle errors"
fi

log "hop 0: transfer pagila_pg -> pagila_ora_target..."
$COMPOSE run --rm dmigrate data transfer --source pagila_pg --target pagila_ora_target --truncate \
    > /tmp/cross-ora2pg-hop0-xfer.log 2>&1 \
    || { tail -40 /tmp/cross-ora2pg-hop0-xfer.log; fail "hop 0 transfer failed"; }
ora_tables=$(ora_val "SELECT COUNT(*) FROM user_tables;")
[ "$ora_tables" = "$logical_tables" ] \
    || fail "hop 0 seeded $ora_tables tables, expected $logical_tables"
log "  Oracle source ready ($ora_tables tables)"

# ══ HOP 1 — das Leg unter Test: Oracle als QUELLE ══
log "schema reverse pagila_ora_source --include-all..."
$COMPOSE run --rm dmigrate schema reverse --source pagila_ora_source --include-all \
    --output /work/out/pagila.ora.reverse.yaml > /dev/null || fail "reverse from Oracle failed"
[ -s "$OUT_DIR/pagila.ora.reverse.yaml" ] || fail "empty reverse.yaml"

log "schema validate (expect 0 errors)..."
val_out=$($COMPOSE run --rm dmigrate schema validate --source /work/out/pagila.ora.reverse.yaml 2>&1) \
    || fail "validate exited non-zero:\n$val_out"
printf '%s' "$val_out" | grep -q "Validation passed" || fail "validate did not pass:\n$val_out"
log "validate passed"

log "schema generate --target postgresql --split pre-post --deterministic..."
$COMPOSE run --rm dmigrate schema generate --source /work/out/pagila.ora.reverse.yaml \
    --target postgresql --split pre-post --deterministic \
    --output /work/out/pagila.oraback.sql > /dev/null || fail "generate --target postgresql failed"
for f in pagila.oraback.pre-data.sql pagila.oraback.post-data.sql pagila.oraback.report.yaml; do
    [ -s "$OUT_DIR/$f" ] || fail "empty generate artifact: $f"
done
# Gegenprobe: `/`-Batchtrenner sind Oracle-Skript-Darstellung und haben in
# einem PostgreSQL-Skript nichts zu suchen. `if`, nicht `cmd && fail`: unter
# `set -e` beendet der Nicht-Treffer einer `&&`-Liste das ganze Skript.
if grep -qx "/" "$OUT_DIR/pagila.oraback.pre-data.sql"; then
    fail "PostgreSQL script carries '/' batch separators (dialect leak)"
fi
{ grep -oE 'code: [A-Z][0-9]+' "$OUT_DIR/pagila.oraback.report.yaml" || true; } | sort | uniq -c \
    | sed 's/^ *//' > "$OUT_DIR/pagila-cross-ora2pg.notes.txt"
[ -s "$OUT_DIR/pagila-cross-ora2pg.notes.txt" ] || echo "0 notes" > "$OUT_DIR/pagila-cross-ora2pg.notes.txt"
if [ ! -f "$NOTES_BASELINE" ]; then
    cp "$OUT_DIR/pagila-cross-ora2pg.notes.txt" "$NOTES_BASELINE"
    log "BASELINE BOOTSTRAP: wrote $NOTES_BASELINE — review + commit it, then re-run."
else
    diff -u "$NOTES_BASELINE" "$OUT_DIR/pagila-cross-ora2pg.notes.txt" > /tmp/cross-ora2pg-notes.diff 2>&1 \
        && log "generate notes == baseline" \
        || { cat /tmp/cross-ora2pg-notes.diff; fail "generate notes DEVIATE from baseline (review; if a real fix changed them, re-pin $NOTES_BASELINE)"; }
fi

log "resetting target DB pagila_back (PostgreSQL) + applying pre-data DDL via psql..."
psql_t postgres 1 -c "DROP DATABASE IF EXISTS pagila_back WITH (FORCE)" -c "CREATE DATABASE pagila_back" > /dev/null
psql_t pagila_back 1 < "$OUT_DIR/pagila.oraback.pre-data.sql" > /tmp/cross-ora2pg-apply.log 2>&1 \
    || { tail -40 /tmp/cross-ora2pg-apply.log; fail "pre-data apply via psql failed"; }
back_tables=$(back_val "SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='public' AND c.relkind IN ('r','p') AND NOT c.relispartition")
[ "$back_tables" = "$logical_tables" ] \
    || fail "expected $logical_tables tables in pagila_back after pre-data, got $back_tables"
log "  pre-data applied ($back_tables tables)"

log "data transfer pagila_ora_source -> pagila_pg_back (with --verify, LN-009)..."
$COMPOSE run --rm dmigrate data transfer --source pagila_ora_source --target pagila_pg_back --truncate --verify \
    > /tmp/cross-ora2pg-xfer.log 2>&1 || { tail -40 /tmp/cross-ora2pg-xfer.log; fail "transfer/--verify failed"; }
grep -q "Transfer complete" /tmp/cross-ora2pg-xfer.log || fail "transfer did not complete"
grep -q "Verify OK" /tmp/cross-ora2pg-xfer.log || { tail -40 /tmp/cross-ora2pg-xfer.log; fail "--verify did not pass (LN-009 divergence)"; }
xfer_excl=$(grep -c "verify excluded" /tmp/cross-ora2pg-xfer.log || true)
if [ ! -f "$EXPECTED_VERIFY_EXCLUSIONS_FILE" ]; then
    echo "$xfer_excl" > "$EXPECTED_VERIFY_EXCLUSIONS_FILE"
    log "BASELINE BOOTSTRAP: pinned $xfer_excl verify exclusion(s) — review + commit, then re-run."
else
    expected_excl=$(tr -d '[:space:]' < "$EXPECTED_VERIFY_EXCLUSIONS_FILE")
    [ "$xfer_excl" = "$expected_excl" ] \
        || fail "expected $expected_excl verify exclusion(s), got $xfer_excl (see /tmp/cross-ora2pg-xfer.log)"
fi
log "  --verify OK (byte-reconciled; $xfer_excl column(s) excluded as cross-dialect transforms)"

# --- Dreifach-Paritaet: Original == Oracle == Rueckziel ------------
log "verifying three-way row-count parity (pagila == Oracle == pagila_back)..."
mismatch=0
compared=0
src_table_list=$(psql_t pagila 0 -tAc "SELECT c.relname FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='public' AND c.relkind IN ('r','p') AND NOT c.relispartition ORDER BY 1") \
    || fail "could not list reference tables for parity"
[ -n "$src_table_list" ] || fail "reference table list for parity is empty"
while IFS= read -r t; do
    [ -n "$t" ] || continue
    o=$(pg_val "SELECT count(*) FROM \"$t\"")          || o="<reference query failed>"
    r=$(ora_exec "SELECT COUNT(*) FROM \"$t\";")       || r="<oracle query failed>"
    b=$(back_val "SELECT count(*) FROM \"$t\"")        || b="<back query failed>"
    if [ "$o" != "$r" ] || [ "$o" != "$b" ]; then
        printf '[cross-ora2pg]   MISMATCH %s: pagila=%s oracle=%s back=%s\n' "$t" "$o" "$r" "$b"
        mismatch=1
    fi
    compared=$((compared + 1))
done <<< "$src_table_list"
[ "$mismatch" = "0" ] || fail "three-way row-count parity violated"
[ "$compared" = "$logical_tables" ] || fail "parity compared $compared tables, expected $logical_tables (generator-swallow guard)"
log "three-way parity OK (all $compared logical tables)"

# --- Rueckwaerts-Typkonvertierungen datenbelegt --------------------
log "verifying reverse-direction type conversions (Oracle -> PostgreSQL)..."
back_type() { back_text "SELECT data_type FROM information_schema.columns WHERE table_name='$1' AND column_name='$2'"; }

# CLOB -> text (customer.email war in der Original-Pagila unbegrenztes text)
[ "$(back_type customer email)" = "text" ] \
    || fail "customer.email expected text, got $(back_type customer email)"
log "  CLOB->text OK"

# TIMESTAMP WITH TIME ZONE -> timestamp with time zone
[ "$(back_type payment payment_date)" = "timestamp with time zone" ] \
    || fail "payment.payment_date expected timestamp with time zone, got $(back_type payment payment_date)"
log "  TIMESTAMP WITH TIME ZONE->timestamptz OK"

# boolean UEBERLEBT den Rundlauf, obwohl Oracle keinen BOOLEAN-Spaltentyp hat:
# der Hinweg legt NUMBER(1) an, der Oracle-Reverse rekonstruiert daraus wieder
# `boolean`, und das PG-Rueckziel entsteht mit boolean. Der Datenpfad setzt die
# Zahl an der Schreibgrenze um -- ohne das scheiterte der Transfer mit
# "column is of type boolean but expression is of type numeric".
[ "$(back_type customer activebool)" = "boolean" ] \
    || fail "customer.activebool expected boolean, got $(back_type customer activebool)"
o_act=$(pg_val "SELECT count(*) FILTER (WHERE activebool) FROM customer")
b_act=$(back_val "SELECT count(*) FILTER (WHERE activebool) FROM customer")
[ "$o_act" = "$b_act" ] || fail "boolean truth-count lost on the round trip: pagila=$o_act back=$b_act"
log "  NUMBER(1)->boolean reconstructed, truth-count preserved ($o_act)"

# Schluessel-Treue ueber beide Hops
o_max=$(pg_val "SELECT max(film_id) FROM film")
b_max=$(back_val "SELECT max(film_id) FROM film")
[ "$o_max" = "$b_max" ] || fail "film_id keys not preserved: pagila max=$o_max back max=$b_max"
log "  keys preserved across both hops (max film_id $b_max)"

# Der Enum-CHECK aus Hop 0 ueberlebt als CHECK im PG-Rueckziel
# (Typ-Seite bleibt text — die Enum-Rekonstruktion ist offen, siehe
# docs/planning/open/enum-inline-check-fidelity.md).
back_checks=$(back_val "SELECT count(*) FROM information_schema.table_constraints WHERE table_name='film' AND constraint_type='CHECK' AND constraint_name LIKE 'ck_film_rating%'")
[ "${back_checks:-0}" -ge 1 ] || fail "film.rating lost its CHECK constraint on the way back"
log "  enum CHECK survived the round trip"

log "SUCCESS — Pagila Oracle->PostgreSQL cross-dialect smoke passed (three-way parity + reverse-direction conversions)."
log "stack is up; clean up with 'make sample-db-down' or 'make sample-db-purge'."
