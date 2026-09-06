#!/usr/bin/env bash
# Sample-DB-Harness — Oracle-Leg: Cross-Dialect-Smoke (Pagila PG → Oracle)
# Plan: docs/planning/next/oracle-sample-db-leg.md (Slice 3b)
# ADR:  docs/adr/0052-oracle-fuenfter-dialekt-scoping.md,
#       docs/adr/0014-sample-db-harness-fetch-and-compose.md
#
# Symmetrisch zu smoke-cross-pg2ms.sh (Pagila PG→SQL Server), mit dem Schritt,
# der Oracle eigen ist: das erzeugte Skript wird per **sqlplus** angewendet,
# also von genau dem Client, den ein Anwender dafuer nimmt. Genau dieser
# Schritt hat gezeigt, dass ein `/` hinter einer `;`-Anweisung sie ein
# zweites Mal ausfuehrt -- ueber JDBC waere das nie aufgefallen.
#
#   Pagila in PG laden -> reverse pagila_pg --include-all -> validate
#   -> generate --target oracle --split pre-post -> pre-data per sqlplus anwenden
#   -> data transfer PG->Oracle --verify -> Zeilen-Paritaet + Typ-Stichproben.
#
# Gepinnt:
#   - generate-Notes == Baseline (was Oracle nicht 1:1 traegt)
#   - Per-Tabelle-Paritaet Quelle == Ziel
#   - Typ-Konvertierungen datenbelegt
#
# Voraussetzung am Host: docker, docker compose, lokal gebautes d-migrate:dev-Image.
# Das Oracle-Image braucht ~2 GB RAM und ~2-3 Minuten Kaltstart; deshalb ist
# dieses Leg nicht Teil des Standard-Smokes.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
EXAMPLES_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
COMPOSE_FILE="$EXAMPLES_DIR/docker-compose.yml"
COMPOSE="docker compose -f $COMPOSE_FILE"
OUT_DIR="$EXAMPLES_DIR/out"
EXPECTED_DIR="$EXAMPLES_DIR/expected"
NOTES_BASELINE="$EXPECTED_DIR/pagila-cross-ora.notes.txt"
# Wie beim MSSQL-Leg gepinnt: waeren es ploetzlich alle Spalten, liefe
# "Verify OK" ins Leere. Der Wert stammt aus einem echten Erstlauf.
EXPECTED_VERIFY_EXCLUSIONS_FILE="$EXPECTED_DIR/pagila-cross-ora.verify-exclusions.txt"

log()  { printf '[cross-pg2ora] %s\n' "$*"; }
fail() { printf '[cross-pg2ora] FAIL: %s\n' "$*" >&2; exit 1; }

# --- 0. .env + Verzeichnisse + Dump-Fetch --------------------------
mkdir -p "$OUT_DIR" "$EXPECTED_DIR"
if [ ! -f "$EXAMPLES_DIR/.env" ]; then
    cp "$EXAMPLES_DIR/.env.example" "$EXAMPLES_DIR/.env"
    log "created examples/sample-db/.env from .env.example"
else
    # Ein `.env` aus einem frueheren Lauf kennt die Oracle-Variablen nicht.
    # Fehlende Schluessel ergaenzen, vorhandene Werte unangetastet lassen.
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

psql_t() { $COMPOSE exec -T postgres psql -v ON_ERROR_STOP="$2" -U "$POSTGRES_USER" -d "$1" "${@:3}"; }
# `</dev/null`: diese Value-Helfer laufen in `while read … done <<< "$list"`-Schleifen;
# `docker compose exec` schluckt sonst das Here-String (False-Green nach der 1. Iteration).
pg_val() { $COMPOSE exec -T postgres psql -U "$POSTGRES_USER" -d pagila -tAc "$1" </dev/null 2>/dev/null | tr -d '[:space:]'; }

# sqlplus im gvenzl-Image liegt auf dem PATH ($ORACLE_HOME/bin).
# -S still, -L kein Retry bei falschem Login, WHENEVER SQLERROR EXIT: sonst
# meldete sqlplus einen Fehler nur auf stdout und beendete mit 0.
ora_run() {  # ora_run <sql-text>
    $COMPOSE exec -T oracle sqlplus -S -L "${APP_USER}/${APP_USER_PASSWORD}@//localhost:1521/FREEPDB1" <<SQL
WHENEVER SQLERROR EXIT SQL.SQLCODE
SET HEADING OFF FEEDBACK OFF PAGESIZE 0 LINESIZE 32767 TRIMSPOOL ON
$1
EXIT
SQL
}
ora_val() { ora_run "$1" </dev/null 2>/dev/null | tr -d '[:space:]'; }
# Wie ora_val, aber mit sichtbarem Fehlerkanal.
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

# --- 1. Stacks hoch (postgres-Quelle + oracle-Ziel) ----------------
log "starting postgres + oracle (Oracle cold start takes ~2-3 min)..."
$COMPOSE up -d postgres oracle
wait_healthy postgres 120
wait_healthy oracle 600

# --- 2. Pagila in eine frische PG-Quell-DB laden -------------------
log "resetting + loading pagila source DB..."
psql_t postgres 1 -c "DROP DATABASE IF EXISTS pagila WITH (FORCE)" -c "CREATE DATABASE pagila" > /dev/null
psql_t pagila 1 < "$EXAMPLES_DIR/.cache/pagila.sql" > /dev/null || fail "pagila dump load failed"
logical_tables=$(pg_val "SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='public' AND c.relkind IN ('r','p') AND NOT c.relispartition")
log "pagila loaded ($logical_tables logical tables, partition children excluded)"

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

# --- 5. generate --target oracle + Notes gegen Baseline ------------
log "schema generate --target oracle --split pre-post --deterministic..."
$COMPOSE run --rm dmigrate schema generate --source /work/out/pagila.reverse.yaml \
    --target oracle --split pre-post --deterministic \
    --output /work/out/pagila.ora.sql > /dev/null || fail "generate failed"
for f in pagila.ora.pre-data.sql pagila.ora.post-data.sql pagila.ora.report.yaml; do
    [ -s "$OUT_DIR/$f" ] || fail "empty generate artifact: $f"
done
# KEIN `/` hinter den mit `;` abgeschlossenen Anweisungen: in sqlplus fuehrt
# `/` den Puffer ein ZWEITES Mal aus (ORA-00955 bei jedem CREATE, bei einem
# Datenskript ein doppelter INSERT). Anders als T-SQLs `GO`, das nur einen
# Batch beendet.
grep -qx "/" "$OUT_DIR/pagila.ora.pre-data.sql" \
    && fail "pre-data script carries lone '/' lines — sqlplus would execute every statement twice"
:
{ grep -oE 'code: [A-Z][0-9]+' "$OUT_DIR/pagila.ora.report.yaml" || true; } | sort | uniq -c \
    | sed 's/^ *//' > "$OUT_DIR/pagila-cross-ora.notes.txt"
[ -s "$OUT_DIR/pagila-cross-ora.notes.txt" ] || echo "0 notes" > "$OUT_DIR/pagila-cross-ora.notes.txt"
if [ ! -f "$NOTES_BASELINE" ]; then
    cp "$OUT_DIR/pagila-cross-ora.notes.txt" "$NOTES_BASELINE"
    log "BASELINE BOOTSTRAP: wrote $NOTES_BASELINE — review + commit it, then re-run."
else
    diff -u "$NOTES_BASELINE" "$OUT_DIR/pagila-cross-ora.notes.txt" > /tmp/cross-pg2ora-notes.diff 2>&1 \
        && log "generate notes == baseline" \
        || { cat /tmp/cross-pg2ora-notes.diff; fail "generate notes DEVIATE from baseline (review; if a real fix changed them, re-pin $NOTES_BASELINE)"; }
fi

# --- 6. Oracle-Ziel aufbauen (sqlplus!) + Daten transferieren -------
log "dropping any objects from a previous run in the ${APP_USER} schema..."
# Oracle hat keine "DROP DATABASE"-Entsprechung fuer ein Schema, das der
# verbundene Benutzer selbst besitzt -- das Schema wird ausgeraeumt.
# PURGE: sonst landen die Tabellen im Recycle-Bin und `BIN$...` taucht im
# naechsten Reverse auf.
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

log "applying pre-data DDL via sqlplus..."
$COMPOSE exec -T oracle sqlplus -S -L "${APP_USER}/${APP_USER_PASSWORD}@//localhost:1521/FREEPDB1" \
    < "$OUT_DIR/pagila.ora.pre-data.sql" > /tmp/cross-pg2ora-apply.log 2>&1 \
    || { tail -40 /tmp/cross-pg2ora-apply.log; fail "pre-data apply via sqlplus failed"; }
# sqlplus meldet Fehler auf stdout und beendet trotzdem mit 0, solange kein
# WHENEVER SQLERROR gesetzt ist -- das erzeugte Skript setzt es nicht. Der
# Fehlerkanal ist deshalb die Ausgabe selbst.
if grep -qE '^ORA-[0-9]+|^SP2-[0-9]+' /tmp/cross-pg2ora-apply.log; then
    grep -E '^ORA-[0-9]+|^SP2-[0-9]+' /tmp/cross-pg2ora-apply.log | sort -u | head -20
    fail "pre-data apply reported Oracle errors (see /tmp/cross-pg2ora-apply.log)"
fi
tgt_tables=$(ora_val "SELECT COUNT(*) FROM user_tables;")
[ "$tgt_tables" = "$logical_tables" ] \
    || fail "expected $logical_tables target tables after pre-data, got $tgt_tables"
log "  pre-data applied ($tgt_tables tables)"

log "data transfer pagila_pg -> pagila_ora_target (with --verify, LN-009)..."
$COMPOSE run --rm dmigrate data transfer --source pagila_pg --target pagila_ora_target --truncate --verify \
    > /tmp/cross-pg2ora-xfer.log 2>&1 || { tail -40 /tmp/cross-pg2ora-xfer.log; fail "transfer/--verify failed"; }
grep -q "Transfer complete" /tmp/cross-pg2ora-xfer.log || fail "transfer did not complete"
grep -q "Verify OK" /tmp/cross-pg2ora-xfer.log || { tail -40 /tmp/cross-pg2ora-xfer.log; fail "--verify did not pass (LN-009 divergence)"; }
xfer_excl=$(grep -c "verify excluded" /tmp/cross-pg2ora-xfer.log || true)
if [ ! -f "$EXPECTED_VERIFY_EXCLUSIONS_FILE" ]; then
    echo "$xfer_excl" > "$EXPECTED_VERIFY_EXCLUSIONS_FILE"
    log "BASELINE BOOTSTRAP: pinned $xfer_excl verify exclusion(s) — review + commit, then re-run."
else
    expected_excl=$(tr -d '[:space:]' < "$EXPECTED_VERIFY_EXCLUSIONS_FILE")
    [ "$xfer_excl" = "$expected_excl" ] \
        || fail "expected $expected_excl verify exclusion(s), got $xfer_excl (see /tmp/cross-pg2ora-xfer.log)"
fi
log "  --verify OK (byte-reconciled; $xfer_excl column(s) excluded as cross-dialect transforms)"

# --- 7. Per-Tabelle-Zeilen-Paritaet --------------------------------
log "verifying per-table row-count parity (source == target)..."
mismatch=0
compared=0
src_table_list=$(psql_t pagila 0 -tAc "SELECT c.relname FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='public' AND c.relkind IN ('r','p') AND NOT c.relispartition ORDER BY 1") \
    || fail "could not list source tables for parity"
[ -n "$src_table_list" ] || fail "source table list for parity is empty"
while IFS= read -r t; do
    [ -n "$t" ] || continue
    s=$(pg_val "SELECT count(*) FROM \"$t\"") || s="<source query failed>"
    d=$(ora_exec "SELECT COUNT(*) FROM \"$t\";") || d="<target query failed>"
    if [ "$s" != "$d" ]; then printf '[cross-pg2ora]   MISMATCH %s: src=%s dst=%s\n' "$t" "$s" "$d"; mismatch=1; fi
    compared=$((compared + 1))
done <<< "$src_table_list"
[ "$mismatch" = "0" ] || fail "per-table row-count parity violated"
[ "$compared" = "$logical_tables" ] || fail "parity compared $compared tables, expected $logical_tables (generator-swallow guard)"
log "per-table parity OK (all $compared logical tables)"

# --- 8. Typ-Konvertierungen datenbelegt ----------------------------
log "verifying critical cross-dialect type conversions..."
ora_type() { ora_val "SELECT data_type FROM user_tab_columns WHERE table_name = '$1' AND column_name = '$2';"; }

# boolean -> NUMBER(1) (customer.activebool)
[ "$(ora_type customer activebool)" = "NUMBER" ] \
    || fail "customer.activebool expected NUMBER, got $(ora_type customer activebool)"
pg_act=$(pg_val "SELECT count(*) FILTER (WHERE activebool) FROM customer")
ora_act=$(ora_val "SELECT SUM(\"activebool\") FROM \"customer\";")
[ "$pg_act" = "$ora_act" ] || fail "boolean->NUMBER mismatch: src=$pg_act dst=$ora_act"
log "  boolean->NUMBER(1) OK (true-count $pg_act)"

# text/varchar -> VARCHAR2
[ "$(ora_type customer email)" = "VARCHAR2" ] \
    || fail "customer.email expected VARCHAR2, got $(ora_type customer email)"
log "  text->VARCHAR2 OK"

# timestamptz -> TIMESTAMP WITH TIME ZONE
ptz=$(ora_type payment payment_date)
case "$ptz" in
    TIMESTAMP*WITH*TIME*ZONE) log "  timestamptz->$ptz OK" ;;
    *) fail "payment.payment_date expected a TIMESTAMP WITH TIME ZONE variant, got $ptz" ;;
esac

# enum -> VARCHAR2 + CHECK (film.rating)
rating_check=$(ora_val "SELECT COUNT(*) FROM user_constraints WHERE table_name = 'film' AND constraint_name = 'ck_film_rating';")
[ "${rating_check:-0}" = "1" ] || fail "film.rating lost its enum CHECK constraint (expected ck_film_rating)"
log "  enum->VARCHAR2 + CHECK OK"

# --- 9. Schluessel-Treue: die Werte der Quelle bleiben erhalten -----
log "verifying key fidelity (source keys preserved)..."
pg_max=$(pg_val "SELECT max(film_id) FROM film")
ora_max=$(ora_val "SELECT MAX(\"film_id\") FROM \"film\";")
[ "$pg_max" = "$ora_max" ] || fail "film_id keys not preserved: src max=$pg_max dst max=$ora_max"
log "  keys OK (max film_id $ora_max preserved)"

log "SUCCESS — Pagila PG->Oracle cross-dialect smoke passed (sqlplus-applied DDL + per-table parity + conversions)."
log "stack is up; clean up with 'make sample-db-down' or 'make sample-db-purge'."
