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
| [`sdkman-distribution.md`](sdkman-distribution.md) | Ready-to-arm | SDKMAN-Kanal: Automatik gebaut und tag-getriggert scharf, inert bis zum externen Candidate-Merge. Kein 1.0.0-Gate ([ADR 0042](../../adr/0042-sdkman-kein-1.0.0-gate.md)); Nachpublizieren per `workflow_dispatch` auch rueckwirkend moeglich. |
| [`native-e2e-regression-gate.md`](native-e2e-regression-gate.md) | Draft mit Scope | CI-Verdrahtung des `DMIGRATE_CLI_BIN`-Hebels: die vorhandenen Subprozess-E2Es gegen das GraalVM-Native-Binary fahren (Linux-only, make+docker). Fand bereits `mcp serve`- und S3-Defekte, die der Sondenlauf nicht sah; jetzt gegen Regression absichern. |
| [`partition-mapping-overlay.md`](partition-mapping-overlay.md) | Draft mit Scope | Dritte Overlay-Art fuer Partitions-Identitaet, die das Werkzeug nicht ableiten kann: Kindnamen (SQL Server nummeriert) und LIST->RANGE (verifizierbar). Bindungsfrage mit [ADR 0050](../../adr/0050-overlay-bindung-uebergang-vs-darstellung.md) entschieden (sealed `Transition`/`Representation`), damit konsumiert auch `schema generate` das Overlay. P0-P7 mit Abnahmekriterien; HASH ist Nicht-Scope. |
| [`ollama-lm-studio-provider-adapter.md`](ollama-lm-studio-provider-adapter.md) | Draft mit Scope | Erster echter `AiProviderPort`-Adapter (`OllamaProvider`) statt des heute fest verdrahteten `NoOp`; Config ueber den bestehenden `--connection-config`-Pfad, `--network host` fuer `LOCAL_LOOPBACK`-Betrieb. P1-P4 mit Abnahmekriterien; kein Versions-/Termin-Commitment (Roadmap 1.5.5 liegt mehrere Meilensteine voraus). |
| [`atomic-preserve-mssql-oracle.md`](atomic-preserve-mssql-oracle.md) | Draft mit Scope | Atomic-Preserve (Sequence-Probe+Restore+DDL in einer Transaktion unter Per-Dialekt-Lock) fuer MSSQL und Oracle nachruesten -- fehlte beiden bislang undokumentiert. Phase A (MSSQL) sofort startbar (`sys.sp_getapplock`); Phase B (Oracle) blockiert bis Oracle Slice 5 (Diff/Migrate) geliefert ist, Lock-Strategie (`DBMS_LOCK` vs. Sentinel-Zeile) offen. |
| [`view-query-transformer-per-dialect-rules.md`](view-query-transformer-per-dialect-rules.md) | Draft mit Scope | `ViewQueryTransformer` traegt Dialekt-Marker/-Regeln/-Funktionslisten als `when`-Zweige in `driver-common` statt in den Adaptermodulen (Muster seit MySQL/SQLite/PostgreSQL, beim Oracle-Slice-2-Bau fortgesetzt). P0-P4: Port-Interface, fuenf Treiber-Implementierungen, `DatabaseDriver`-Erweiterung, Test-Aufteilung, Vollregression aller fuenf Dialekt-Goldens. Reine Struktur-Verschiebung, kein Verhaltens-Trigger. |
| [`capability-tables-driver-interface.md`](capability-tables-driver-interface.md) | Draft mit Scope | Fuenf statische Capability-Tabellen (`DialectCapabilities`, `SequenceCapabilityDefaults`, `RoutineCapabilityDefaults`, `TriggerCapabilityDefaults`, `SpatialProfilePolicy`) tragen `when`-Zweige im Hexagon statt ueber `DatabaseDriver`/Registry pro Dialekt aufgeloest zu werden. Offene Designfrage: Registry-Verfuegbarkeit in Aufrufkontexten ohne registrierten Treiber (Tests). P0-P5, reine Struktur-Verschiebung, kein Verhaltens-Trigger. |