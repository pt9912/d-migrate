# Geplante Arbeit (Scope skizziert, noch nicht aktiv)

Einträge in diesem Ordner haben einen ausgearbeiteten Scope (Ziel,
grobe Phasen oder Arbeitspakete, Akzeptanzkriterien), aber **noch
keine aktive Slice-Arbeit** im Code. Sobald der erste Implementierungs-
Commit landet, wandert der Eintrag nach `../in-progress/`.

Lebenszyklus und Verzeichnisstruktur sind in
[`ADR 0004`](../../adr/0004-documentation-and-planning-structure.md)
festgehalten.

## Konvention für Einträge

- Sprechender lowercase-kebab-Dateiname (z. B.
  `parquet-export-import-evaluation.md`,
  `trino.md`, `telemetry-observability-port.md`).
- Jeder Eintrag enthält im Kopf:
  - **Status**: `Entwurf (<datum>)` / `Vorschlag` / `Draft mit Scope`
  - **Ziel**: was soll das Feature liefern?
  - **Scope-Skizze**: Phasen, grobe Arbeitspakete oder Sub-Slices
  - **Vorbedingungen**: was muss vorher feststehen (andere Pläne,
    ADRs, Lastenheft-Ergänzungen)
- Plan-Doc-interne Status-Updates (`> Status-Update YYYY-MM-DD`)
  sind willkommen, signalisieren aber keine Aktivierung — der Move
  nach `../in-progress/` erfolgt erst beim ersten Code-Commit.

## Wann **nicht** hierher

- Trigger ohne Scope → `../open/`.
- Slice-Arbeit aktiv (mindestens ein Implementierungs-Commit, das
  sich auf den Plan beruft) → `../in-progress/`.
- Vollständig geliefert → `../done/`.

## Bestand

| Datei | Typ | Gegenstand |
| ----- | --- | ---------- |
| [`atomic-preserve-service-mode.md`](atomic-preserve-service-mode.md) | Next-Plan | Atomic-Preserve Service-Mode fuer MCP/REST/gRPC: Connection-Sub-Pool, Quota-Plumbing und Schema-Migrate-Handler-Skeleton; wartet auf einen realen Service-Mode-Konsumenten. |
| [`harness-bootstrap-v1.4.0.md`](harness-bootstrap-v1.4.0.md) | Bootstrap-Plan (Slice-Bündel) | Adoption der Regelwerk-v1.4.0-Pflichtartefakte: `harness/conventions.md` (deklariert die Layout-Adaption gemäß ADR 0004 + Carveout-/Gate-Adaptionen als `MR-NNN`), `harness/README.md`, Carveout-Modell-Entscheidung, `AGENTS.md`, optional Durchsetzungs-Hooks. Bewusst hinter dem Sample-DB-Harness priorisiert. |
| [`config-cli-management-surface.md`](config-cli-management-surface.md) | Next-Plan | CLI-`config`-Kommandos (`show` / `credentials list` / `credentials set`): Phase 1 CLI-Huelle ueber vorhandener Connection-/Config-Mechanik, Phase 2 AES-256-Credential-Store (Stufe 4 der §4.1-Kette) mit Lastenheft-Backfill + Security-Review. |
| [`mcp-schema-migrate-tool.md`](mcp-schema-migrate-tool.md) | Next-Plan | MCP-Tool-Vertrag fuer `schema_migrate` / `schema_migrate_start` mit Wire-Vertrag V1 und Sub-Slices F.1-F.5. || [`migrations-ef-core-10.md`](migrations-ef-core-10.md) | Vorschlag | EF Core 10 als weiteres Migrations-Exportziel neben Flyway, Liquibase, Django und Knex.js. || [`orchestrator-examples.md`](orchestrator-examples.md) | Integrationsplan | Dokumentierte und getestete Orchestrator-Beispiele fuer Airflow, Dagster und Prefect. |
| [`persistence-jdbc-mig.md`](persistence-jdbc-mig.md) | Architekturplan | Generierung von Persistence-JDBC-Flyway-Migrationen aus einem neutralen Server-State-Schema. |
| [`profiling-data-quality-export.md`](profiling-data-quality-export.md) | Integrationsplan | Export von `data profile`-Reports in Data-Quality-Artefakte fuer Great Expectations, Soda, Pandera und aehnliche Tools. |
| [`semgrep-scoped-packs.md`](semgrep-scoped-packs.md) | Security-Tooling | Hermetisches semgrep-Gate (bereits geliefert) vom 2-Regel-Cache auf scoped Packs (`p/dockerfile` + `p/secrets` + `p/python`, ggf. `p/github-actions`) verbreitern — gecacht + SHA256-content-gepinnt, offline. 198 Regeln / 8 bekannte / 0 neue Findings gemessen. |
| [`telemetry-observability-port.md`](telemetry-observability-port.md) | Implementierungsplan | Neutraler Telemetry-/Observability-Port mit fachlichem Eventmodell und JSONL-Referenzadapter. |
| [`trino.md`](trino.md) | Architektur- und Umsetzungsplan | Read-first Trino/Federation-Adapter fuer Reverse Engineering, Schema-Vergleich, Export, Profiling und Source-only Transfers. |
| [`validate-data-against-schema.md`](validate-data-against-schema.md) | Next-Plan | DB-freie Daten-gegen-Schema-Konformitaetspruefung (`validate data`): `validate`-Command-Group, Format-Reader-Wiederverwendung, Konformitaets-Engine; offene Designfrage Tabellen-Zuordnung; braucht Lastenheft-Backfill. |
| [`pk-constraint-prefix-length.md`](pk-constraint-prefix-length.md) | Draft mit Scope (1.0.x) | Praefixlaengen fuer PRIMARY-KEY-/Constraint-Spalten (`List<String>` -> strukturierte Eintraege, analog `IndexColumn.prefixLength`); ADR 0012 bleibt in 0.9.9 in Kraft. Phasen P1-P4 + Akzeptanzkriterien; aktiv bei Praefix-PK-Trigger. |
| [`import-throughput-parallel.md`](import-throughput-parallel.md) | Draft mit Scope | Schicht-paralleler Tabellen-Import (Kahn-Schichten, FK enforced ohne PG-Disable); Design-Spike erledigt, Schnitt-1 mit Phasen/Akzeptanz. Aktiv erst bei Volumen-Trigger; Intra-Tabellen-Chunk-Parallelitaet vertagt. |
| [`adapter-coverage-uplift.md`](adapter-coverage-uplift.md) | Draft mit Scope | Per-Modul-Split-Plan zum Aufloesen der Live-JDBC-/Streaming-Adapter-Kover-Excludes (Strategie durchgaengig Split/Refactor); Design-Spike erledigt, Phasen P0-P4 + Per-Modul-Akzeptanz. 22 excludes-ledger-Eintraege jetzt auf next/ gezeigt; aktiv beim ersten Implementierungs-Commit. |