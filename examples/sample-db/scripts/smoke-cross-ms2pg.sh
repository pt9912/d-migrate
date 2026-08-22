#!/usr/bin/env bash
# Sample-DB-Harness — MSSQL-Leg, Gegenrichtung: Pagila SQL Server → PostgreSQL
# Plan: docs/planning/in-progress/mssql-dialect-scoping.md (Slice 4)
# ADR:  docs/adr/0047-mssql-vierter-dialekt-scoping.md,
#       docs/adr/0014-sample-db-harness-fetch-and-compose.md
#
# smoke-cross-pg2ms.sh (Slice 3b) faehrt SQL Server als ZIEL. Dieses Leg faehrt
# ihn als QUELLE — der Pfad, den `schema reverse` + `data export` gegen ein
# gewachsenes T-SQL-Schema nehmen, und der einzige, der die Rueckrichtung der
# Typtabelle belegt (bit→boolean, nvarchar→text, datetimeoffset→timestamptz,
# IDENTITY→identifier).
#
#   Hop 0 (Saat, bewaehrte pg2ms-Mechanik): Pagila in PG laden -> reverse ->
#     generate --target mssql -> pre-data per sqlcmd -> transfer PG->MSSQL.
#   Hop 1 (das Leg unter Test): reverse pagila_ms_source --include-all ->
#     validate -> generate --target postgresql --split pre-post -> pre-data
#     per psql -> transfer MSSQL->PG --verify -> Paritaet + Typ-Stichproben.
#
# Gepinnt:
#   - generate-Notes == Baseline (was PG aus einem T-SQL-Modell nicht 1:1 traegt)
#   - DREIFACHE Zeilen-Paritaet: PG-Original == SQL Server == PG-Rueckziel.
#     Das ist der Gewinn gegenueber einem reinen Einweg-Leg — der Vergleich
#     laeuft gegen die Original-Pagila, nicht gegen sich selbst.
#   - Rueckwaerts-Typkonvertierungen datenbelegt
#   - der partitionierte `payment` bleibt ueber beide Hops EINE plain Tabelle
#     (E055 in Hop 0), zaehlt also auch im PG-Rueckziel als eine
#
# Voraussetzung am Host: docker, docker compose, lokal gebautes d-migrate:dev-Image.
# Das SQL-Server-Image laeuft nur mit akzeptierter Microsoft-EULA (ACCEPT_EULA=Y,
# siehe docs/user/quality.md) und braucht ~2 GB RAM.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
EXAMPLES_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
COMPOSE_FILE="$EXAMPLES_DIR/docker-compose.yml"
COMPOSE="docker compose -f $COMPOSE_FILE"
OUT_DIR="$EXAMPLES_DIR/out"
EXPECTED_DIR="$EXAMPLES_DIR/expected"
NOTES_BASELINE="$EXPECTED_DIR/pagila-cross-ms2pg.notes.txt"
# Siehe smoke-cross-pg2ms.sh: `--verify` schliesst genau die Spalten aus, deren
# Wert der Cross-Dialect-Transfer umformt. Gepinnt wie die Notes-Baseline, damit
# ein "Verify OK" ueber einem leergeraeumten Vergleich auffaellt.
EXPECTED_VERIFY_EXCLUSIONS=0

log()  { printf '[cross-ms2pg] %s\n' "$*"; }
fail() { printf '[cross-ms2pg] FAIL: %s\n' "$*" >&2; exit 1; }

# --- 0. .env + Verzeichnisse + Dump-Fetch --------------------------
mkdir -p "$OUT_DIR" "$EXPECTED_DIR"
if [ ! -f "$EXAMPLES_DIR/.env" ]; then
    cp "$EXAMPLES_DIR/.env.example" "$EXAMPLES_DIR/.env"
    log "created examples/sample-db/.env from .env.example"
else
    # Fehlende Schluessel aus .env.example ergaenzen, vorhandene Werte
    # unangetastet lassen — sonst scheitert jeder Bestandsnutzer an einer
    # nackten "not set"-Meldung. Ohne abschliessenden Zeilenumbruch klebte der
    # erste angehaengte Schluessel an die letzte bestehende Zeile.
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
: "${MSSQL_SA_PASSWORD:?MSSQL_SA_PASSWORD not set (add it to examples/sample-db/.env)}"

export SAMPLE_DB_DMIGRATE_USER="$(id -u):$(id -g)"

"$SCRIPT_DIR/fetch-dumps.sh"

psql_t() { $COMPOSE exec -T postgres psql -v ON_ERROR_STOP="$2" -U "$POSTGRES_USER" -d "$1" "${@:3}"; }
# `</dev/null`: diese Value-Helfer laufen in `while read … done <<< "$list"`-Schleifen;
# `docker compose exec` schluckt sonst das Here-String (False-Green nach der 1. Iteration).
pg_val()  { $COMPOSE exec -T postgres psql -U "$POSTGRES_USER" -d pagila -tAc "$1" </dev/null 2>/dev/null | tr -d '[:space:]'; }
back_val() { $COMPOSE exec -T postgres psql -U "$POSTGRES_USER" -d pagila_back -tAc "$1" </dev/null 2>/dev/null | tr -d '[:space:]'; }
# Wie back_val, aber nur getrimmt statt whitespace-frei: PG-Typnamen tragen
# Leerzeichen ("timestamp with time zone"), die back_val wegwerfen wuerde.
back_text() { $COMPOSE exec -T postgres psql -U "$POSTGRES_USER" -d pagila_back -tAc "$1" </dev/null 2>/dev/null | tr -d '\r\n' | sed 's/^ *//; s/ *$//'; }
# sqlcmd im 2022-Image: mssql-tools18 + -C (Self-Signed-Zertifikat), -b = Exit != 0 bei Fehler.
sqlcmd() { $COMPOSE exec -T mssql /opt/mssql-tools18/bin/sqlcmd -C -S localhost -U sa -P "$MSSQL_SA_PASSWORD" -b "$@"; }
ms_val()  { sqlcmd -d pagila_ms_src -h -1 -W -Q "SET NOCOUNT ON; $1" </dev/null 2>/dev/null | tr -d '[:space:]'; }
# Wie ms_val, aber mit sichtbarem Fehlerkanal — fuer Anweisungen, deren Scheitern
# eine Aussage ist (sonst stirbt das Skript unter `set -e` ohne Meldung).
ms_exec() { sqlcmd -d pagila_ms_src -h -1 -W -Q "SET NOCOUNT ON; $1" </dev/null | tr -d '[:space:]'; }

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

# ══ HOP 0 — SQL-Server-Quelle saeen (Mechanik aus smoke-cross-pg2ms.sh) ══
log "starting postgres + mssql..."
$COMPOSE up -d postgres mssql
wait_healthy postgres 120
wait_healthy mssql 240

log "hop 0: resetting + loading pagila reference DB (PostgreSQL)..."
psql_t postgres 1 -c "DROP DATABASE IF EXISTS pagila WITH (FORCE)" -c "CREATE DATABASE pagila" > /dev/null
psql_t pagila 1 < "$EXAMPLES_DIR/.cache/pagila.sql" > /dev/null || fail "pagila dump load failed"
# Logische Tabellen = Basistabellen ohne Partitionskinder.
logical_tables=$(pg_val "SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='public' AND c.relkind IN ('r','p') AND NOT c.relispartition")
log "  pagila loaded ($logical_tables logical tables)"

log "hop 0: reverse PG + generate --target mssql..."
$COMPOSE run --rm dmigrate schema reverse --source pagila_pg --include-all \
    --output /work/out/ms2pg.hop0.reverse.yaml > /dev/null || fail "hop 0 reverse failed"
$COMPOSE run --rm dmigrate schema generate --source /work/out/ms2pg.hop0.reverse.yaml \
    --target mssql --split pre-post --deterministic \
    --output /work/out/ms2pg.hop0.ms.sql > /dev/null || fail "hop 0 generate failed"

log "hop 0: (re)creating pagila_ms_src + applying pre-data via sqlcmd..."
sqlcmd -Q "IF DB_ID('pagila_ms_src') IS NOT NULL BEGIN ALTER DATABASE pagila_ms_src SET SINGLE_USER WITH ROLLBACK IMMEDIATE; DROP DATABASE pagila_ms_src; END; CREATE DATABASE pagila_ms_src;" \
    > /dev/null || fail "could not (re)create pagila_ms_src"
sqlcmd -d pagila_ms_src -I < "$OUT_DIR/ms2pg.hop0.ms.pre-data.sql" > /tmp/cross-ms2pg-hop0.log 2>&1 \
    || { tail -40 /tmp/cross-ms2pg-hop0.log; fail "hop 0 pre-data apply failed"; }

log "hop 0: transfer pagila_pg -> pagila_ms_source..."
$COMPOSE run --rm dmigrate data transfer --source pagila_pg --target pagila_ms_source --truncate \
    > /tmp/cross-ms2pg-hop0-xfer.log 2>&1 \
    || { tail -40 /tmp/cross-ms2pg-hop0-xfer.log; fail "hop 0 transfer failed"; }
ms_tables=$(ms_val "SELECT COUNT(*) FROM sys.tables WHERE is_ms_shipped = 0;")
[ "$ms_tables" = "$logical_tables" ] \
    || fail "hop 0 seeded $ms_tables tables, expected $logical_tables"
log "  SQL Server source ready ($ms_tables tables)"

# ══ HOP 1 — das Leg unter Test: SQL Server als QUELLE ══
log "schema reverse pagila_ms_source --include-all..."
$COMPOSE run --rm dmigrate schema reverse --source pagila_ms_source --include-all \
    --output /work/out/pagila.ms.reverse.yaml > /dev/null || fail "reverse from SQL Server failed"
[ -s "$OUT_DIR/pagila.ms.reverse.yaml" ] || fail "empty reverse.yaml"

log "schema validate (expect 0 errors)..."
val_out=$($COMPOSE run --rm dmigrate schema validate --source /work/out/pagila.ms.reverse.yaml 2>&1) \
    || fail "validate exited non-zero:\n$val_out"
printf '%s' "$val_out" | grep -q "Validation passed" || fail "validate did not pass:\n$val_out"
log "validate passed"

log "schema generate --target postgresql --split pre-post --deterministic..."
$COMPOSE run --rm dmigrate schema generate --source /work/out/pagila.ms.reverse.yaml \
    --target postgresql --split pre-post --deterministic \
    --output /work/out/pagila.back.sql > /dev/null || fail "generate --target postgresql failed"
for f in pagila.back.pre-data.sql pagila.back.post-data.sql pagila.back.report.yaml; do
    [ -s "$OUT_DIR/$f" ] || fail "empty generate artifact: $f"
done
# Gegenprobe zu Slice 2a: die GO-Batches sind MSSQL-Darstellung, ein
# PostgreSQL-Skript darf sie nicht tragen.
# `if`, nicht `cmd && fail`: unter `set -e` beendet der Nicht-Treffer (Exit 1)
# einer `&&`-Liste das ganze Skript — der Gutfall waere der Fehlerfall.
if grep -qx "GO" "$OUT_DIR/pagila.back.pre-data.sql"; then
    fail "PostgreSQL script carries GO batch separators (dialect leak)"
fi
# `|| true`: ein notes-FREIER Report ist ein gueltiges Ergebnis (und hier der
# Normalfall) — ohne das beendet der grep-Exit 1 unter `set -o pipefail` das
# Skript ohne Meldung. Eine leere Datei faellt beim Baseline-Diff auf.
{ grep -oE 'code: [A-Z][0-9]+' "$OUT_DIR/pagila.back.report.yaml" || true; } | sort | uniq -c \
    | sed 's/^ *//' > "$OUT_DIR/pagila-cross-ms2pg.notes.txt"
# Eine LEERE Notes-Datei ist ein gueltiges Ergebnis, sieht aber aus wie
# "nichts erfasst". Der Platzhalter macht die gepinnte Baseline lesbar und
# faellt auf, wenn die Erfassung selbst kaputtgeht.
[ -s "$OUT_DIR/pagila-cross-ms2pg.notes.txt" ] || echo "0 notes" > "$OUT_DIR/pagila-cross-ms2pg.notes.txt"
if [ ! -f "$NOTES_BASELINE" ]; then
    cp "$OUT_DIR/pagila-cross-ms2pg.notes.txt" "$NOTES_BASELINE"
    log "BASELINE BOOTSTRAP: wrote $NOTES_BASELINE — review + commit it, then re-run."
else
    diff -u "$NOTES_BASELINE" "$OUT_DIR/pagila-cross-ms2pg.notes.txt" > /tmp/cross-ms2pg-notes.diff 2>&1 \
        && log "generate notes == baseline" \
        || { cat /tmp/cross-ms2pg-notes.diff; fail "generate notes DEVIATE from baseline (review; if a real fix changed them, re-pin $NOTES_BASELINE)"; }
fi

log "resetting target DB pagila_back (PostgreSQL) + applying pre-data DDL via psql..."
psql_t postgres 1 -c "DROP DATABASE IF EXISTS pagila_back WITH (FORCE)" -c "CREATE DATABASE pagila_back" > /dev/null
psql_t pagila_back 1 < "$OUT_DIR/pagila.back.pre-data.sql" > /tmp/cross-ms2pg-apply.log 2>&1 \
    || { tail -40 /tmp/cross-ms2pg-apply.log; fail "pre-data apply via psql failed"; }
back_tables=$(back_val "SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='public' AND c.relkind IN ('r','p') AND NOT c.relispartition")
[ "$back_tables" = "$logical_tables" ] \
    || fail "expected $logical_tables tables in pagila_back after pre-data, got $back_tables"
log "  pre-data applied ($back_tables tables)"

log "data transfer pagila_ms_source -> pagila_pg_back (with --verify, LN-009)..."
$COMPOSE run --rm dmigrate data transfer --source pagila_ms_source --target pagila_pg_back --truncate --verify \
    > /tmp/cross-ms2pg-xfer.log 2>&1 || { tail -40 /tmp/cross-ms2pg-xfer.log; fail "transfer/--verify failed"; }
grep -q "Transfer complete" /tmp/cross-ms2pg-xfer.log || fail "transfer did not complete"
grep -q "Verify OK" /tmp/cross-ms2pg-xfer.log || { tail -40 /tmp/cross-ms2pg-xfer.log; fail "--verify did not pass (LN-009 divergence)"; }
xfer_excl=$(grep -c "verify excluded" /tmp/cross-ms2pg-xfer.log || true)
[ "$xfer_excl" = "$EXPECTED_VERIFY_EXCLUSIONS" ] \
    || fail "expected $EXPECTED_VERIFY_EXCLUSIONS verify exclusion(s), got $xfer_excl (see /tmp/cross-ms2pg-xfer.log)"
log "  --verify OK (byte-reconciled; $xfer_excl column(s) excluded as cross-dialect transforms)"

# --- Dreifach-Paritaet: Original == SQL Server == Rueckziel --------
log "verifying three-way row-count parity (pagila == SQL Server == pagila_back)..."
mismatch=0
compared=0
src_table_list=$(psql_t pagila 0 -tAc "SELECT c.relname FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='public' AND c.relkind IN ('r','p') AND NOT c.relispartition ORDER BY 1") \
    || fail "could not list reference tables for parity"
[ -n "$src_table_list" ] || fail "reference table list for parity is empty"
while IFS= read -r t; do
    [ -n "$t" ] || continue
    o=$(pg_val "SELECT count(*) FROM \"$t\"")        || o="<reference query failed>"
    m=$(ms_exec "SELECT COUNT(*) FROM [$t];")        || m="<mssql query failed>"
    b=$(back_val "SELECT count(*) FROM \"$t\"")      || b="<back query failed>"
    if [ "$o" != "$m" ] || [ "$o" != "$b" ]; then
        printf '[cross-ms2pg]   MISMATCH %s: pagila=%s mssql=%s back=%s\n' "$t" "$o" "$m" "$b"
        mismatch=1
    fi
    compared=$((compared + 1))
done <<< "$src_table_list"
[ "$mismatch" = "0" ] || fail "three-way row-count parity violated"
[ "$compared" = "$logical_tables" ] || fail "parity compared $compared tables, expected $logical_tables (generator-swallow guard)"
log "three-way parity OK (all $compared logical tables)"

# --- Rueckwaerts-Typkonvertierungen datenbelegt --------------------
log "verifying reverse-direction type conversions (T-SQL -> PostgreSQL)..."
back_type() { back_text "SELECT data_type FROM information_schema.columns WHERE table_name='$1' AND column_name='$2'"; }

# bit -> boolean (customer.activebool), Werte erhalten
[ "$(back_type customer activebool)" = "boolean" ] \
    || fail "customer.activebool expected boolean, got $(back_type customer activebool)"
o_act=$(pg_val "SELECT count(*) FILTER (WHERE activebool) FROM customer")
b_act=$(back_val "SELECT count(*) FILTER (WHERE activebool) FROM customer")
[ "$o_act" = "$b_act" ] || fail "bit->boolean mismatch: pagila=$o_act back=$b_act"
log "  bit->boolean OK (true-count $o_act)"

# nvarchar -> character varying / text
case "$(back_type customer email)" in
    "character varying"|"text") log "  nvarchar->$(back_type customer email) OK" ;;
    *) fail "customer.email expected character varying or text, got $(back_type customer email)" ;;
esac

# datetimeoffset -> timestamp with time zone
[ "$(back_type payment payment_date)" = "timestamp with time zone" ] \
    || fail "payment.payment_date expected timestamp with time zone, got $(back_type payment payment_date)"
log "  datetimeoffset->timestamptz OK"

# IDENTITY -> identifier: Schluessel erhalten, Zaehler kollisionsfrei
o_max=$(pg_val "SELECT max(film_id) FROM film")
b_max=$(back_val "SELECT max(film_id) FROM film")
[ "$o_max" = "$b_max" ] || fail "film_id keys not preserved: pagila max=$o_max back max=$b_max"
log "  IDENTITY->identifier OK (max film_id $b_max preserved)"

# Der Enum-CHECK aus Hop 0 ueberlebt als CHECK im PG-Rueckziel
# (Typ-Seite bleibt text — die Enum-Rekonstruktion ist offen, siehe
# docs/planning/open/enum-inline-check-fidelity.md).
back_checks=$(back_val "SELECT count(*) FROM information_schema.table_constraints WHERE table_name='film' AND constraint_type='CHECK' AND constraint_name LIKE 'ck_film_rating%'")
[ "${back_checks:-0}" -ge 1 ] || fail "film.rating lost its CHECK constraint on the way back"
log "  enum CHECK survived the round trip"

log "SUCCESS — Pagila SQL Server->PostgreSQL cross-dialect smoke passed (three-way parity + reverse-direction conversions)."
log "stack is up; clean up with 'make sample-db-down' or 'make sample-db-purge'."
