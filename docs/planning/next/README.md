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
| [`ollama-lm-studio-provider-adapter.md`](ollama-lm-studio-provider-adapter.md) | Draft mit Scope | Erster echter `AiProviderPort`-Adapter (`OllamaProvider`) statt des heute fest verdrahteten `NoOp`; Config ueber den bestehenden `--connection-config`-Pfad, `--network host` fuer `LOCAL_LOOPBACK`-Betrieb. P1-P4 mit Abnahmekriterien; kein Versions-/Termin-Commitment (Roadmap 1.5.5 liegt mehrere Meilensteine voraus). |
| [`native-server-state-defekte.md`](native-server-state-defekte.md) | Entwurf mit Scope | Der JDBC-Server-State ist im Native-Binary nicht benutzbar: die fuenf JSON-Codecs des Adapters fehlen in den Reflexionsmetadaten, weil die Sonde (`native-probe.sh`) nur einen `initialize`-Handshake fahrt. Reproduziert gegen das ausgelieferte `1.7.1-native` (`INTERNAL_AGENT_ERROR`, JVM dagegen sauber). Dazu Flyway (Scanner **und** fehlende Migrationen im Image), eine unerreichbare Log-Zeile und ein Nebenbefund zum fluessigen Inhalts-State. P1-P4; baut auf dem Native-E2E-Gate auf. |
| [`reader-treue-spatial-array-json.md`](reader-treue-spatial-array-json.md) | Entwurf mit Scope (zwei Review-Runden eingearbeitet) | Neun Posten aus einer Konsumentenmessung gegen 1.7.1 plus **C1** (MySQL-Introducer, am 2026-09-16 aus der Compare-Messung uebernommen; dort ist er Posten 4), **zwei** Muster: Fidelity (der Reader verliert Information oder der Generator verwirft ein Objekt, und **sagt es nicht** — Oracle-SRID bei quotiert-kleingeschriebenen Tabellen, heute stumm; stille Degradierung ohne `search_path`; `geography` als Enum statt Geometrie; MySQL meldet den Array-Verlust nicht, den Oracle und MSSQL melden) und Modell-Reinheit (der Reader nimmt **zuviel** auf: Oracles `MDRS_*`-Sequenz, ~1000 PostGIS-Funktionen, MySQLs Charset-Introducer). P1, P2a/P2b, P3, P4, P5, P6. **A6** (SpatiaLite verwirft die ganze Tabelle) ist als spec-konform aus dem Slice heraus, **B3** (`json`/`jsonb`) als kanonische Modellform, **B4** (`interval`) widerlegt; der SRID-Abgleich bleibt bewusst wortgetreu (der naheliegende tolerante Fix ist begruendet verworfen), die vier offenen Fragen und die Eigner-Frage stehen unter „Offen". |
| [`compare-projektion-und-normalisierung.md`](compare-projektion-und-normalisierung.md) | Entwurf mit Scope (zwei Review-Runden eingearbeitet) | Sechs Posten aus der Konsumentenmessung gegen 1.7.1: Constraint-Funde ohne `details` (beide Oberflächen), zwei Pfad-Vokabulare (beide im Code verifiziert), fehlende Faltungszweige (`OR`/`IS NULL`, Listen-Kommas, Index-Prädikat), PG-seitiger Identity-`sequenceName`. P1, P2a/P2b, P3, P5, P6, P7 — **P4** (MySQL-Introducer) ist als Reader-Posten in den Reader-Slice gewandert, `= ANY(ARRAY[…])` gegen `IN (…)` ist ADR-entschieden (0055) und kein Posten. P3/P5 bewegen eine Linie, die `ADR 0053` besitzt: P7 superseded sie per Statuszeile (ein zweiter ADR daneben ließe alle Gates grün). Die zwei Grenzfragen und die zweite MCP-Oberfläche stehen unter „Offen". |
