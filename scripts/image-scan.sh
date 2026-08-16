#!/usr/bin/env bash
# Trivy gegen die PUBLIZIERTEN Container-Images.
#
# Prueft, was Anwender tatsaechlich ziehen — nicht den Arbeitsbaum. Zwischen zwei
# Releases altert das publizierte Image, ohne dass sich das Repo aendert; ein
# push-getriggertes Gate ist gegen diesen Fall prinzipiell blind
# (docs/planning/open/security-gates-not-in-ci.md).
#
# Zwei Laeufe je Image, mit Absicht verschieden:
#   1. Vollbericht ueber alle Schweregrade — faellt NIE. Er beantwortet
#      "was steckt gerade drin", auch wenn nichts davon behebbar ist.
#   2. Handlungspflichtige Teilmenge: CRITICAL/HIGH, die einen Fix HABEN und
#      nicht begruendet in .trivyignore.yaml stehen — faellt.
# Ein Nightly, das an nicht behebbaren Basis-Image-CVEs rot wird, ist in zwei
# Wochen ein weggeklicktes Abzeichen und dann schlechter als nichts.
#
# KEIN Docker-Socket: Trivy liest die Images direkt aus der Registry. Das
# Security-Audit vom 2026-07-17 hat das Mounten von /var/run/docker.sock als
# Host-Root-Ausbruchspfad markiert; fuer publizierte Images braucht es ihn nicht.
# Die Vuln-DB wird aus dem Netz geladen — hier gibt es bewusst kein
# `--network none` wie bei semgrep/a-check: Aktualitaet IST der Zweck.
set -euo pipefail

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
    --ignorefile /work/.trivyignore.yaml "$@"
}

failed=0
for ref in ${IMAGE_SCAN_REFS}; do
  echo "=============================================================="
  echo "== Vollbericht: ${ref}"
  echo "=============================================================="
  # `|| true`: der Bericht darf den Lauf nie faellen, auch nicht bei einem
  # Registry-/Netzfehler — dafuer ist der zweite Lauf zustaendig.
  run_trivy --severity CRITICAL,HIGH,MEDIUM,LOW --exit-code 0 "${ref}" || true

  echo
  echo "--------------------------------------------------------------"
  echo "-- Handlungspflichtig (CRITICAL/HIGH mit verfuegbarem Fix): ${ref}"
  echo "--------------------------------------------------------------"
  if run_trivy --severity CRITICAL,HIGH --ignore-unfixed --exit-code 1 "${ref}"; then
    echo "OK — keine behebbaren CRITICAL/HIGH in ${ref}."
  else
    echo "::error title=Image-Scan::${ref} enthaelt behebbare CRITICAL/HIGH-Befunde."
    failed=1
  fi
  echo
done

exit "${failed}"
