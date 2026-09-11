#!/usr/bin/env bash
# Die Container-Images der Integrationstests stehen an genau einer Stelle je
# Dialekt (`test/test-images`), und jede Angabe traegt einen Digest.
#
# Ohne dieses Gate zerfaellt beides lautlos: eine neue Spec uebernimmt den
# Bildnamen aus der Nachbardatei, ein Versionssprung erwischt sie nicht, und
# ein Teil der Suiten prueft gegen einen anderen Server als der Rest — sichtbar
# erst, wenn ein Dialektfehler nur in der Haelfte der Module auftritt.
#
# Bewusst ein Shell-Gate (Projekt-Idiom, vgl. ports-jdbc-free-gate.sh): der
# Befund ist eine Zeichenkette in Testquellen, keine Typfrage, die Detekt
# beantworten koennte.
set -euo pipefail
cd "$(dirname "$0")/.."

CENTRAL="test/test-images/src/main/kotlin/dev/dmigrate/test/images/TestImages.kt"

# Repositories, die ueber TestImages laufen muessen. Abgeleitete, lokal gebaute
# Images (`d-migrate-mssql-fts:local`) stehen bewusst nicht hier — sie haben
# keinen Upstream-Digest (ADR 0014) und werden im jeweiligen Dockerfile gepinnt.
REPOS='postgres|postgis/postgis|mysql|mariadb|mcr\.microsoft\.com/mssql/server|gvenzl/oracle-free'

# `"repo:tag"` bzw. `"repo@sha256:…"`. Das `[^/"]` nach dem Doppelpunkt haelt
# Verbindungs-URLs (`"postgres://host/db"`) heraus.
hits="$(grep -rnE "\"($REPOS)(:[^/\"]|@)" --include=*.kt . 2>/dev/null \
          | grep -v "^\./$CENTRAL:" \
          | grep -vE '^[^:]+:[0-9]+:[[:space:]]*(\*|//|/\*)' || true)"

if [ -n "$hits" ]; then
  echo "FAIL: Container-Image ausserhalb von TestImages:"
  echo "$hits"
  echo ""
  echo "Fix: Konstante in $CENTRAL ergaenzen bzw. verwenden."
  exit 1
fi

# Jede Konstante dort traegt Version UND Digest — ein blosser Tag bewegt sich
# unter der Hand und nimmt zwei Laeufen die Vergleichbarkeit.
undigested="$(grep -nE "^\s+\"[^\"]+:[^/\"]" "$CENTRAL" \
                | grep -v "@\|sha256:" || true)"

if [ -n "$undigested" ]; then
  echo "FAIL: Image ohne Digest in $CENTRAL:"
  echo "$undigested"
  echo ""
  echo "Fix: docker buildx imagetools inspect --format '{{.Manifest.Digest}}' <image>"
  exit 1
fi

# Das abgeleitete Volltext-Image (`test/integration-mssql/fts/Dockerfile`) kann
# nicht auf die Konstante zeigen — es ist ein Dockerfile. Sein Basis-Digest muss
# deshalb hier mit dem von `MSSQL` uebereinstimmen, sonst prueft der
# Volltext-Slice gegen eine andere Engine als der Rest der Suiten.
FTS_DOCKERFILE="test/integration-mssql/fts/Dockerfile"

fts_base="$(grep -E '^FROM ' "$FTS_DOCKERFILE" | grep -oE 'sha256:[0-9a-f]{64}' | head -1)"
mssql_pin="$(awk '/val MSSQL: DockerImageName/,/asCompatibleSubstituteFor/' "$CENTRAL" \
               | grep -oE 'sha256:[0-9a-f]{64}' | head -1)"

if [ -z "$fts_base" ] || [ -z "$mssql_pin" ]; then
  echo "FAIL: Basis-Digest nicht auffindbar (FTS='$fts_base', TestImages='$mssql_pin')."
  exit 1
fi

if [ "$fts_base" != "$mssql_pin" ]; then
  echo "FAIL: das Volltext-Image steht auf einer anderen SQL-Server-Version:"
  echo "  $FTS_DOCKERFILE: $fts_base"
  echo "  TestImages.MSSQL:  $mssql_pin"
  echo ""
  echo "Fix: beide auf denselben Digest ziehen; die gepinnte mssql-server-fts-"
  echo "Version im Dockerfile gehoert zur Engine des Basis-Images."
  exit 1
fi

echo "OK: Container-Images zentral in test/test-images, jede Angabe mit Digest."
