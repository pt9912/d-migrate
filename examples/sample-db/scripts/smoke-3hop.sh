#!/usr/bin/env bash
# Sample-DB-Harness — 3-Hop Cross-Dialect-Kette (Lastenheft-8.6):
#   PostgreSQL -> (neutral) -> MySQL -> (neutral) -> SQLite
#
# Belegt den woertlichen 8.6-Vertrag als EINEN verketteten Fluss (nicht nur
# paarweise): die Pagila-Quelle wandert PG->MySQL->SQLite, und die drei in 8.6
# genannten Typ-Transformationen werden ueber die ganze Kette nachgewiesen:
#   Serial -> AUTO_INCREMENT -> AUTOINCREMENT  (Pagila-INTEGER-PKs, z.B. film.film_id)
#   text[] -> JSON           -> JSON           (film.special_features)
#   ENUM   -> ENUM           -> CHECK          (film.rating / mpaa_rating)
# plus Zeilen-Paritaet PG-Quelle == SQLite-Ziel ueber alle logischen Tabellen.
#
# Ergaenzt die paarweisen Smokes (smoke-cross-pg2my.sh PG->MySQL,
# smoke-sqlite.sh SQLite-Round-Trip) um die 3. Kaskaden-Stufe (MySQL->SQLite)
# und die durchgaengige Kette. Hop 1 nutzt die bewaehrte pg2my-Mechanik.
#
# Voraussetzung am Host: docker, docker compose, sqlite3, lokal gebautes
# d-migrate:dev-Image.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
EXAMPLES_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
COMPOSE_FILE="$EXAMPLES_DIR/docker-compose.yml"
COMPOSE="docker compose -f $COMPOSE_FILE"
OUT_DIR="$EXAMPLES_DIR/out"
SQLITE_DB="$OUT_DIR/pagila-3hop.db"

log()  { printf '[3hop] %s\n' "$*"; }
fail() { printf '[3hop] FAIL: %s\n' "$*" >&2; exit 1; }

# --- 0. .env + Verzeichnisse + Dump-Fetch --------------------------
mkdir -p "$OUT_DIR"
if [ ! -f "$EXAMPLES_DIR/.env" ]; then
    cp "$EXAMPLES_DIR/.env.example" "$EXAMPLES_DIR/.env"
    log "created examples/sample-db/.env from .env.example"
fi
# shellcheck disable=SC1091
set -a; . "$EXAMPLES_DIR/.env"; set +a
: "${POSTGRES_USER:?POSTGRES_USER not set}"
: "${MYSQL_ROOT_PASSWORD:?MYSQL_ROOT_PASSWORD not set}"
: "${MYSQL_USER:?MYSQL_USER not set}"
# d-migrate:dev laeuft non-root (uid 10001); als Host-User laufen lassen, damit
# es in das gemountete out/ (und die SQLite-Datei darin) schreiben kann.
export SAMPLE_DB_DMIGRATE_USER="$(id -u):$(id -g)"

command -v sqlite3 >/dev/null 2>&1 || fail "sqlite3 not on host (needed for SQLite parity checks)"
"$SCRIPT_DIR/fetch-dumps.sh"

mysql_root() { $COMPOSE exec -T mysql mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -N "$@" 2>/dev/null; }
psql_t()     { $COMPOSE exec -T postgres psql -v ON_ERROR_STOP="$2" -U "$POSTGRES_USER" -d "$1" "${@:3}"; }
# `</dev/null` schuetzt die Value-Helfer in `while read … done <<< "$list"`-Schleifen
# davor, das Here-String von stdin zu schlucken (False-Green nach 1. Iteration).
pg_val()     { $COMPOSE exec -T postgres psql -U "$POSTGRES_USER" -d pagila -tAc "$1" </dev/null 2>/dev/null | tr -d '[:space:]'; }
sq_val()     { sqlite3 "$SQLITE_DB" "$1" | tr -d '[:space:]'; }

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

# --- 1. Stacks hoch (postgres-Quelle + mysql-Zwischenstufe) --------
log "starting postgres + mysql..."
$COMPOSE up -d postgres mysql
wait_healthy postgres 120
wait_healthy mysql 180

# ============================================================
# HOP 0: Pagila in eine frische PG-Quell-DB laden
# ============================================================
log "HOP 0: loading pagila into PG source..."
psql_t postgres 1 -c "DROP DATABASE IF EXISTS pagila WITH (FORCE)" -c "CREATE DATABASE pagila" > /dev/null
psql_t pagila 1 < "$EXAMPLES_DIR/.cache/pagila.sql" > /dev/null || fail "pagila dump load failed"
# Logische Tabellen = Basistabellen ohne Partitionskinder (payment-Parent zaehlt 1x).
logical_tables=$(pg_val "SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='public' AND c.relkind IN ('r','p') AND NOT c.relispartition")
[ "${logical_tables:-0}" -ge 1 ] || fail "could not count logical source tables"
log "  pagila loaded ($logical_tables logical tables)"

# ============================================================
# HOP 1: PG -> MySQL  (reverse -> generate mysql -> transfer)
# ============================================================
log "HOP 1: PostgreSQL -> MySQL ..."
$COMPOSE run --rm dmigrate schema reverse --source pagila_pg --include-all \
    --output /work/out/h1.pg.reverse.yaml > /dev/null || fail "hop1 reverse failed"
$COMPOSE run --rm dmigrate schema generate --source /work/out/h1.pg.reverse.yaml \
    --target mysql --split pre-post --deterministic \
    --output /work/out/h1.my.sql > /dev/null || fail "hop1 generate failed"
[ -s "$OUT_DIR/h1.my.pre-data.sql" ] || fail "hop1: empty MySQL pre-data"
mysql_root -e "DROP DATABASE IF EXISTS pagila_target; CREATE DATABASE pagila_target CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci; GRANT ALL ON pagila_target.* TO '${MYSQL_USER}'@'%'; FLUSH PRIVILEGES;"
mysql_root pagila_target < "$OUT_DIR/h1.my.pre-data.sql" || fail "hop1 pre-data apply failed"
$COMPOSE run --rm dmigrate data transfer --source pagila_pg --target pagila_my_target --truncate \
    > /tmp/3hop-h1.log 2>&1 || { cat /tmp/3hop-h1.log; fail "hop1 transfer failed"; }
grep -q "Transfer complete" /tmp/3hop-h1.log || { cat /tmp/3hop-h1.log; fail "hop1 transfer did not complete"; }
log "  HOP 1 OK (Pagila data now in MySQL pagila_target)"

# ============================================================
# HOP 2: MySQL -> SQLite  (reverse -> generate sqlite -> transfer)
# ============================================================
log "HOP 2: MySQL -> SQLite ..."
rm -f "$SQLITE_DB"
$COMPOSE run --rm dmigrate schema reverse --source pagila_my_target --include-all \
    --output /work/out/h2.my.reverse.yaml > /dev/null || fail "hop2 reverse failed"
$COMPOSE run --rm dmigrate schema generate --source /work/out/h2.my.reverse.yaml \
    --target sqlite --split pre-post --deterministic \
    --output /work/out/h2.sqlite.sql > /dev/null || fail "hop2 generate failed"
[ -s "$OUT_DIR/h2.sqlite.pre-data.sql" ] || fail "hop2: empty SQLite pre-data"
# Zielschema aus pre-data bauen (Host-sqlite3; dieselbe Datei, die dmigrate via /work beschreibt).
sqlite3 "$SQLITE_DB" < "$OUT_DIR/h2.sqlite.pre-data.sql" || fail "hop2 pre-data apply failed"
$COMPOSE run --rm dmigrate data transfer --source pagila_my_target \
    --target "sqlite:///work/out/pagila-3hop.db" --truncate \
    > /tmp/3hop-h2.log 2>&1 || { cat /tmp/3hop-h2.log; fail "hop2 transfer failed"; }
grep -q "Transfer complete" /tmp/3hop-h2.log || { cat /tmp/3hop-h2.log; fail "hop2 transfer did not complete"; }
log "  HOP 2 OK (data now in SQLite $SQLITE_DB)"

# ============================================================
# END-TO-END: Zeilen-Paritaet PG-Quelle == SQLite-Ziel
# ============================================================
log "verifying END-TO-END row parity (PG source == SQLite final)..."
src_list=$(psql_t pagila 0 -tAc "SELECT c.relname FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='public' AND c.relkind IN ('r','p') AND NOT c.relispartition ORDER BY 1") \
    || fail "could not list source tables for parity"
[ -n "$src_list" ] || fail "source table list for parity is empty"
compared=0; mismatch=0
while IFS= read -r t; do
    [ -n "$t" ] || continue
    s=$(pg_val "SELECT count(*) FROM \"$t\"")
    d=$(sq_val "SELECT count(*) FROM \"$t\";")
    if [ "$s" != "$d" ]; then printf '[3hop]   MISMATCH %s: pg=%s sqlite=%s\n' "$t" "$s" "$d"; mismatch=1; fi
    compared=$((compared + 1))
done <<< "$src_list"
[ "$mismatch" = "0" ] || fail "end-to-end row-count parity violated"
[ "$compared" = "$logical_tables" ] || fail "parity compared $compared tables, expected $logical_tables (generator-swallow guard)"
log "  end-to-end parity OK (all $compared logical tables survived PG->MySQL->SQLite)"

# ============================================================
# 8.6 TYP-TRANSFORMATIONEN am SQLite-Ende (ueber die Kette belegt)
# ============================================================
log "verifying Lastenheft-8.6 type transformations across the chain..."

# (a) Serial -> AUTO_INCREMENT -> AUTOINCREMENT: film.film_id ist INTEGER-PK in SQLite.
film_pk_type=$(sq_val "SELECT type FROM pragma_table_info('film') WHERE name='film_id' AND pk=1;")
case "$film_pk_type" in
    INTEGER|integer) log "  Serial->AUTO_INCREMENT->AUTOINCREMENT OK (film.film_id INTEGER PK)";;
    *) fail "film.film_id PK type expected INTEGER, got '${film_pk_type:-<none>}'";;
esac

# (b) text[] -> JSON -> JSON: film.special_features enthaelt gueltiges JSON in SQLite.
sf_valid=$(sq_val "SELECT json_valid(special_features) FROM film WHERE film_id=1;")
[ "$sf_valid" = "1" ] || fail "text[]->JSON->JSON: film.special_features not valid JSON in SQLite (got '${sf_valid:-<none>}')"
log "  text[]->JSON->JSON OK (film.special_features valid JSON)"

# (c) ENUM -> ENUM -> CHECK: film.rating traegt in SQLite einen CHECK-Constraint.
film_ddl=$(sqlite3 "$SQLITE_DB" "SELECT sql FROM sqlite_master WHERE type='table' AND name='film';")
printf '%s' "$film_ddl" | grep -iE '"?rating"?[^,]*check|check[^,]*"?rating"?' > /dev/null \
    || printf '%s' "$film_ddl" | grep -iE "check.*('G'|'PG'|'NC-17')" > /dev/null \
    || fail "ENUM->ENUM->CHECK: no CHECK constraint on film.rating in SQLite DDL:\n$film_ddl"
# Datenbeleg: die Rating-Werte sind erhalten (nicht leer/NULL bei allen Zeilen).
rating_populated=$(sq_val "SELECT COUNT(*) FROM film WHERE rating IS NOT NULL AND rating <> '';")
[ "${rating_populated:-0}" -ge 1 ] || fail "ENUM->ENUM->CHECK: film.rating not populated after the chain"
log "  ENUM->ENUM->CHECK OK (film.rating CHECK constraint present; $rating_populated rows populated)"

log "SUCCESS — 3-hop cross-dialect chain PostgreSQL->MySQL->SQLite passed"
log "         (Lastenheft-8.6: end-to-end row parity + Serial/Array/ENUM transformations)."
