#!/usr/bin/env bash
# Sample-DB-Harness — Tool-Vergleich PG→PG-Durchsatz (#2, INTERNER Sanity-Check)
# Doc: docs/planning/open/tool-comparison.md  (#2-Head-to-Head)
#
# *** KEIN Audit-/Veröffentlichungs-Benchmark *** (Nicht-Ziel des TPC-Slice).
# Diagnostisch: Off-Spec-Host, ohne designierten Runner. Misst die Wandzeit, die
# verschiedene Tools brauchen, um DIESELBE TPC-H-Workload (SF, default 0.2) PG→PG zu
# bewegen, auf DEMSELBEN Host:
#   - COPY/`\copy` (file via STDOUT/STDIN)  = PostgreSQL-native Durchsatz-DECKE
#   - d-migrate `data export`→`import` CSV   = unser file-basierter Pfad (gecappter Client)
#   - pgloader (direct PG→PG)                = OSS-Migrations-Peer (gecappter Client) [opt]
#
# METHODIK-CAVEAT (ehrlich): der **Server** (postgres) ist für ALLE Tools ungecappt; nur
# der **Client** (d-migrate/pgloader) läuft unter Caps 2 CPU/4 GB. COPY hat keinen
# separaten Client (psql streamt). Der Vergleich zeigt also den **Tool-Overhead über der
# COPY-Decke**, nicht eine kontrollierte Per-Komponenten-Messung. Format = CSV für alle
# (d-migrate-JSON-Overhead bewusst neutralisiert). d-migrates Allein-Features
# (Verlustfreiheits-Hash, Resume, Cross-Dialect) sind hier NICHT Teil des Speed-Vergleichs.
#
# pgloader ist per Default AUS (WITH_PGLOADER=1 schaltet es zu) und laeuft dann BEST-EFFORT:
# als DIAGNOSTISCHER Peer kann sein SBCL-Heap bei hohem SF unter den Caps 2cpu/4g reissen
# ("Heap exhausted") — das faerbt den Kern-Vergleich (COPY-Decke vs d-migrate) NICHT rot,
# sondern wird nur laut gewarnt. Kein d-migrate-Befund.
#
# Opt-in (kein PR-Gate): `make sample-db-tool-compare`. Voraussetzung: docker, d-migrate:dev.

set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
EXAMPLES_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
COMPOSE="docker compose -f $EXAMPLES_DIR/docker-compose.yml"
OUT_DIR="$EXAMPLES_DIR/out"
TPCH="$EXAMPLES_DIR/.cache/tpch"
SF="${SF:-0.2}"
WITH_PGLOADER="${WITH_PGLOADER:-0}"            # diagnostischer Peer: Default AUS; =1 schaltet best-effort zu
PGLOADER_TIMEOUT="${PGLOADER_TIMEOUT:-300}"   # Sicherheitsnetz: pgloader nie unbegrenzt haengen lassen

log()  { printf '[cmp] %s\n' "$*"; }
fail() { printf '[cmp] FAIL: %s\n' "$*" >&2; exit 1; }
num()  { case "$1" in ''|*[!0-9]*) return 1;; *) return 0;; esac; }
rps()  { local rows="$1" ms="$2"; [ "$ms" -gt 0 ] || ms=1; echo $(( rows * 1000 / ms )); }

if [ ! -f "$EXAMPLES_DIR/.env" ]; then cp "$EXAMPLES_DIR/.env.example" "$EXAMPLES_DIR/.env"; fi
# shellcheck disable=SC1091
set -a; . "$EXAMPLES_DIR/.env"; set +a
: "${POSTGRES_USER:?POSTGRES_USER not set}"
export SAMPLE_DB_DMIGRATE_USER="$(id -u):$(id -g)"
PGURL() { echo "postgresql://${POSTGRES_USER}:${POSTGRES_PASSWORD}@postgres:5432/$1"; }
PSQL()  { $COMPOSE exec -T postgres psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$1" "${@:2}"; }
PGq()   { $COMPOSE exec -T postgres psql -tAq -U "$POSTGRES_USER" -d "$1" -c "$2" 2>/dev/null | tr -d '\r'; }

# --- 0. dataset (4a) + postgres up + source load -------------------
log "ensuring TPC-H dataset (SF=$SF) + fresh source load..."
SF="$SF" "$SCRIPT_DIR/tpch-generate.sh" > /tmp/cmp-gen.log 2>&1 || { cat /tmp/cmp-gen.log; fail "generation failed"; }
$COMPOSE up -d postgres >/dev/null 2>&1
deadline=$(($(date +%s)+120))
until [ "$(docker inspect --format '{{.State.Health.Status}}' "$($COMPOSE ps -q postgres)" 2>/dev/null)" = "healthy" ]; do
    [ "$(date +%s)" -lt "$deadline" ] || fail "postgres not healthy"; sleep 2
done
PGq postgres "DROP DATABASE IF EXISTS tpch WITH (FORCE)" >/dev/null; PGq postgres "CREATE DATABASE tpch" >/dev/null
PSQL tpch -q < "$TPCH/schema.sql" >/tmp/cmp-sch.log 2>&1 || { cat /tmp/cmp-sch.log; fail "source schema load failed"; }
tables=""
for csv in "$TPCH"/*.csv; do
    t=$(basename "$csv" .csv); tables="$tables $t"
    PSQL tpch -q -c "\copy $t FROM STDIN WITH (FORMAT csv, HEADER true)" < "$csv" >/dev/null || fail "source load $t failed"
done
total_rows=0
for t in $tables; do n=$(PGq tpch "SELECT count(*) FROM $t"); num "$n" || fail "count $t failed"; total_rows=$((total_rows+n)); done
log "source loaded: $total_rows rows across 8 tables"

mk_target() { PGq postgres "DROP DATABASE IF EXISTS $1 WITH (FORCE)" >/dev/null; PGq postgres "CREATE DATABASE $1" >/dev/null; PSQL "$1" -q < "$TPCH/schema.sql" >/dev/null || fail "target $1 schema failed"; }
parity() {  # parity <target-db>
    local tgt="$1" t s d
    for t in $tables; do
        s=$(PGq tpch "SELECT count(*) FROM $t"); d=$(PGq "$tgt" "SELECT count(*) FROM $t")
        num "$s" || fail "parity: src count $t invalid"
        [ "$s" = "$d" ] || fail "$tgt INCOMPLETE for $t: src=$s got=$d"
    done
}

# --- 1. COPY/\copy — native Durchsatz-Decke (file via STDOUT/STDIN) -
log "[copy] export (\\copy TO STDOUT, csv) ..."
rm -rf "$OUT_DIR/cmp-copy"; mkdir -p "$OUT_DIR/cmp-copy"
t0=$(date +%s%3N)
for t in $tables; do
    PSQL tpch -c "\copy (SELECT * FROM $t) TO STDOUT WITH (FORMAT csv)" > "$OUT_DIR/cmp-copy/$t.csv" || fail "[copy] export $t failed"
done
copy_exp=$(( $(date +%s%3N) - t0 ))
mk_target tpch_copy_target
log "[copy] import (\\copy FROM STDIN, csv) ..."
t0=$(date +%s%3N)
for t in $tables; do
    PSQL tpch_copy_target -c "\copy $t FROM STDIN WITH (FORMAT csv)" < "$OUT_DIR/cmp-copy/$t.csv" || fail "[copy] import $t failed"
done
copy_imp=$(( $(date +%s%3N) - t0 ))
parity tpch_copy_target
log "[copy] OK — export ${copy_exp}ms ($(rps "$total_rows" "$copy_exp") rows/s), import ${copy_imp}ms ($(rps "$total_rows" "$copy_imp") rows/s)"

# --- 2. d-migrate (gecappter Client, CSV) --------------------------
log "[d-migrate] export (data export --format csv --split-files, capped) ..."
rm -rf "$OUT_DIR/cmp-dm"
t0=$(date +%s%3N)
$COMPOSE run --rm dmigrate-capped data export --source tpch_pg_src --format csv --split-files \
    --output /work/out/cmp-dm > /tmp/cmp-dm-exp.log 2>&1 || { tail -8 /tmp/cmp-dm-exp.log; fail "[d-migrate] export failed"; }
dm_exp=$(( $(date +%s%3N) - t0 ))
mk_target tpch_dm_target
log "[d-migrate] import (data import --format csv, capped) ..."
t0=$(date +%s%3N)
$COMPOSE run --rm dmigrate-capped data import --target "$(PGURL tpch_dm_target)" --source /work/out/cmp-dm --format csv \
    > /tmp/cmp-dm-imp.log 2>&1 || { tail -8 /tmp/cmp-dm-imp.log; fail "[d-migrate] import failed"; }
dm_imp=$(( $(date +%s%3N) - t0 ))
parity tpch_dm_target
log "[d-migrate] OK — export ${dm_exp}ms ($(rps "$total_rows" "$dm_exp") rows/s), import ${dm_imp}ms ($(rps "$total_rows" "$dm_imp") rows/s)"

# --- 3. pgloader (direct PG->PG, gecappter Client) [Phase B] -------
# DIAGNOSTISCHER Peer, per Default AUS. Eingeschaltet (WITH_PGLOADER=1) laeuft er BEST-EFFORT:
# pgloader (SBCL) kann bei hohem SF unter dem 4g-Cap (ADR 0018) seinen Lisp-Heap erschoepfen
# ("Heap exhausted") und sterben — ein bekanntes Limit des Peers, KEIN d-migrate-Befund. Fehler,
# Timeout oder unvollstaendiger Load faerben den Kern-Vergleich (COPY-Decke vs d-migrate) NICHT
# rot, sondern werden laut gewarnt (die pgloader-Zeile faellt im Report weg). Das `timeout` ist
# das Sicherheitsnetz gegen ein haengendes pgloader (SBCL-LDB-Prompt); die Parity hier ist
# bewusst NICHT-fatal (das globale `parity` wuerde den ganzen Lauf killen).
pgl_total=""
if [ "$WITH_PGLOADER" = "1" ]; then
    log "[pgloader] direct PG->PG (capped, diagnostic peer, best-effort) ..."
    mk_target tpch_pgloader_target
    t0=$(date +%s%3N)
    pgl_ok=1
    # Fester Container-Name + explizites Aufraeumen: `compose run` raeumt bei einem timeout-SIGTERM
    # den One-off-Container NICHT zuverlaessig ab (er ueberlebt als Orphan und laeuft weiter). Wir
    # benennen ihn daher und entfernen ihn nach dem Lauf hart — bei Erfolg hat `--rm` ihn schon
    # weg (rm -f = No-op), bei Timeout/Kill raeumt das rm die Leiche.
    pgl_cname="sample-db-pgl-cmp"
    docker rm -f "$pgl_cname" >/dev/null 2>&1 || true
    timeout -k 10 "$PGLOADER_TIMEOUT" $COMPOSE run --rm --name "$pgl_cname" pgloader pgloader \
        --with "data only" --with "include no drop" \
        "$(PGURL tpch)" "$(PGURL tpch_pgloader_target)" > /tmp/cmp-pgl.log 2>&1 || pgl_ok=0
    docker rm -f "$pgl_cname" >/dev/null 2>&1 || true
    if [ "$pgl_ok" = "1" ]; then
        for t in $tables; do
            s=$(PGq tpch "SELECT count(*) FROM $t"); d=$(PGq tpch_pgloader_target "SELECT count(*) FROM $t")
            { num "$s" && [ "$s" = "$d" ]; } || { pgl_ok=0; break; }
        done
    fi
    if [ "$pgl_ok" = "1" ]; then
        pgl_total=$(( $(date +%s%3N) - t0 ))
        log "[pgloader] OK — direct move ${pgl_total}ms ($(rps "$total_rows" "$pgl_total") rows/s)"
    else
        printf '[cmp] WARN: pgloader-Leg uebersprungen (Fehler/Timeout/unvollstaendig — s. /tmp/cmp-pgl.log). DIAGNOSTISCHER Peer, KEIN Gate; typ. SBCL-Heap unter Caps 2cpu/4g bei SF=%s.\n' "$SF" >&2
        tail -3 /tmp/cmp-pgl.log 2>/dev/null >&2 || true
    fi
fi

# --- 4. Report -----------------------------------------------------
copy_tot=$((copy_exp+copy_imp)); dm_tot=$((dm_exp+dm_imp))
log "================ #2 Tool-Vergleich (SF=$SF, $total_rows Zeilen, DIAGNOSTISCH) ================"
log "  COPY-Decke      : export $(rps "$total_rows" "$copy_exp") + import $(rps "$total_rows" "$copy_imp") rows/s | total ${copy_tot}ms"
log "  d-migrate (CSV) : export $(rps "$total_rows" "$dm_exp") + import $(rps "$total_rows" "$dm_imp") rows/s | total ${dm_tot}ms = $(( dm_tot*100/copy_tot ))% der COPY-Zeit"
[ -n "$pgl_total" ] && log "  pgloader        : direct $(rps "$total_rows" "$pgl_total") rows/s | total ${pgl_total}ms = $(( pgl_total*100/copy_tot ))% der COPY-Zeit"
log "  Hinweis: Server ungecappt für alle; nur Client gecappt. Diagnostisch, kein Audit-Wert."
log "==========================================================================================="
log "stack up; cleanup: make sample-db-down / sample-db-purge"
