#!/usr/bin/env bash
# Trivy gegen die PUBLIZIERTEN Container-Images.
#
# Prueft, was Anwender tatsaechlich ziehen — nicht den Arbeitsbaum. Zwischen zwei
# Releases altert das publizierte Image, ohne dass sich das Repo aendert; ein
# push-getriggertes Gate ist gegen diesen Fall prinzipiell blind
# (docs/planning/open/security-gates-not-in-ci.md).
#
# Zwei Laeufe je Image, mit Absicht verschieden:
#   1. Vollbericht ueber alle Schweregrade — beantwortet "was steckt gerade
#      drin", auch wenn nichts davon behebbar ist.
#   2. Entscheidungslauf: CRITICAL/HIGH, die einen Fix HABEN und nicht begruendet
#      in .trivyignore.yaml stehen.
# Ein Nightly, das an nicht behebbaren Basis-Image-CVEs rot wird, ist in zwei
# Wochen ein weggeklicktes Abzeichen und dann schlechter als nichts.
#
# BEIDE Laeufe fahren `--exit-code 0`, und das ist der Kern der Fehlerbehandlung:
# Trivy quittiert einen ECHTEN Fehler (Image nicht gefunden, Registry nicht
# erreichbar, DB-Download gescheitert) ebenfalls mit Exit 1 — nachgemessen. Mit
# `--exit-code 1` waere ein Netzausfall von einem Befund nicht zu unterscheiden,
# und das Gate meldete "behebbare CRITICAL/HIGH", wo in Wahrheit gar nicht
# geprueft wurde. Mit `--exit-code 0` heisst ein Nicht-Null-Exit eindeutig
# "Scan gescheitert"; ueber Befunde entscheidet die Auswertung des JSON.
#
# KEIN Docker-Socket: Trivy liest die Images direkt aus der Registry. Das
# Security-Audit vom 2026-07-17 hat das Mounten von /var/run/docker.sock als
# Host-Root-Ausbruchspfad markiert; fuer publizierte Images braucht es ihn nicht.
# Die Vuln-DB wird aus dem Netz geladen — hier gibt es bewusst kein
# `--network none` wie bei semgrep/a-check: Aktualitaet IST der Zweck.
#
# Exit-Codes: 0 = sauber, 1 = behebbare Befunde, 2 = Scan gescheitert.
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TRIVY_IMAGE="${TRIVY_IMAGE:?TRIVY_IMAGE muss gesetzt sein (Pin steht in make/gate.mk)}"
IMAGE_SCAN_REFS="${IMAGE_SCAN_REFS:?IMAGE_SCAN_REFS muss gesetzt sein}"
CACHE="${ROOT}/.cache/trivy"
IGNOREFILE="${ROOT}/.trivyignore.yaml"

mkdir -p "${CACHE}"

run_trivy() {
  docker run --rm \
    -v "${CACHE}:/root/.cache/trivy" \
    -v "${IGNOREFILE}:/work/.trivyignore.yaml:ro" \
    "${TRIVY_IMAGE}" image --no-progress --scanners vuln \
    --ignorefile /work/.trivyignore.yaml --exit-code 0 "$@"
}

findings=0
errored=0

for ref in ${IMAGE_SCAN_REFS}; do
  echo "=============================================================="
  echo "== Vollbericht: ${ref}"
  echo "=============================================================="
  if ! run_trivy --severity CRITICAL,HIGH,MEDIUM,LOW --format table "${ref}"; then
    echo "::error title=Image-Scan::Scan von ${ref} ist GESCHEITERT (nicht: Befunde gefunden)."
    errored=1
    continue
  fi

  echo
  echo "--------------------------------------------------------------"
  echo "-- Handlungspflichtig (CRITICAL/HIGH mit verfuegbarem Fix): ${ref}"
  echo "--------------------------------------------------------------"
  if ! json="$(run_trivy --severity CRITICAL,HIGH --ignore-unfixed --format json "${ref}")"; then
    echo "::error title=Image-Scan::Entscheidungslauf fuer ${ref} ist GESCHEITERT."
    errored=1
    continue
  fi

  # Auswertung getrennt vom Scan: so entscheidet der INHALT, nicht der Exit-Code.
  count="$(printf '%s' "${json}" | python3 -c '
import json, sys
try:
    d = json.load(sys.stdin)
except Exception:
    print("PARSE_ERROR"); raise SystemExit(0)
n = 0
for r in d.get("Results") or []:
    for v in r.get("Vulnerabilities") or []:
        print("  %-9s %-34s %-16s -> fix %s  %s" % (
            v.get("Severity"), v.get("PkgName"), v.get("InstalledVersion"),
            v.get("FixedVersion", "-"), v.get("VulnerabilityID")), file=sys.stderr)
        n += 1
print(n)
')"

  if [ "${count}" = "PARSE_ERROR" ]; then
    echo "::error title=Image-Scan::Trivy-Ausgabe fuer ${ref} war kein gueltiges JSON."
    errored=1
  elif [ "${count}" = "0" ]; then
    echo "OK — keine behebbaren CRITICAL/HIGH in ${ref}."
  else
    echo "::error title=Image-Scan::${ref}: ${count} behebbare CRITICAL/HIGH-Befunde."
    findings=1
  fi
  echo
done

if [ "${errored}" = "1" ]; then
  exit 2
fi
exit "${findings}"
