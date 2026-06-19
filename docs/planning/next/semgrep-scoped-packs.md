# Semgrep-Gate auf scoped Packs umstellen

> **Status:** Draft mit Scope (2026-06-19) — Fortsetzung morgen.
> Das hermetische semgrep-Gate ist bereits geliefert (Commits `43bda239`,
> `6d39d49e`); dieser Eintrag ist der **nächste Increment**: das gepinnte
> Regelset von 2 cherry-gepickten Regeln auf **scoped Packs** verbreitern.

## Ziel

Ein breiteres, weiterhin **hermetisches** (offline, reproduzierbares)
Sicherheits-Gate: statt 2 einzelner Regeln ein paar **scoped Packs**, die zum
Stack passen — gecacht + per SHA256 content-gepinnt, kein `--config auto`.

## Ausgangslage (bereits geliefert)

- [`make semgrep`](../../../Makefile) läuft hermetisch: `docker run --network none
  --metrics off … --config /src/config/semgrep`, Scanner-Image per Digest gepinnt
  (`SEMGREP_IMAGE`). In `make gates` + `make docker-gates` (lokale Gates; die CI
  ruft sie derzeit **nicht** auf — ein eigener CI-Job wäre eine separate
  Entscheidung).
- Regeln **nicht vendored** (Upstream `semgrep-rules` = LGPL-2.1 + Commons Clause):
  [`scripts/fetch-semgrep-rules.sh`](../../../scripts/fetch-semgrep-rules.sh) holt
  sie gepinnt + SHA256-verifiziert nach `config/semgrep/` (gitignored; nur
  [`config/semgrep/README.md`](../../../config/semgrep/README.md) eingecheckt).
- Aktuell gecacht: nur `missing-user` + `use-defused-xml` (eng).
- Bewusst akzeptierte Befunde: 8 ephemere CI-Helfer-Stages im
  [`Dockerfile`](../../../Dockerfile) via `# nosemgrep: <rule-id>` (Begründung am
  Fundort). Hintergründe in [`config/semgrep/README.md`](../../../config/semgrep/README.md).

## Datengrundlage (am 2026-06-19 gemessen)

Test offline gegen das Repo mit `p/dockerfile` + `p/secrets` + `p/python`
(als YAML von `https://semgrep.dev/c/p/<name>` gezogen):

- **198 Regeln**, **8 Findings — alle** der bereits bekannten Build-Stage-
  `ENTRYPOINT`s (Regel `…dockerfile.security.missing-user-entrypoint…`),
  **null neue** Befunde (secrets/python sauber).
- Ein Pack lässt sich als **ein YAML** ziehen und damit wie bisher cachen +
  pinnen. Packs haben keinen Git-Commit → der **SHA256 des Pack-YAML *ist* der
  Pin** (ändert die Registry den Pack, schlägt die Verifikation an → bewusster
  Pin-Bump).

## Scope-Skizze (morgen)

1. **Pack-Auswahl bestätigen** (offene Frage unten).
2. [`scripts/fetch-semgrep-rules.sh`](../../../scripts/fetch-semgrep-rules.sh)
   umstellen: statt der 2 Einzelregeln die Packs (`semgrep.dev/c/p/<pack>`, ein
   YAML je Pack) in den `config/semgrep/`-Cache ziehen, je SHA256-verifiziert.
   Pin-Tabelle + Fetch-Datum im Script-Kopf.
3. **nosemgrep-IDs anpassen** im [`Dockerfile`](../../../Dockerfile): die Pack-
   Rule-ID ist pfad-präfixiert (`config.semgrep.dockerfile.security.missing-user-entrypoint.missing-user-entrypoint`
   o. ä.) — am Anfang **einmal die echte ID per Lauf ermitteln** (`make semgrep`
   zeigt sie im Finding) und die 8 Kommentare darauf setzen.
4. [`config/semgrep/README.md`](../../../config/semgrep/README.md) auf die Packs
   aktualisieren.
5. **Offline-grün verifizieren:** Cache löschen → `make semgrep` → fetch + Scan
   `--network none` → 0 Findings, exit 0.
6. Commit auf `develop`.

## Offene Fragen (vor Schritt 2 klären)

- **Pack-Set:** Vorschlag `p/dockerfile` + `p/secrets` + `p/python` (geprüft: 8
  bekannte / 0 neue Findings). Reicht das, oder breiter?
- **`p/github-actions`** zusätzlich (deckt `.github/workflows/` ab)? Noch nicht
  gegen das Repo gemessen — Triage-Kosten unbekannt, vor Aufnahme einmal laufen
  lassen.
- Bewusst **nicht** `p/default` / `p/security-audit` (sehr breit → viel Triage),
  außer es wird ausdrücklich gewünscht.

## Vorbedingungen

- Keine — alle Bausteine (hermetisches Gate, Fetch-Pattern, nosemgrep-Mechanik)
  stehen bereits. Reines Verbreitern des Regelsets.

## Nicht-Ziel

- Aufnahme in den CI-Workflow (`.github/workflows/build.yml`) — eigene
  Entscheidung; das Gate ist heute lokal (`make gates`/`make docker-gates`).
