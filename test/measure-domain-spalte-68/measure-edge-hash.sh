#!/usr/bin/env bash
# Edge: Datenbank-Hash je Domain-Basistyp (real, bit(5), numeric ohne precision, varchar(50)[]) -- Image als $IMAGE
IMAGE=$1; WORK=$(mktemp -d "$HOME/.dm-p68e-XXXXXX"); NET=dm-e-net-$$; PG=dm-e-pg-$$
trap 'docker rm -f $PG >/dev/null 2>&1; docker network rm $NET >/dev/null 2>&1; rm -rf $WORK' EXIT
docker network create $NET >/dev/null; docker run -d --name $PG --network $NET -e POSTGRES_PASSWORD=pw postgres:18.6-alpine >/dev/null
until docker exec $PG pg_isready -U postgres >/dev/null 2>&1; do sleep 1; done; sleep 2
i=0
for base in real "bit(5)" numeric "varchar(50)[]"; do
  i=$((i+1)); docker exec $PG psql -q -U postgres -c "create database e$i" >/dev/null
  docker exec -i $PG psql -q -U postgres e$i <<SQL
CREATE DOMAIN dd AS $base;
CREATE TABLE t (id serial primary key, d dd);
SQL
  printf 'schema_format: "1.0"\nname: "T"\nversion: "1.0.0"\ntables:\n  t:\n    columns:\n      id: { type: identifier, auto_increment: true }\n      n: { type: integer }\n    primary_key: [id]\n' > $WORK/f.yaml
  docker run --rm --network $NET -v "$WORK:/w" -w /w $IMAGE schema migrate --source f.yaml --target "db:postgresql://postgres:pw@$PG:5432/e$i" --plan-only --report r.json --plan-artefact pa.json >/dev/null 2>&1
  # Datei = reverse der Datenbank, die Spalte d von Hand als ref_type geschrieben: der Plan gegen die Datenbank
  docker run --rm --network $NET -v "$WORK:/w" -w /w $IMAGE schema reverse --source "postgresql://postgres:pw@$PG:5432/e$i" --output rev.yaml >/dev/null 2>&1
  python3 -I - "$WORK/rev.yaml" <<'PY'
import re,sys
p=sys.argv[1]; s=open(p).read()
s=re.sub(r"(\n      d:\n)(        [^\n]*\n)+?(?=        ordinal|      \w|    \w)", r"\1        type: enum\n        ref_type: dd\n", s, count=1)
open(p,'w').write(s)
PY
  docker run --rm --network $NET -v "$WORK:/w" -w /w $IMAGE schema migrate --source rev.yaml --target "db:postgresql://postgres:pw@$PG:5432/e$i" --plan-only --report r2.json >/dev/null 2>&1; ex=$?
  echo "base=$base ref_type-Datei gegen die Datenbank: exit=$ex alterColumnType=$(grep -c AlterColumnType $WORK/r2.json) ($(grep -A3 '^      d:' $WORK/rev.yaml | tr -s ' ' | tr '\n' ' '))"
  echo "base=$base src(DB)=$(python3 -I -c "import json;print(json.load(open('$WORK/pa.json'))['sourceFingerprint'][:12])")"
done
