#!/usr/bin/env bash
# Eroeffnet EIN Issue, wenn der Nightly-Image-Scan behebbare CRITICAL/HIGH im
# publizierten Image findet. Aufgerufen aus .github/workflows/image-scan.yml.
#
# Warum ueberhaupt ein Issue statt nur eines roten Laufs: Ein fehlgeschlagener
# Scheduled-Run erzeugt keine Benachrichtigung, auf die jemand zwangslaeufig
# stoesst — er faellt nur im Actions-Reiter auf, in den niemand taeglich sieht.
#
# Warum genau EINES: Der Befund bleibt bestehen, bis ein neues Release das
# publizierte Image bewegt. Ein datiertes Issue pro Nacht (Muster aus dem
# Schwesterprojekt m-trace) waere hier taeglicher Spam und trainierte genau das
# Wegklicken an, das dieses Gate vermeiden soll. Existiert bereits ein offenes
# Issue mit demselben Titel, passiert nichts — der rote Lauf und das
# Artefakt tragen die Details.
#
# Erwartete Env-Variablen (aus dem Workflow):
#   GH_TOKEN   Token mit `issues:write`
#   RUN_URL    Link auf den Lauf
#   LOG_FILE   Pfad zum Scan-Log (fuer den Auszug im Issue-Body)
set -euo pipefail

TITLE="Image-Scan: behebbare CRITICAL/HIGH im publizierten Image"
LOG_FILE="${LOG_FILE:-}"

existing="$(gh issue list --state open --search "\"${TITLE}\" in:title" \
  --json number,title --jq "[.[] | select(.title == \"${TITLE}\")] | .[0].number // empty")"

if [ -n "${existing}" ]; then
  echo "Issue #${existing} ist bereits offen — kein neues angelegt."
  exit 0
fi

extract() {
  if [ -n "${LOG_FILE}" ] && [ -f "${LOG_FILE}" ]; then
    # Nur die Abschnitte der handlungspflichtigen Laeufe; der Vollbericht ist
    # zu lang fuer einen Issue-Body und liegt vollstaendig im Artefakt.
    grep -E "^-- Handlungspflichtig|^Total:|^::error|^OK —" "${LOG_FILE}" | head -40
  else
    echo "<kein Log erfasst>"
  fi
}

body="$(cat <<EOF
Der naechtliche Trivy-Lauf hat im **publizierten** Image CRITICAL- oder
HIGH-Befunde gefunden, **fuer die es einen Fix gibt**. Nicht behebbare Befunde
loesen dieses Issue bewusst nicht aus.

**Lauf:** ${RUN_URL:-<unbekannt>}

\`\`\`
$(extract)
\`\`\`

Der vollstaendige Bericht ueber alle Schweregrade haengt als Artefakt am Lauf.

**Zur Einordnung:** Das publizierte Image bewegt sich erst mit dem naechsten
Release. Sind die Abhaengigkeiten auf \`develop\` bereits gehoben, bleibt dieses
Issue bis zum Release bestehen und schliesst sich danach von selbst — das ist
kein Fehlalarm, sondern die Aussage "was draussen liegt, ist aelter als der
Arbeitsbaum".

Begruendete Ausnahmen gehoeren nach \`.trivyignore.yaml\` (mit \`statement\` und
\`expired_at\`), nicht in eine Unterdrueckung ohne Spur.
EOF
)"

gh issue create --title "${TITLE}" --body "${body}" --label security --label docker
echo "Issue angelegt."
