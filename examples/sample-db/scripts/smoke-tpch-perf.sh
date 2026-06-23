#!/usr/bin/env bash
# Sample-DB-Harness — Phase 4 Volumen-Abnahme Mess-Kern (4c, Teil 1)
# Slice: docs/planning/in-progress/tpc-4c-volume-acceptance-slice.md
# ADR:   docs/adr/0018-normalized-perf-measurement-environment.md · 0017
#
# Misst die TPC-H-Volumen-Abnahme (LF 8.1/8.2/8.5) über den datei-basierten
# `data export` → `data import`-Pfad, unter Container-Caps 2 CPU/4 GB (Service
# `dmigrate-capped`, ADR 0018):
#   - VERLUSTFREIHEIT (LF 8.1/8.5) — HART + host-unabhängig: kanonischer Inhalts-
#     SHA-256 je Tabelle (spalten-namens-geordnet + zeilen-sortiert) Quelle ==
#     Re-Import. Strenger als Phase-3-Zeilen-Parität; invariant gegen Spalten-/
#     Zeilen-Reihenfolge — der ROHE Datei-Hash ist es NICHT (reverse/generate
#     vertauscht Spalten; 4c-Spike), ein korrekter Transfer fiele sonst durch.
#   - DURCHSATZ (LF 8.2) — DIAGNOSTISCH (hart nur unter PERF_GATE=true auf dem
#     designierten Runner): Export ≥ 10k Sätze/s (LN-002 → 1 Mio < 100 s),
#     Import ≥ 5k Sätze/s (LN-003 → 1 Mio < 200 s).
#   - RESUME @ ~50 % (LF 8.2): Abbruch nach ~Hälfte der Chunks + `--resume`.
#
# Diagnostisch vs. hart: absolute Zeiten gelten nur auf einer normierten Umgebung
# (ADR 0018). Ein Off-Spec-Host meldet den Durchsatz nur (kein Fehler). Der
# Kalibrier-Guard + das perf-acceptance.yml-Hart-Gate sind 4c-Teil-2.
#
# Opt-in (kein PR-Gate): `make sample-db-tpch-perf` (SF=0.2 default → ≥ 1 Mio Zeilen).
# Voraussetzung: docker, docker compose, lokales d-migrate:dev.

set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
EXAMPLES_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
COMPOSE="docker compose -f $EXAMPLES_DIR/docker-compose.yml"
OUT_DIR="$EXAMPLES_DIR/out"
EXPORT_DIR="$OUT_DIR/tpch-perf-export"
TPCH="$EXAMPLES_DIR/.cache/tpch"
SF="${SF:-0.2}"
PERF_GATE="${PERF_GATE:-false}"
EXPORT_MIN_RPS=10000   # LN-002 → 1 Mio / 100 s
IMPORT_MIN_RPS=5000    # LN-003 → 1 Mio / 200 s
MIN_ROWS=1000000       # LF 8.1 „mindestens 1 Million"

log()  { printf '[tpch-perf] %s\n' "$*"; }
fail() { printf '[tpch-perf] FAIL: %s\n' "$*" >&2; exit 1; }

if [ ! -f "$EXAMPLES_DIR/.env" ]; then cp "$EXAMPLES_DIR/.env.example" "$EXAMPLES_DIR/.env"; fi
# shellcheck disable=SC1091
set -a; . "$EXAMPLES_DIR/.env"; set +a
: "${POSTGRES_USER:?POSTGRES_USER not set}"
export SAMPLE_DB_DMIGRATE_USER="$(id -u):$(id -g)"

PGq() { $COMPOSE exec -T postgres psql -tAq -U "$POSTGRES_USER" -d "$1" -c "$2" 2>/dev/null | tr -d '\r'; }
num() { case "$1" in ''|*[!0-9]*) return 1;; *) return 0;; esac; }
# Kanonischer Inhalts-Hash: Spalten namens-geordnet (quote_ident) + Zeilen voll
# sortiert -> deterministisch + invariant gegen physische Spalten-/Zeilen-Order.
canon() {
    local db="$1" t="$2" c
    c=$(PGq "$db" "SELECT string_agg(quote_ident(column_name),',' ORDER BY column_name) FROM information_schema.columns WHERE table_schema='public' AND table_name='$t'")
    case "$c" in ''|*[!a-zA-Z0-9_,\"]*) fail "canon: bad column list for $db.$t ('$c')";; esac
    $COMPOSE exec -T postgres psql -tAq -U "$POSTGRES_USER" -d "$db" \
        -c "\copy (SELECT $c FROM $t ORDER BY $c) TO STDOUT WITH (FORMAT csv)" 2>/dev/null | sha256sum | awk '{print $1}'
}

# --- 0. dataset (4a) + postgres up ---------------------------------
log "ensuring TPC-H dataset (SF=$SF)..."
SF="$SF" "$SCRIPT_DIR/tpch-generate.sh" > /tmp/tpch-perf-gen.log 2>&1 || { cat /tmp/tpch-perf-gen.log; fail "4a generation failed"; }
$COMPOSE up -d postgres >/dev/null 2>&1
deadline=$(($(date +%s)+120))
until [ "$(docker inspect --format '{{.State.Health.Status}}' "$($COMPOSE ps -q postgres)" 2>/dev/null)" = "healthy" ]; do
    [ "$(date +%s)" -lt "$deadline" ] || fail "postgres not healthy"; sleep 2
done

# --- 1. Quell-DB laden + ≥ 1 Mio-Gate ------------------------------
log "loading tpch source..."
PGq postgres "DROP DATABASE IF EXISTS tpch WITH (FORCE)" >/dev/null; PGq postgres "CREATE DATABASE tpch" >/dev/null
$COMPOSE exec -T postgres psql -q -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d tpch < "$TPCH/schema.sql" >/tmp/tpch-perf-sch.log 2>&1 \
    || { cat /tmp/tpch-perf-sch.log; fail "schema load failed"; }
tables=""
for csv in "$TPCH"/*.csv; do
    t=$(basename "$csv" .csv); tables="$tables $t"
    $COMPOSE exec -T postgres psql -q -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d tpch \
        -c "\copy $t FROM STDIN WITH (FORMAT csv, HEADER true)" < "$csv" >/dev/null || fail "load $t failed"
done
total_rows=0
for t in $tables; do n=$(PGq tpch "SELECT count(*) FROM $t"); num "$n" || fail "count $t failed ('$n')"; total_rows=$((total_rows+n)); done
log "loaded ($total_rows rows total)"
[ "$total_rows" -ge "$MIN_ROWS" ] \
    || fail "dataset has $total_rows rows (< $MIN_ROWS = LF 8.1 1 Mio); raise SF (default 0.2)"

# --- 2. getimter EXPORT (gecappt) ----------------------------------
log "timed EXPORT under caps 2cpu/4g..."
rm -rf "$EXPORT_DIR"
t0=$(date +%s)
$COMPOSE run --rm dmigrate-capped data export --source tpch_pg_src --format json --split-files \
    --output /work/out/tpch-perf-export > /tmp/tpch-perf-exp.log 2>&1 || { tail -8 /tmp/tpch-perf-exp.log; fail "export failed"; }
t1=$(date +%s); exp_dt=$((t1-t0)); [ "$exp_dt" -gt 0 ] || exp_dt=1
exp_rps=$((total_rows/exp_dt))
log "export: $total_rows rows in ${exp_dt}s -> ${exp_rps} rows/s"

# --- 3. frische Ziel-DB + getimter IMPORT (gecappt) ----------------
log "fresh target + timed IMPORT under caps 2cpu/4g..."
PGq postgres "DROP DATABASE IF EXISTS tpch_perf_target WITH (FORCE)" >/dev/null; PGq postgres "CREATE DATABASE tpch_perf_target" >/dev/null
$COMPOSE exec -T postgres psql -q -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d tpch_perf_target < "$TPCH/schema.sql" >/dev/null \
    || fail "target schema load failed"
t0=$(date +%s)
$COMPOSE run --rm dmigrate-capped data import --target tpch_perf_target --source /work/out/tpch-perf-export --format json \
    > /tmp/tpch-perf-imp.log 2>&1 || { tail -8 /tmp/tpch-perf-imp.log; fail "import failed"; }
t1=$(date +%s); imp_dt=$((t1-t0)); [ "$imp_dt" -gt 0 ] || imp_dt=1
imp_rps=$((total_rows/imp_dt))
log "import: $total_rows rows in ${imp_dt}s -> ${imp_rps} rows/s"

# --- 4. VERLUSTFREIHEIT (HART): kanonischer SHA-256 je Tabelle -----
log "verifying losslessness (canonical content SHA-256, source == re-import)..."
for t in $tables; do
    hs=$(canon tpch "$t"); ht=$(canon tpch_perf_target "$t")
    case "$hs" in ''|*[!0-9a-f]*) fail "canon hash src $t invalid ('$hs') — query failed?";; esac
    [ "$hs" = "$ht" ] || fail "LOSSLESSNESS VIOLATED for $t: src=$hs target=$ht"
done
log "losslessness OK — all 8 tables canonical SHA-256 identical (LF 8.1/8.5, host-independent HARD)"

# --- 5. Durchsatz-Budgets: diagnostisch, hart nur unter PERF_GATE --
gate() {  # gate <name> <actual_rps> <min_rps>
    local name="$1" act="$2" min="$3"
    if [ "$act" -ge "$min" ]; then log "throughput $name OK (${act} >= ${min} rows/s)"; return 0; fi
    [ "$PERF_GATE" = "true" ] && fail "throughput $name ${act} < ${min} rows/s (PERF_GATE hard gate)"
    log "throughput $name DIAGNOSTIC: ${act} < ${min} rows/s — below budget, but no PERF_GATE / Off-Spec-Host -> not failed (ADR 0018)"
}
gate export "$exp_rps" "$EXPORT_MIN_RPS"
gate import "$imp_rps" "$IMPORT_MIN_RPS"

# --- 6. RESUME @ ~50 % (LF 8.2) ------------------------------------
# Abbruch nach ~der Hälfte der erwarteten Chunks (chunk-count-basiert, NICHT
# zeitbasiert), dann `--resume`. Phase 3 bricht beim ERSTEN Checkpoint ab (< 50 %);
# 4c trifft gezielt ~50 % über die im Checkpoint protokollierten chunksProcessed.
log "resume@50% — abort export at ~half the chunks, then --resume to completion..."
RCHUNK=1000   # feine Resume-Granularität. Hinweis: der TATSÄCHLICHE Abbruchpunkt hängt
              # an der Checkpoint-Flush-Latenz, nicht an RCHUNK — auf einem schnellen Host
              # erscheint der erste resumebare Checkpoint erst > 50%. Wir berichten den
              # echten %-Wert und belegen „mid-stream" per Band [25,90] statt „~50%" zu behaupten.
RUN_NAME="tpch-perf-resume-export"
REXPORT_H="$OUT_DIR/tpch-resume-export"; RCKPT_H="$OUT_DIR/tpch-resume-ckpt"
# Sicherheitsnetz: den benannten Resume-Container bei JEDEM Exit aufräumen (auch bei
# fail/SIGINT zwischen Start und regulärem rm) — sonst verwaist er.
trap 'docker rm -f "$RUN_NAME" >/dev/null 2>&1 || true' EXIT
rm -rf "$REXPORT_H" "$RCKPT_H"; docker rm -f "$RUN_NAME" >/dev/null 2>&1 || true
total_chunks=0
for t in $tables; do n=$(PGq tpch "SELECT count(*) FROM $t"); total_chunks=$(( total_chunks + (n + RCHUNK - 1)/RCHUNK )); done
half=$(( total_chunks / 2 )); [ "$half" -ge 1 ] || half=1
log "expected ~$total_chunks chunks (chunk=$RCHUNK); abort at >= $half (~50%)"
# Hinweis (set -euo pipefail!): solange noch KEIN Checkpoint existiert, matcht grep
# nichts → unter pipefail exit≠0. Ohne `|| true` würde die standalone-Zuweisung
# `cur=$(sum_chunks)` per set -e das Skript stumm killen. awk gibt immer >= "0" aus.
sum_chunks() { grep -hoE 'chunksProcessed: [0-9]+' "$RCKPT_H"/*.checkpoint.yaml 2>/dev/null | grep -oE '[0-9]+' | awk '{s+=$1} END{print s+0}' || true; }

$COMPOSE run --rm --name "$RUN_NAME" -T dmigrate-capped data export \
    --source tpch_pg_src --format json --split-files \
    --output /work/out/tpch-resume-export --chunk-size "$RCHUNK" \
    --checkpoint-dir /work/out/tpch-resume-ckpt > /tmp/tpch-resume-1.log 2>&1 &
RUN_PID=$!
deadline=$(( $(date +%s) + 300 )); killed=0
while [ "$(date +%s)" -lt "$deadline" ]; do
    kill -0 "$RUN_PID" 2>/dev/null || { log "export finished before ~50% abort (host too fast for chunk=$RCHUNK)"; break; }
    cur=$(sum_chunks)
    if [ "$cur" -ge "$half" ]; then
        log "abort threshold (~50%) reached at $cur/$total_chunks chunks — interrupting export"
        docker kill "$RUN_NAME" >/dev/null 2>&1 || true; killed=1; break
    fi
    sleep 0.2
done
wait "$RUN_PID" 2>/dev/null || true
docker rm -f "$RUN_NAME" >/dev/null 2>&1 || true
[ "$killed" = "1" ] || fail "resume@50%: export not interrupted mid-stream (too fast) — lower RCHUNK or raise SF"

ck=$(ls "$RCKPT_H"/*.checkpoint.yaml 2>/dev/null | head -1 || true)
[ -n "$ck" ] || { cat /tmp/tpch-resume-1.log; fail "resume@50%: no resumable checkpoint after abort"; }
OPID=$(basename "$ck" .checkpoint.yaml)
abort_sum=$(sum_chunks); abort_pct=$(( abort_sum * 100 / total_chunks ))
log "aborted mid-stream at operationId=$OPID, ~${abort_pct}% of chunks ($abort_sum/$total_chunks)"
# Beleg, dass der Abbruch wirklich „bei ~50 %" (mid-stream) lag — nicht degeneriert
# am Anfang (< erster Checkpoint) oder quasi-fertig.
{ [ "$abort_pct" -ge 25 ] && [ "$abort_pct" -le 90 ]; } \
    || fail "resume abort at ${abort_pct}% is not mid-stream (~50% target) — tune RCHUNK/SF"

$COMPOSE run --rm --name "$RUN_NAME" -T dmigrate-capped data export \
    --source tpch_pg_src --format json --split-files \
    --output /work/out/tpch-resume-export --chunk-size "$RCHUNK" \
    --checkpoint-dir /work/out/tpch-resume-ckpt --resume "$OPID" > /tmp/tpch-resume-2.log 2>&1 \
    || { cat /tmp/tpch-resume-2.log; fail "resume export failed"; }
for t in $tables; do [ -s "$REXPORT_H/$t.json" ] || fail "resume: missing/empty export file $t.json"; done

# Vollständigkeit + Verlustfreiheit nach Resume: importieren + KANONISCHER HASH ==
# Quelle. Stärker als Zeilen-Parität — fängt auch Wert-Korruption/Duplikate bei
# GLEICHER Zeilenzahl, was genau das Resume-Risiko ist (re-export ab Checkpoint).
log "verifying resumed export is COMPLETE + lossless (import + canonical SHA-256 vs source)..."
PGq postgres "DROP DATABASE IF EXISTS tpch_perf_target WITH (FORCE)" >/dev/null; PGq postgres "CREATE DATABASE tpch_perf_target" >/dev/null
$COMPOSE exec -T postgres psql -q -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d tpch_perf_target < "$TPCH/schema.sql" >/dev/null || fail "resume target schema failed"
$COMPOSE run --rm dmigrate data import --target tpch_perf_target --source /work/out/tpch-resume-export --format json > /tmp/tpch-resume-imp.log 2>&1 \
    || { tail -8 /tmp/tpch-resume-imp.log; fail "resume import failed"; }
for t in $tables; do
    hs=$(canon tpch "$t"); ht=$(canon tpch_perf_target "$t")
    case "$hs" in ''|*[!0-9a-f]*) fail "resume: src hash $t invalid ('$hs') — query failed?";; esac
    [ "$hs" = "$ht" ] || fail "resume LOSSY/INCOMPLETE for $t: src=$hs resumed=$ht"
done
log "resume OK — mid-stream abort at ~${abort_pct}% (LF 8.2 target ~50%, actual point is host-dependent via checkpoint-flush latency), --resume produced a COMPLETE + lossless export (all 8 tables canonical SHA-256 identical to source)"

log "SUCCESS (Mess-Kern) — losslessness HARD + throughput (diagnostic, under caps 2cpu/4g) + resume-after-mid-stream-abort verified."
log "4c-Teil-2 (designierter Runner): calibration-guard + reference-median + perf-acceptance.yml hard-gate."
log "stack is up; clean up with 'make sample-db-down' / 'make sample-db-purge'."
