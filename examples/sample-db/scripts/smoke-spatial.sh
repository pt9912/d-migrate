#!/usr/bin/env bash
# Sample-DB-Harness — VA1+VA2-Live-Smoke (Spatial, vorgezogenes 5a/5b)
# Plan: docs/planning/in-progress/spatial-harness-slice.md
# ADR:  docs/adr/0014-sample-db-harness-fetch-and-compose.md
#
# Verifiziert die Spatial-Kette (Geometrie-WERT- + SRID-Transfer) LIVE gegen echtes
# PostGIS und echtes MySQL — das, was die Unit-/SQLite-Tests strukturell NICHT
# können (ST_AsBinary→WKB→ST_GeomFromWKB, setBytes-Bind, getColumnTypeName):
#
#   1. PG→PG (PostGIS): Point + Polygon round-trippen via `data transfer`;
#      Wert-Gleichheit über ST_AsText + ST_Equals. In derselben Quelle eine
#      NATIVE PG-`point`-Spalte (Gegenprobe R1): der Transfer darf NICHT
#      fehlschlagen — native point ist kein WKB und darf nicht ST_AsBinary-gewrappt
#      werden. VA2: zusätzlich eine geometry(Point,4326)-Spalte — SRID muss erhalten
#      bleiben (Bind als ST_GeomFromWKB(?, 4326), sonst typmod-Reject).
#   2. MySQL→MySQL (native): dito; VA2 mit POINT SRID 4326 (sonst ER_WRONG_SRID).
#
# Kein externes Sample (winzige WKT-Inserts inline) — bewusst minimal; das volle
# 5a/5b mit gepinntem Spatial-Sample (VA5) folgt. Voraussetzung am Host: docker,
# docker compose, lokales d-migrate:dev mit VA1+VA2 (`make docker-build IMAGE_TAG=dev`).

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
EXAMPLES_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
COMPOSE="docker compose -f $EXAMPLES_DIR/docker-compose.yml"

log()  { printf '[spatial] %s\n' "$*"; }
fail() { printf '[spatial] FAIL: %s\n' "$*" >&2; exit 1; }

# --- 0. .env + dmigrate-Host-User ----------------------------------
if [ ! -f "$EXAMPLES_DIR/.env" ]; then
    cp "$EXAMPLES_DIR/.env.example" "$EXAMPLES_DIR/.env"
    log "created examples/sample-db/.env from .env.example"
fi
# shellcheck disable=SC1091
set -a; . "$EXAMPLES_DIR/.env"; set +a
: "${POSTGRES_USER:?POSTGRES_USER not set}"
: "${MYSQL_ROOT_PASSWORD:?MYSQL_ROOT_PASSWORD not set}"
# Fallback, falls eine ältere .env den neuen Port-Var nicht trägt (compose braucht
# einen gültigen Wert; intern wird ohnehin Compose-DNS postgis:5432 genutzt).
export SAMPLE_DB_POSTGIS_PORT="${SAMPLE_DB_POSTGIS_PORT:-55434}"
export SAMPLE_DB_DMIGRATE_USER="$(id -u):$(id -g)"

psql_pg() { $COMPOSE exec -T postgis psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$1" "${@:2}"; }
pg_val()  { $COMPOSE exec -T postgis psql -U "$POSTGRES_USER" -d "$1" -tAc "$2" 2>/dev/null | tr -d '\r'; }
mysql_root() { $COMPOSE exec -T mysql mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -N "$@" 2>/dev/null; }
my_val()  { mysql_root "$1" -e "$2" | tr -d '\r'; }

wait_healthy() {
    local svc="$1" to="$2" deadline st
    deadline=$(( $(date +%s) + to ))
    while [ "$(date +%s)" -lt "$deadline" ]; do
        st=$(docker inspect --format '{{.State.Health.Status}}' "$($COMPOSE ps -q "$svc")" 2>/dev/null || echo "?")
        [ "$st" = "healthy" ] && { log "$svc healthy"; return 0; }
        sleep 3
    done
    fail "$svc did not reach healthy state within ${to}s"
}

log "starting postgis + mysql..."
$COMPOSE up -d postgis mysql
wait_healthy postgis 120
wait_healthy mysql 180

# Geometrie-Sample (WKT) — bewusst exakt darstellbare Integer-Koordinaten.
PT="POINT(1 2)"
POLY="POLYGON((0 0,4 0,4 4,0 4,0 0))"

# ─── 1. PG→PG (PostGIS) ────────────────────────────────────────────
log "[pg] preparing PostGIS source + target (geometry + native point)..."
for db in geo_pg_src geo_pg_target; do
    psql_pg "$db" -c "CREATE EXTENSION IF NOT EXISTS postgis" > /dev/null
    # Selbstheilung: Reststände aus früheren Läufen droppen, sonst bricht der
    # Whole-Schema-Transfer an einer quell-only Tabelle ab. PostGIS-Systemtabelle
    # spatial_ref_sys bleibt erhalten (in beiden DBs vorhanden, transfer-neutral).
    psql_pg "$db" -tAc "SELECT 'DROP TABLE IF EXISTS \"'||tablename||'\" CASCADE;'
        FROM pg_tables WHERE schemaname='public' AND tablename <> 'spatial_ref_sys'" \
        | psql_pg "$db" > /dev/null
    psql_pg "$db" -c "CREATE TABLE geo (id int PRIMARY KEY, name text, g geometry);" > /dev/null
    # R1-Gegenprobe: NATIVE PG point (kein PostGIS-WKB) in derselben Quelle/Ziel.
    psql_pg "$db" -c "DROP TABLE IF EXISTS pgnative; CREATE TABLE pgnative (id int PRIMARY KEY, p point);" > /dev/null
    # VA2: SRID-4326-constrained Ziel — der Bind MUSS ST_GeomFromWKB(?, 4326) sein,
    # ein SRID-0-WKB-Insert würde an der typmod-Prüfung scheitern.
    psql_pg "$db" -c "DROP TABLE IF EXISTS geo4326; CREATE TABLE geo4326 (id int PRIMARY KEY, g geometry(Point,4326));" > /dev/null
done
psql_pg geo_pg_src -c "INSERT INTO geo VALUES
    (1,'pt', ST_GeomFromText('$PT')),
    (2,'poly', ST_GeomFromText('$POLY'));" > /dev/null
psql_pg geo_pg_src -c "INSERT INTO pgnative VALUES (1,'(5,6)'),(2,'(7,8)');" > /dev/null
psql_pg geo_pg_src -c "INSERT INTO geo4326 VALUES (1, ST_SetSRID(ST_MakePoint(1,2),4326));" > /dev/null

log "[pg] data transfer postgis_geo_src -> postgis_geo_target (incl. native point)..."
$COMPOSE run --rm dmigrate data transfer --source postgis_geo_src --target postgis_geo_target --truncate \
    > /tmp/spatial-pg.log 2>&1 || { cat /tmp/spatial-pg.log; fail "[pg] transfer failed (R1: native point may have been ST_AsBinary-wrapped?)"; }
grep -q "Transfer complete" /tmp/spatial-pg.log || { cat /tmp/spatial-pg.log; fail "[pg] transfer did not complete"; }

# Geometrie-Wert round-trippt (WKT-Gleichheit). Zusätzlich pro Zeile prüfen, dass
# das Ziel eine gültige, nicht-leere Geometrie trägt (ST_IsValid auf bytea-Müll
# wäre false/Fehler) — fängt einen kaputten WKB-Bind ab.
for id in 1 2; do
    s=$(pg_val geo_pg_src "SELECT ST_AsText(g) FROM geo WHERE id=$id")
    d=$(pg_val geo_pg_target "SELECT ST_AsText(g) FROM geo WHERE id=$id")
    [ -n "$d" ] || fail "[pg] target geometry id=$id is NULL/empty (WKB transfer broken)"
    [ "$s" = "$d" ] || fail "[pg] geometry mismatch id=$id: src='$s' dst='$d'"
    valid=$(pg_val geo_pg_target "SELECT ST_IsValid(g) FROM geo WHERE id=$id")
    [ "$valid" = "t" ] || fail "[pg] target geometry id=$id is not valid (got ST_IsValid=$valid)"
done
log "[pg] geometry round-trip OK (id1=$(pg_val geo_pg_target "SELECT ST_AsText(g) FROM geo WHERE id=1"), id2 polygon)"

# R1: native point round-trippt als gewöhnlicher Wert (NICHT als WKB gewrappt).
np_s=$(pg_val geo_pg_src "SELECT p::text FROM pgnative WHERE id=1")
np_d=$(pg_val geo_pg_target "SELECT p::text FROM pgnative WHERE id=1")
[ "$np_s" = "$np_d" ] && [ -n "$np_d" ] || fail "[pg] native point mismatch: src='$np_s' dst='$np_d'"
log "[pg] native PG point round-trip OK ('$np_d') — R1 confirmed: not ST_AsBinary-wrapped"

# VA2: SRID 4326 muss erhalten bleiben. Das Ziel ist auf geometry(Point,4326)
# typmod-beschränkt; gelänge der Insert nur mit SRID 0 (alter Bind), wäre er hier
# bereits am Transfer gescheitert. Zusätzlich Wert- und SRID-Gleichheit prüfen.
srid_s=$(pg_val geo_pg_src "SELECT ST_SRID(g) FROM geo4326 WHERE id=1")
srid_d=$(pg_val geo_pg_target "SELECT ST_SRID(g) FROM geo4326 WHERE id=1")
[ "$srid_d" = "4326" ] || fail "[pg] SRID not preserved: src=$srid_s dst=$srid_d (expected 4326)"
v_s=$(pg_val geo_pg_src "SELECT ST_AsText(g) FROM geo4326 WHERE id=1")
v_d=$(pg_val geo_pg_target "SELECT ST_AsText(g) FROM geo4326 WHERE id=1")
[ "$v_s" = "$v_d" ] || fail "[pg] SRID geometry value mismatch: src='$v_s' dst='$v_d'"
log "[pg] SRID 4326 round-trip OK (value='$v_d', SRID=$srid_d) — VA2 confirmed"

# ─── 2. MySQL→MySQL (native) ───────────────────────────────────────
log "[my] preparing MySQL source + target..."
mysql_root -e "
  DROP DATABASE IF EXISTS geo_my_src;   CREATE DATABASE geo_my_src   CHARACTER SET utf8mb4;
  DROP DATABASE IF EXISTS geo_my_target;CREATE DATABASE geo_my_target CHARACTER SET utf8mb4;
  GRANT ALL PRIVILEGES ON geo_my_src.*    TO '${MYSQL_USER}'@'%';
  GRANT ALL PRIVILEGES ON geo_my_target.* TO '${MYSQL_USER}'@'%';
  FLUSH PRIVILEGES;" || fail "[my] db setup failed"
for db in geo_my_src geo_my_target; do
    mysql_root "$db" -e "CREATE TABLE geo (id INT PRIMARY KEY, name VARCHAR(16), g GEOMETRY NOT NULL);" || fail "[my] create geo failed"
    # VA2: SRID-4326-constrained Ziel — der Bind MUSS ST_GeomFromWKB(?, 4326) sein;
    # ein SRID-0-WKB-Insert in eine SRID-4326-Spalte lehnt MySQL ab (ER_WRONG_SRID).
    mysql_root "$db" -e "CREATE TABLE geo4326 (id INT PRIMARY KEY, g POINT SRID 4326 NOT NULL);" || fail "[my] create geo4326 failed"
done
mysql_root geo_my_src -e "INSERT INTO geo VALUES
    (1,'pt', ST_GeomFromText('$PT')),
    (2,'poly', ST_GeomFromText('$POLY'));" || fail "[my] insert failed"
mysql_root geo_my_src -e "INSERT INTO geo4326 VALUES (1, ST_GeomFromText('$PT', 4326));" || fail "[my] insert geo4326 failed"

log "[my] data transfer geo_my_src -> geo_my_target..."
$COMPOSE run --rm dmigrate data transfer --source geo_my_src --target geo_my_target --truncate \
    > /tmp/spatial-my.log 2>&1 || { cat /tmp/spatial-my.log; fail "[my] transfer failed"; }
grep -q "Transfer complete" /tmp/spatial-my.log || { cat /tmp/spatial-my.log; fail "[my] transfer did not complete"; }

for id in 1 2; do
    s=$(my_val geo_my_src "SELECT ST_AsText(g) FROM geo WHERE id=$id;")
    d=$(my_val geo_my_target "SELECT ST_AsText(g) FROM geo WHERE id=$id;")
    [ -n "$d" ] || fail "[my] target geometry id=$id is NULL/empty (WKB transfer broken)"
    [ "$s" = "$d" ] || fail "[my] geometry mismatch id=$id: src='$s' dst='$d'"
done
log "[my] geometry round-trip OK (id1=$(my_val geo_my_target "SELECT ST_AsText(g) FROM geo WHERE id=1;"), id2 polygon)"

# VA2: SRID 4326 muss erhalten bleiben (Spalte ist POINT SRID 4326; ein SRID-0-Bind
# wäre bereits am Transfer mit ER_WRONG_SRID gescheitert).
msrid_d=$(my_val geo_my_target "SELECT ST_SRID(g) FROM geo4326 WHERE id=1;")
[ "$msrid_d" = "4326" ] || fail "[my] SRID not preserved: dst=$msrid_d (expected 4326)"
mv_s=$(my_val geo_my_src "SELECT ST_AsText(g) FROM geo4326 WHERE id=1;")
mv_d=$(my_val geo_my_target "SELECT ST_AsText(g) FROM geo4326 WHERE id=1;")
[ "$mv_s" = "$mv_d" ] || fail "[my] SRID geometry value mismatch: src='$mv_s' dst='$mv_d'"
log "[my] SRID 4326 round-trip OK (value='$mv_d', SRID=$msrid_d) — VA2 confirmed"

log "SUCCESS — VA1+VA2 live-verified: geometry value + SRID round-trip PG→PG and MySQL→MySQL; native PG point unaffected (R1)."
log "stack is up; clean up with 'make sample-db-down' or 'make sample-db-purge'."
