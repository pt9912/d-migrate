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

## Nachtrag 2026-09-17 — Shell, Kotlin und YAML haben kein statisches Gate

Aus der Verifikation Runde 5 des Compare-Slices
([`compare-projektion-und-normalisierung.md`](../in-progress/compare-projektion-und-normalisierung.md),
„Offen"):

- **`make semgrep` fährt zwei Regeln auf fünf Dateien.** Die Ausgangslage und
  das Nicht-Ziel oben sind in einem Punkt überholt: das Gate läuft inzwischen
  in CI (Job `security-gates` in `build.yml`). An der Breite ändert das nichts.
- **Shell:** die E2E-Harnesses (`examples/mcp-e2e/scripts/`,
  `examples/sample-db/scripts/`) und `scripts/` haben kein Repo-Gate.
  shellcheck ist im Compare-Slice nur per Container gefahren worden;
  [`examples/mcp-e2e/scripts/smoke-scope-matrix.sh`](../../../examples/mcp-e2e/scripts/smoke-scope-matrix.sh)
  trägt zwei vorbestehende Befunde (`SC1091`, `SC2155`) und ist dort bewusst
  nicht angefasst. shellcheck ist kein semgrep-Pack — ein eigenes
  hermetisches Target (Image per Digest) liegt näher als ein Pack.
- **YAML:** die offene Frage `p/github-actions` oben deckt die Workflows ab
  (vor Aufnahme messen); die Compose-Dateien der Harnesses fallen nicht darunter.
- **Kotlin:** kein Pack im Vorschlag; ob `p/kotlin` für den Stack etwas bringt,
  ist ungemessen. Detekt deckt Stil und Größe, nicht Sicherheit.

**Für den Schnitt:** Pack-Auswahl um `p/github-actions` und ggf. `p/kotlin`
erweitern, jeweils erst nach einem Messlauf gegen das Repo; shellcheck als eigenes Target neben semgrep,
mit den zwei Altbefunden als erstem Triage-Fall.
