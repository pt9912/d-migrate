#!/usr/bin/env bash
# Sample-DB-Harness — Spatial-Leg Oracle: SRID-Transfer PostGIS -> Oracle
# ADR:  docs/adr/0052-oracle-fuenfter-dialekt-scoping.md,
#       docs/adr/0014-sample-db-harness-fetch-and-compose.md
#
# Die Gegenprobe zu smoke-spatial.sh, dort wo das Ziel die SRID nicht tragen
# kann: Oracle fuehrt sie nicht auf der Spalte, sondern in
# USER_SDO_GEOM_METADATA -- und hebt Tabellen- und Spaltennamen in dieser
# Zeile bedingungslos hoch, weshalb sie eine quoted-lowercase Tabelle gar
# nicht beschreiben kann. Die Angabe kommt deshalb aus dem QUELLschema, das
# `data transfer` ohnehin liest, und tritt beim Binden in den
# SDO_UTIL.FROM_WKBGEOMETRY-Aufruf ein.
#
#   geo_ora_src in PostGIS saeen (geometry(Point,4326) + eine SRID-lose Spalte)
#   -> reverse -> generate --target oracle -> pre-data per sqlplus anwenden
#   -> data transfer PG->Oracle -> SDO_SRID + Koordinaten am Ziel pruefen.
#
# Gepinnt:
#   - SDO_SRID am Ziel == SRID der Quelle (4326), nicht NULL
#   - Koordinaten unveraendert (kein Achsentausch, keine Verfaelschung)
#   - eine Spalte OHNE Quell-SRID bleibt am Ziel ohne SRID (kein Default)
#
# Voraussetzung am Host: docker, docker compose, lokal gebautes d-migrate:dev.
# Eigener Oracle-Dienst (oracle-spatial): das schlanke Image des oracle-
# Service kennt SDO_GEOMETRY nicht. Kaltstart ~2-3 Minuten, ~6 GB Image --
# deshalb ist dieses Leg nicht Teil des Standard-Spatial-Smokes.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
EXAMPLES_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
COMPOSE="docker compose -f $EXAMPLES_DIR/docker-compose.yml"
OUT_DIR="$EXAMPLES_DIR/out"

log()  { printf '[spatial-ora] %s\n' "$*"; }
fail() { printf '[spatial-ora] FAIL: %s\n' "$*" >&2; exit 1; }

# --- 0. .env + Verzeichnisse ---------------------------------------
mkdir -p "$OUT_DIR" "$EXAMPLES_DIR/.cache"
if [ ! -f "$EXAMPLES_DIR/.env" ]; then
    cp "$EXAMPLES_DIR/.env.example" "$EXAMPLES_DIR/.env"
    log "created examples/sample-db/.env from .env.example"
else
    # Ein `.env` aus einem frueheren Lauf kennt SAMPLE_DB_ORACLE_SPATIAL_PORT
    # nicht. Fehlende Schluessel ergaenzen, vorhandene Werte unangetastet lassen.
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
: "${APP_USER:?APP_USER not set (add it to examples/sample-db/.env)}"
: "${APP_USER_PASSWORD:?APP_USER_PASSWORD not set (add it to examples/sample-db/.env)}"
export SAMPLE_DB_POSTGIS_PORT="${SAMPLE_DB_POSTGIS_PORT:-55434}"
export SAMPLE_DB_ORACLE_SPATIAL_PORT="${SAMPLE_DB_ORACLE_SPATIAL_PORT:-15212}"
export SAMPLE_DB_DMIGRATE_USER="$(id -u):$(id -g)"

psql_pg() { $COMPOSE exec -T postgis psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$1" "${@:2}"; }
pg_val()  { $COMPOSE exec -T postgis psql -U "$POSTGRES_USER" -d "$1" -tAc "$2" </dev/null 2>/dev/null | tr -d '[:space:]'; }

# sqlplus im gvenzl-Image liegt auf dem PATH ($ORACLE_HOME/bin). -S still,
# -L kein Retry bei falschem Login, WHENEVER SQLERROR EXIT: sonst meldet
# sqlplus einen Fehler nur auf stdout und beendet trotzdem mit 0.
ora_run() {  # ora_run <sql-text>
    $COMPOSE exec -T oracle-spatial sqlplus -S -L "${APP_USER}/${APP_USER_PASSWORD}@//localhost:1521/FREEPDB1" <<SQL
WHENEVER SQLERROR EXIT SQL.SQLCODE
SET HEADING OFF FEEDBACK OFF PAGESIZE 0 LINESIZE 32767 TRIMSPOOL ON
$1
EXIT
SQL
}
ora_val()  { ora_run "$1" </dev/null 2>/dev/null | tr -d '[:space:]'; }
ora_exec() { ora_run "$1" </dev/null | tr -d '[:space:]'; }

wait_healthy() {  # wait_healthy <service> <timeout_s>
    local svc="$1" to="$2" deadline st
    deadline=$(( $(date +%s) + to ))
    while [ "$(date +%s)" -lt "$deadline" ]; do
        st=$(docker inspect --format '{{.State.Health.Status}}' "$($COMPOSE ps -q "$svc")" 2>/dev/null || echo "?")
        [ "$st" = "healthy" ] && { log "$svc healthy"; return 0; }
        sleep 5
    done
    fail "$svc did not reach healthy state within ${to}s"
}

# --- 1. Dienste hochfahren -----------------------------------------
log "starting postgis + oracle-spatial (full image, cold start takes a few minutes)..."
$COMPOSE up -d postgis oracle-spatial
wait_healthy postgis 120
wait_healthy oracle-spatial 600

# Belegt, dass dieses Image Spatial wirklich mitbringt -- sonst schluege
# erst das Anwenden des DDL fehl, mit einer Meldung ueber einen unbekannten
# Typ statt ueber das falsche Image.
sdo=$(ora_val "SELECT COUNT(*) FROM all_types WHERE type_name = 'SDO_GEOMETRY';")
[ "$sdo" = "1" ] || fail "oracle-spatial image does not carry Oracle Spatial (SDO_GEOMETRY not found) — wrong image pinned?"
log "Oracle Spatial present"

# --- 2. Quelle saeen ------------------------------------------------
log "seeding geo_ora_src in PostGIS..."
psql_pg postgres -c "DROP DATABASE IF EXISTS geo_ora_src" -c "CREATE DATABASE geo_ora_src" > /dev/null \
    || fail "could not (re)create geo_ora_src"
psql_pg geo_ora_src -c "CREATE EXTENSION IF NOT EXISTS postgis" > /dev/null
# `plain` traegt bewusst KEINE SRID: sie ist die Gegenprobe dazu, dass der
# Transfer nicht pauschal eine SRID setzt, sondern nur die der Quelle.
psql_pg geo_ora_src -c "CREATE TABLE places (
    id    int PRIMARY KEY,
    label varchar(50) NOT NULL,
    geom  geometry(Point,4326),
    plain geometry
);" > /dev/null
# Zeile 3 ohne Geometrie: die NULL-Bindung laeuft ueber einen anderen Zweig
# als der WKB-Wert und darf nicht mitscheitern.
psql_pg geo_ora_src -c "INSERT INTO places VALUES
    (1,'Bremen', ST_SetSRID(ST_MakePoint(8.8017,53.0793),4326), ST_MakePoint(1,2)),
    (2,'Kassel', ST_SetSRID(ST_MakePoint(9.4797,51.3127),4326), ST_MakePoint(3,4)),
    (3,'ohne',   NULL,                                          NULL);" > /dev/null

# --- 3. reverse + generate ueber das IMAGE --------------------------
log "schema reverse postgis_geo_ora_src..."
$COMPOSE run --rm dmigrate schema reverse --source postgis_geo_ora_src \
    --output /work/out/geo-ora.reverse.yaml > /tmp/spatial-ora-reverse.log 2>&1 \
    || { cat /tmp/spatial-ora-reverse.log; fail "reverse failed"; }
grep -q "4326" "$OUT_DIR/geo-ora.reverse.yaml" \
    || fail "reversed schema does not carry the source SRID — the transfer would have nothing to pass on"

log "schema generate --target oracle..."
$COMPOSE run --rm dmigrate schema generate --source /work/out/geo-ora.reverse.yaml \
    --target oracle --split pre-post --deterministic \
    --output /work/out/geo-ora.sql > /tmp/spatial-ora-generate.log 2>&1 \
    || { cat /tmp/spatial-ora-generate.log; fail "generate failed"; }
grep -q "SDO_GEOMETRY" "$OUT_DIR/geo-ora.pre-data.sql" \
    || fail "generated DDL carries no SDO_GEOMETRY column"
# Die Zielspalte ist typlos, was die SRID angeht -- genau der Verlust, den
# der Transfer aus dem Quellschema ausgleicht. Das Generate sagt es an.
grep -q "W120" "$OUT_DIR/geo-ora.pre-data.sql" \
    || fail "generated DDL does not warn (W120) that Oracle cannot carry the SRID on the column"

# --- 4. Ziel aufbauen (sqlplus) ------------------------------------
log "clearing the ${APP_USER} schema..."
ora_exec "BEGIN
  FOR r IN (SELECT table_name FROM user_tables) LOOP
    EXECUTE IMMEDIATE 'DROP TABLE \"' || r.table_name || '\" CASCADE CONSTRAINTS PURGE';
  END LOOP;
END;
/" > /dev/null || fail "could not clear the ${APP_USER} schema"

log "applying pre-data DDL via sqlplus..."
$COMPOSE exec -T oracle-spatial sqlplus -S -L "${APP_USER}/${APP_USER_PASSWORD}@//localhost:1521/FREEPDB1" \
    < "$OUT_DIR/geo-ora.pre-data.sql" > /tmp/spatial-ora-apply.log 2>&1 \
    || { tail -40 /tmp/spatial-ora-apply.log; fail "pre-data apply via sqlplus failed"; }
# sqlplus meldet Fehler auf stdout und beendet trotzdem mit 0, solange das
# Skript kein WHENEVER SQLERROR setzt -- das erzeugte tut es nicht.
if grep -qE '^ORA-[0-9]+|^SP2-[0-9]+' /tmp/spatial-ora-apply.log; then
    grep -E '^ORA-[0-9]+|^SP2-[0-9]+' /tmp/spatial-ora-apply.log | sort -u | head -20
    fail "pre-data apply reported Oracle errors (see /tmp/spatial-ora-apply.log)"
fi
log "  pre-data applied"

# --- 5. data transfer ueber das IMAGE ------------------------------
log "data transfer postgis_geo_ora_src -> ora_geo_target..."
$COMPOSE run --rm dmigrate data transfer --source postgis_geo_ora_src --target ora_geo_target \
    --tables places --truncate > /tmp/spatial-ora-transfer.log 2>&1 \
    || { cat /tmp/spatial-ora-transfer.log; fail "transfer failed"; }
grep -q "Transfer complete" /tmp/spatial-ora-transfer.log \
    || { cat /tmp/spatial-ora-transfer.log; fail "transfer did not complete"; }

# --- 6. SRID + Koordinaten am Ziel ---------------------------------
# Bezeichner quoted-lowercase, wie OracleDdlGenerator sie anlegt; ein
# unquoted Verweis faltete auf GROSSSCHREIBUNG und schluege mit ORA-00942 fehl.
rows=$(ora_val "SELECT COUNT(*) FROM \"places\";")
[ "$rows" = "3" ] || fail "expected 3 target rows, got '$rows'"

for id in 1 2; do
    src_srid=$(pg_val geo_ora_src "SELECT ST_SRID(geom) FROM places WHERE id=$id")
    tgt_srid=$(ora_val "SELECT t.\"geom\".SDO_SRID FROM \"places\" t WHERE t.\"id\" = $id;")
    [ -n "$tgt_srid" ] || fail "id=$id: target SDO_SRID is NULL — the source SRID was lost in transfer"
    [ "$tgt_srid" = "$src_srid" ] \
        || fail "id=$id: SRID mismatch — source=$src_srid target=$tgt_srid"
    # Ein Achsentausch bliebe bei reiner SRID-Pruefung unsichtbar.
    src_x=$(pg_val geo_ora_src "SELECT ROUND(ST_X(geom)::numeric,4) FROM places WHERE id=$id")
    tgt_x=$(ora_val "SELECT TO_CHAR(ROUND(t.\"geom\".SDO_POINT.X, 4)) FROM \"places\" t WHERE t.\"id\" = $id;")
    [ "$tgt_x" = "$src_x" ] || fail "id=$id: X mismatch — source=$src_x target=$tgt_x (axes swapped?)"
done
log "SRID + coordinates preserved for both geometries (SRID $(ora_val "SELECT t.\"geom\".SDO_SRID FROM \"places\" t WHERE t.\"id\" = 1;"))"

# Gegenprobe: die SRID-lose Quellspalte bekommt am Ziel keine zugeteilt.
plain_srid=$(ora_val "SELECT NVL(TO_CHAR(t.\"plain\".SDO_SRID), 'none') FROM \"places\" t WHERE t.\"id\" = 1;")
[ "$plain_srid" = "none" ] \
    || fail "a source column without SRID must not gain one at the target, got '$plain_srid'"

# Und die Zeile ohne Geometrie bleibt leer statt zu scheitern.
null_geom=$(ora_val "SELECT NVL2(t.\"geom\", 'set', 'null') FROM \"places\" t WHERE t.\"id\" = 3;")
[ "$null_geom" = "null" ] || fail "row 3 should carry no geometry, got '$null_geom'"

log "SUCCESS — PostGIS->Oracle spatial smoke passed (source SRID carried into a target that cannot hold one)."
