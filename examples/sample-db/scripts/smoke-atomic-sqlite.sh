#!/usr/bin/env bash
# LN-013 — SQLite `data transfer --atomic` Smoke (Clean-Load-Rollback).
#
# Beweist "alle Tabellen oder keine": ein Fehler in Tabelle 2 (CHECK-Verletzung)
# rollt auch die bereits committete Tabelle 1 zurück (Kompensations-Truncate).
#   1. Kontrast: OHNE --atomic bleibt t1 als Teil-Import stehen (Ist-Verhalten).
#   2. --atomic: bei Fehler sind ALLE Zieltabellen leer, Exit 5.
#   3. Preflight: --atomic ohne --truncate → Exit 2.
#
# Voraussetzung: sqlite3 am Host + lokal gebautes d-migrate:dev-Image.
# ADR: docs/adr/0031-atomic-clean-load-rollback.md

set -euo pipefail

IMG="${DMIGRATE_IMAGE:-d-migrate:dev}"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

log()  { printf '[atomic-sqlite] %s\n' "$*"; }
fail() { printf '[atomic-sqlite] FAIL: %s\n' "$*" >&2; exit 1; }

# Ziel-t2 hat CHECK(n >= 0); die Quelle liefert n=-1 → Insert in t2 scheitert.
SRC="CREATE TABLE t1 (id INTEGER PRIMARY KEY, v TEXT);
     INSERT INTO t1 VALUES (1,'a'),(2,'b');
     CREATE TABLE t2 (id INTEGER PRIMARY KEY, n INTEGER);
     INSERT INTO t2 VALUES (1,5),(2,-1);
     CREATE TABLE t3 (id INTEGER PRIMARY KEY, v TEXT);
     INSERT INTO t3 VALUES (1,'x');"
TGT="CREATE TABLE t1 (id INTEGER PRIMARY KEY, v TEXT);
     CREATE TABLE t2 (id INTEGER PRIMARY KEY, n INTEGER CHECK(n >= 0));
     CREATE TABLE t3 (id INTEGER PRIMARY KEY, v TEXT);"

build_dbs() {
    rm -f "$WORK/src.db" "$WORK/tgt.db"
    sqlite3 "$WORK/src.db" "$SRC"
    sqlite3 "$WORK/tgt.db" "$TGT"
}

dmigrate() { docker run --rm --user "$(id -u):$(id -g)" -v "$WORK:/w" -w /w "$IMG" "$@"; }
tgt_count() { sqlite3 "$WORK/tgt.db" "SELECT count(*) FROM $1;"; }

# --- 1. Kontrast: OHNE --atomic bleibt der Teil-Import (t1) stehen -----------
build_dbs
log "transfer WITHOUT --atomic (expect partial import: t1 committed, then t2 fails)..."
set +e
dmigrate data transfer --source sqlite:///w/src.db --target sqlite:///w/tgt.db --truncate \
    > "$WORK/plain.log" 2>&1
code=$?
set -e
[ "$code" = "5" ] || { cat "$WORK/plain.log"; fail "plain transfer expected Exit 5 (t2 CHECK), got $code"; }
[ "$(tgt_count t1)" = "2" ] || fail "without --atomic, t1 should retain its 2 partial rows (got $(tgt_count t1))"
log "  OK (partial import confirmed: t1 has 2 rows after t2 failure)"

# --- 2. --atomic: Fehler → ALLE Tabellen leer (Clean-Load-Rollback) ----------
build_dbs
log "transfer WITH --atomic --truncate (expect Exit 5 + all tables rolled back to empty)..."
set +e
dmigrate data transfer --source sqlite:///w/src.db --target sqlite:///w/tgt.db --truncate --atomic \
    > "$WORK/atomic.log" 2>&1
code=$?
set -e
[ "$code" = "5" ] || { cat "$WORK/atomic.log"; fail "atomic transfer expected Exit 5, got $code"; }
grep -q "atomic rollback" "$WORK/atomic.log" || { cat "$WORK/atomic.log"; fail "no atomic-rollback message"; }
for t in t1 t2 t3; do
    [ "$(tgt_count $t)" = "0" ] || fail "--atomic: $t should be empty after rollback (got $(tgt_count $t))"
done
log "  OK (all tables empty — the committed t1 was rolled back too: 'all tables or none')"

# --- 3. Preflight: --atomic ohne --truncate → Exit 2 -------------------------
build_dbs
log "transfer --atomic WITHOUT --truncate (expect Exit 2 preflight)..."
set +e
dmigrate data transfer --source sqlite:///w/src.db --target sqlite:///w/tgt.db --atomic \
    > "$WORK/preflight.log" 2>&1
code=$?
set -e
[ "$code" = "2" ] || { cat "$WORK/preflight.log"; fail "--atomic without --truncate expected Exit 2, got $code"; }
grep -q "requires --truncate" "$WORK/preflight.log" || fail "no '--atomic requires --truncate' message"
log "  OK (--atomic requires --truncate, Exit 2)"

log "SUCCESS — SQLite --atomic smoke passed (contrast + all-or-none rollback + preflight)."
