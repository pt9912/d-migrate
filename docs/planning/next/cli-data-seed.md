# CLI-Befehl `data seed` (Testdatengenerierung)

> **Status:** Entwurf (2026-09-04). Erste Scope-Ausarbeitung des in
> [`cli-spec.md`](../../../spec/cli-spec.md) §6.2 spezifizierten, bislang
> nicht registrierten Befehls (siehe Tracker
> [`../open/cli-unimplemented-commands.md`](../open/cli-unimplemented-commands.md)).
> **Vorbedingung:** Keine harte Blockade; Aktivierung (Move nach
> `../in-progress/`) frühestens bei Priorisierung des Milestones „1.3.0
> (Testdaten)" laut [`roadmap.md`](../in-progress/roadmap.md) oder einem
> konkreten Pilot-/Anwenderbedarf.

## Ziel

`d-migrate data seed --schema <path> --target <url>` gemäß cli-spec §6.2 als
synchronen CLI-Befehl liefern: Testdaten regelbasiert (Faker-artig,
deterministisch reproduzierbar über `--seed`/`--count`/`--locale`/`--rules`)
generieren und über die bestehende Import-Pipeline ins Ziel schreiben.
`--ai-backend` ergänzt optional um kontextrelevante Werte
([`LF-024`](../../../spec/lastenheft-d-migrate.md#lf-024)).

## Abgrenzung (bereits abgedeckt — NICHT Scope)

- Die MCP-Tools `testdata_plan`/`testdata_execute` selbst — bleiben
  unangetastet, sind über den Server-/Artefakt-Workflow bereits geliefert
  (Roadmap Phase G, ✅ 2026-05-07; Hintergrund in
  [`../done-archive/mcp-followups-testdata-ai-approval-bundle-import.md`](../done-archive/mcp-followups-testdata-ai-approval-bundle-import.md)).
- Das asynchrone Plan→Execute-Artefaktmodell samt Multi-Tenant-Scoping der
  MCP-Variante wird **nicht** auf die CLI übertragen — die CLI ist
  synchron, single-user, lokal.
- Der volle Approval-Challenge-/Quota-Mechanismus (`PolicyService`,
  `ApprovalGrantService`, `QuotaService`) der Server-Seite wird nicht 1:1
  übernommen; SECURITY.md-Bedrohungsmodell für die CLI ist „Operator ≠
  Angreifer" (siehe Sicherheitsaudit-Notiz), ein anderes Gate-Niveau ist
  hier plausibel — **Entscheidung Teil dieses Slices, s.u.**

## Vorhandene Bausteine (wiederverwenden, nicht duplizieren)

- `AiProviderPort` (`hexagon/application/src/main/kotlin/dev/dmigrate/server/application/ai/AiProviderPort.kt`)
  — bestehender Port für dieselben LLM-Backends wie
  [`LF-017`](../../../spec/lastenheft-d-migrate.md#lf-017), für
  `--ai-backend` wiederverwendbar statt neu zu bauen.
- Bestehende Import-Pipeline (`data import`/Transfer-Schreibweg,
  `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/commands/DataCommands.kt`)
  für das eigentliche Einfügen generierter Zeilen — `data seed` generiert,
  importiert aber über den vorhandenen Schreibpfad, nicht über eine eigene
  Insert-Logik.
- Topologische FK-Layer-Reihenfolge aus dem parallelen Datenpfad
  ([`LN-007`](../../../spec/lastenheft-d-migrate.md#ln-007)/[`LN-008`](../../../spec/lastenheft-d-migrate.md#ln-008))
  für FK-konsistente Einfüge-Reihenfolge über mehrere Tabellen.
- CLI-Command-Refactor-Pattern (Command → Runner → Wiring → Factory-Port)
  für den neuen `DataSeedCommand`, analog zu den bestehenden
  `Data*Command`-Klassen.
- `NeutralType`-Modell (`TableDefinition`/`ColumnDefinition`) als Grundlage
  für typ-bewusste Wertegeneratoren.

## Designentscheidungen und offene Folgefragen

1. **Generator-Engine.** Es existiert im Repo keine deterministische
   Wertegenerierung (kein Faker-Äquivalent) — der heutige MCP-Pfad ist
   reiner Passthrough zu `AiProviderPort.invoke()`.
   [`LF-024`](../../../spec/lastenheft-d-migrate.md#lf-024) nennt Faker
   (Python/JS) als Vorbild, nicht als Pflicht-Library; eine JVM-Alternative
   wäre eine **neue Runtime-Dependency** und braucht vor Aufnahme einen
   CVE-/Lizenz-Check (Historie: Dependency-CVE-Reduktion 90→0). Alternative:
   handgerollte Generatoren pro `NeutralType`. Nicht hier entschieden.
2. **Gate-Niveau für `--ai-backend`.** Voller Server-Approval-Flow ist für
   einen synchronen CLI-Aufruf ungeeignet. Zu klären: reicht ein einfaches
   Prompt-Hygiene-Äquivalent + expliziter `--ai-backend`-Opt-in, oder wird
   ein schlankerer, CLI-spezifischer Aufruf der bestehenden
   `hexagon/application/src/main/kotlin/dev/dmigrate/server/application`-
   Bausteine gebaut? Keine Kopplung der CLI an das Multi-Tenant-
   Servermodell ohne diese Klärung.
3. **`--rules`-Dateiformat.** In der Spec nicht spezifiziert (nur „Pfad,
   Regeldatei für Generierung"). Eigenes Mikroformat nötig (Spalten-
   Overrides: Wertebereiche, Muster, feste Wertelisten, Gewichtungen).
4. **Fallback-Verhalten.** Roadmap-Formulierung „Fallback auf regelbasierte
   Generierung bei KI-Ausfall"
   ([`LF-024`](../../../spec/lastenheft-d-migrate.md#lf-024)) legt fest: der deterministische
   Kern (P1) ist der Normalfall, `--ai-backend` ist additiv und darf den
   Befehl bei Ausfall nicht scheitern lassen.

## Scope-Skizze (Phasen)

- **P1 — Deterministischer Generator-Kern (ohne KI).** `NeutralType`-
  bewusste Wertegeneratoren, `--count`, `--seed` (reproduzierbarer RNG),
  `--locale`. FK-Topo-Reihenfolge wiederverwendet aus
  [`LN-007`](../../../spec/lastenheft-d-migrate.md#ln-007)/[`LN-008`](../../../spec/lastenheft-d-migrate.md#ln-008).
  Schreiben über bestehende Import-Pipeline. **DoD:** `data seed` erzeugt
  für ein Beispielschema reproduzierbare (gleicher `--seed` ⇒ identische
  Daten), FK-konsistente Datensätze im Ziel.
- **P2 — `--rules`-Regeldatei.** Format entscheiden (Designentscheidung 3),
  Parser + Validierung, Spalten-Overrides. **DoD:** Regeldatei überschreibt
  den Default-Generator je Spalte; ungültige Regeldatei = definierter
  Fehler-Exit mit klarer Meldung.
- **P3 — `--ai-backend` (optional, additiv).** Wiederverwendung von
  `AiProviderPort`; Gate-Niveau aus Designentscheidung 2 umgesetzt;
  Fallback auf P1 bei Ausfall (Designentscheidung 4). **DoD:**
  `--ai-backend` liefert kontextreichere Werte für ausgewählte Spalten;
  Backend-Fehler degradiert sauber statt abzubrechen.
- **P4 — CLI-Wiring, Doku, Goldens.** `DataSeedCommand` nach dem
  CLI-Command-Refactor-Pattern; Exit-Codes gemäß cli-spec §6.2;
  `docs/user/anwenderhandbuch.md` aufgabenorientiert ergänzen; Tracker-
  Eintrag in `../open/cli-unimplemented-commands.md` auf dieses Dokument
  verlinken. **DoD:** `make docker-check` + `make docs-check` grün; ein
  E2E-Smoke (sample-db oder eigenes Fixture) mit `data seed` gegen
  mindestens zwei Dialekte.

## Blast Radius (zur Aufwandsschätzung)

Additiver neuer Befehl, kein bestehender Contract wird verändert. Größte
Unsicherheiten: (a) eine mögliche neue Runtime-Dependency für die
Generator-Engine (CVE-/Lizenz-Prüfung Pflicht), (b) das Zuschneiden von P3
auf die bislang server-/multi-tenant-orientierten
`hexagon/application/src/main/kotlin/dev/dmigrate/server/application`-
Bausteine, ohne die CLI unnötig an dieses Modell zu koppeln.

## Akzeptanzkriterien

- `d-migrate data seed --schema <path> --target <url>` erzeugt und
  importiert Testdaten gemäß allen in cli-spec.md §6.2 gelisteten Flags.
- Gleicher `--seed` + gleiches Schema ⇒ identische generierte Daten über
  mehrere Läufe hinweg.
- FK-Constraints werden bei mehreren Tabellen nicht verletzt.
- `--ai-backend`-Abwesenheit oder -Fehler lässt `data seed` nicht
  scheitern (Fallback auf P1).
- `../open/cli-unimplemented-commands.md`-Zeile `data seed` verweist auf
  dieses Dokument.

## Aktivierungs-Trigger

Priorisierung des Milestones „1.3.0 (Testdaten)" laut
[`roadmap.md`](../in-progress/roadmap.md), oder ein konkreter
Pilot-/Anwenderbedarf für Testdaten-Generierung. Bis dahin bleibt der
Eintrag hier (geplant, nicht aktiv).

## Referenzen

- [`spec/cli-spec.md` §6.2](../../../spec/cli-spec.md) — Befehls-Zielbild
- [`LF-024`](../../../spec/lastenheft-d-migrate.md#lf-024) — Requirement
- [`../in-progress/roadmap.md`](../in-progress/roadmap.md) — Milestone-Zuordnung
- [`../open/cli-unimplemented-commands.md`](../open/cli-unimplemented-commands.md) — Tracker-Eintrag
- [`../done-archive/mcp-followups-testdata-ai-approval-bundle-import.md`](../done-archive/mcp-followups-testdata-ai-approval-bundle-import.md) — Vorgänger/Hintergrund (MCP-Testdata-Tools)
