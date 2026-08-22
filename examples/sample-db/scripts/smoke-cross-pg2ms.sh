#!/usr/bin/env bash
# Sample-DB-Harness — MSSQL-Leg: Cross-Dialect-Smoke (Pagila PG → SQL Server)
# Plan: docs/planning/in-progress/mssql-dialect-scoping.md (Slice 3b)
# ADR:  docs/adr/0047-mssql-vierter-dialekt-scoping.md,
#       docs/adr/0014-sample-db-harness-fetch-and-compose.md
#
# Symmetrisch zu smoke-cross-pg2my.sh (Pagila PG→MySQL), aber mit dem Schritt,
# der SQL Server eigen ist: das erzeugte Skript wird per **sqlcmd** angewendet,
# also von genau dem Client, der Batches nur an `GO`-Zeilen trennt und per
# Default mit `QUOTED_IDENTIFIER OFF` verbindet (Slice 2a: GO-Trenner +
# SET-Options-Praeambel).
#
#   Pagila in PG laden -> reverse pagila_pg --include-all -> validate
#   -> generate --target mssql --split pre-post -> pre-data per sqlcmd anwenden
#   -> data transfer PG->MSSQL --verify -> Zeilen-Paritaet + Typ-Stichproben.
#
# Gepinnt:
#   - generate-Notes == Baseline (was T-SQL nicht 1:1 traegt: Routinen/Views
#     E053, Partitionierung E055, Volltext-/Spatial-Indizes und Kaskadenpfade
#     E057, Typ-Degradierungen W136/W137/W138)
#   - Per-Tabelle-Paritaet Quelle == Ziel; `payment` landet als EINE **plain**
#     Tabelle (E055 — SQL Server partitioniert ueber Partition Function/Scheme,
#     die das neutrale Modell nicht traegt), also ohne Kind-Duplikation
#   - Typ-Konvertierungen datenbelegt (boolean→bit, text→nvarchar,
#     timestamptz→datetimeoffset, text[]→nvarchar(max), enum→nvarchar+CHECK)
#   - IDENTITY-Treue: die Schluessel der Quelle bleiben erhalten
#     (SET IDENTITY_INSERT) und der Zaehler steht danach kollisionsfrei
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
NOTES_BASELINE="$EXPECTED_DIR/pagila-cross-ms.notes.txt"
# `--verify` schliesst genau die Spalten aus, deren Wert der Cross-Dialect-Transfer
# umformt (LN-009, familien-basiert). Die Zahl ist gepinnt wie die Notes-Baseline:
# waeren es ploetzlich alle, liefe "Verify OK" ins Leere.
EXPECTED_VERIFY_EXCLUSIONS=2

log()  { printf '[cross-pg2ms] %s\n' "$*"; }
fail() { printf '[cross-pg2ms] FAIL: %s\n' "$*" >&2; exit 1; }

# --- 0. .env + Verzeichnisse + Dump-Fetch --------------------------
mkdir -p "$OUT_DIR" "$EXPECTED_DIR"
if [ ! -f "$EXAMPLES_DIR/.env" ]; then
    cp "$EXAMPLES_DIR/.env.example" "$EXAMPLES_DIR/.env"
    log "created examples/sample-db/.env from .env.example"
else
    # Ein `.env` aus einem frueheren Lauf kennt die MSSQL-Variablen nicht (dieses
    # Leg hat sie neu eingefuehrt). Fehlende Schluessel aus .env.example ergaenzen,
    # vorhandene Werte unangetastet lassen — sonst scheitert jeder Bestandsnutzer
    # an einer nackten "not set"-Meldung.
    # Ohne abschliessenden Zeilenumbruch klebte der erste angehaengte Schluessel
    # an die letzte bestehende Zeile — beide Variablen waeren zerstoert.
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
pg_val() { $COMPOSE exec -T postgres psql -U "$POSTGRES_USER" -d pagila -tAc "$1" </dev/null 2>/dev/null | tr -d '[:space:]'; }
# sqlcmd im 2022-Image: mssql-tools18 + -C (Self-Signed-Zertifikat), -b = Exit != 0 bei Fehler.
sqlcmd() { $COMPOSE exec -T mssql /opt/mssql-tools18/bin/sqlcmd -C -S localhost -U sa -P "$MSSQL_SA_PASSWORD" -b "$@"; }
ms_val() { sqlcmd -d pagila_target -h -1 -W -Q "SET NOCOUNT ON; $1" </dev/null 2>/dev/null | tr -d '[:space:]'; }
# Wie ms_val, aber mit sichtbarem Fehlerkanal — fuer Anweisungen, deren Scheitern
# eine Aussage ist (sonst stirbt das Skript unter `set -e` ohne Meldung).
ms_exec() { sqlcmd -d pagila_target -h -1 -W -Q "SET NOCOUNT ON; $1" </dev/null | tr -d '[:space:]'; }

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

# --- 1. Stacks hoch (postgres-Quelle + mssql-Ziel) -----------------
log "starting postgres + mssql..."
$COMPOSE up -d postgres mssql
wait_healthy postgres 120
wait_healthy mssql 240

# --- 2. Pagila in eine frische PG-Quell-DB laden -------------------
log "resetting + loading pagila source DB..."
psql_t postgres 1 -c "DROP DATABASE IF EXISTS pagila WITH (FORCE)" -c "CREATE DATABASE pagila" > /dev/null
psql_t pagila 1 < "$EXAMPLES_DIR/.cache/pagila.sql" > /dev/null || fail "pagila dump load failed"
# Logische Tabellen = Basistabellen ohne Partitionskinder: der partitionierte
# `payment`-Parent zaehlt einmal, seine Kinder sind Partitionen.
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

# --- 5. generate --target mssql + Notes gegen Baseline -------------
log "schema generate --target mssql --split pre-post --deterministic..."
$COMPOSE run --rm dmigrate schema generate --source /work/out/pagila.reverse.yaml \
    --target mssql --split pre-post --deterministic \
    --output /work/out/pagila.ms.sql > /dev/null || fail "generate failed"
for f in pagila.ms.pre-data.sql pagila.ms.post-data.sql pagila.ms.report.yaml; do
    [ -s "$OUT_DIR/$f" ] || fail "empty generate artifact: $f"
done
# Slice 2a: die Skript-Darstellung traegt SET-Praeambel + GO-Batches — genau das,
# was sqlcmd unten braucht. Ohne sie scheitert ein gefilterter Index (Msg 1934).
grep -q "SET QUOTED_IDENTIFIER ON;" "$OUT_DIR/pagila.ms.pre-data.sql" \
    || fail "pre-data script lacks the SET-options preamble (Slice 2a regression)"
grep -qx "GO" "$OUT_DIR/pagila.ms.pre-data.sql" \
    || fail "pre-data script lacks GO batch separators (Slice 2a regression)"
grep -oE 'code: [A-Z][0-9]+' "$OUT_DIR/pagila.ms.report.yaml" | sort | uniq -c \
    | sed 's/^ *//' > "$OUT_DIR/pagila-cross-ms.notes.txt"
if [ ! -f "$NOTES_BASELINE" ]; then
    cp "$OUT_DIR/pagila-cross-ms.notes.txt" "$NOTES_BASELINE"
    log "BASELINE BOOTSTRAP: wrote $NOTES_BASELINE — review + commit it, then re-run."
else
    diff -u "$NOTES_BASELINE" "$OUT_DIR/pagila-cross-ms.notes.txt" > /tmp/cross-pg2ms-notes.diff 2>&1 \
        && log "generate notes == baseline" \
        || { cat /tmp/cross-pg2ms-notes.diff; fail "generate notes DEVIATE from baseline (review; if a real fix changed them, re-pin $NOTES_BASELINE)"; }
fi

# --- 6. SQL-Server-Ziel aufbauen (sqlcmd!) + Daten transferieren ----
log "resetting target DB pagila_target (SQL Server) + applying pre-data DDL via sqlcmd..."
sqlcmd -Q "IF DB_ID('pagila_target') IS NOT NULL BEGIN ALTER DATABASE pagila_target SET SINGLE_USER WITH ROLLBACK IMMEDIATE; DROP DATABASE pagila_target; END; CREATE DATABASE pagila_target;" \
    > /dev/null || fail "could not (re)create pagila_target"
# Das Skript geht ueber stdin an sqlcmd — dieselbe Anwendung, die ein Operator faehrt.
sqlcmd -d pagila_target -I < "$OUT_DIR/pagila.ms.pre-data.sql" > /tmp/cross-pg2ms-apply.log 2>&1 \
    || { tail -40 /tmp/cross-pg2ms-apply.log; fail "pre-data apply via sqlcmd failed"; }
tgt_tables=$(ms_val "SELECT COUNT(*) FROM sys.tables WHERE is_ms_shipped = 0;")
[ "$tgt_tables" = "$logical_tables" ] \
    || fail "expected $logical_tables target tables after pre-data (payment plain, E055), got $tgt_tables"
log "  pre-data applied ($tgt_tables tables)"

log "data transfer pagila_pg -> pagila_ms_target (with --verify, LN-009)..."
$COMPOSE run --rm dmigrate data transfer --source pagila_pg --target pagila_ms_target --truncate --verify \
    > /tmp/cross-pg2ms-xfer.log 2>&1 || { tail -40 /tmp/cross-pg2ms-xfer.log; fail "transfer/--verify failed"; }
grep -q "Transfer complete" /tmp/cross-pg2ms-xfer.log || fail "transfer did not complete"
grep -q "Verify OK" /tmp/cross-pg2ms-xfer.log || { tail -40 /tmp/cross-pg2ms-xfer.log; fail "--verify did not pass (LN-009 divergence)"; }
xfer_excl=$(grep -c "verify excluded" /tmp/cross-pg2ms-xfer.log || true)
[ "$xfer_excl" = "$EXPECTED_VERIFY_EXCLUSIONS" ] \
    || fail "expected $EXPECTED_VERIFY_EXCLUSIONS verify exclusion(s), got $xfer_excl (see /tmp/cross-pg2ms-xfer.log)"
log "  --verify OK (byte-reconciled; $xfer_excl column(s) excluded as cross-dialect transforms)"

# --- 7. Per-Tabelle-Zeilen-Paritaet --------------------------------
log "verifying per-table row-count parity (source == target)..."
mismatch=0
compared=0
# F1-Muster: Liste ZUERST in eine Variable — Prozess-Substitution verschluckt den
# Generator-Exit, eine 0-Iterationen-Schleife meldete sonst faelschlich "parity OK".
src_table_list=$(psql_t pagila 0 -tAc "SELECT c.relname FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='public' AND c.relkind IN ('r','p') AND NOT c.relispartition ORDER BY 1") \
    || fail "could not list source tables for parity"
[ -n "$src_table_list" ] || fail "source table list for parity is empty"
while IFS= read -r t; do
    [ -n "$t" ] || continue
    # `set -e` wuerde eine fehlgeschlagene Zaehlabfrage hier ohne jede Meldung
    # beenden; ms_exec haelt zusaetzlich den sqlcmd-Fehlerkanal offen.
    s=$(pg_val "SELECT count(*) FROM \"$t\"") || s="<source query failed>"
    d=$(ms_exec "SELECT COUNT(*) FROM [$t];") || d="<target query failed>"
    if [ "$s" != "$d" ]; then printf '[cross-pg2ms]   MISMATCH %s: src=%s dst=%s\n' "$t" "$s" "$d"; mismatch=1; fi
    compared=$((compared + 1))
done <<< "$src_table_list"
[ "$mismatch" = "0" ] || fail "per-table row-count parity violated"
[ "$compared" = "$logical_tables" ] || fail "parity compared $compared tables, expected $logical_tables (generator-swallow guard)"
log "per-table parity OK (all $compared logical tables; payment compared as a whole)"

# --- 8. Typ-Konvertierungen datenbelegt ----------------------------
log "verifying critical cross-dialect type conversions..."
ms_type() { ms_val "SELECT ty.name FROM sys.columns c JOIN sys.types ty ON ty.user_type_id = c.user_type_id WHERE c.object_id = OBJECT_ID('$1') AND c.name = '$2';"; }

# boolean -> bit (customer.activebool)
[ "$(ms_type customer activebool)" = "bit" ] || fail "customer.activebool expected bit, got $(ms_type customer activebool)"
pg_act=$(pg_val "SELECT count(*) FILTER (WHERE activebool) FROM customer")
ms_act=$(ms_val "SELECT SUM(CAST(activebool AS int)) FROM customer;")
[ "$pg_act" = "$ms_act" ] || fail "boolean->bit mismatch: src=$pg_act dst=$ms_act"
log "  boolean->bit OK (true-count $pg_act)"

# text/varchar -> nvarchar (Unicode-sicher)
[ "$(ms_type customer email)" = "nvarchar" ] || fail "customer.email expected nvarchar, got $(ms_type customer email)"
log "  text->nvarchar OK"

# timestamptz -> datetimeoffset
[ "$(ms_type payment payment_date)" = "datetimeoffset" ] \
    || fail "payment.payment_date expected datetimeoffset, got $(ms_type payment payment_date)"
log "  timestamptz->datetimeoffset OK"

# text[] -> nvarchar(max) (film.special_features), Werte als JSON-Text erhalten
[ "$(ms_type film special_features)" = "nvarchar" ] \
    || fail "film.special_features expected nvarchar, got $(ms_type film special_features)"
sf_empty=$(ms_val "SELECT COUNT(*) FROM film WHERE special_features IS NULL OR LEN(special_features) = 0;")
[ "${sf_empty:-1}" = "0" ] || fail "text[]->nvarchar: $sf_empty film rows lost their special_features"
log "  text[]->nvarchar(max) OK (all rows populated)"

# enum -> nvarchar + CHECK (film.rating)
rating_check=$(ms_val "SELECT COUNT(*) FROM sys.check_constraints WHERE parent_object_id = OBJECT_ID('film') AND name = 'ck_film_rating';")
[ "${rating_check:-0}" = "1" ] || fail "film.rating lost its enum CHECK constraint (expected ck_film_rating)"
log "  enum->nvarchar + CHECK OK"

# --- 9. IDENTITY-Treue: Schluessel erhalten, Zaehler kollisionsfrei --
log "verifying IDENTITY round-trip (keys preserved, counter reseeded)..."
pg_max=$(pg_val "SELECT max(film_id) FROM film")
ms_max=$(ms_val "SELECT MAX(film_id) FROM film;")
[ "$pg_max" = "$ms_max" ] || fail "film_id keys not preserved: src max=$pg_max dst max=$ms_max"
# `fulltext` (tsvector -> nvarchar) ist in Pagila NOT NULL und hat keinen Default;
# der Spaltenname ist in T-SQL ein Schluesselwort und braucht Klammern.
next_id=$(ms_exec "INSERT INTO film (title, language_id, rating, [fulltext]) OUTPUT INSERTED.film_id VALUES (N'DMIGRATE SMOKE', (SELECT MIN(language_id) FROM language), N'G', N'smoke');") \
    || fail "insert after transfer failed (identity/defaults regression, see sqlcmd output above)"
[ -n "$next_id" ] || fail "insert after transfer produced no identity value"
[ "$next_id" -gt "$ms_max" ] || fail "identity counter not reseeded: next=$next_id, max was $ms_max"
ms_exec "DELETE FROM film WHERE title = N'DMIGRATE SMOKE';" > /dev/null
log "  IDENTITY OK (max film_id $ms_max preserved; next server-assigned id $next_id)"

log "SUCCESS — Pagila PG->SQL Server cross-dialect smoke passed (sqlcmd-applied DDL + per-table parity + conversions + IDENTITY)."
log "stack is up; clean up with 'make sample-db-down' or 'make sample-db-purge'."
