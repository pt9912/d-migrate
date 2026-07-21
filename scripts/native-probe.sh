#!/usr/bin/env bash
# Phase-F.0-Sonden fuer das GraalVM-Native-Binary
# (docs/planning/done/graalvm-native-image-distribution.md).
#
# MESSUNG, kein Gate: jede Sonde laeuft, ihr Ergebnis wird protokolliert, das Skript endet mit 0.
# Ein Fehlschlag ist hier ein BEFUND, kein Fehler — bräche das Skript ab, bekaeme man nur den
# ersten Blocker statt der Liste.
#
# JVM-GRUNDLINIE: alle Sonden wurden gegen die JVM-CLI (`d-migrate:dev`) ausgefuehrt und liefern
# dort ausnahmslos Exit 0. Jeder Nicht-Null-Code hier ist damit ein native-image-Befund und KEIN
# Aufruffehler.
#
# Eine Kopie fuer lokal UND CI: `make native-probe` ruft dasselbe Skript wie
# .github/workflows/native-image.yml. Zwei Kopien wuerden auseinanderlaufen.
#
# Aufruf: native-probe.sh [PFAD_ZUM_BINARY] [REPORT_PFAD]
set -uo pipefail

BIN="${1:-/src/adapters/driving/cli/build/native/nativeCompile/d-migrate}"
REPORT="${2:-/tmp/f0-report.md}"
OUTDIR="$(dirname "${REPORT}")/f0-out"
SCHEMA="examples/sample-db/calib-schema.yaml"
PROBE_DB="${OUTDIR}/f0-probe.db"

# Auf Windows laeuft dieses Skript unter Git-Bash, das POSIX-Pfade (/d/a/...) fuehrt — das native
# Binary ist aber eine Windows-Anwendung und erwartet D:/a/... Ohne Umrechnung scheitert die
# SQLite-Sonde mit SQLITE_CANTOPEN und sieht wie ein JNI-Befund aus, obwohl nur der Pfad falsch ist.
# `cygpath -m` liefert die Mixed-Form (Laufwerksbuchstabe + Schraegstriche), die in eine JDBC-URL passt.
# Der POSIX-Zweig behaelt bewusst exakt die bisher bewaehrte Form `sqlite:///` + absoluter Pfad
# (ergibt vier Schraegstriche). Nicht "vereinheitlichen": die Slash-Anzahl ist bei URL-Parsing
# bedeutungstragend, das Projekt hatte dort bereits einen Authority-Kollaps-Bug.
if command -v cygpath >/dev/null 2>&1; then
  PROBE_DB_URL="sqlite:///$(cygpath -m "${PROBE_DB}")"
else
  PROBE_DB_URL="sqlite:///${PROBE_DB}"
fi

mkdir -p "${OUTDIR}"

# Minimal, dialektneutral, keine custom_types. calib-schema.yaml taugt als SQLite-JNI-Sonde NICHT:
# sein Enum laesst `schema migrate` fachlich mit Exit 8 (DIALECT_UNSUPPORTED_OPERATION) blocken —
# die DB entsteht, aber es wird kein DDL angewendet, der Schreibpfad also nie wirklich getestet.
cat > "${OUTDIR}/f0-probe-schema.yaml" <<'YAML'
schema_format: "1.0"
name: "f0_probe"
version: "1.0.0"
encoding: "utf-8"
tables:
  probe_items:
    columns:
      id: { type: identifier, auto_increment: true }
      label: { type: text, max_length: 80, required: true }
      amount: { type: decimal, precision: 10, scale: 2 }
    primary_key: [id]
YAML

{
  echo "# Phase F.0 — Sondenlauf"
  echo
  echo "- Binary: ${BIN}"
  echo "- JVM-Grundlinie: alle Sonden Exit 0"
  echo
  echo "| # | Zweck | Exit | Erste Fehlerzeile |"
  echo "| --- | --- | --- | --- |"
} > "${REPORT}"

n=0
probe() {
  local purpose="$1"; shift
  n=$((n + 1))
  local log="${OUTDIR}/probe-${n}.log" rc
  "$@" > "${log}" 2>&1 && rc=0 || rc=$?

  # Den Exception-KOPF suchen, nicht die letzte Zeile: `tail` zeigt den innersten Stackframe und
  # verschweigt genau die Diagnose. Logausgabe (Hikari-WARN o. ae.) steht oft davor, deshalb
  # gezielt nach der Exception-Zeile greifen und erst ersatzweise die erste Nicht-Frame-Zeile.
  local first
  first="$(grep -m1 -E '(Exception|Error)( in thread)?[: ]' "${log}" 2>/dev/null)"
  if [ -z "${first}" ]; then
    first="$(grep -vE '^[[:space:]]*(at |\.\.\.)' "${log}" | grep -vE '^\s*$' | head -1)"
  fi
  first="$(printf '%s' "${first}" | tr '|' '/' | cut -c1-150)"

  printf '%s\n' "--- probe ${n} (exit ${rc}): $*" >&2
  sed -n '1,20p' "${log}" >&2
  printf '| %s | %s | %s | %s |\n' \
    "${n}" "${purpose}" "${rc}" "${first:-(keine Ausgabe)}" >> "${REPORT}"
}

# AP 2 — Startpfad: ServiceLoader-Aufloesung + Treiber-/ICU-Provider-Konstruktion.
probe "Startpfad --version" "${BIN}" --version
probe "Startpfad --help"    "${BIN}" --help

# AP 3 — was --help NICHT beweist: SqliteDriver hat keinen Konstruktor-Body (laedt beim Start keine
# Nativelib) und IcuUnicodeTextService ist konstruktionsseitig leer (ICU erst in normalize).
# Beides braucht echte Nutzung.
probe "ICU-Resources (normalize im Header)" \
  "${BIN}" schema validate --source "${SCHEMA}"
probe "sqlite-JNI Schreibpfad (DDL wird angewendet)" \
  "${BIN}" schema migrate --source "${OUTDIR}/f0-probe-schema.yaml" \
    --target "db:${PROBE_DB_URL}" --execute --report "${OUTDIR}/migrate-report.yaml"
probe "sqlite-JNI Lesepfad" \
  "${BIN}" schema reverse --source "${PROBE_DB_URL}" --output "${OUTDIR}/reverse.yaml"

# AP 4 — Kern-Kommandos gegen den HEUTIGEN Korpus (kommando-lokale Flaechen fehlen dort, F.2).
probe "DDL-Rendering PostgreSQL" \
  "${BIN}" schema generate --source "${SCHEMA}" --target postgresql --output "${OUTDIR}/pg.sql"
probe "DDL-Rendering MySQL" \
  "${BIN}" schema generate --source "${SCHEMA}" --target mysql --output "${OUTDIR}/my.sql"
probe "DDL-Rendering SQLite" \
  "${BIN}" schema generate --source "${SCHEMA}" --target sqlite --output "${OUTDIR}/lite.sql"
probe "JSON-Ausgabepfad (zweiter Formatter-Zweig)" \
  "${BIN}" --output-format json schema validate --source "${SCHEMA}"

# AP 5 — die kommando-lokalen Schwergewichts-Flaechen (Phase F.4). Bis 2026-07-20 beruehrte der
# Korpus KEINE davon: der Tracing-Agent konnte fuer sie folglich nichts aufzeichnen, und ihre
# Metadaten waren still unvollstaendig. Genau diese Luecke schliessen die folgenden Sonden.
#
# Alle Aufrufe vorab gegen die JVM-CLI (`d-migrate:dev`) verifiziert — Exit 0. Zwei Fallstricke, die
# dabei auffielen: `export django` verlangt eine 4-stellige Version, `export knex` eine numerische;
# aus der Flag-Hilfe allein waere das nicht ersichtlich gewesen (beide scheitern sonst mit Exit 2).
probe "data profile (Profiling-Pfad)" \
  "${BIN}" data profile --source "${PROBE_DB_URL}" --output "${OUTDIR}/profile.json"
probe "data export PARQUET (parquet-hadoop)" \
  "${BIN}" data export --source "${PROBE_DB_URL}" --format parquet --output "${OUTDIR}/out.parquet"
probe "export flyway (Tool-Export)" \
  "${BIN}" export flyway --source "${SCHEMA}" --output "${OUTDIR}/fly" --target postgresql
probe "export liquibase (Tool-Export)" \
  "${BIN}" export liquibase --source "${SCHEMA}" --output "${OUTDIR}/lb" --target postgresql
probe "export django (Tool-Export)" \
  "${BIN}" export django --source "${SCHEMA}" --output "${OUTDIR}/dj" --target postgresql \
    --version 0001
probe "export knex (Tool-Export)" \
  "${BIN}" export knex --source "${SCHEMA}" --output "${OUTDIR}/kx" --target postgresql \
    --version 20260720120000

# `mcp serve` laeuft als stdio-Server und beendet sich nicht von selbst — deshalb ein einzelner
# initialize-Handshake ueber die Pipe statt eines normalen Aufrufs. Die Protokollversion ist die vom
# Server geforderte; eine falsche liefert zwar auch eine Antwort, aber als JSON-RPC-Fehler.
n=$((n + 1))
mcp_log="${OUTDIR}/probe-${n}.log"
printf '%s\n' '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"probe","version":"1"}}}' \
  | "${BIN}" mcp serve --transport stdio > "${mcp_log}" 2>&1
mcp_rc=$?
# Der Exit-Code taugt hier NICHT als Signal: der Server beendet sich bei EOF sauber mit 0, auch
# wenn er den Request mit einem Fehlerobjekt beantwortet hat. Bewertet wird die ANTWORT.
mcp_response="$(grep -m1 -E '"jsonrpc"' "${mcp_log}" 2>/dev/null | tr '|' '/' | cut -c1-130)"
if printf '%s' "${mcp_response}" | grep -q '"result"'; then
  mcp_verdict="ok — initialize beantwortet"
elif [ -n "${mcp_response}" ]; then
  mcp_verdict="**Blocker** — Antwort ohne result: ${mcp_response}"
else
  mcp_verdict="**Blocker** — keine JSON-RPC-Antwort"
fi
printf '%s\n' "--- probe ${n} (exit ${mcp_rc}): mcp serve initialize" >&2
sed -n '1,20p' "${mcp_log}" >&2
printf '| %s | %s | %s | %s |\n' \
  "${n}" "mcp serve (stdio-Handshake)" "${mcp_rc}" "${mcp_verdict}" >> "${REPORT}"

# AP 6 — S3-Artefaktablage (Phase F.4, letzte Flaeche). S3 ist KEIN eigenes Kommando, sondern die
# Artefakt-Ablage von `mcp serve` (`artifacts.store: s3`, McpCliRuntimeWiring -> S3ByteStores.create).
#
# Braucht KEINEN Server: der Startup-Sweep ueberspringt bei S3 die Segment-Laeufe, es wird also kein
# Request gesendet. Der Endpunkt zeigt bewusst ins Leere.
#
# WAS DAS PRUEFT: Konstruktion des AWS-SDK-Clients samt Credential-Chain und Region-Aufloesung —
# dort sitzt die Reflection.
#
# WAS ES NICHT PRUEFT: eine echte S3-Operation (Upload/Download/List). Das liegt NICHT an fehlender
# Infrastruktur — das Projekt hat mit `newSeaweedS3Container()` (storage-s3/src/testFixtures)
# Testcontainers-Unterstuetzung fuer SeaweedFS. Gegengeprueft 2026-07-20 mit einem LIVE laufenden
# SeaweedFS: das Binary sendete NULL Requests. Der initialize-Handshake erzeugt keine Artefakte, und
# der Startup-Sweep ueberspringt bei S3 die Segment-Laeufe. Ein echter Operationstest braeuchte einen
# artefakt-erzeugenden MCP-tools/call — eigene Orchestrierung, offener Punkt.
# Bewertet wird die Startzeile, nicht der Exit-Code: der Server endet bei EOF ohnehin mit 0.
n=$((n + 1))
s3_log="${OUTDIR}/probe-${n}.log"
cat > "${OUTDIR}/s3-probe.yaml" <<'YAML'
artifacts:
  store: s3
  s3:
    bucket: "f0-probe-bucket"
    endpoint: "http://127.0.0.1:1"
    region: "eu-central-1"
    pathStyle: true
YAML
printf '%s\n' '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"probe","version":"1"}}}' \
  | AWS_ACCESS_KEY_ID=probe AWS_SECRET_ACCESS_KEY=probe \
    "${BIN}" --config "${OUTDIR}/s3-probe.yaml" mcp serve --transport stdio > "${s3_log}" 2>&1
s3_rc=$?
if grep -q "S3-backed" "${s3_log}" 2>/dev/null; then
  s3_verdict="ok — S3-Client konstruiert (keine Operation geprueft)"
else
  s3_first="$(grep -m1 -E '(Exception|Error)[: ]' "${s3_log}" | tr '|' '/' | cut -c1-120)"
  s3_verdict="**Blocker** — ${s3_first:-keine S3-Startzeile}"
fi
printf '%s\n' "--- probe ${n} (exit ${s3_rc}): mcp serve mit artifacts.store=s3" >&2
sed -n '1,20p' "${s3_log}" >&2
printf '| %s | %s | %s | %s |\n' \
  "${n}" "S3-Artefaktablage (AWS-SDK)" "${s3_rc}" "${s3_verdict}" >> "${REPORT}"

echo
cat "${REPORT}"
exit 0
