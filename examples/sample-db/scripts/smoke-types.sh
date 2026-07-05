#!/usr/bin/env bash
# Sample-DB-Harness — Typ-Kanonisierungs-Smoke (AP5)
# Plan: docs/planning/in-progress/postcompare-type-canonicalization-slice.md
#
# Permanenter Sensor für die Post-Compare-Drift-Familie (identifier-PK/v3,
# Typ-Faltung + UNIQUE-/FK-Fold + effectiveRequired/v7, Plan-Konvergenz/AP7):
#   [T1] SQLite-Typ-Matrix: frisches `migrate --execute` je Neutraltyp → Exit 0
#   [T2] UNIQUE-/FK-Folds inkl. Reverse-Fidelity (unique: true / benannter Constraint)
#   [T3] Plan-Konvergenz: Zweitlauf gegen migriertes Ziel plant 0 Statements
#   [T4] Rebuild mit nachträglich benanntem UNIQUE → Exit 0 (Fulltext-Slice-Trigger)
#   [T5] Rollback-Round-Trip: v7-Artefakt schreiben + `schema rollback --execute` → Exit 0
#   [T6] Gegenprobe Scope-Entscheidung a: `schema compare` bleibt strikt (Exit 1)
#   [PG]/[MY] Kanten-Proben + Konvergenz je Dialekt (Compose-Services)
#
# Voraussetzung: docker, lokal gebautes d-migrate:dev (docker build --target runtime).
# `geometry`/`fulltext` sind bewusst NICHT hier (eigene Smokes: spatial/fulltext).
# Impliziter identifier-PK ist jetzt Teil des Sensors ([PK]): SQLite
# identifier+primary_key (Doppel-PK-Dedup), MySQL identifier-only (AUTO_INCREMENT-KEY),
# PG identifier-only (PK-Materialisierung im Ziel) — Slice
# generate-implicit-identifier-pk-materialization. TEXT-UNIQUE ohne Länge (MySQL)
# bleibt getracktes Präfixlängen-Ticket, nicht Teil dieses Sensors.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
EXAMPLES_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
WORK_REL=".cache/types-smoke"
WORK="$EXAMPLES_DIR/$WORK_REL"
COMPOSE="docker compose -f $EXAMPLES_DIR/docker-compose.yml"
export SAMPLE_DB_DMIGRATE_USER="$(id -u):$(id -g)"

log()  { printf '[types] %s\n' "$*"; }
fail() { printf '[types] FAIL: %s\n' "$*" >&2; exit 1; }

DRUN="docker run --rm --user $(id -u):$(id -g) -v $EXAMPLES_DIR:/work -w /work d-migrate:dev"

mkdir -p "$WORK/out"
rm -f "$WORK"/*.yaml "$WORK"/out/*

# ── Probe-Schemata (Ein-Spalten, kein PK — isoliert die Typ-Kante) ──
col_probe() { # $1=name $2=column-spec
  printf 'name: "types-%s"\nversion: "1.0.0"\ntables:\n  probe:\n    columns:\n      val: { %s }\n' "$1" "$2" > "$WORK/$1.yaml"
}
col_probe text        'type: text'
col_probe text50      'type: text, max_length: 50'
col_probe char10      'type: char, length: 10'
col_probe integer     'type: integer'
col_probe smallint    'type: smallint'
col_probe biginteger  'type: biginteger'
col_probe float       'type: float'
col_probe decimal     'type: decimal, precision: 10, scale: 2'
col_probe boolean     'type: boolean'
col_probe datetime    'type: datetime'
col_probe datetime_tz 'type: datetime, timezone: true'
col_probe date        'type: date'
col_probe time        'type: time'
col_probe uuid        'type: uuid'
col_probe json        'type: json'
col_probe xml         'type: xml'
col_probe binary      'type: binary'
col_probe email       'type: email'
col_probe enum        'type: enum, values: ["red", "green"]'
col_probe array       'type: array, element_type: text'
printf 'name: "types-identifier"\nversion: "1.0.0"\ntables:\n  probe:\n    columns:\n      id: { type: identifier, auto_increment: true }\n' > "$WORK/identifier.yaml"
printf 'name: "types-identifier_pk"\nversion: "1.0.0"\ntables:\n  probe:\n    columns:\n      id: { type: identifier, auto_increment: true }\n    primary_key: [id]\n' > "$WORK/identifier_pk.yaml"
printf 'name: "types-identifier_pk_rb"\nversion: "1.0.0"\ntables:\n  probe:\n    columns:\n      id: { type: identifier, auto_increment: true }\n      n: { type: smallint }\n    primary_key: [id]\n' > "$WORK/identifier_pk_rb1.yaml"
printf 'name: "types-identifier_pk_rb"\nversion: "1.0.1"\ntables:\n  probe:\n    columns:\n      id: { type: identifier, auto_increment: true }\n      n: { type: integer }\n    primary_key: [id]\n' > "$WORK/identifier_pk_rb2.yaml"
printf 'name: "types-uq_single"\nversion: "1.0.0"\ntables:\n  probe:\n    columns:\n      a: { type: text }\n      b: { type: text }\n    constraints:\n      - name: uq_a\n        type: unique\n        columns: [a]\n' > "$WORK/uq_single.yaml"
printf 'name: "types-uq_multi"\nversion: "1.0.0"\ntables:\n  probe:\n    columns:\n      a: { type: text }\n      b: { type: text }\n    constraints:\n      - name: uq_ab\n        type: unique\n        columns: [a, b]\n' > "$WORK/uq_multi.yaml"
printf 'name: "types-uq_single_vc"\nversion: "1.0.0"\ntables:\n  probe:\n    columns:\n      a: { type: text, max_length: 50 }\n      b: { type: text, max_length: 50 }\n    constraints:\n      - name: uq_a\n        type: unique\n        columns: [a]\n' > "$WORK/uq_single_vc.yaml"
printf 'name: "types-fk_colref"\nversion: "1.0.0"\ntables:\n  parent:\n    columns:\n      id: { type: integer }\n    primary_key: [id]\n  child:\n    columns:\n      id: { type: integer }\n      parent_id: { type: integer, references: { table: parent, column: id } }\n    primary_key: [id]\n' > "$WORK/fk_colref.yaml"
printf 'name: "types-rebuild"\nversion: "1.0.0"\ntables:\n  t:\n    columns:\n      a: { type: text }\n      b: { type: text }\n' > "$WORK/rebuild-v1.yaml"
printf 'name: "types-rebuild"\nversion: "1.0.1"\ntables:\n  t:\n    columns:\n      a: { type: text }\n      b: { type: text }\n    constraints:\n      - name: uq_a\n        type: unique\n        columns: [a]\n' > "$WORK/rebuild-v2.yaml"

# ── Helfer ──────────────────────────────────────────────────────────
migrate_sqlite() { # $1=probe $2=db-name (persistiert für Zweitlauf/Reverse)
  $DRUN schema migrate --execute \
    --source "/work/$WORK_REL/$1.yaml" \
    --target "db:sqlite:///work/$WORK_REL/out/$2.db" \
    --report "/work/$WORK_REL/out/$1-$2.report.yaml" > /dev/null 2>&1
}

statements_in() { # $1=report-datei → Anzahl SQL-Statements
  grep -c '"sql":' "$WORK/out/$1" || true
}

# ── [T1] SQLite-Typ-Matrix ─────────────────────────────────────────
SQLITE_TYPES="text text50 char10 integer smallint biginteger float decimal boolean \
datetime datetime_tz date time uuid json xml binary email enum array identifier"
log "[T1] SQLite-Typ-Matrix (frisches migrate --execute je Typ)..."
for t in $SQLITE_TYPES; do
  rm -f "$WORK/out/$t.db"
  migrate_sqlite "$t" "$t" || fail "[T1] sqlite $t: migrate --execute != 0 (Typ-Kanonisierung regressiert?)"
done
log "[T1] OK — $(echo $SQLITE_TYPES | wc -w) Typen Exit 0"

# ── [T2] UNIQUE-/FK-Folds + Reverse-Fidelity ───────────────────────
log "[T2] UNIQUE-/FK-Folds..."
# identifier_pk (identifier + explizites primary_key) läuft separat in [PK] —
# der SQLite-Doppel-PK ist mit dem Slice
# generate-implicit-identifier-pk-materialization behoben.
for p in uq_single uq_multi fk_colref; do
  rm -f "$WORK/out/$p.db"
  migrate_sqlite "$p" "$p" || fail "[T2] sqlite $p: migrate --execute != 0"
done
$DRUN schema reverse --source "sqlite:///work/$WORK_REL/out/uq_single.db" \
  --output "/work/$WORK_REL/out/uq_single.reverse.yaml" > /dev/null 2>&1 || fail "[T2] reverse uq_single"
grep -q "unique: true" "$WORK/out/uq_single.reverse.yaml" \
  || fail "[T2] Reverse-Fidelity: Single-Column-UNIQUE nicht als unique:true gelesen (AP4-Regression)"
$DRUN schema reverse --source "sqlite:///work/$WORK_REL/out/uq_multi.db" \
  --output "/work/$WORK_REL/out/uq_multi.reverse.yaml" > /dev/null 2>&1 || fail "[T2] reverse uq_multi"
grep -q "name: uq_ab" "$WORK/out/uq_multi.reverse.yaml" \
  || fail "[T2] Reverse-Fidelity: Multi-Column-UNIQUE-Name uq_ab nicht rekonstruiert (AP4-Regression)"
log "[T2] OK"

# ── [T3] Plan-Konvergenz (AP7): Zweitlauf plant 0 Statements ───────
log "[T3] Konvergenz-Zweitlauf (smallint gegen migriertes Ziel)..."
$DRUN schema migrate --execute \
  --source "/work/$WORK_REL/smallint.yaml" \
  --target "db:sqlite:///work/$WORK_REL/out/smallint.db" \
  --report "/work/$WORK_REL/out/converge.report.yaml" > /dev/null 2>&1 \
  || fail "[T3] Zweitlauf != 0"
[ "$(statements_in converge.report.yaml)" = "0" ] \
  || fail "[T3] Zweitlauf plant Statements (No-Op-Rebuild — AP7-Regression)"
log "[T3] OK — 0 Statements"

# ── [T4] Rebuild mit nachträglichem UNIQUE ─────────────────────────
log "[T4] Rebuild + benannter UNIQUE..."
rm -f "$WORK/out/rebuild.db"
migrate_sqlite rebuild-v1 rebuild || fail "[T4] v1-Apply != 0"
migrate_sqlite rebuild-v2 rebuild || fail "[T4] Rebuild mit UNIQUE != 0 (Original-Trigger-Szenario regressiert)"
log "[T4] OK"

# ── [T5] Rollback-Round-Trip (v7-Artefakt) ─────────────────────────
log "[T5] Rollback-Round-Trip..."
rm -f "$WORK/out/rollback.db" "$WORK/out/down.sql"
$DRUN schema migrate --execute --generate-rollback \
  --source "/work/$WORK_REL/smallint.yaml" \
  --target "db:sqlite:///work/$WORK_REL/out/rollback.db" \
  --report "/work/$WORK_REL/out/rollback-up.report.yaml" \
  --rollback-output "/work/$WORK_REL/out/down.sql" > /dev/null 2>&1 \
  || fail "[T5] migrate mit --rollback-output != 0"
grep -q "schema-fingerprint-v7" "$WORK/out/down.sql" \
  || fail "[T5] Artefakt trägt nicht schema-fingerprint-v7"
$DRUN schema rollback --execute --allow-destructive \
  --source "/work/$WORK_REL/out/down.sql" \
  --target "db:sqlite:///work/$WORK_REL/out/rollback.db" > /dev/null 2>&1 \
  || fail "[T5] schema rollback --execute != 0 (v7-Verify/Kanonisierer-Regression?)"
log "[T5] OK"

# ── [T6] Gegenprobe: schema compare bleibt strikt ──────────────────
log "[T6] schema compare Gegenprobe (smallint vs integer)..."
set +e
$DRUN schema compare --source "/work/$WORK_REL/smallint.yaml" \
  --target "/work/$WORK_REL/integer.yaml" > /dev/null 2>&1
COMPARE_EXIT=$?
set -e
[ "$COMPARE_EXIT" = "1" ] \
  || fail "[T6] schema compare Exit $COMPARE_EXIT statt 1 (Striktheit verloren?)"
log "[T6] OK — compare meldet Unterschied"

# ── [PK] Impliziter identifier-PK (SQLite-Teil) ────────────────────
log "[PK] SQLite identifier + explizites primary_key (Doppel-PK-Dedup)..."
rm -f "$WORK/out/identifier_pk.db"
migrate_sqlite identifier_pk identifier_pk \
  || fail "[PK] sqlite identifier_pk: migrate --execute != 0 (Doppel-PK-Regression)"
$DRUN schema migrate --execute \
  --source "/work/$WORK_REL/identifier_pk.yaml" \
  --target "db:sqlite:///work/$WORK_REL/out/identifier_pk.db" \
  --report "/work/$WORK_REL/out/pk-converge.report.yaml" > /dev/null 2>&1 \
  || fail "[PK] sqlite identifier_pk Zweitlauf != 0"
[ "$(statements_in pk-converge.report.yaml)" = "0" ] \
  || fail "[PK] sqlite identifier_pk Zweitlauf plant Statements (Drift)"
# Rebuild-Pfad (zweiter CREATE-TABLE-Emitter, Review-Regression 2026-07-05): eine
# identifier+PK-Tabelle reshapen (Sibling-Typ smallint→integer erzwingt SQLite-
# Table-Rebuild) — der Rebuild darf den PK nicht doppeln (SQLITE_ERROR sonst).
rm -f "$WORK/out/identifier_pk_rb.db"
migrate_sqlite identifier_pk_rb1 identifier_pk_rb || fail "[PK] sqlite identifier_pk_rb v1 != 0"
migrate_sqlite identifier_pk_rb2 identifier_pk_rb \
  || fail "[PK] sqlite identifier_pk Rebuild != 0 (Doppel-PK im Rebuild-Emitter)"
log "[PK] OK — SQLite identifier_pk Exit 0 (frisch + Rebuild), konvergent (MySQL/PG folgen unten)"

# ── [PG] Kanten-Proben + Konvergenz ────────────────────────────────
log "[PG] postgres hoch + Kanten-Proben..."
$COMPOSE up -d postgres > /dev/null 2>&1
for i in $(seq 1 60); do
  $COMPOSE exec -T postgres pg_isready -U "${POSTGRES_USER:-dmigrate}" -d postgres > /dev/null 2>&1 && break
  sleep 2
done
set -a; source "$EXAMPLES_DIR/.env"; set +a
PG_URL="postgresql://$POSTGRES_USER:$POSTGRES_PASSWORD@postgres:5432/types_smoke"
CRUN="$COMPOSE run --rm -T dmigrate"
pg_fresh() {
  $COMPOSE exec -T postgres psql -U "$POSTGRES_USER" -d postgres \
    -c "DROP DATABASE IF EXISTS types_smoke WITH (FORCE)" -c "CREATE DATABASE types_smoke" > /dev/null 2>&1
}
for p in email enum uq_single_vc identifier_pk fk_colref; do
  pg_fresh
  $CRUN schema migrate --execute --source "/work/$WORK_REL/$p.yaml" \
    --target "db:$PG_URL" --report "/work/$WORK_REL/out/pg-$p.report.yaml" > /dev/null 2>&1 \
    || fail "[PG] $p: migrate --execute != 0"
done
$CRUN schema migrate --execute --source "/work/$WORK_REL/fk_colref.yaml" \
  --target "db:$PG_URL" --report "/work/$WORK_REL/out/pg-converge.report.yaml" > /dev/null 2>&1 \
  || fail "[PG] Konvergenz-Zweitlauf != 0"
[ "$(statements_in pg-converge.report.yaml)" = "0" ] || fail "[PG] Zweitlauf plant Statements"
# [PK] PG identifier-only muss jetzt einen PRIMARY KEY im Ziel tragen (D1/D3) —
# Exit 0 allein genügt hier nicht (SERIAL ohne PK ist valides DDL), daher zählen.
pg_fresh
$CRUN schema migrate --execute --source "/work/$WORK_REL/identifier.yaml" \
  --target "db:$PG_URL" --report "/work/$WORK_REL/out/pg-identifier.report.yaml" > /dev/null 2>&1 \
  || fail "[PK] PG identifier-only: migrate --execute != 0"
PG_PK=$($COMPOSE exec -T postgres psql -U "$POSTGRES_USER" -d types_smoke -tAc \
  "SELECT count(*) FROM information_schema.table_constraints WHERE table_name='probe' AND constraint_type='PRIMARY KEY'" 2>/dev/null | tr -d '[:space:]')
[ "$PG_PK" = "1" ] || fail "[PK] PG identifier-only: kein PRIMARY KEY im Ziel materialisiert (D1-Regression, count=$PG_PK)"
log "[PK] PG identifier-only Exit 0, PRIMARY KEY im Ziel materialisiert"
log "[PG] OK"

# ── [MY] Kanten-Proben + Konvergenz ────────────────────────────────
log "[MY] mysql hoch + Kanten-Proben..."
$COMPOSE up -d mysql > /dev/null 2>&1
for i in $(seq 1 90); do
  $COMPOSE exec -T mysql mysqladmin ping -h127.0.0.1 -uroot -p"$MYSQL_ROOT_PASSWORD" > /dev/null 2>&1 && break
  sleep 2
done
MY_URL="mysql://$MYSQL_USER:$MYSQL_PASSWORD@mysql:3306/types_smoke"
my_fresh() {
  $COMPOSE exec -T mysql mysql -uroot -p"$MYSQL_ROOT_PASSWORD" \
    -e "DROP DATABASE IF EXISTS types_smoke; CREATE DATABASE types_smoke; GRANT ALL ON types_smoke.* TO '$MYSQL_USER'@'%'; FLUSH PRIVILEGES;" > /dev/null 2>&1
}
# [PK] `identifier` (ohne PK) neu dabei: MySQL rendert AUTO_INCREMENT ohne KEY nur
# dann fehlerfrei (Error 1075), wenn der effektive PK materialisiert wird → Exit 0
# ist hier der Sensor.
for p in datetime_tz xml email enum array uq_single_vc identifier identifier_pk; do
  my_fresh
  $CRUN schema migrate --execute --source "/work/$WORK_REL/$p.yaml" \
    --target "db:$MY_URL" --report "/work/$WORK_REL/out/my-$p.report.yaml" > /dev/null 2>&1 \
    || fail "[MY] $p: migrate --execute != 0"
done
$CRUN schema migrate --execute --source "/work/$WORK_REL/identifier_pk.yaml" \
  --target "db:$MY_URL" --report "/work/$WORK_REL/out/my-converge.report.yaml" > /dev/null 2>&1 \
  || fail "[MY] Konvergenz-Zweitlauf != 0"
[ "$(statements_in my-converge.report.yaml)" = "0" ] || fail "[MY] Zweitlauf plant Statements"
log "[MY] OK"

log "TYPES SMOKE OK — Matrix, Folds, Konvergenz, Rebuild, Rollback, compare-Striktheit grün"
