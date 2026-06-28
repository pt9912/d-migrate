# Tracker: In cli-spec.md spezifizierte, noch nicht implementierte CLI-Befehle

> **Status:** Sammlung/Tracker (2026-06-15)
> **Trigger:** `spec/cli-spec.md` ist das **Zielbild** und beschreibt die
> Befehls-Oberfläche herstellerunabhängig vom Implementierungsstand. Der
> Implementierungs-Status („implementiert/geplant") gehört nicht in die Spec
> (ADR 0004: Status/Pläne leben in `docs/planning`). Dieser Tracker hält fest,
> welche in der Spec beschriebenen Befehle heute **noch nicht** in der CLI
> registriert sind.
> **Aktivierungsbedingung:** Wird einer dieser Befehle für einen Milestone
> priorisiert, entsteht dafür ein eigener `next/`-Plan; der Eintrag hier
> verweist dann darauf.

## Heute nicht in der CLI registriert (`schema`/`data`/`export`/`mcp` sind es)

| Befehl | cli-spec §6 | Requirement | Roadmap |
| ------ | ----------- | ----------- | ------- |
| `transform procedure` | 6.3 | [`LF-017`](../../../spec/lastenheft-d-migrate.md#lf-017) | Phase 4 (KI-Integration) |
| `generate procedure` | 6.4 | [`LF-017`](../../../spec/lastenheft-d-migrate.md#lf-017) | Phase 4 (KI-Integration) |
| `data seed` | 6.2 | [`LF-024`](../../../spec/lastenheft-d-migrate.md#lf-024) | 1.3.0 (Testdaten) |
| `validate data` | 6.6 | [`LF-027`](../../../spec/lastenheft-d-migrate.md#lf-027) | — (siehe auch [`validate-data-against-schema.md`](../next/validate-data-against-schema.md)) |
| `validate procedure` | 6.6 | [`LN-034`](../../../spec/lastenheft-d-migrate.md#ln-034) | Phase 4 |
| `config credentials set` / `config credentials list` / `config show` | 6.7 | Credential-Management (`connection-config-spec.md` §4) | 1.0.0-RC (AES-256-Credentials, [`LN-025`](../../../spec/lastenheft-d-migrate.md#ln-025)) |

Die oberste Kommandogruppe `transform` existiert entsprechend ebenfalls noch
nicht (nur `schema`/`data`/`export`/`mcp` sind registriert).

## Trigger-Rendering — noch nicht abgedeckte Erweiterungen (cli-spec §6 „schema migrate")

- Schemaqualifizierter `DROP TRIGGER` (`<schema>.<name>`).
- SQLite-Trigger-Reverse-Read aus `sqlite_master`.
- `TriggerDefinition`-Modellerweiterung: `events`-Liste mit Spaltenliste,
  `enabledState`.

## Hinweis

Die profiling-seitigen Spec-voraus-Flags (`--query`, `--analyze-normalization`)
sind separat in [`profiling-query-and-normalization.md`](profiling-query-and-normalization.md)
erfasst. Dieser Tracker ersetzt **keine** Roadmap — er verlinkt nur Spec ↔
Requirement ↔ Milestone für die noch offenen Kommando-Stubs.

## Referenzen

- [`../../../spec/cli-spec.md`](../../../spec/cli-spec.md) — Zielbild der CLI
- [`../in-progress/roadmap.md`](../in-progress/roadmap.md) — Milestones/Status
- [`../../adr/0004-documentation-and-planning-structure.md`](../../adr/0004-documentation-and-planning-structure.md)
