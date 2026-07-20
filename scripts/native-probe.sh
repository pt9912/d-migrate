#!/usr/bin/env bash
# Phase-F.0-Sonden fuer das GraalVM-Native-Binary
# (docs/planning/in-progress/graalvm-native-image-distribution.md).
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

echo
cat "${REPORT}"
exit 0
