#!/usr/bin/env bash
# LN-009 — SQLite->SQLite `data transfer --verify` Smoke (same-dialect).
#
# Validiert den Verify-Pfad end-to-end ohne DB-Container:
#   1. Happy: sauberer Load (--truncate) -> jede Spalte byte-exakt -> "Verify OK", Exit 0.
#   2. Divergenz-Erkennung: vorbelegtes Ziel mit einem abweichenden Wert +
#      --on-conflict skip -> Ziel != Quelle -> "Verify divergence", Exit 3.
#
# Voraussetzung: sqlite3 am Host + lokal gebautes d-migrate:dev-Image.
# ADR: docs/adr/0030-datenwert-kanonisierung-verify.md

set -euo pipefail

IMG="${DMIGRATE_IMAGE:-d-migrate:dev}"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

log()  { printf '[verify-sqlite] %s\n' "$*"; }
fail() { printf '[verify-sqlite] FAIL: %s\n' "$*" >&2; exit 1; }

SCHEMA="CREATE TABLE items (
  id INTEGER PRIMARY KEY, name TEXT NOT NULL, qty INTEGER, price REAL,
  active INTEGER, note TEXT, created TEXT, payload BLOB
);"

log "building source + (empty) target SQLite DBs..."
sqlite3 "$WORK/src.db" "$SCHEMA
INSERT INTO items VALUES (1,'alpha',10,1.50,1,'x','2020-01-01',X'0102');
INSERT INTO items VALUES (2,'beta unicode',NULL,0.0,0,'','2021-06-15',NULL);
INSERT INTO items VALUES (3,'gamma',7,99.99,1,'note','2022-12-31',X'FF00FF');"
sqlite3 "$WORK/tgt.db" "$SCHEMA"

dmigrate() { docker run --rm --user "$(id -u):$(id -g)" -v "$WORK:/w" -w /w "$IMG" "$@"; }

# --- 1. Happy path: clean load + verify --------------------------------------
log "data transfer src -> tgt --truncate --verify (expect Exit 0 + Verify OK)..."
set +e
dmigrate data transfer --source sqlite:///w/src.db --target sqlite:///w/tgt.db --truncate --verify \
    > "$WORK/happy.log" 2>&1
code=$?
set -e
[ "$code" = "0" ] || { cat "$WORK/happy.log"; fail "happy-path transfer/verify exited $code (expected 0)"; }
grep -q "Verify OK" "$WORK/happy.log" || { cat "$WORK/happy.log"; fail "happy path did not print 'Verify OK'"; }
log "  OK (same-dialect byte-exact reconciliation passed)"

# --- 2. Divergence detection: pre-seed target with a wrong row + skip ---------
log "seeding target with a divergent row + transfer --on-conflict skip --verify (expect Exit 3)..."
sqlite3 "$WORK/tgt.db" "DELETE FROM items; INSERT INTO items VALUES (1,'WRONG',999,7.77,0,'bad','1999-01-01',X'DEAD');"
set +e
dmigrate data transfer --source sqlite:///w/src.db --target sqlite:///w/tgt.db --on-conflict skip --verify \
    > "$WORK/diverge.log" 2>&1
code=$?
set -e
[ "$code" = "3" ] || { cat "$WORK/diverge.log"; fail "divergence transfer/verify exited $code (expected 3)"; }
grep -q "Verify divergence" "$WORK/diverge.log" || { cat "$WORK/diverge.log"; fail "divergence not reported"; }
log "  OK (verify detected the divergent row id=1, Exit 3)"

log "SUCCESS — SQLite->SQLite --verify smoke passed (happy path + divergence detection)."
