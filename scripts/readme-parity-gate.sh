#!/usr/bin/env bash
# README-Sprachparitaets-Gate.
#
# Warum: `docs-check` (d-check) validiert Links und Anker, aber NICHT, ob die beiden
# Root-READMEs denselben Stand beschreiben. Beim 1.0.0-RC2-Cut wurde nur README.md
# gepflegt; README.de.md nannte danach weiter Version 0.9.8 und fuehrte 0.9.9 als
# "Geplant" — sechs Releases Rueckstand auf der Startseite, ueber Wochen unbemerkt.
# Genau diese Klasse faengt dieses Gate.
#
# Geprueft wird NICHT der Text (Uebersetzungen duerfen frei formulieren), sondern das,
# was strukturell gleich sein MUSS:
#   1. die Menge der genannten Versionsnummern im Status-Block
#   2. die Anzahl der Milestone-Eintraege im Status-Block
#   3. die Versionsangabe im "Was kann ich heute laufen lassen"-Abschnitt
#
# Aufruf: scripts/readme-parity-gate.sh   (Exit 1 bei Abweichung)
set -uo pipefail

EN="README.md"
DE="README.de.md"
fail=0

for f in "${EN}" "${DE}"; do
  [ -f "${f}" ] || { echo "FEHLER: ${f} fehlt"; exit 1; }
done

# --- Status-Block extrahieren (von "## Status" bis zur naechsten "## "-Ueberschrift) ----
status_block() {
  awk '/^## Status$/{f=1;next} f&&/^## /{exit} f' "$1"
}

# --- 1) Versionsnummern im Status-Block --------------------------------------------
# Praerelease-Suffix bewusst auf die tatsaechlich verwendeten Kennungen begrenzt statt
# auf beliebiges [A-Za-z0-9.]: sonst verschluckt der deutsche Bindestrich-Kompositum-Stil
# ("1.0.0-Release-Candidate") das Wort als Suffix und meldet eine Phantom-Abweichung
# gegen das englische "1.0.0 release candidate".
VERSION_RE='[0-9]+\.[0-9]+\.[0-9]+(-(RC[0-9]+|SNAPSHOT))?'

versions_in_status() {
  status_block "$1" | grep -oE "${VERSION_RE}" | sort -u
}

en_versions="$(versions_in_status "${EN}")"
de_versions="$(versions_in_status "${DE}")"

if [ "${en_versions}" != "${de_versions}" ]; then
  echo "FEHLER: Die Status-Bloecke nennen unterschiedliche Versionen."
  echo "--- nur in ${EN}:"; comm -23 <(printf '%s\n' "${en_versions}") <(printf '%s\n' "${de_versions}") | sed 's/^/    /'
  echo "--- nur in ${DE}:"; comm -13 <(printf '%s\n' "${en_versions}") <(printf '%s\n' "${de_versions}") | sed 's/^/    /'
  fail=1
fi

# --- 2) Anzahl der Milestone-Eintraege ---------------------------------------------
en_bullets="$(status_block "${EN}" | grep -c '^- \*\*')"
de_bullets="$(status_block "${DE}" | grep -c '^- \*\*')"

if [ "${en_bullets}" != "${de_bullets}" ]; then
  echo "FEHLER: Status-Block hat unterschiedlich viele Eintraege — ${EN}=${en_bullets}, ${DE}=${de_bullets}."
  fail=1
fi

# --- 3) Aktuelle Version im "heute nutzbar"-Abschnitt -------------------------------
# EN: "at version **X**", DE: "in Version **X**" — jeweils die erste Fettung danach.
# Zeilenumbrueche vorher glaetten: die deutsche Fassung umbricht zwischen "in Version"
# und der Fettung, ein zeilenweises grep faende sie nie.
current_version() {
  tr '\n' ' ' < "$1" \
    | grep -oiE "(at|in) version \*\*${VERSION_RE}\*\*" \
    | head -1 | grep -oE "${VERSION_RE}"
}

en_current="$(current_version "${EN}")"
de_current="$(current_version "${DE}")"

if [ -z "${en_current}" ] || [ -z "${de_current}" ]; then
  echo "FEHLER: Versionsangabe (\"at/in version **X.Y.Z**\") in ${EN} und/oder ${DE} nicht gefunden."
  echo "        Wurde der Abschnitt umformuliert? Dann dieses Gate mit anpassen."
  fail=1
elif [ "${en_current}" != "${de_current}" ]; then
  echo "FEHLER: Unterschiedliche aktuelle Version — ${EN}=${en_current}, ${DE}=${de_current}."
  fail=1
fi

if [ "${fail}" -ne 0 ]; then
  echo
  echo "Beide Sprachfassungen gehoeren im SELBEN Commit gepflegt (releasing.md 3.6)."
  exit 1
fi

echo "README-Paritaet ok: ${en_bullets} Status-Eintraege, aktuelle Version ${en_current}, Versionsmengen identisch."
