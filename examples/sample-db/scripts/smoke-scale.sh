#!/usr/bin/env bash
# Sample-DB-Harness — Phase 3 Scale-Smoke (Employees/MySQL, opt-in/nightly)
# Plan: docs/planning/done/sample-db-integration-harness.md
# ADR:  docs/adr/0014-sample-db-harness-fetch-and-compose.md
#
# Großvolumiger Streaming-/Chunking-/Resume-Test gegen das echte CLI mit dem
# klassischen Employees-Dataset (datacharmer/test_db, ~4 Mio Zeilen, 6 Tabellen).
# Im Gegensatz zu Phase 1/2 (direkter `data transfer`) übt Phase 3 den DATEI-
# basierten export→import-Pfad, weil NUR dieser `--resume` unterstützt:
#
#   1. Employees in eine frische MySQL-Quell-DB laden.
#   2. reverse + validate (Basis-Tabellen, ohne Views/Routinen).
#   3. `data export --format json --split-files --chunk-size 5000` — Pass 1 wird
#      MITTEN im Stream hart unterbrochen (docker kill), sobald ein Checkpoint
#      auf Platte liegt; Pass 2 setzt via `--resume <operationId>` fort und
#      vollendet das Bundle. (Chunking + Resume datenbelegt.)
#   4. Das EINE exportierte Bundle in ZWEI Ziele importieren:
#        - employees_my_target (MySQL  — Round-Trip)
#        - employees_pg_target (PostgreSQL — Cross-Dialect)
#      Ziel = nur pre-data (Tabellen+PK); FKs/Views (post-data) sind Schema-
#      Fidelity (Phase-2-Domäne) und hier bewusst NICHT im Scope.
#   5. Zeilen-Parität (Quelle == Ziel == gepinnte Baseline) + SUM(salary)-
#      Checksumme über alle 6 Tabellen, je Ziel.
#
# Gating: opt-in (`make sample-db-scale-smoke`) + nightly Workflow
# (.github/workflows/sample-db-scale.yml). NICHT im PR-Gate (Laufzeit/Volumen).
#
# Voraussetzung am Host: docker, docker compose sowie das lokal gebaute
# d-migrate:dev-Image (`make docker-build IMAGE_TAG=dev`).

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
EXAMPLES_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
COMPOSE_FILE="$EXAMPLES_DIR/docker-compose.yml"
COMPOSE="docker compose -f $COMPOSE_FILE"
OUT_DIR="$EXAMPLES_DIR/out"
EXPECTED_DIR="$EXAMPLES_DIR/expected"
COUNTS_BASELINE="$EXPECTED_DIR/employees-scale.counts.txt"

# Container-relative Pfade (out/ und .cache/ sind über `.:/work` gemountet)
EXPORT_DIR_HOST="$OUT_DIR/emp-export"
CKPT_DIR_HOST="$OUT_DIR/emp-ckpt"
EXPORT_DIR_CTR="/work/out/emp-export"
CKPT_DIR_CTR="/work/out/emp-ckpt"
REVERSE_CTR="/work/out/employees.reverse.yaml"

# 6 Basis-Tabellen in FK-Abhängigkeitsreihenfolge; salaries (~2,84 Mio) als
# Volumen-Treiber bewusst zuletzt, damit der Abbruch zuverlässig mitten im
# Lauf landet (kleine Tabellen sind dann schon COMPLETED).
TABLES="departments,employees,dept_manager,dept_emp,titles,salaries"
TABLE_LIST="departments employees dept_manager dept_emp titles salaries"
CHUNK=5000
RUN_NAME="sample-db-emp-export"

log()  { printf '[scale] %s\n' "$*"; }
note() { printf '[scale] NOTE: %s\n' "$*"; }
fail() { printf '[scale] FAIL: %s\n' "$*" >&2; exit 1; }

# --- 0. .env + Verzeichnisse + Dump-Fetch (inkl. Employees) --------
mkdir -p "$OUT_DIR" "$EXPECTED_DIR"
if [ ! -f "$EXAMPLES_DIR/.env" ]; then
    cp "$EXAMPLES_DIR/.env.example" "$EXAMPLES_DIR/.env"
    log "created examples/sample-db/.env from .env.example"
fi
# shellcheck disable=SC1091
set -a; . "$EXAMPLES_DIR/.env"; set +a
: "${POSTGRES_USER:?POSTGRES_USER not set}"
: "${MYSQL_ROOT_PASSWORD:?MYSQL_ROOT_PASSWORD not set}"

# Non-root-Image (uid 10001) muss ins gemountete out/ schreiben können →
# Container läuft als Host-User (vgl. smoke-cross.sh).
export SAMPLE_DB_DMIGRATE_USER="$(id -u):$(id -g)"

FETCH_EMPLOYEES=1 "$SCRIPT_DIR/fetch-dumps.sh"

mysql_root()  { $COMPOSE exec -T mysql mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -N "$@" 2>/dev/null; }
mysql_apply() { $COMPOSE exec -T mysql mysql -uroot -p"$MYSQL_ROOT_PASSWORD" "$1"; }
psql_t()      { $COMPOSE exec -T postgres psql -v ON_ERROR_STOP="$2" -U "$POSTGRES_USER" -d "$1" "${@:3}"; }
my_val()      { mysql_root -e "$1" | tr -d '[:space:]'; }
my_val_db()   { mysql_root "$1" -e "$2" | tr -d '[:space:]'; }
pg_val_db()   { $COMPOSE exec -T postgres psql -U "$POSTGRES_USER" -d "$1" -tAc "$2" 2>/dev/null | tr -d '[:space:]'; }

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

# --- 1. Stacks hoch (mysql-Quelle + beide Ziele) -------------------
log "starting mysql + postgres..."
$COMPOSE up -d mysql postgres
wait_healthy mysql 180
wait_healthy postgres 120

# --- 2. Employees in eine frische MySQL-Quell-DB laden -------------
# employees.sql legt die DB selbst an (DROP/CREATE/USE) und `source`t die
# Dumps RELATIV → mysql läuft mit cwd=/sample-cache/employees. Die letzte
# Zeile `source show_elapsed.sql` wird gestrippt (nicht im Pin-Set).
log "loading employees source DB (~4M rows, may take a minute)..."
$COMPOSE exec -T mysql sh -c \
    "cd /sample-cache/employees && grep -v 'show_elapsed' employees.sql | mysql -uroot -p'$MYSQL_ROOT_PASSWORD'" \
    || fail "employees load failed"

# DROP/CREATE in employees.sql entfernt Grants → Quelle granten; das MySQL-Ziel
# FRISCH anlegen (DROP+CREATE, idempotent über Läufe — sonst kollidiert das
# CREATE TABLE der pre-data mit Resten eines Vorlaufs) und granten.
mysql_root -e "
  DROP DATABASE IF EXISTS employees_my_target;
  CREATE DATABASE employees_my_target CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
  GRANT ALL PRIVILEGES ON employees.* TO '${MYSQL_USER}'@'%';
  GRANT ALL PRIVILEGES ON employees_my_target.* TO '${MYSQL_USER}'@'%';
  FLUSH PRIVILEGES;" || fail "grant failed"

src_tables=$(my_val "SELECT count(*) FROM information_schema.tables WHERE table_schema='employees' AND table_type='BASE TABLE';")
[ "$src_tables" = "6" ] || fail "expected 6 source base tables, got $src_tables"
log "employees loaded ($src_tables base tables)"

# Quell-Zeilenzahlen einsammeln (= erwartete Parität) + SUM(salary)-Checksumme.
declare -A SRC_COUNT
for t in $TABLE_LIST; do
    SRC_COUNT[$t]=$(my_val "SELECT count(*) FROM employees.\`$t\`;")
done
SRC_SALARY_SUM=$(my_val "SELECT SUM(salary) FROM employees.salaries;")
log "source counts: $(for t in $TABLE_LIST; do printf '%s=%s ' "$t" "${SRC_COUNT[$t]}"; done)"
log "source SUM(salary)=$SRC_SALARY_SUM"

# Gegen gepinnte Baseline (Dataset-Integrität: belegt, dass der gepinnte
# Dump wirklich geladen wurde). Bootstrap beim Erstlauf.
{
    for t in $TABLE_LIST; do printf '%s %s\n' "$t" "${SRC_COUNT[$t]}"; done
    printf 'salary_sum %s\n' "$SRC_SALARY_SUM"
} > "$OUT_DIR/employees-scale.counts.txt"
if [ ! -f "$COUNTS_BASELINE" ]; then
    cp "$OUT_DIR/employees-scale.counts.txt" "$COUNTS_BASELINE"
    log "BASELINE BOOTSTRAP: wrote $COUNTS_BASELINE — review + commit it, then re-run."
else
    diff -u "$COUNTS_BASELINE" "$OUT_DIR/employees-scale.counts.txt" > /tmp/scale-counts.diff 2>&1 \
        && log "source counts == pinned baseline" \
        || { cat /tmp/scale-counts.diff; fail "source counts DEVIATE from pinned baseline (pin moved?)"; }
fi

# --- 3. reverse + validate (Basis-Tabellen, ohne Views) ------------
log "schema reverse employees_my..."
$COMPOSE run --rm dmigrate schema reverse --source employees_my \
    --output "$REVERSE_CTR" > /dev/null || fail "reverse failed"
[ -s "$OUT_DIR/employees.reverse.yaml" ] || fail "empty reverse.yaml"

log "schema validate (expect 0 errors)..."
val_out=$($COMPOSE run --rm dmigrate schema validate --source "$REVERSE_CTR" 2>&1) \
    || fail "validate exited non-zero:\n$val_out"
printf '%s' "$val_out" | grep -q "Validation passed" || fail "validate did not pass:\n$val_out"
log "validate passed"

# --- 4. Export Pass 1 — mitten im Stream hart unterbrechen ---------
rm -rf "$EXPORT_DIR_HOST" "$CKPT_DIR_HOST"
docker rm -f "$RUN_NAME" >/dev/null 2>&1 || true

log "export pass 1 (json --split-files --chunk-size $CHUNK; will be interrupted)..."
$COMPOSE run --rm --name "$RUN_NAME" -T dmigrate data export \
    --source employees_my --format json --split-files \
    --output "$EXPORT_DIR_CTR" --tables "$TABLES" \
    --chunk-size "$CHUNK" --checkpoint-dir "$CKPT_DIR_CTR" \
    > /tmp/emp-export1.log 2>&1 &
RUN_PID=$!

# Pollen bis ein (atomar geschriebener) Checkpoint auf Platte liegt, dann den
# Container hart killen. Der Checkpoint überlebt → resumebar.
deadline=$(( $(date +%s) + 180 ))
ckpt_file=""
while [ "$(date +%s)" -lt "$deadline" ]; do
    if ! kill -0 "$RUN_PID" 2>/dev/null; then
        note "export pass 1 finished before interruption could fire — dataset too small or host too fast?"
        break
    fi
    ckpt_file=$(ls "$CKPT_DIR_HOST"/*.checkpoint.yaml 2>/dev/null | head -1 || true)
    if [ -n "$ckpt_file" ]; then
        log "checkpoint persisted ($(basename "$ckpt_file")) — interrupting export now"
        docker kill "$RUN_NAME" >/dev/null 2>&1 || true
        break
    fi
    sleep 0.5
done
wait "$RUN_PID" 2>/dev/null && rc1=0 || rc1=$?
docker rm -f "$RUN_NAME" >/dev/null 2>&1 || true

ckpt_file=$(ls "$CKPT_DIR_HOST"/*.checkpoint.yaml 2>/dev/null | head -1 || true)
[ -n "$ckpt_file" ] || { cat /tmp/emp-export1.log; fail "no resumable checkpoint after pass 1 (interruption did not catch a persisted checkpoint)"; }
[ "$rc1" != "0" ] || note "export pass 1 exited 0 despite kill (race) — checkpoint present, continuing"
OPID=$(basename "$ckpt_file" .checkpoint.yaml)

# Checkpoint-Snapshot als Chunking-/Resume-Beleg sichern (Pass 1 ist VOR
# completion → zeigt chunksProcessed>1 für mindestens eine Tabelle).
cp "$ckpt_file" "$OUT_DIR/employees-scale.checkpoint.snapshot.yaml"
chunks_seen=$(grep -oE 'chunksProcessed: [0-9]+' "$ckpt_file" | grep -oE '[0-9]+' | sort -rn | head -1 || echo 0)
[ "${chunks_seen:-0}" -ge 1 ] || fail "checkpoint shows no processed chunks — chunking not exercised"
log "interrupted with operationId=$OPID; max chunksProcessed in a slice=$chunks_seen (chunking confirmed)"

# --- 4b. Export Pass 2 — Resume bis zur Vollendung ----------------
log "export pass 2 — resume from checkpoint $OPID..."
$COMPOSE run --rm -T dmigrate data export \
    --source employees_my --format json --split-files \
    --output "$EXPORT_DIR_CTR" --tables "$TABLES" \
    --chunk-size "$CHUNK" --checkpoint-dir "$CKPT_DIR_CTR" \
    --resume "$OPID" > /tmp/emp-export2.log 2>&1 \
    || { cat /tmp/emp-export2.log; fail "resume export failed"; }

# Erfolgreicher Abschluss completed den Checkpoint (= gelöscht) und das Bundle
# enthält alle 6 Tabellen-Dateien.
[ -z "$(ls "$CKPT_DIR_HOST"/*.checkpoint.yaml 2>/dev/null || true)" ] \
    || note "checkpoint still present after resume completion (expected removal)"
for t in $TABLE_LIST; do
    [ -s "$EXPORT_DIR_HOST/$t.json" ] || fail "missing/empty export file after resume: $t.json"
done
log "resume completed — all 6 table files present (resume after interruption proven)"

# --- 5. Import in beide Ziele + Parität ---------------------------
# gen_target <label> <dialect> — generiert pre/post-data; nur pre-data wird
# angewendet (Tabellen+PK), siehe Scope-Hinweis im Kopf.
gen_target() {
    local label="$1" dialect="$2"
    local pre="$OUT_DIR/employees.$label.pre-data.sql"

    log "[$label] generate --target $dialect (pre-data only)..."
    $COMPOSE run --rm dmigrate schema generate --source "$REVERSE_CTR" \
        --target "$dialect" --split pre-post --deterministic \
        --output "/work/out/employees.$label.sql" > /dev/null || fail "[$label] generate failed"
    [ -s "$pre" ] || fail "[$label] empty pre-data: $pre"
}

# 5a. MySQL-Ziel (Round-Trip)
gen_target my mysql
log "[my] applying pre-data to employees_my_target..."
mysql_apply employees_my_target < "$OUT_DIR/employees.my.pre-data.sql" || fail "[my] pre-data apply failed"
log "[my] data import (json bundle, --chunk-size $CHUNK, fk-checks off)..."
$COMPOSE run --rm dmigrate data import --source "$EXPORT_DIR_CTR" \
    --target employees_my_target --format json --schema "$REVERSE_CTR" \
    --truncate --disable-fk-checks --chunk-size "$CHUNK" \
    > /tmp/emp-import-my.log 2>&1 || { cat /tmp/emp-import-my.log; fail "[my] import failed"; }

# 5b. PostgreSQL-Ziel (Cross-Dialect)
gen_target pg postgresql
log "[pg] (re)creating employees_pg_target + applying pre-data..."
psql_t postgres 1 -c "DROP DATABASE IF EXISTS employees_pg_target WITH (FORCE)" \
    -c "CREATE DATABASE employees_pg_target" > /dev/null || fail "[pg] target db reset failed"
psql_t employees_pg_target 1 < "$OUT_DIR/employees.pg.pre-data.sql" > /dev/null || fail "[pg] pre-data apply failed"
log "[pg] data import (json bundle, --chunk-size $CHUNK)..."
$COMPOSE run --rm dmigrate data import --source "$EXPORT_DIR_CTR" \
    --target employees_pg_target --format json --schema "$REVERSE_CTR" \
    --truncate --chunk-size "$CHUNK" \
    > /tmp/emp-import-pg.log 2>&1 || { cat /tmp/emp-import-pg.log; fail "[pg] import failed"; }

# --- 6. Parität (Quelle == Ziel == Baseline) + Checksumme ---------
verify_parity() {  # verify_parity <label> <get_count_cmd...>
    local label="$1"; shift
    local mismatch=0 t s d
    for t in $TABLE_LIST; do
        s="${SRC_COUNT[$t]}"
        d=$("$@" "$t")
        if [ "$s" != "$d" ]; then printf '[scale]   [%s] MISMATCH %s: src=%s dst=%s\n' "$label" "$t" "$s" "$d"; mismatch=1; fi
    done
    [ "$mismatch" = "0" ] || fail "[$label] row-count parity violated"
    log "[$label] row-count parity OK (all 6 tables == source == baseline)"
}
my_count() { my_val_db employees_my_target "SELECT count(*) FROM \`$1\`;"; }
pg_count() { pg_val_db employees_pg_target "SELECT count(*) FROM \"$1\""; }

verify_parity my my_count
verify_parity pg pg_count

# Checksumme: SUM(salary) muss exakt round-trippen (Daten-Integrität bei Volumen).
my_sum=$(my_val_db employees_my_target "SELECT SUM(salary) FROM salaries;")
pg_sum=$(pg_val_db employees_pg_target "SELECT SUM(salary) FROM salaries")
[ "$my_sum" = "$SRC_SALARY_SUM" ] || fail "[my] SUM(salary) mismatch: src=$SRC_SALARY_SUM dst=$my_sum"
[ "$pg_sum" = "$SRC_SALARY_SUM" ] || fail "[pg] SUM(salary) mismatch: src=$SRC_SALARY_SUM dst=$pg_sum"
log "checksum OK: SUM(salary)=$SRC_SALARY_SUM round-trips to MySQL and PG targets"

log "SUCCESS — Employees scale smoke passed (export-resume + chunking + dual-target parity)."
log "stack is up; clean up with 'make sample-db-down' or 'make sample-db-purge'."
