#!/usr/bin/env bash
# Sample-DB-Harness — VA1+VA2-Live-Smoke (Spatial, vorgezogenes 5a/5b)
# Plan: docs/planning/done/spatial-harness-slice.md
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
#   4. Spatial-Index (VA3): MySQL `SPATIAL INDEX` reverse→generate→apply; reverse
#      liefert `type: spatial`, generate emittiert MySQL `SPATIAL INDEX` und
#      cross-dialect PostGIS `USING GIST`; das angewandte MySQL-DDL erzeugt real
#      einen SPATIAL-Index (information_schema.statistics.index_type=SPATIAL).
#   5. SpatiaLite (VA4-Kern): `schema generate --spatial-profile spatialite` emittiert
#      AddGeometryColumn(SRID/Subtyp) + CreateSpatialIndex; mod_spatialite ist im Image
#      (`?spatialite=true` lädt es). Voller migrate--execute-Round-Trip = 5d-Folgearbeit.
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

# 5b-Formalisierung: nicht nur transfer, sondern der volle Schema-Tool-Pfad —
# reverse → validate → generate --spatial-profile native — belegt, dass d-migrate
# MySQL-native Geometrie-DDL (GEOMETRY/POINT + SRID) erzeugt (nicht nur Werte transferiert).
log "[my] schema reverse → validate → generate --spatial-profile native (5b)..."
$COMPOSE run --rm dmigrate schema reverse --source geo_my_src \
    --output /work/.cache/geo-my.reverse.yaml > /tmp/my-rev.log 2>&1 \
    || { cat /tmp/my-rev.log; fail "[my] reverse failed"; }
grep -q "geometry_type: point" "$EXAMPLES_DIR/.cache/geo-my.reverse.yaml" \
    || { cat "$EXAMPLES_DIR/.cache/geo-my.reverse.yaml"; fail "[my] reverse lost point geometry"; }
grep -q "srid: 4326" "$EXAMPLES_DIR/.cache/geo-my.reverse.yaml" \
    || fail "[my] reverse lost SRID 4326"
$COMPOSE run --rm dmigrate schema validate --source /work/.cache/geo-my.reverse.yaml \
    > /tmp/my-val.log 2>&1 || { cat /tmp/my-val.log; fail "[my] validate failed"; }
$COMPOSE run --rm dmigrate schema generate --source /work/.cache/geo-my.reverse.yaml \
    --target mysql --spatial-profile native --deterministic \
    --output /work/.cache/geo-my.gen.sql > /tmp/my-gen.log 2>&1 \
    || { cat /tmp/my-gen.log; fail "[my] generate failed"; }
grep -qiE "\bGEOMETRY\b|\bPOINT\b" "$EXAMPLES_DIR/.cache/geo-my.gen.sql" \
    || { cat "$EXAMPLES_DIR/.cache/geo-my.gen.sql"; fail "[my] generated DDL missing native geometry type"; }
grep -qiE "SRID 4326|/\*!80003 SRID 4326" "$EXAMPLES_DIR/.cache/geo-my.gen.sql" \
    || { cat "$EXAMPLES_DIR/.cache/geo-my.gen.sql"; fail "[my] generated DDL missing SRID 4326"; }
log "[my] generate OK — native GEOMETRY/POINT + SRID 4326 DDL (5b formalized)"

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
# Gauß-Krüger Zone 2 (DHDN): MySQLs Definition deklariert die Achsen historisch
# als AXIS["X",NORTH]/AXIS["Y",EAST] (Hochwert/Rechtswert). Empirisch dreht MySQL
# aber NUR geografische SRS — projizierte (auch GK) bleiben X/Y=(erste,zweite), also
# kein Tausch und WKB-byte-identisch zu PostGIS. Dieser Fall sichert das ab.
xd_projected_roundtrip 31466 2580000 5680000  # DHDN/GK Zone 2 (R=2580000, H=5680000)
# Hinweis: EPSG:4937 (ETRS89 3D-geographic) ist in MySQL 8.4 NICHT registriert
# (ST_SPATIAL_REFERENCE_SYSTEMS), eine POINT SRID 4937-Spalte ist nicht anlegbar →
# Cross-Dialect-Transfer nach MySQL scheitert sauber. 2D-Alternative: EPSG:4258.

# ─── 4. Spatial-Index reverse→generate→apply (VA3) ─────────────────
# Belegt VA3 gegen echte DBs: MySQL-`SPATIAL INDEX` wird reverse-t (→ type: spatial),
# nach MySQL als `SPATIAL INDEX` und cross-dialect nach PostGIS als `USING GIST`
# generiert, und das MySQL-DDL angewandt erzeugt real einen SPATIAL-Index im Katalog.
log "[idx] preparing dedicated MySQL DB with a SPATIAL INDEX..."
mysql_root -e "
  DROP DATABASE IF EXISTS va3_idx_src;    CREATE DATABASE va3_idx_src;
  DROP DATABASE IF EXISTS va3_idx_target; CREATE DATABASE va3_idx_target;
  GRANT ALL PRIVILEGES ON va3_idx_src.*    TO '${MYSQL_USER}'@'%';
  GRANT ALL PRIVILEGES ON va3_idx_target.* TO '${MYSQL_USER}'@'%';
  FLUSH PRIVILEGES;" || fail "[idx] db setup failed"
# SPATIAL INDEX erfordert eine NOT-NULL-Geometriespalte.
mysql_root va3_idx_src -e "CREATE TABLE places (id INT PRIMARY KEY, shape POINT NOT NULL, SPATIAL INDEX sidx_shape (shape));" \
    || fail "[idx] create places failed"

MYURL_SRC="mysql://${MYSQL_USER}:${MYSQL_PASSWORD}@mysql:3306/va3_idx_src"
mkdir -p "$EXAMPLES_DIR/.cache"

log "[idx] schema reverse (va3_idx_src)..."
$COMPOSE run --rm dmigrate schema reverse --source "$MYURL_SRC" --output /work/.cache/va3.yaml \
    > /tmp/va3-reverse.log 2>&1 || { cat /tmp/va3-reverse.log; fail "[idx] reverse failed"; }
grep -q "type: spatial" "$EXAMPLES_DIR/.cache/va3.yaml" \
    || { cat "$EXAMPLES_DIR/.cache/va3.yaml"; fail "[idx] reverse did not capture index as type: spatial"; }
log "[idx] reverse OK — MySQL SPATIAL index captured as 'type: spatial'"

log "[idx] schema generate → MySQL (SPATIAL INDEX) + PostgreSQL (USING GIST)..."
$COMPOSE run --rm dmigrate schema generate --source /work/.cache/va3.yaml --target mysql \
    --spatial-profile native --deterministic --output /work/.cache/va3-my.sql \
    > /tmp/va3-gen-my.log 2>&1 || { cat /tmp/va3-gen-my.log; fail "[idx] generate mysql failed"; }
grep -qi "SPATIAL INDEX" "$EXAMPLES_DIR/.cache/va3-my.sql" \
    || { cat "$EXAMPLES_DIR/.cache/va3-my.sql"; fail "[idx] MySQL DDL missing SPATIAL INDEX"; }
$COMPOSE run --rm dmigrate schema generate --source /work/.cache/va3.yaml --target postgresql \
    --spatial-profile postgis --deterministic --output /work/.cache/va3-pg.sql \
    > /tmp/va3-gen-pg.log 2>&1 || { cat /tmp/va3-gen-pg.log; fail "[idx] generate pg failed"; }
grep -qiE "USING GIST" "$EXAMPLES_DIR/.cache/va3-pg.sql" \
    || { cat "$EXAMPLES_DIR/.cache/va3-pg.sql"; fail "[idx] PG DDL missing USING GIST"; }
log "[idx] generate OK — MySQL 'SPATIAL INDEX' + cross-dialect PostGIS 'USING GIST'"

log "[idx] apply MySQL DDL → va3_idx_target, verify index_type=SPATIAL in catalog..."
mysql_root va3_idx_target < "$EXAMPLES_DIR/.cache/va3-my.sql" || fail "[idx] applying generated MySQL DDL failed"
idx_type=$(my_val va3_idx_target "SELECT DISTINCT index_type FROM information_schema.statistics WHERE table_schema='va3_idx_target' AND table_name='places' AND index_name='sidx_shape';")
[ "$idx_type" = "SPATIAL" ] || fail "[idx] applied index is not SPATIAL (got '$idx_type')"
log "[idx] apply OK — real SPATIAL index exists in target catalog — VA3 confirmed"

# ─── 5. SpatiaLite generate (VA4-Kern, SQLite) ─────────────────────
# Belegt VA4 reproduzierbar: `schema generate --spatial-profile spatialite` emittiert
# AddGeometryColumn (mit SRID/Subtyp) + CreateSpatialIndex; ohne das Profil bleibt
# der Geometrie-Index geblockt. mod_spatialite ist im dmigrate-Image installiert und
# wird per `?spatialite=true` geladen (Connection-Beleg). Der volle migrate--execute-
# Round-Trip (Index real in der .db) ist im Abschnitt [lite]-Apply unten umgesetzt
# (5d, docs/planning/done/spatialite-migrate-roundtrip.md).
log "[lite] schema generate --spatial-profile spatialite..."
cat > "$EXAMPLES_DIR/.cache/va4-schema.yaml" <<'YAML'
name: "va4 spatialite"
version: "1.0.0"
tables:
  places:
    columns:
      id: { type: identifier, auto_increment: true }
      name: { type: text, max_length: 64 }
      shape: { type: geometry, geometry_type: point, srid: 4326 }
    indices:
      - { name: idx_places_shape, columns: [shape], type: spatial }
YAML
$COMPOSE run --rm dmigrate schema generate --source /work/.cache/va4-schema.yaml \
    --target sqlite --spatial-profile spatialite --deterministic --output /work/.cache/va4-lite.sql \
    > /tmp/va4-gen.log 2>&1 || { cat /tmp/va4-gen.log; fail "[lite] generate spatialite failed"; }
grep -qi "AddGeometryColumn('places', 'shape', 4326, 'POINT'" "$EXAMPLES_DIR/.cache/va4-lite.sql" \
    || { cat "$EXAMPLES_DIR/.cache/va4-lite.sql"; fail "[lite] DDL missing AddGeometryColumn(... 4326, POINT)"; }
grep -qi "CreateSpatialIndex('places', 'shape')" "$EXAMPLES_DIR/.cache/va4-lite.sql" \
    || { cat "$EXAMPLES_DIR/.cache/va4-lite.sql"; fail "[lite] DDL missing CreateSpatialIndex"; }
log "[lite] generate OK — AddGeometryColumn(SRID 4326, POINT) + CreateSpatialIndex (VA4)"

# ─── 5d: voller SpatiaLite migrate --execute Round-Trip ────────────
# Belegt 5d gegen eine ECHTE frische SpatiaLite-.db (kein Generate-only):
#   Befund 1 — `InitSpatialMetaData()`-Bootstrap läuft vor dem ersten
#     AddGeometryColumn (sonst „unexpected metadata layout").
#   Befund 2 — der Geometrie-Index entsteht als R*Tree `CreateSpatialIndex`
#     (geometry_columns.spatial_index_enabled=1), nicht als generischer CREATE INDEX.
#   Befund 3 — Reverse rekonstruiert Geometrie+SRID+Spatial-Index und filtert ALLE
#     SpatiaLite-Metatabellen + R*Tree-Schattentabellen heraus (nur `places` bleibt).
# HINWEIS: SQLite `migrate --execute` endet hier mit Exit 5 (Post-Execute-Compare-Drift),
# weil va4-apply-schema.yaml den PK nur implizit über `identifier` trägt (KEIN explizites
# `primary_key`) und der Fingerprint diese `identifier`→`primary_key`-Äquivalenz nicht
# kanonisiert — ein PRE-EXISTING, NICHT-spatialer SQLite-Befund (mit explizitem
# `primary_key: [id]` ist es Exit 0; auch ohne Geometrie reproduzierbar,
# docs/planning/open/sqlite-migrate-postcompare-identifier-drift.md). Wir prüfen die
# Ausführung daher über den Report (status ok, kein executionError), nicht über den
# Prozess-Exit.
log "[lite] migrate --execute --spatial-profile spatialite gegen frische .db..."
rm -f "$EXAMPLES_DIR"/.cache/va4-apply.db "$EXAMPLES_DIR"/.cache/va4-apply.db-wal "$EXAMPLES_DIR"/.cache/va4-apply.db-shm
cat > "$EXAMPLES_DIR/.cache/va4-apply-schema.yaml" <<'YAML'
name: "va4 spatialite apply"
version: "1.0.0"
tables:
  places:
    columns:
      id: { type: identifier, auto_increment: true }
      name: { type: text }
      shape: { type: geometry, geometry_type: point, srid: 4326 }
    indices:
      - { name: idx_places_shape, columns: [shape], type: spatial }
YAML
$COMPOSE run --rm dmigrate schema migrate --execute --spatial-profile spatialite \
    --source /work/.cache/va4-apply-schema.yaml \
    --target "db:sqlite:///work/.cache/va4-apply.db?spatialite=true" \
    --report /work/.cache/va4-apply.report.yaml > /tmp/va4-apply.log 2>&1 || true
grep -q '"status": "ok"' "$EXAMPLES_DIR/.cache/va4-apply.report.yaml" 2>/dev/null \
    || { cat /tmp/va4-apply.log; fail "[lite] migrate execution status not ok"; }
grep -q '"executionError":null' "$EXAMPLES_DIR/.cache/va4-apply.report.yaml" \
    || { cat "$EXAMPLES_DIR/.cache/va4-apply.report.yaml"; fail "[lite] migrate reported an execution error"; }
log "[lite] migrate executed cleanly (report status ok, no executionError)"

log "[lite] reverse of migrated .db (Befund 3)..."
$COMPOSE run --rm dmigrate schema reverse \
    --source "sqlite:///work/.cache/va4-apply.db?spatialite=true" \
    --output /work/.cache/va4-roundtrip.yaml > /tmp/va4-rev.log 2>&1 \
    || { cat /tmp/va4-rev.log; fail "[lite] reverse of migrated .db failed"; }
grep -q "srid: 4326" "$EXAMPLES_DIR/.cache/va4-roundtrip.yaml" \
    || { cat "$EXAMPLES_DIR/.cache/va4-roundtrip.yaml"; fail "[lite] reverse lost SRID 4326"; }
grep -q "type: spatial" "$EXAMPLES_DIR/.cache/va4-roundtrip.yaml" \
    || { cat "$EXAMPLES_DIR/.cache/va4-roundtrip.yaml"; fail "[lite] reverse lost the spatial index"; }
# Leak-Check: SpatiaLite-Metatabellennamen + R*Tree-Schattentabellen (mit
# _node/_parent/_rowid-Suffix). NICHT `idx_places_shape` ohne Suffix — das ist der
# legitime, von Befund 3 rekonstruierte Spatial-Index-NAME.
if grep -qE "geometry_columns|spatial_ref_sys|spatialite_history|idx_places_shape_(node|parent|rowid)" "$EXAMPLES_DIR/.cache/va4-roundtrip.yaml"; then
    cat "$EXAMPLES_DIR/.cache/va4-roundtrip.yaml"; fail "[lite] reverse leaked SpatiaLite metadata/R*Tree tables"
fi
log "[lite] migrate→reverse round-trip OK — geometry+SRID 4326+spatial index recovered, metadata filtered (5d Befund 1/2/3)"

# ─── 6. PostGIS nyc Round-Trip (5a, ECHTES Sample, opt-in FETCH_NYC=1) ──
# Belegt 5a gegen das gepinnte, ECHTE „Introduction to PostGIS"-Workshop-Sample
# (nyc_neighborhoods: 129 MultiPolygons, EPSG:26918 NAD83/UTM18N). postgis/postgis
# hat kein shp2pgsql/ogr2ogr → der gepinnte gdal-Service lädt die Shapefile per
# ogr2ogr über das Compose-Netz in die Quelle (legt auto. einen GIST-Index an).
# Dann der volle d-migrate-Pfad: reverse → validate → generate --spatial-profile
# postgis → angewandtes DDL (Zielschema) → data transfer → Parität (Zeilen +
# Flächen-Checksumme), SRID 26918 erhalten, GIST-Index im Ziel. Opt-in (~22 MB
# Fetch, kein PR-Gate) wie Phase 3 Scale: `FETCH_NYC=1 make sample-db-spatial-smoke`.
if [ "${FETCH_NYC:-0}" = "1" ]; then
    log "[pg-nyc] fetching pinned nyc workshop sample (opt-in)..."
    FETCH_NYC=1 "$SCRIPT_DIR/fetch-dumps.sh" > /tmp/nyc-fetch.log 2>&1 \
        || { cat /tmp/nyc-fetch.log; fail "[pg-nyc] nyc fetch failed"; }
    [ -f "$EXAMPLES_DIR/.cache/nyc/nyc_neighborhoods.shp" ] \
        || fail "[pg-nyc] nyc_neighborhoods.shp missing after fetch"

    log "[pg-nyc] (re)create dedicated nyc_src/nyc_target databases + PostGIS..."
    psql_pg geo_pg_src -c "DROP DATABASE IF EXISTS nyc_src" -c "CREATE DATABASE nyc_src" \
        -c "DROP DATABASE IF EXISTS nyc_target" -c "CREATE DATABASE nyc_target" >/dev/null \
        || fail "[pg-nyc] nyc db (re)create failed"
    for db in nyc_src nyc_target; do
        psql_pg "$db" -c "CREATE EXTENSION IF NOT EXISTS postgis" >/dev/null
    done

    log "[pg-nyc] ogr2ogr load nyc_neighborhoods → nyc_src (MultiPolygon, EPSG:26918)..."
    $COMPOSE run --rm gdal ogr2ogr -f PostgreSQL \
        "PG:host=postgis port=5432 dbname=nyc_src user=${POSTGRES_USER} password=${POSTGRES_PASSWORD}" \
        /work/.cache/nyc/nyc_neighborhoods.shp -nln nyc_neighborhoods -nlt PROMOTE_TO_MULTI \
        -lco GEOMETRY_NAME=geom -lco FID=gid > /tmp/nyc-ogr.log 2>&1 \
        || { cat /tmp/nyc-ogr.log; fail "[pg-nyc] ogr2ogr load failed"; }
    src_rows=$(pg_val nyc_src "SELECT count(*) FROM nyc_neighborhoods")
    [ "$src_rows" = "129" ] || fail "[pg-nyc] source row count $src_rows (expected 129)"

    log "[pg-nyc] schema reverse → validate → generate --spatial-profile postgis..."
    $COMPOSE run --rm dmigrate schema reverse --source nyc_pg_src \
        --output /work/.cache/nyc.reverse.yaml > /tmp/nyc-rev.log 2>&1 \
        || { cat /tmp/nyc-rev.log; fail "[pg-nyc] reverse failed"; }
    grep -q "geometry_type: multipolygon" "$EXAMPLES_DIR/.cache/nyc.reverse.yaml" \
        || { cat "$EXAMPLES_DIR/.cache/nyc.reverse.yaml"; fail "[pg-nyc] reverse lost multipolygon"; }
    grep -q "srid: 26918" "$EXAMPLES_DIR/.cache/nyc.reverse.yaml" \
        || fail "[pg-nyc] reverse lost SRID 26918"
    $COMPOSE run --rm dmigrate schema validate --source /work/.cache/nyc.reverse.yaml \
        > /tmp/nyc-val.log 2>&1 || { cat /tmp/nyc-val.log; fail "[pg-nyc] validate failed"; }
    $COMPOSE run --rm dmigrate schema generate --source /work/.cache/nyc.reverse.yaml \
        --target postgresql --spatial-profile postgis --deterministic \
        --output /work/.cache/nyc.gen.sql > /tmp/nyc-gen.log 2>&1 \
        || { cat /tmp/nyc-gen.log; fail "[pg-nyc] generate failed"; }
    grep -qiE "USING GIST" "$EXAMPLES_DIR/.cache/nyc.gen.sql" \
        || { cat "$EXAMPLES_DIR/.cache/nyc.gen.sql"; fail "[pg-nyc] generated DDL missing USING GIST"; }

    log "[pg-nyc] apply generated DDL → nyc_target, then data transfer..."
    psql_pg nyc_target < "$EXAMPLES_DIR/.cache/nyc.gen.sql" > /tmp/nyc-apply.log 2>&1 \
        || { cat /tmp/nyc-apply.log; fail "[pg-nyc] applying generated DDL failed"; }
    $COMPOSE run --rm dmigrate data transfer --source nyc_pg_src --target nyc_pg_target \
        --tables nyc_neighborhoods --truncate > /tmp/nyc-transfer.log 2>&1 \
        || { cat /tmp/nyc-transfer.log; fail "[pg-nyc] transfer failed"; }

    # Parität: Zeilen + SRID + Geometrie-Werte (Flächen-Checksumme src==target) + GIST.
    tgt_rows=$(pg_val nyc_target "SELECT count(*) FROM nyc_neighborhoods")
    [ "$tgt_rows" = "129" ] || fail "[pg-nyc] target row count $tgt_rows (expected 129)"
    tgt_srid=$(pg_val nyc_target "SELECT DISTINCT ST_SRID(geom) FROM nyc_neighborhoods")
    [ "$tgt_srid" = "26918" ] || fail "[pg-nyc] target SRID $tgt_srid (expected 26918)"
    src_area=$(pg_val nyc_src    "SELECT round(sum(ST_Area(geom))) FROM nyc_neighborhoods")
    tgt_area=$(pg_val nyc_target "SELECT round(sum(ST_Area(geom))) FROM nyc_neighborhoods")
    [ -n "$tgt_area" ] && [ "$src_area" = "$tgt_area" ] \
        || fail "[pg-nyc] geometry area checksum mismatch: src=$src_area tgt=$tgt_area"
    gist=$(pg_val nyc_target "SELECT count(*) FROM pg_indexes WHERE tablename='nyc_neighborhoods' AND indexdef ILIKE '%gist%'")
    [ "${gist:-0}" -ge 1 ] || fail "[pg-nyc] no GIST index on target nyc_neighborhoods"
    log "[pg-nyc] OK — 129 MultiPolygons round-tripped (SRID 26918 erhalten, Flächen-Checksumme $tgt_area gleich, GIST-Index belegt) — 5a confirmed (echtes nyc-Sample)"
else
    log "[pg-nyc] SKIP (5a) — set FETCH_NYC=1 to run the real pinned nyc PostGIS round-trip (~22 MB fetch)"
fi

log "SUCCESS — VA1+VA2+VA2-X1+VA3 live-verified + VA4/5d full round-trip: geometry value + SRID round-trip PG→PG, MySQL→MySQL UND cross-dialect PG↔MySQL (geografisch 4326 long-lat-korrekt + projiziert EPSG:25832/3857/31466 Rechtswert/Hochwert, inkl. GK mit gedrehter AXIS-Deklaration); MySQL SPATIAL-Index reverse→generate→apply (cross-dialect → PostGIS USING GIST); SpatiaLite migrate --execute legt Geometrie+R*Tree-Spatial-Index real an (Bootstrap InitSpatialMetaData) und reverse rekonstruiert sie verlustfrei (Metatabellen gefiltert); native PG point unaffected (R1)."
log "stack is up; clean up with 'make sample-db-down' or 'make sample-db-purge'."
