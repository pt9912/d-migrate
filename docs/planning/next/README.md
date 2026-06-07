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
| [`mcp-schema-migrate-tool.md`](mcp-schema-migrate-tool.md) | Next-Plan | MCP-Tool-Vertrag fuer `schema_migrate` / `schema_migrate_start` mit Wire-Vertrag V1 und Sub-Slices F.1-F.5. |
| [`migrations-ef-core-10.md`](migrations-ef-core-10.md) | Vorschlag | EF Core 10 als weiteres Migrations-Exportziel neben Flyway, Liquibase, Django und Knex.js. |
| [`object-storage-artifact-store.md`](object-storage-artifact-store.md) | Architekturplan | Object-Storage-ArtifactStore fuer langlebige/grosse Artefakte, zuerst S3-kompatibel. |
| [`orchestrator-examples.md`](orchestrator-examples.md) | Integrationsplan | Dokumentierte und getestete Orchestrator-Beispiele fuer Airflow, Dagster und Prefect. |
| [`persistence-jdbc-mig.md`](persistence-jdbc-mig.md) | Architekturplan | Generierung von Persistence-JDBC-Flyway-Migrationen aus einem neutralen Server-State-Schema. |
| [`profiling-data-quality-export.md`](profiling-data-quality-export.md) | Integrationsplan | Export von `data profile`-Reports in Data-Quality-Artefakte fuer Great Expectations, Soda, Pandera und aehnliche Tools. |
| [`telemetry-observability-port.md`](telemetry-observability-port.md) | Implementierungsplan | Neutraler Telemetry-/Observability-Port mit fachlichem Eventmodell und JSONL-Referenzadapter. |
| [`trino.md`](trino.md) | Architektur- und Umsetzungsplan | Read-first Trino/Federation-Adapter fuer Reverse Engineering, Schema-Vergleich, Export, Profiling und Source-only Transfers. |
