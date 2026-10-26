#!/usr/bin/env bash
# P1a-Messung zu Issue #68 (PostgreSQL: Domain-Spalte, Fingerabdruck je Seite,
# Rollback, Plan-Artefakt, Lesestellen). Kein Gradle-Modul, kein Teil der Build-Gates:
# das Skript misst den Ist-Stand eines gebauten Images und gibt Befunde aus.
#
#   make docker-build                        # Image bauen (Tag d-migrate:dev)
#   IMAGE=d-migrate:dev test/measure-domain-spalte-68/measure.sh
#
# Voraussetzungen: docker, python3. Colima: das Arbeitsverzeichnis muss unter /Users
# liegen (Standard: ein Verzeichnis unter $HOME), nicht unter /private/tmp.
# Ausgabe: Zeilen "RESULT <schluessel>: <wert>"; mit WORK=<dir> bleibt das Verzeichnis
# stehen (Artefakte, Reports), sonst wird es am Ende entfernt.
set -u
IMAGE=${IMAGE:-d-migrate:dev}
PG_IMAGE=${PG_IMAGE:-postgres:18.6-alpine}
WORK_GIVEN=${WORK:-}
WORK=${WORK:-$(mktemp -d "$HOME/.dm-p68-XXXXXX")}
mkdir -p "$WORK"
SUF=$$
NET=dm-p68-net-$SUF
PG=dm-p68-pg-$SUF
export D_MIGRATE_LANG=en

cleanup() {
  docker rm -f "$PG" >/dev/null 2>&1
  docker network rm "$NET" >/dev/null 2>&1
  [ -z "$WORK_GIVEN" ] && rm -rf "$WORK"
}
trap cleanup EXIT

res() { echo "RESULT $1: $2"; }
dm() { docker run --rm --network "$NET" -v "$WORK:/w" -w /w "$IMAGE" "$@"; }
psql_db() { docker exec -i "$PG" psql -q -v ON_ERROR_STOP=1 -U postgres "$1"; }
url() { echo "db:postgresql://postgres:pw@$PG:5432/$1"; }
# data-Kommandos nehmen die URL ohne das Praefix "db:" (nur schema migrate/rollback kennt es)
purl() { echo "postgresql://postgres:pw@$PG:5432/$1"; }
newdb() { docker exec "$PG" psql -q -U postgres -c "create database $1" >/dev/null; }
json_get() { python3 -I -c "import json,sys; print(json.load(open(sys.argv[1])).get(sys.argv[2],'-'))" "$1" "$2" 2>/dev/null || echo "-"; }
short() { echo "${1:0:12}"; }

docker network create "$NET" >/dev/null
docker run -d --name "$PG" --network "$NET" -e POSTGRES_PASSWORD=pw "$PG_IMAGE" >/dev/null
until docker exec "$PG" pg_isready -U postgres >/dev/null 2>&1; do sleep 1; done
sleep 2

# ---- Fixtures ----------------------------------------------------------
# mk <datei> <spalte-d> <mit custom_types dd: 1|0> [extra spalten]
mk() {
  local f=$1 dcol=$2 withdom=$3 extra=${4:-}
  {
    echo 'schema_format: "1.0"'; echo 'name: "T"'; echo 'version: "1.0.0"'
    if [ "$withdom" = 1 ]; then
      printf 'custom_types:\n  dd:\n    kind: domain\n    base_type: integer\n    check: "VALUE >= 0"\n'
    fi
    printf 'tables:\n  t:\n    columns:\n      id: { type: identifier, auto_increment: true }\n      d: %s\n' "$dcol"
    [ -n "$extra" ] && printf '      %s\n' "$extra"
    printf '    primary_key: [id]\n'
  } > "$WORK/$f"
}
mk ref.yaml   '{ type: enum, ref_type: dd }' 1
mk alt.yaml   '{ type: integer }' 1
mk plain.yaml '{ type: integer }' 0
mk ref_n.yaml '{ type: enum, ref_type: dd }' 1 'n: { type: integer }'
mk alt_n.yaml '{ type: integer }' 1 'n: { type: integer }'

# DBs: dom = Domain-Spalte; pdom = Domain angelegt, Spalte plain integer; plain = keine Domain
newdb dom; newdb pdom; newdb plain
psql_db dom   <<'SQL'
CREATE DOMAIN dd AS integer CHECK (VALUE >= 0);
CREATE TABLE t (id serial primary key, d dd);
SQL
psql_db pdom  <<'SQL'
CREATE DOMAIN dd AS integer CHECK (VALUE >= 0);
CREATE TABLE t (id serial primary key, d integer);
SQL
psql_db plain <<'SQL'
CREATE TABLE t (id serial primary key, d integer);
SQL

# ---- M1 Fingerabdruck je Seite + M2 Plan-Artefakt bei Exit 8 -----------
echo "== M1/M2 Hashes je Seite (sourceFingerprint = Ist/Datenbank, targetFingerprint = Soll/Datei)"
for f in ref alt plain; do
  for d in dom pdom plain; do
    rm -f "$WORK/pa.json"
    dm schema migrate --source "$f.yaml" --target "$(url $d)" --plan-only \
       --report r.json --plan-artefact pa.json >/dev/null 2>&1; ex=$?
    if [ -f "$WORK/pa.json" ]; then
      res "hash file=$f db=$d" "exit=$ex plan-artefact=ja src(DB)=$(short "$(json_get "$WORK/pa.json" sourceFingerprint)") tgt(Datei)=$(short "$(json_get "$WORK/pa.json" targetFingerprint)") algo=$(json_get "$WORK/pa.json" fingerprintAlgorithm)"
    else
      res "hash file=$f db=$d" "exit=$ex plan-artefact=nein"
    fi
  done
done

# ---- M3 Rollback mit Altdatei (Weg b: Artefakt nur bei executeOk) ------
echo "== M3 Altdatei: execute + Rollback-Artefakt, danach schema rollback"
newdb rb
psql_db rb <<'SQL'
CREATE DOMAIN dd AS integer CHECK (VALUE >= 0);
CREATE TABLE t (id serial primary key, d dd);
SQL
rm -f "$WORK/down.sql" "$WORK/rep.json"
dm schema migrate --source alt_n.yaml --target "$(url rb)" --execute --generate-rollback \
   --rollback-output down.sql --report rep.json >"$WORK/rb.out" 2>&1; ex=$?
res "execute altdatei+n" "exit=$ex rollback-artefakt=$([ -f "$WORK/down.sql" ] && echo ja || echo nein)"
if [ -f "$WORK/down.sql" ]; then
  res "rollback-artefakt Felder" "$(sed -n 2p "$WORK/down.sql" | sed 's/^-- //' | python3 -I -c "import json,sys; d=json.load(sys.stdin); print(' '.join('%s=%s'%(k,str(d[k])[:12] if 'ingerprint' in k and k!='fingerprintAlgorithm' else d[k]) for k in ['currentFingerprint','desiredFingerprint','postUpFingerprint','postUpVerified','fingerprintAlgorithm','recovery']))")"
  dm schema rollback --source down.sql --target "$(url rb)" --execute --allow-destructive >"$WORK/rb2.out" 2>&1; ex=$?
  res "schema rollback --execute" "exit=$ex $(tail -n 2 "$WORK/rb2.out" | tr '\n' ' ' | cut -c1-200)"
fi

# Drift auf main: unter PostgreSQL liess sich keiner erzeugen (BETWEEN-CHECK u. a. konvergieren);
# der Composer ist dialektunabhaengig, daher der dokumentierte SQLite-Drift (biginteger + identity).
mk lite_b.yaml '{ type: integer }' 0
sed -i.bak '/^      d: /d' "$WORK/lite_b.yaml"; rm -f "$WORK/lite_b.yaml.bak"
sed 's|^    primary_key|      counter: { type: biginteger, generation: { type: identity } }\n    primary_key|' "$WORK/lite_b.yaml" > "$WORK/lite_c.yaml"
rm -f "$WORK/dr.db" "$WORK/down2.sql"
dm schema migrate --source lite_b.yaml --target "db:sqlite:///w/dr.db" --execute --report dr0.json >/dev/null 2>&1
dm schema migrate --source lite_c.yaml --target "db:sqlite:///w/dr.db" --execute --generate-rollback \
   --rollback-output down2.sql --report dr1.json >/dev/null 2>&1; ex=$?
res "drift (SQLite identity) mit --generate-rollback" "exit=$ex diag=$(grep -o 'POST_EXECUTE_DRIFT' "$WORK/dr1.json" | head -1) rollback-artefakt=$([ -f "$WORK/down2.sql" ] && echo ja || echo nein)"

# ---- M4 Kurzschluss "gleicher Hash => kein Plan" (Code) ----------------
echo "== M4 Kurzschluss im Code (Hashvergleiche ausserhalb Overlay/Rollback/Post-Compare)"
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
res "DiffPlanner/SchemaMigrateRunner: Vergleich current==desired Fingerabdruck" \
    "$(grep -nE 'ingerprint[A-Za-z]* *(==|!=)' "$ROOT/hexagon/core/src/main/kotlin/dev/dmigrate/core/diff/migration/DiffPlanner.kt" "$ROOT/hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/SchemaMigrateRunner.kt" | wc -l | tr -d ' ') Treffer"
res "Fingerabdruck-Gleichheit im Hexagon (alle)" "$(grep -rnE '(current|desired|source|target|observed)[A-Za-z]*[Ff]ingerprint[A-Za-z]* *(==|!=)|(==|!=) *(current|desired|source|target|observed)[A-Za-z]*[Ff]ingerprint' --include='*.kt' "$ROOT/hexagon" | grep -v /test/ | sed "s|$ROOT/||" | tr '\n' ';' | cut -c1-700)"

# ---- M5 Validierung/Identity -------------------------------------------
echo "== M5 Identity an Domain-Spalte (validate, generate)"
mk ident.yaml '{ type: enum, ref_type: dd, generation: { type: identity } }' 1
dm schema validate --source ident.yaml >"$WORK/v.out" 2>&1; ex=$?
res "validate identity" "exit=$ex $(tr '\n' ' ' < "$WORK/v.out" | cut -c1-250)"
dm schema generate --source ident.yaml --target postgresql --output ident.sql >/dev/null 2>&1; ex=$?
res "generate identity" "exit=$ex $(grep -E '\"d\"' "$WORK/ident.sql" 2>/dev/null | tr -s ' ')"

# ---- M6 Lesestellen -----------------------------------------------------
echo "== M6 Lesestellen (Datei mit ref_type gegen Domain-Spalte; Ist = Reader liefert Basistyp)"
newdb rd
psql_db rd <<'SQL'
CREATE DOMAIN dd AS integer CHECK (VALUE >= 0);
CREATE TABLE t (id serial primary key, d dd);
SQL
dm data seed --schema ref.yaml --target "$(purl rd)" --count 3 --seed 1 >"$WORK/s1.out" 2>&1; ex=$?
res "seed ref_type-Datei" "exit=$ex $(tr '\n' ' ' < "$WORK/s1.out" | cut -c1-250)"
res "seed ref_type-Datei: Zeilen/ nicht-NULL d" "$(docker exec "$PG" psql -At -U postgres rd -c 'select count(*)||'"'"'/'"'"'||count(d) from t')"
docker exec "$PG" psql -q -U postgres rd -c 'truncate t' >/dev/null
dm data seed --schema alt.yaml --target "$(purl rd)" --count 3 --seed 1 >"$WORK/s2.out" 2>&1; ex=$?
res "seed Altdatei (Basistyp)" "exit=$ex $(tr '\n' ' ' < "$WORK/s2.out" | tail -n 3 | tr '\n' ' ' | cut -c1-250)"
echo '[{"d": 5}, {"d": 7}]' > "$WORK/in.json"
dm data import --target "$(purl rd)" --source in.json --format json --table t --schema ref.yaml >"$WORK/i1.out" 2>&1; ex=$?
res "import mit ref_type-Schema" "exit=$ex $(tr '\n' ' ' < "$WORK/i1.out" | cut -c1-250)"
dm data import --target "$(purl rd)" --source in.json --format json --table t >"$WORK/i2.out" 2>&1; ex=$?
res "import ohne Schemadatei (Reader)" "exit=$ex $(tr '\n' ' ' < "$WORK/i2.out" | cut -c1-250)"
newdb tr_to
psql_db tr_to <<'SQL'
CREATE DOMAIN dd AS integer CHECK (VALUE >= 0);
CREATE TABLE t (id serial primary key, d dd);
SQL
dm data transfer --source "$(purl rd)" --target "$(purl tr_to)" --tables t --verify >"$WORK/t1.out" 2>&1; ex=$?
res "transfer PG->PG --verify (Reader: Basistyp)" "exit=$ex $(tr '\n' ' ' < "$WORK/t1.out" | cut -c1-300)"
# Cross-Dialekt: Ziel SQLite (Tabelle zuerst per migrate anlegen)
rm -f "$WORK/x.db"
dm schema migrate --source plain.yaml --target "db:sqlite:///w/x.db" --execute --report xr.json >/dev/null 2>&1
dm data transfer --source "$(purl rd)" --target "sqlite:///w/x.db" --tables t --verify >"$WORK/t2.out" 2>&1; ex=$?
res "transfer PG->SQLite --verify (Reader: Basistyp)" "exit=$ex $(tr '\n' ' ' < "$WORK/t2.out" | cut -c1-300)"
rm -rf "$WORK/pq"; mkdir -p "$WORK/pq"
dm data export --source "$(purl rd)" --format parquet --output pq --split-files --tables t >"$WORK/e1.out" 2>&1; ex=$?
res "export parquet (Reader: Basistyp)" "exit=$ex files=$(ls "$WORK/pq" | tr '\n' ' ') manifest-d=$(grep -A3 'name: d' "$WORK/pq/manifest.yaml" | grep kind | tr -s ' ')"

# ---- M7 SQLite-Altbefund (kontextfreier Rollback) -----------------------
echo "== M7 SQLite: Domain-Spalte, Migrate + Rollback"
rm -f "$WORK/s.db" "$WORK/sdown.sql"
mk lite_base.yaml '{ type: integer }' 1
sed -i.bak '/^      d: /d' "$WORK/lite_base.yaml"; rm -f "$WORK/lite_base.yaml.bak"
dm schema migrate --source lite_base.yaml --target "db:sqlite:///w/s.db" --execute --report sr0.json >/dev/null 2>&1; ex=$?
res "sqlite basis anlegen" "exit=$ex"
dm schema migrate --source ref.yaml --target "db:sqlite:///w/s.db" --execute --generate-rollback \
   --rollback-output sdown.sql --report sr1.json >"$WORK/sl.out" 2>&1; ex=$?
res "sqlite migrate Domain-Spalte (ref_type)" "exit=$ex rollback-artefakt=$([ -f "$WORK/sdown.sql" ] && echo ja || echo nein)"
if [ -f "$WORK/sdown.sql" ]; then
  dm schema rollback --source sdown.sql --target "db:sqlite:///w/s.db" --execute --allow-destructive >"$WORK/sl2.out" 2>&1; ex=$?
  res "sqlite schema rollback" "exit=$ex $(tail -n 2 "$WORK/sl2.out" | tr '\n' ' ' | cut -c1-200)"
fi
echo "== Ende"
