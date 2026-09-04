# CLI-Befehle `transform procedure` / `generate procedure`

> **Status:** Entwurf (2026-09-04). Erste Scope-Ausarbeitung der in
> [`cli-spec.md`](../../../spec/cli-spec.md) §6.3/§6.4 spezifizierten,
> bislang nicht registrierten Befehle (siehe Tracker
> [`../open/cli-unimplemented-commands.md`](../open/cli-unimplemented-commands.md)).
> Geschwisterdokument zu [`cli-data-seed.md`](cli-data-seed.md) — gleiches
> Grundmuster: MCP liefert die Fachlogik bereits, aber nur als
> server-/artefaktgebundenes Tool-Paar, nicht als CLI-taugliche
> Wiederverwendung.
> **Vorbedingung:** Keine harte Blockade; Aktivierung frühestens bei
> Priorisierung von Milestone 1.5.5 (KI-Integration) laut
> [`roadmap.md`](../in-progress/roadmap.md) oder konkretem Anwenderbedarf.

## Ziel

Zwei zusammengehörige Befehle aus cli-spec §6.3/§6.4 liefern:

- `transform procedure --source <path> --procedure <name> --ai-backend <provider>`
  — Stored Procedure/Function → abstraktes Markdown-Zwischenformat (KI-Pflicht).
- `generate procedure --source <path> --target <dialect>` — Markdown-
  Zwischenformat → dialektspezifischer Code.

Beide zusammen bilden den in [`LF-017`](../../../spec/lastenheft-d-migrate.md#lf-017)
beschriebenen Zwei-Stufen-Pfad (Quelle → neutrales Zwischenformat →
Zieldialekt), synchron über die CLI nutzbar.

## Abgrenzung (bereits abgedeckt — NICHT Scope)

- Die MCP-Tools `procedure_transform_plan`/`procedure_transform_execute`
  selbst bleiben unverändert — server-/artefaktgebundener Workflow, bereits
  registriert.
- Voller Server-Approval-Challenge-/Quota-Mechanismus wird nicht 1:1
  übernommen (gleiche Grundhaltung wie in
  [`cli-data-seed.md`](cli-data-seed.md) Designentscheidung 2 — Operator-
  Vertrauensmodell der CLI, nicht Multi-Tenant-Server).
- Semantische Äquivalenzprüfung generierter Prozeduren
  ([`LN-034`](../../../spec/lastenheft-d-migrate.md#ln-034), `validate
  procedure`) ist ein eigener Tracker-Eintrag, nicht hier.

## Vorhandene Bausteine (wiederverwenden, nicht duplizieren)

- `AiProviderPort`/`AiProviderRegistry`/`AiToolOrchestrator`,
  `PromptHygieneService`
  (`hexagon/application/src/main/kotlin/dev/dmigrate/server/application/ai`)
  — generische KI-Plumbing-Schicht, dialektunabhängig.
- `RuleBasedProvider` ([`LN-035`](../../../spec/lastenheft-d-migrate.md#ln-035))
  als KI-freier Fallback/Testpfad ohne externe API-Kosten.
- `ProcedureTransformPlanHandler.kt`/`ProcedureTransformExecuteHandler.kt`
  (`adapters/driving/mcp/src/main/kotlin/dev/dmigrate/mcp/registry`) als
  **Referenzimplementierung**
  (Prompt-Bau, Markdown-Zwischenformat-Umgang, Provider-Aufruf) — die
  fachliche Logik dort lesen und in eine hexagon-taugliche Form heben,
  nicht den Handler selbst wiederverwenden (adapter-/artefaktgebunden).
- CLI-Command-Refactor-Pattern (Command → Runner → Wiring → Factory-Port).

## Designentscheidungen und offene Folgefragen

1. **Zwei CLI-Befehle vs. MCP-Plan/Execute-Paar passen nicht 1:1.**
   `procedure_transform_execute` ruft selbst wieder einen KI-Provider auf
   (`providerId`/`model`-Felder im Schema), während `generate procedure`
   in der Spec **kein** `--ai-backend`-Flag hat — impliziert reines,
   deterministisches Markdown→Code-Templating. Zu klären: ist `generate
   procedure` wirklich KI-frei, oder muss `cli-spec.md` nachgezogen
   werden? Bestimmt die gesamte Bauweise von P3.
2. **Gate-Niveau für `--ai-backend`** — dieselbe offene Frage wie in
   [`cli-data-seed.md`](cli-data-seed.md) Designentscheidung 2. Beide
   Slices sollten dieselbe Antwort verwenden (ein gemeinsames
   CLI-KI-Gate-Bauteil statt zweier unabhängiger Lösungen).
3. **Markdown-Zwischenformat als Dateicontract.** Heute nur implizit im
   MCP-Plan-Artefakt vorhanden. Für CLI-Datei-Interop (`transform
   procedure --output`, `generate procedure --source`) muss das Format
   als eigenständiger, versionierter Contract entworfen und dokumentiert
   werden (vermutlich `spec/`-Eintrag) — kein reines CLI-Wiring-Problem.
4. **`--compare` (A/B-Test mehrerer Provider).** Nur bei `transform
   procedure` in der Spec, kein MCP-Vorbild (Handler kennt nur einen
   einzelnen `providerId`). Muss neu entworfen werden: Ausgabeformat bei
   mehreren Providern (mehrere Dateien? ein Vergleichsreport?) ist offen.
5. **Doku-Drift-Hinweis.** `roadmap.md` führt „Stored Procedure →
   Markdown-Zwischenformat" und „Markdown-Zwischenformat → Ziel-DB-Code"
   unter Milestone 1.5.5 ohne Status-Häkchen, obwohl die MCP-seitige
   Funktionalität real existiert. Beim Slice-Start Roadmap-Zeilen
   943/944/949/950 gegen den Ist-Stand abgleichen (Korrektur ist nicht
   Teil dieses Dokuments, nur Hinweis).

## Scope-Skizze (Phasen)

- **P1 — Markdown-Zwischenformat als Contract festziehen.** Klärung
  Designentscheidung 1 + 3: Format spezifizieren (Struktur, ggf.
  `spec/`-Eintrag), Quelle/Ziel für beide Befehle. **DoD:** Format
  dokumentiert; ein manueller Round-Trip (eine Beispielprozedur, ein
  Dialekt) über beide Befehle funktioniert.
- **P2 — `transform procedure` (KI-Pflicht).** CLI-Wiring, Nutzung
  `AiProviderPort`/`AiProviderRegistry`, Gate-Niveau aus
  Designentscheidung 2, Exit-Code `6` bei KI-Fehlern gemäß Spec. **DoD:**
  Befehl liefert Markdown-Datei aus einer Quellprozedur;
  `RuleBasedProvider` als KI-freier Testpfad in CI nutzbar.
- **P3 — `generate procedure` (Markdown → Zieldialekt-Code).** Bauweise
  folgt der P1-Entscheidung (deterministisches Templating oder
  KI-gestützt analog `execute`). **DoD:** Befehl liefert aus dem
  Markdown-Zwischenformat plausiblen Zieldialekt-Code für ein Beispiel.
- **P4 — `--compare`.** Mehrere Provider aufrufen, Ergebnisse
  gegenüberstellen; Einzelausfall eines Providers darf den Befehl nicht
  scheitern lassen. **DoD:** `--compare providerA,providerB` liefert
  vergleichbare Ausgabe je Provider.
- **P5 — Doku, Goldens, Tracker.** `docs/user/anwenderhandbuch.md`
  aufgabenorientiert ergänzen; Tracker-Verweise in
  `../open/cli-unimplemented-commands.md`; Roadmap-Zeilen abgleichen
  (Designentscheidung 5). **DoD:** `make docker-check` + `make
  docs-check` grün.

## Blast Radius (zur Aufwandsschätzung)

Additiv, zwei neue CLI-Befehle. Größte Unsicherheit: das
Markdown-Zwischenformat existiert bislang nur implizit im MCP-Artefakt —
P1 ist damit mehr als CLI-Wiring, es ist Contract-Design. Zweitgrößte:
`--compare` hat kein Adapter-Vorbild und muss vollständig neu entworfen
werden.

## Akzeptanzkriterien

- `transform procedure` erzeugt aus einer Quellprozedur ein
  Markdown-Zwischenformat gemäß den in cli-spec §6.3 gelisteten Flags;
  Exit `6` bei KI-Fehlern.
- `generate procedure` erzeugt aus dem Markdown-Zwischenformat validen
  Zieldialekt-Code gemäß cli-spec §6.4.
- Round-Trip (`transform` → `generate`) funktioniert für mindestens eine
  Beispielprozedur über mindestens zwei Zieldialekte.
- `--compare` liefert vergleichbare Ausgaben mehrerer Provider; Ausfall
  eines einzelnen Providers bricht den Gesamtlauf nicht ab.
- `../open/cli-unimplemented-commands.md`-Zeilen `transform procedure`
  und `generate procedure` verweisen auf dieses Dokument.

## Aktivierungs-Trigger

Priorisierung von Milestone 1.5.5 (KI-Integration) laut
[`roadmap.md`](../in-progress/roadmap.md), oder ein konkreter
Anwenderbedarf für Stored-Procedure-Migration zwischen Dialekten. Bis
dahin bleibt der Eintrag hier (geplant, nicht aktiv).

## Referenzen

- [`spec/cli-spec.md` §6.3/§6.4](../../../spec/cli-spec.md) — Befehls-Zielbild
- [`LF-017`](../../../spec/lastenheft-d-migrate.md#lf-017) — Requirement
- [`../in-progress/roadmap.md`](../in-progress/roadmap.md) — Milestone-Zuordnung (Zeilen 943/944/949/950)
- [`../open/cli-unimplemented-commands.md`](../open/cli-unimplemented-commands.md) — Tracker-Eintrag
- [`cli-data-seed.md`](cli-data-seed.md) — Geschwisterdokument, gleiche Design-Grundfragen (Gate-Niveau KI)
