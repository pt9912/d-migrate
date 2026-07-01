#!/usr/bin/env bash
# Sample-DB-Harness — Fulltext-Slice P4 (SQLite FTS5)
# Plan: docs/planning/in-progress/fulltext-structural-cross-dialect.md (P4)
# ADR:  docs/adr/0025-fulltext-source-columns-as-index.md
#
# Belegt live, dass ein neutraler FULLTEXT-Index cross-dialect zu einer SQLite
# FTS5-External-Content-Virtual-Table + `'rebuild'` + drei Sync-Triggern expandiert
# (SqliteFullTextExpansion) — strukturerhaltend, Volltext-Suche funktioniert am Ziel.
#
#   Teil A — PG→SQLite STRUKTUR (live): Pagila (PG) reverse -> generate --target sqlite;
#            der GiST-tsvector-Index `film_fulltext_idx` wird als FTS5-Virtual-Table
#            über die Quelltext-Spalten (title, description) generiert.
#   Teil B — FTS5 FUNKTIONIERT (self-contained): generate -> `sqlite3`-Apply gegen eine
#            frische .db -> kuratierte Daten -> FTS5-`MATCH` liefert Treffer. Deckt die
#            `'rebuild'`-Befüllung UND alle drei Sync-Trigger (INSERT/UPDATE/DELETE) ab,
#            plus eine Nonsens-Negativkontrolle.
#   Teil C — DIFF-PFAD + DRIFT-FREIER ROUND-TRIP (Slice P5): `migrate --execute` legt die
#            FTS5-Struktur real in einer frischen .db an (Diff-Renderpfad) UND endet mit
#            Exit 0 — der Post-Execute-Compare reverst die .db, filtert die FTS5-Shadow-
#            Tabellen/Sync-Trigger und rekonstruiert den FULLTEXT-Index (kein Phantom-Drift).
#
# Voraussetzung am Host: docker, docker compose, sqlite3, lokal gebautes d-migrate:dev-Image.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
EXAMPLES_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
COMPOSE_FILE="$EXAMPLES_DIR/docker-compose.yml"
COMPOSE="docker compose -f $COMPOSE_FILE"
CACHE_DIR="$EXAMPLES_DIR/.cache"
OUT_DIR="$EXAMPLES_DIR/out"

log()  { printf '[fts-sqlite] %s\n' "$*"; }
note() { printf '[fts-sqlite] NOTE: %s\n' "$*"; }
fail() { printf '[fts-sqlite] FAIL: %s\n' "$*" >&2; exit 1; }

command -v sqlite3 >/dev/null 2>&1 || fail "sqlite3 not found on host (needed to build+query the target DB)"

mkdir -p "$OUT_DIR" "$CACHE_DIR"
if [ ! -f "$EXAMPLES_DIR/.env" ]; then
    cp "$EXAMPLES_DIR/.env.example" "$EXAMPLES_DIR/.env"
    log "created examples/sample-db/.env from .env.example"
fi
# shellcheck disable=SC1091
set -a; . "$EXAMPLES_DIR/.env"; set +a
: "${POSTGRES_USER:?POSTGRES_USER not set}"

# Non-root Image (uid 10001): der dmigrate-Container läuft als Host-User, damit er in
# das bind-gemountete out/ schreiben kann (sonst "Failed to write schema").
export SAMPLE_DB_DMIGRATE_USER="$(id -u):$(id -g)"
DRUN="docker run --rm --user $(id -u):$(id -g) -v $EXAMPLES_DIR:/work -w /work d-migrate:dev"

psql_t() { $COMPOSE exec -T postgres psql -v ON_ERROR_STOP="$2" -U "$POSTGRES_USER" -d "$1" "${@:3}"; }

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

"$SCRIPT_DIR/fetch-dumps.sh"

# ─── Teil A — PG→SQLite Struktur (live) ─────────────────────────────
log "[A] starting postgres + loading pagila..."
$COMPOSE up -d postgres
wait_healthy postgres 120
psql_t postgres 1 -c "DROP DATABASE IF EXISTS pagila WITH (FORCE)" -c "CREATE DATABASE pagila" > /dev/null
psql_t pagila 1 < "$EXAMPLES_DIR/.cache/pagila.sql" > /dev/null || fail "[A] pagila dump load failed"

log "[A] schema reverse pagila_pg --include-all..."
$COMPOSE run --rm dmigrate schema reverse --source pagila_pg --include-all \
    --output /work/out/pagila.fts.reverse.yaml > /dev/null || fail "[A] reverse failed"
grep -q "type: fulltext" "$OUT_DIR/pagila.fts.reverse.yaml" \
    || fail "[A] reverse did not synthesize a FULLTEXT index for film (P2 regression?)"
log "[A] reverse OK — film_fulltext_idx captured as type: fulltext"

log "[A] schema generate --target sqlite --split pre-post..."
$COMPOSE run --rm dmigrate schema generate --source /work/out/pagila.fts.reverse.yaml \
    --target sqlite --split pre-post --deterministic \
    --output /work/out/pagila.fts.sql > /dev/null || fail "[A] generate failed"
GEN_ALL="$OUT_DIR/pagila.fts.pre-data.sql $OUT_DIR/pagila.fts.post-data.sql"
# shellcheck disable=SC2086
if ! grep -hq "CREATE VIRTUAL TABLE \"film_fulltext_idx\" USING fts5(\"title\", \"description\", content='film')" $GEN_ALL; then
    # shellcheck disable=SC2086
    grep -hi "fulltext\|fts5\|film_fulltext" $GEN_ALL || true
    fail "[A] generated SQLite DDL is missing the film FTS5 virtual table over (title, description)"
fi
# shellcheck disable=SC2086
grep -hq "CREATE TRIGGER \"film_fulltext_idx_ai\" AFTER INSERT ON \"film\"" $GEN_ALL || fail "[A] missing FTS5 AFTER INSERT sync trigger"
# shellcheck disable=SC2086
grep -hq "CREATE TRIGGER \"film_fulltext_idx_ad\" AFTER DELETE ON \"film\"" $GEN_ALL || fail "[A] missing FTS5 AFTER DELETE sync trigger"
# shellcheck disable=SC2086
grep -hq "CREATE TRIGGER \"film_fulltext_idx_au\" AFTER UPDATE ON \"film\"" $GEN_ALL || fail "[A] missing FTS5 AFTER UPDATE sync trigger"
# shellcheck disable=SC2086
grep -hq "INSERT INTO \"film_fulltext_idx\"(\"film_fulltext_idx\") VALUES('rebuild')" $GEN_ALL || fail "[A] missing FTS5 initial rebuild"
# The tsvector column still degrades to TEXT (W132), never a silent fts5 column type.
grep -q "\"fulltext\" TEXT" "$OUT_DIR/pagila.fts.pre-data.sql" || fail "[A] film.fulltext column did not degrade to TEXT"
log "[A] OK — PG FULLTEXT index → SQLite FTS5 virtual table + rebuild + 3 sync triggers (title, description)"

# ─── Teil B — FTS5 funktioniert (self-contained, MATCH) ─────────────
log "[B] generate a small fulltext schema → SQLite..."
cat > "$CACHE_DIR/fts5-schema.yaml" <<'YAML'
name: "fts5 smoke"
version: "1.0.0"
tables:
  docs:
    columns:
      id: { type: identifier, auto_increment: true }
      title: { type: text }
      body: { type: text }
    indices:
      - { name: docs_fts, columns: [title, body], type: fulltext }
YAML
$DRUN schema generate --source /work/.cache/fts5-schema.yaml \
    --target sqlite --split pre-post --deterministic --output /work/.cache/fts5-gen.sql \
    > /tmp/fts5-gen.log 2>&1 || { cat /tmp/fts5-gen.log; fail "[B] generate failed"; }
PRE="$CACHE_DIR/fts5-gen.pre-data.sql"
POST="$CACHE_DIR/fts5-gen.post-data.sql"
grep -q "CREATE VIRTUAL TABLE \"docs_fts\" USING fts5(\"title\", \"body\", content='docs')" "$POST" \
    || { cat "$POST"; fail "[B] post-data missing docs_fts FTS5 virtual table"; }
grep -q "CREATE VIRTUAL TABLE" "$PRE" && fail "[B] FTS5 virtual table leaked into pre-data (must be POST_DATA)"
log "[B] generate OK — docs_fts in post-data (POST_DATA)"

DB="$CACHE_DIR/fts5-smoke.db"
rm -f "$DB" "$DB-wal" "$DB-shm"
sqlite3 "$DB" < "$PRE" || fail "[B] pre-data apply failed"
# Two rows inserted BEFORE the FTS5 objects exist → covered by the initial `'rebuild'`.
sqlite3 "$DB" "INSERT INTO docs(id,title,body) VALUES
    (1,'The Astronaut','A quiet story about deep space travel'),
    (2,'Cooking Basics','How to bake bread at home');" || fail "[B] seed insert failed"
sqlite3 "$DB" < "$POST" || fail "[B] post-data apply (FTS5) failed"

mch() { sqlite3 "$DB" "SELECT count(*) FROM docs_fts WHERE docs_fts MATCH '$1';" 2>/dev/null | tr -d '[:space:]'; }

# rebuild path: row 1 ('astronaut') was present before the FTS5 rebuild.
[ "$(mch astronaut)" = "1" ] || fail "[B] rebuild path: MATCH 'astronaut' expected 1, got '$(mch astronaut)'"
log "[B] rebuild population OK (MATCH 'astronaut' → 1)"

# INSERT trigger: a new row must sync into the FTS index.
sqlite3 "$DB" "INSERT INTO docs(id,title,body) VALUES (3,'Dinosaur Park','ancient reptiles still roam');" \
    || fail "[B] insert-trigger seed failed"
[ "$(mch dinosaur)" = "1" ] || fail "[B] AFTER INSERT trigger: MATCH 'dinosaur' expected 1, got '$(mch dinosaur)'"
log "[B] AFTER INSERT trigger OK (MATCH 'dinosaur' → 1)"

# UPDATE trigger: editing row 2 to mention an astronaut must make it match. Singular token
# on purpose — FTS5's default tokenizer does not stem, so 'astronauts' would NOT match 'astronaut'.
sqlite3 "$DB" "UPDATE docs SET body='now this one mentions an astronaut too' WHERE id=2;" \
    || fail "[B] update-trigger edit failed"
[ "$(mch astronaut)" = "2" ] || fail "[B] AFTER UPDATE trigger: MATCH 'astronaut' expected 2, got '$(mch astronaut)'"
log "[B] AFTER UPDATE trigger OK (MATCH 'astronaut' → 2)"

# DELETE trigger: removing row 1 must drop it from the index.
sqlite3 "$DB" "DELETE FROM docs WHERE id=1;" || fail "[B] delete-trigger removal failed"
[ "$(mch astronaut)" = "1" ] || fail "[B] AFTER DELETE trigger: MATCH 'astronaut' expected 1, got '$(mch astronaut)'"
log "[B] AFTER DELETE trigger OK (MATCH 'astronaut' → 1 after deleting row 1)"

# Negative control: a nonsense term must return nothing (MATCH really filters).
[ "$(mch zzqxnonexistentterm)" = "0" ] || fail "[B] negative control: nonsense term returned $(mch zzqxnonexistentterm), expected 0"
log "[B] negative control OK (nonsense → 0)"

# ─── Teil C — Diff-Pfad-Apply (migrate --execute) ───────────────────
# migrate --execute rendert über den Diff-Renderpfad (SqliteDiffSimpleOps) und legt die
# FTS5-Struktur real an. Der *drift-freie* Round-Trip (Post-Compare) braucht den Reverse-
# Filter/-Rekonstruktion aus Slice P5 — daher wird hier nur der Apply belegt (FTS5-Tabelle
# entsteht + ist per MATCH abfragbar), das Exit/Drift-Ergebnis nur diagnostisch geloggt.
log "[C] migrate --execute (diff render path) against a fresh .db..."
MDB="$CACHE_DIR/fts5-migrate.db"
rm -f "$MDB" "$MDB-wal" "$MDB-shm"
set +e
$DRUN schema migrate --execute --source /work/.cache/fts5-schema.yaml \
    --target "db:sqlite:///work/.cache/fts5-migrate.db" \
    --report /work/.cache/fts5-migrate.report.yaml > /tmp/fts5-migrate.log 2>&1
mig_exit=$?
set -e
[ -f "$MDB" ] || { cat /tmp/fts5-migrate.log; fail "[C] migrate did not create the target .db"; }
fts_present=$(sqlite3 "$MDB" "SELECT count(*) FROM sqlite_master WHERE name='docs_fts';" 2>/dev/null | tr -d '[:space:]')
[ "$fts_present" = "1" ] || { cat /tmp/fts5-migrate.log; fail "[C] diff path did not create the docs_fts FTS5 table (got '$fts_present')"; }
# The diff-path triggers must work too: an insert syncs into the index.
sqlite3 "$MDB" "INSERT INTO docs(id,title,body) VALUES (1,'Comet Watch','tracking a bright comet tonight');" \
    || fail "[C] insert into migrated table failed"
c_match=$(sqlite3 "$MDB" "SELECT count(*) FROM docs_fts WHERE docs_fts MATCH 'comet';" 2>/dev/null | tr -d '[:space:]')
[ "$c_match" = "1" ] || { cat /tmp/fts5-migrate.log; fail "[C] diff-path FTS5 MATCH 'comet' expected 1, got '$c_match'"; }
# The migration EXECUTION must complete cleanly.
grep -q '"executionError":null' "$CACHE_DIR/fts5-migrate.report.yaml" 2>/dev/null \
    || { cat /tmp/fts5-migrate.log; fail "[C] migrate reported an execution error (the apply itself failed)"; }
# P5: the round-trip is now drift-free — the post-execute compare reverses the .db, filters the
# FTS5 shadow tables/sync triggers and reconstructs the FULLTEXT index, so migrate --execute
# exits 0 (no phantom drift). A non-zero exit here is a real regression.
[ "$mig_exit" = "0" ] \
    || { cat /tmp/fts5-migrate.log; fail "[C] migrate --execute exited $mig_exit (expected 0 — P5 reverse filter/reconstruction should make the round-trip drift-free)"; }
log "[C] OK — migrate --execute Exit 0 (drift-free round-trip); docs_fts created + MATCH works"

log "SUCCESS — SQLite FTS5 fulltext: PG→SQLite structure (A) + live MATCH incl. all 3 sync triggers (B) + diff-path apply (C)."
log "postgres stack is up; clean up with 'make sample-db-down' or 'make sample-db-purge'."
