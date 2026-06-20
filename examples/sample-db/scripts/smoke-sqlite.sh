#!/usr/bin/env bash
# Sample-DB-Harness — Phase 2b SQLite-Round-Trip (Chinook)
# Plan: docs/planning/in-progress/sample-db-integration-harness.md
# ADR:  docs/adr/0014-sample-db-harness-fetch-and-compose.md
#
# SQLite hat KEINEN Server — die CLI arbeitet gegen eine bind-gemountete
# .db-Datei. Daher direkter `docker run` (kein docker-compose). Flow:
#   reverse chinook.db -> validate -> generate --target sqlite --split pre-post
#   -> Zielschema (sqlite3 < pre-data) -> data transfer -> post-data
#   -> Zeilen-Parität + Wert-Stichprobe (Decimal->REAL-Präzision).
#
# Same-Dialect-Round-Trip (wie Phase 1 Pagila/PG, nicht cross-dialect). Gepinnt:
#   - generate-Notes == Baseline (R201 NVARCHAR->text, W200 Decimal->REAL)
#   - Zeilen-Parität Quelle == Ziel (11 Tabellen)
#   - Decimal(10,2)->REAL ohne Datenverlust für Chinooks 2-Dezimal-Preise
#
# Voraussetzung am Host: docker, sqlite3, lokal gebautes d-migrate:dev-Image.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
EXAMPLES_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
CACHE_DIR="$EXAMPLES_DIR/.cache"
OUT_DIR="$EXAMPLES_DIR/out"
EXPECTED_DIR="$EXAMPLES_DIR/expected"
NOTES_BASELINE="$EXPECTED_DIR/chinook-sqlite.notes.txt"
SRC_DB="$CACHE_DIR/chinook.db"
TGT_DB="$OUT_DIR/chinook-target.db"

log()  { printf '[sqlite] %s\n' "$*"; }
fail() { printf '[sqlite] FAIL: %s\n' "$*" >&2; exit 1; }

command -v sqlite3 >/dev/null 2>&1 || fail "sqlite3 not found on host (needed to build the target DB)"

# d-migrate gegen das bind-gemountete examples/sample-db als Host-User
# (non-root Image, uid 10001 → sonst 'Failed to write schema').
DRUN="docker run --rm --user $(id -u):$(id -g) -v $EXAMPLES_DIR:/work -w /work d-migrate:dev"

mkdir -p "$OUT_DIR" "$EXPECTED_DIR"
"$SCRIPT_DIR/fetch-dumps.sh"
[ -s "$SRC_DB" ] || fail "chinook source DB missing after fetch"

# --- 1. reverse --include-all (SQLite-Datei) -----------------------
log "schema reverse chinook.db --include-all..."
$DRUN schema reverse --source "sqlite:///work/.cache/chinook.db" --include-all \
    --output /work/out/chinook.reverse.yaml > /dev/null || fail "reverse failed"
[ -s "$OUT_DIR/chinook.reverse.yaml" ] || fail "empty reverse.yaml"
src_tables=$(sqlite3 "$SRC_DB" "SELECT count(*) FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%';")
[ "$src_tables" = "11" ] || fail "expected 11 source tables, got $src_tables"
log "reverse OK ($src_tables tables)"

# --- 2. validate (0 Errors hart) -----------------------------------
log "schema validate (expect 0 errors)..."
val_out=$($DRUN schema validate --source /work/out/chinook.reverse.yaml 2>&1) \
    || fail "validate exited non-zero:\n$val_out"
printf '%s' "$val_out" | grep -q "Validation passed" || fail "validate did not pass:\n$val_out"
log "validate passed"

# --- 3. generate --target sqlite + Notes gegen Baseline ------------
log "schema generate --target sqlite --split pre-post --deterministic..."
$DRUN schema generate --source /work/out/chinook.reverse.yaml \
    --target sqlite --split pre-post --deterministic \
    --output /work/out/chinook.gen.sql > /dev/null || fail "generate failed"
for f in chinook.gen.pre-data.sql chinook.gen.post-data.sql chinook.gen.report.yaml; do
    [ -s "$OUT_DIR/$f" ] || fail "empty generate artifact: $f"
done
grep -oE 'code: [A-Z][0-9]+' "$OUT_DIR/chinook.gen.report.yaml" | sort | uniq -c \
    | sed 's/^ *//' > "$OUT_DIR/chinook-sqlite.notes.txt"
if [ ! -f "$NOTES_BASELINE" ]; then
    cp "$OUT_DIR/chinook-sqlite.notes.txt" "$NOTES_BASELINE"
    log "BASELINE BOOTSTRAP: wrote $NOTES_BASELINE — review + commit it, then re-run."
else
    diff -u "$NOTES_BASELINE" "$OUT_DIR/chinook-sqlite.notes.txt" > /tmp/sqlite-notes.diff 2>&1 \
        && log "generate notes == baseline" \
        || { cat /tmp/sqlite-notes.diff; fail "generate notes DEVIATE from baseline (review; if a real fix changed them, re-pin chinook-sqlite.notes.txt)"; }
fi

# --- 4. Zielschema bauen (pre-data) + Daten transferieren ----------
log "building target DB (pre-data DDL)..."
rm -f "$TGT_DB"
sqlite3 "$TGT_DB" < "$OUT_DIR/chinook.gen.pre-data.sql" || fail "pre-data apply failed"
tgt_tables=$(sqlite3 "$TGT_DB" "SELECT count(*) FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%';")
[ "$tgt_tables" = "11" ] || fail "expected 11 target tables after pre-data, got $tgt_tables"

log "data transfer chinook.db -> chinook-target.db..."
$DRUN data transfer --source "sqlite:///work/.cache/chinook.db" \
    --target "sqlite:///work/out/chinook-target.db" --truncate \
    > /tmp/sqlite-xfer.log 2>&1 || { cat /tmp/sqlite-xfer.log; fail "transfer failed"; }
grep -q "Transfer complete" /tmp/sqlite-xfer.log || fail "transfer did not complete"

log "applying post-data DDL (indexes/triggers)..."
sqlite3 "$TGT_DB" < "$OUT_DIR/chinook.gen.post-data.sql" || fail "post-data apply failed"

# --- 5. Zeilen-Parität Quelle == Ziel (alle Tabellen) --------------
log "verifying row-count parity (source == target)..."
mismatch=0
while IFS= read -r t; do
    [ -n "$t" ] || continue
    s=$(sqlite3 "$SRC_DB" "SELECT count(*) FROM \"$t\";")
    d=$(sqlite3 "$TGT_DB" "SELECT count(*) FROM \"$t\";")
    if [ "$s" != "$d" ]; then printf '[sqlite]   MISMATCH %s: src=%s dst=%s\n' "$t" "$s" "$d"; mismatch=1; fi
done < <(sqlite3 "$SRC_DB" "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name;")
[ "$mismatch" = "0" ] || fail "row-count parity violated"
log "row-count parity OK (all $src_tables tables)"

# --- 6. Decimal(10,2)->REAL ohne Datenverlust (W200, datenbelegt) --
log "verifying Decimal->REAL precision (no data loss for 2-decimal prices)..."
src_sum=$(sqlite3 "$SRC_DB" "SELECT ROUND(SUM(UnitPrice),2) FROM Track;")
tgt_sum=$(sqlite3 "$TGT_DB" "SELECT ROUND(SUM(UnitPrice),2) FROM Track;")
[ "$src_sum" = "$tgt_sum" ] || fail "Decimal->REAL precision loss: Track.UnitPrice sum src=$src_sum dst=$tgt_sum"
log "  Decimal->REAL OK (Track.UnitPrice sum $src_sum round-trips exactly)"

log "SUCCESS — Chinook SQLite round-trip smoke passed (parity + precision)."
