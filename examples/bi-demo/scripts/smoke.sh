#!/usr/bin/env bash
# BI-Demo End-to-End-Smoke (BD.5)
# Spec: docs/planning/done/bi-demo-compose.md §9 BD.5
#
# Faehrt den kompletten Demo-Ablauf:
#   1. .env aus .env.example anlegen (falls noch nicht da)
#   2. Compose-Stack pullen + hochfahren
#   3. State-Pinnung (postgres healthy + seaweed-init exited 0)
#   4. d-migrate-Workflow (reverse + profile + generate)
#   5. aws-tools-Upload nach s3://dmigrate-demo/runs/smoke/
#   6. S3-Verifikation
#
# Voraussetzungen am Host: docker, docker-compose, jq, sowie das
# lokal gebaute d-migrate:dev-Image (`make docker-build
# IMAGE_TAG=dev`). Der Stack bleibt nach erfolgreichem Lauf
# **stehen** — Cleanup via `make bi-demo-down` (Volumes bleiben)
# oder `make bi-demo-purge` (Komplett-Reset).

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
EXAMPLES_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
COMPOSE_FILE="$EXAMPLES_DIR/docker-compose.yml"
COMPOSE="docker compose -f $COMPOSE_FILE"

log()  { printf '[smoke] %s\n' "$*"; }
fail() { printf '[smoke] FAIL: %s\n' "$*" >&2; exit 1; }

# --- 1. Bind-Mount-Owner-Schutz + .env -----------------------------
mkdir -p "$EXAMPLES_DIR/out"
if [ ! -f "$EXAMPLES_DIR/.env" ]; then
    cp "$EXAMPLES_DIR/.env.example" "$EXAMPLES_DIR/.env"
    log "created examples/bi-demo/.env from .env.example"
fi

# --- 2. Pre-Start-Pull + up -d -------------------------------------
log "pulling images..."
$COMPOSE pull --quiet

log "starting stack..."
$COMPOSE up -d

# --- 3. State-Pinnung via jq -s ------------------------------------
log "waiting for postgres healthy + seaweed-init exited(0) (timeout 120s)..."
deadline=$(($(date +%s) + 120))
pg_ok="no"; init_ok="no"
while [ "$(date +%s)" -lt "$deadline" ]; do
    ps_json=$($COMPOSE ps --all --format json)
    if printf '%s' "$ps_json" | jq -s -e \
        'map(select(.Service == "postgres")) | .[0].Health == "healthy"' \
        > /dev/null 2>&1; then pg_ok="yes"; fi
    if printf '%s' "$ps_json" | jq -s -e \
        'map(select(.Service == "seaweed-init")) | .[0].State == "exited" and .[0].ExitCode == 0' \
        > /dev/null 2>&1; then init_ok="yes"; fi
    if [ "$pg_ok" = "yes" ] && [ "$init_ok" = "yes" ]; then break; fi
    sleep 2
done
[ "$pg_ok" = "yes" ]   || fail "postgres did not reach healthy state"
[ "$init_ok" = "yes" ] || fail "seaweed-init did not exit(0)"
log "postgres healthy + seaweed-init exited(0) OK"

# --- 4. d-migrate-Workflow -----------------------------------------
log "running d-migrate schema reverse..."
$COMPOSE run --rm dmigrate schema reverse \
    --source demo_pg_container \
    --output /work/out/reverse.yaml > /dev/null

log "running d-migrate data profile..."
$COMPOSE run --rm dmigrate data profile \
    --source demo_pg_container \
    --output /work/out/profile.json > /dev/null

log "running d-migrate schema generate..."
$COMPOSE run --rm dmigrate schema generate \
    --source /work/out/reverse.yaml \
    --target postgresql \
    --output /work/out/generated.sql > /dev/null

for f in reverse.yaml profile.json generated.sql; do
    [ -s "$EXAMPLES_DIR/out/$f" ] || fail "expected non-empty out/$f"
done
log "reverse.yaml + profile.json + generated.sql OK"

# Zusatz-Pinnung: 5 Tabellen im reverse-Output + 5 CREATE TABLE im DDL
reverse_tables=$(grep -cE '^  [a-z_]+:$' "$EXAMPLES_DIR/out/reverse.yaml")
[ "$reverse_tables" = "5" ] || fail "reverse.yaml has $reverse_tables tables (expected 5)"
ddl_tables=$(grep -c '^CREATE TABLE' "$EXAMPLES_DIR/out/generated.sql")
[ "$ddl_tables" = "5" ] || fail "generated.sql has $ddl_tables CREATE TABLE (expected 5)"
log "5 tables in reverse + 5 CREATE TABLE in DDL OK"

# --- 5. S3-Upload --------------------------------------------------
log "uploading artefacts to s3://dmigrate-demo/runs/smoke/..."
$COMPOSE run --rm aws-tools s3 cp --recursive /work/ \
    s3://dmigrate-demo/runs/smoke/ > /dev/null

# --- 6. S3-Verifikation --------------------------------------------
log "verifying S3 upload..."
s3_listing=$($COMPOSE run --rm aws-tools s3 ls --recursive \
    s3://dmigrate-demo/runs/smoke/)
for f in reverse.yaml profile.json generated.sql; do
    printf '%s' "$s3_listing" | grep -q "$f" \
        || fail "expected $f in S3 listing"
done
log "S3 upload OK"

log "SUCCESS — stack is up; clean up with 'make bi-demo-down' or 'make bi-demo-purge'"
