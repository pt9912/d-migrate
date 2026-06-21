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
#   3. Cross-Dialect PG↔MySQL (VA2-X1): SRID 4326 mit asymmetrischen Koordinaten;
#      SEMANTISCHER Vergleich (ST_Longitude/ST_Latitude bzw. ST_X/ST_Y), weil ein
#      Achsentausch bei gleicher ST_AsText-Ausgabe sonst False-Green bliebe. Beleg,
#      dass `axis-order=long-lat` (MySQL Read+Bind) WKB OGC-konform hält. Zusätzlich
#      projizierte/kartesische SRS EPSG:25832 (ETRS89/UTM32N, Rechtswert/Hochwert)
#      und EPSG:3857 (Web Mercator): kein Achsenproblem (E,N=X,Y), axis-order no-op.
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

# VA2-X1 (projizierte SRS): Cross-Dialect-Round-Trip für eine projizierte/kartesische
# SRS (X/Y = Rechtswert/Hochwert in Metern). Anders als bei geografischen SRS (4326)
# gibt es hier keine lat-long-vs-long-lat-Frage — beide Dialekte nutzen (E,N)=(X,Y),
# und `axis-order=long-lat` ist ein no-op. Der Test belegt, dass Transfer + Bind die
# Koordinaten weder vertauschen noch verfälschen (semantisch via ST_X/ST_Y).
# Args: <srid> <x_rechtswert> <y_hochwert> (ganze Zahlen → exakt in double).
xd_projected_roundtrip() {
    local srid="$1" x="$2" y="$3" tbl="geo$1" r
    for db in geo_pg_src geo_pg_target; do
        psql_pg "$db" -c "DROP TABLE IF EXISTS $tbl; CREATE TABLE $tbl (id int PRIMARY KEY, g geometry(Point,$srid));" >/dev/null
    done
    for db in geo_my_src geo_my_target; do
        mysql_root "$db" -e "DROP TABLE IF EXISTS $tbl; CREATE TABLE $tbl (id INT PRIMARY KEY, g POINT SRID $srid NOT NULL);" \
            || fail "[xd] create $tbl in $db failed"
    done
    # PG → MySQL
    psql_pg geo_pg_src -c "INSERT INTO $tbl VALUES (1, ST_SetSRID(ST_MakePoint($x,$y),$srid));" >/dev/null
    $COMPOSE run --rm dmigrate data transfer --source postgis_geo_src --target geo_my_target \
        --tables "$tbl" --truncate >"/tmp/spatial-xd-$srid-pg2my.log" 2>&1 \
        || { cat "/tmp/spatial-xd-$srid-pg2my.log"; fail "[xd] PG→MySQL $srid transfer failed"; }
    r=$(my_val geo_my_target "SELECT ST_X(g)=$x AND ST_Y(g)=$y AND ST_SRID(g)=$srid FROM $tbl WHERE id=1;")
    [ "$r" = "1" ] || fail "[xd] PG→MySQL $srid wrong: got x=$(my_val geo_my_target "SELECT ST_X(g) FROM $tbl WHERE id=1;") y=$(my_val geo_my_target "SELECT ST_Y(g) FROM $tbl WHERE id=1;") (expected x=$x y=$y)"
    # MySQL → PG
    mysql_root geo_my_src -e "INSERT INTO $tbl VALUES (1, ST_GeomFromText('POINT($x $y)',$srid));" \
        || fail "[xd] MySQL insert $srid failed"
    $COMPOSE run --rm dmigrate data transfer --source geo_my_src --target postgis_geo_target \
        --tables "$tbl" --truncate >"/tmp/spatial-xd-$srid-my2pg.log" 2>&1 \
        || { cat "/tmp/spatial-xd-$srid-my2pg.log"; fail "[xd] MySQL→PG $srid transfer failed"; }
    r=$(pg_val geo_pg_target "SELECT ST_X(g)=$x AND ST_Y(g)=$y AND ST_SRID(g)=$srid FROM $tbl WHERE id=1")
    [ "$r" = "t" ] || fail "[xd] MySQL→PG $srid wrong: got x=$(pg_val geo_pg_target "SELECT ST_X(g) FROM $tbl WHERE id=1") y=$(pg_val geo_pg_target "SELECT ST_Y(g) FROM $tbl WHERE id=1") (expected x=$x y=$y)"
    log "[xd] $srid (projiziert/kartesisch) OK — X/Rechtswert=$x, Y/Hochwert=$y erhalten beidseitig, kein Achsentausch"
}

log "starting postgis + mysql..."
$COMPOSE up -d postgis mysql
wait_healthy postgis 120
wait_healthy mysql 180

# Geometrie-Sample (WKT) — bewusst exakt darstellbare Integer-Koordinaten.
PT="POINT(1 2)"
POLY="POLYGON((0 0,4 0,4 4,0 4,0 0))"

# VA2-X1 (Cross-Dialect-Achsen): asymmetrische, exakt-in-double darstellbare
# Koordinaten (11.5 = 23/2, 48.25 = 193/4), damit ein Achsentausch sichtbar wird
# und Float-Stringvergleiche entfallen. München-artig: long=11.5, lat=48.25.
X_LONG="11.5"; X_LAT="48.25"

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

# ─── 3. Cross-Dialect SRID 4326 (VA2-X1: Achsenreihenfolge) ─────────
# PostGIS schreibt/liest WKB in OGC-X/Y (long-lat); MySQL nutzt für 4326 ohne
# Korrektur lat-long → ein Cross-Dialect-Transfer vertauschte sonst die Achsen,
# bei *gleicher* ST_AsText-Ausgabe (False-Green). Daher SEMANTISCHER Vergleich
# über ST_Longitude/ST_Latitude bzw. ST_X/ST_Y. `--tables geo4326` grenzt auf die
# eine SRID-Tabelle ein (sonst zöge der Whole-Schema-Transfer pgnative/
# spatial_ref_sys mit, die im jeweils anderen Dialekt fehlen).

log "[xd] PG→MySQL: long=$X_LONG lat=$X_LAT (München-artig)..."
psql_pg geo_pg_src -c "TRUNCATE geo4326;
    INSERT INTO geo4326 VALUES (1, ST_SetSRID(ST_MakePoint($X_LONG,$X_LAT),4326));" > /dev/null
$COMPOSE run --rm dmigrate data transfer --source postgis_geo_src --target geo_my_target \
    --tables geo4326 --truncate > /tmp/spatial-xd-pg2my.log 2>&1 \
    || { cat /tmp/spatial-xd-pg2my.log; fail "[xd] PG→MySQL transfer failed"; }
xd1=$(my_val geo_my_target "SELECT ROUND(ST_Longitude(g),4)=$X_LONG AND ROUND(ST_Latitude(g),4)=$X_LAT AND ST_SRID(g)=4326 FROM geo4326 WHERE id=1;")
[ "$xd1" = "1" ] || fail "[xd] PG→MySQL axis/SRID wrong: got long=$(my_val geo_my_target "SELECT ST_Longitude(g) FROM geo4326 WHERE id=1;") lat=$(my_val geo_my_target "SELECT ST_Latitude(g) FROM geo4326 WHERE id=1;") (expected long=$X_LONG lat=$X_LAT)"
log "[xd] PG→MySQL OK — long=$X_LONG/lat=$X_LAT erhalten, keine Achsenvertauschung"

log "[xd] MySQL→PG: lat=$X_LAT long=$X_LONG (MySQL lat-long order)..."
mysql_root geo_my_src -e "TRUNCATE geo4326;
    INSERT INTO geo4326 VALUES (1, ST_GeomFromText('POINT($X_LAT $X_LONG)',4326));" || fail "[xd] MySQL insert failed"
$COMPOSE run --rm dmigrate data transfer --source geo_my_src --target postgis_geo_target \
    --tables geo4326 --truncate > /tmp/spatial-xd-my2pg.log 2>&1 \
    || { cat /tmp/spatial-xd-my2pg.log; fail "[xd] MySQL→PG transfer failed"; }
xd2=$(pg_val geo_pg_target "SELECT round(ST_X(g)::numeric,4)=$X_LONG AND round(ST_Y(g)::numeric,4)=$X_LAT AND ST_SRID(g)=4326 FROM geo4326 WHERE id=1")
[ "$xd2" = "t" ] || fail "[xd] MySQL→PG axis/SRID wrong: got x_long=$(pg_val geo_pg_target "SELECT ST_X(g) FROM geo4326 WHERE id=1") y_lat=$(pg_val geo_pg_target "SELECT ST_Y(g) FROM geo4326 WHERE id=1") (expected long=$X_LONG lat=$X_LAT)"
log "[xd] MySQL→PG OK — x/long=$X_LONG, y/lat=$X_LAT erhalten, keine Achsenvertauschung"

# Projizierte/kartesische SRS (Rechtswert/Hochwert). Beide Richtungen, semantisch.
xd_projected_roundtrip 25832 691000 5334000   # ETRS89/UTM32N (München, Meter)
xd_projected_roundtrip 3857  1283000 6126000  # WGS84 Web Mercator (München, Meter)

log "SUCCESS — VA1+VA2+VA2-X1 live-verified: geometry value + SRID round-trip PG→PG, MySQL→MySQL UND cross-dialect PG↔MySQL (geografisch SRID 4326 mit korrekter Achsenreihenfolge + projiziert EPSG:25832/3857 Rechtswert/Hochwert); native PG point unaffected (R1)."
log "stack is up; clean up with 'make sample-db-down' or 'make sample-db-purge'."
