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
| [`mcp-schema-migrate-tool.md`](mcp-schema-migrate-tool.md) | Next-Plan | MCP-Tool-Vertrag fuer `schema_migrate` / `schema_migrate_start` mit Wire-Vertrag V1 und Sub-Slices F.1-F.5. |
| [`migrations-ef-core-10.md`](migrations-ef-core-10.md) | Vorschlag | EF Core 10 als weiteres Migrations-Exportziel neben Flyway, Liquibase, Django und Knex.js. |
| [`orchestrator-examples.md`](orchestrator-examples.md) | Integrationsplan | Dokumentierte und getestete Orchestrator-Beispiele fuer Airflow, Dagster und Prefect. |
| [`persistence-jdbc-mig.md`](persistence-jdbc-mig.md) | Architekturplan | Generierung von Persistence-JDBC-Flyway-Migrationen aus einem neutralen Server-State-Schema. |
| [`profiling-data-quality-export.md`](profiling-data-quality-export.md) | Integrationsplan | Export von `data profile`-Reports in Data-Quality-Artefakte fuer Great Expectations, Soda, Pandera und aehnliche Tools. |
| [`semgrep-scoped-packs.md`](semgrep-scoped-packs.md) | Security-Tooling | Hermetisches semgrep-Gate (bereits geliefert) vom 2-Regel-Cache auf scoped Packs (`p/dockerfile` + `p/secrets` + `p/python`, ggf. `p/github-actions`) verbreitern — gecacht + SHA256-content-gepinnt, offline. 198 Regeln / 8 bekannte / 0 neue Findings gemessen. Nachtrag 2026-09-17: Shell, Kotlin und YAML haben kein statisches Gate (shellcheck als eigenes Target, `p/github-actions`, `p/kotlin` messen). |
| [`telemetry-observability-port.md`](telemetry-observability-port.md) | Implementierungsplan | Neutraler Telemetry-/Observability-Port mit fachlichem Eventmodell und JSONL-Referenzadapter. |
| [`trino.md`](trino.md) | Architektur- und Umsetzungsplan | Read-first Trino/Federation-Adapter fuer Reverse Engineering, Schema-Vergleich, Export, Profiling und Source-only Transfers. |
| [`validate-data-against-schema.md`](validate-data-against-schema.md) | Next-Plan | DB-freie Daten-gegen-Schema-Konformitaetspruefung (`validate data`): `validate`-Command-Group, Format-Reader-Wiederverwendung, Konformitaets-Engine; offene Designfrage Tabellen-Zuordnung; braucht Lastenheft-Backfill. |
| [`pk-constraint-prefix-length.md`](pk-constraint-prefix-length.md) | Draft mit Scope (1.0.x) | Praefixlaengen fuer PRIMARY-KEY-/Constraint-Spalten (`List<String>` -> strukturierte Eintraege, analog `IndexColumn.prefixLength`); ADR 0012 bleibt in 0.9.9 in Kraft. Phasen P1-P4 + Akzeptanzkriterien; aktiv bei Praefix-PK-Trigger. Nachtrag 2026-09-17: `ERROR 1170` der Compare-Matrix (SQLite → MySQL) hat eine andere Ursache — längenloser Text nach dem SQLite-Generate, keine Präfix-PK. |
| [`import-throughput-parallel.md`](import-throughput-parallel.md) | Draft mit Scope | Schicht-paralleler Tabellen-Import (Kahn-Schichten, FK enforced ohne PG-Disable); Design-Spike erledigt, Schnitt-1 mit Phasen/Akzeptanz. Aktiv erst bei Volumen-Trigger; Intra-Tabellen-Chunk-Parallelitaet vertagt. |
| [`adapter-coverage-uplift.md`](adapter-coverage-uplift.md) | Draft mit Scope | Per-Modul-Split-Plan zum Aufloesen der Live-JDBC-/Streaming-Adapter-Kover-Excludes (Strategie durchgaengig Split/Refactor); Design-Spike erledigt, Phasen P0-P4 + Per-Modul-Akzeptanz. 22 excludes-ledger-Eintraege jetzt auf next/ gezeigt; aktiv beim ersten Implementierungs-Commit. |
| [`sdkman-distribution.md`](sdkman-distribution.md) | Ready-to-arm | SDKMAN-Kanal: Automatik gebaut und tag-getriggert scharf, inert bis zum externen Candidate-Merge. Kein 1.0.0-Gate ([ADR 0042](../../adr/0042-sdkman-kein-1.0.0-gate.md)); Nachpublizieren per `workflow_dispatch` auch rueckwirkend moeglich. |
| [`native-e2e-regression-gate.md`](native-e2e-regression-gate.md) | Draft mit Scope | CI-Verdrahtung des `DMIGRATE_CLI_BIN`-Hebels: die vorhandenen Subprozess-E2Es gegen das GraalVM-Native-Binary fahren (Linux-only, make+docker). Fand bereits `mcp serve`- und S3-Defekte, die der Sondenlauf nicht sah; jetzt gegen Regression absichern. |
| [`ollama-lm-studio-provider-adapter.md`](ollama-lm-studio-provider-adapter.md) | Draft mit Scope | Erster echter `AiProviderPort`-Adapter (`OllamaProvider`) statt des heute fest verdrahteten `NoOp`; Config ueber den bestehenden `--connection-config`-Pfad, `--network host` fuer `LOCAL_LOOPBACK`-Betrieb. P1-P4 mit Abnahmekriterien; kein Versions-/Termin-Commitment (Roadmap 1.5.5 liegt mehrere Meilensteine voraus). |
| [`native-server-state-defekte.md`](native-server-state-defekte.md) | Entwurf mit Scope | Der JDBC-Server-State ist im Native-Binary nicht benutzbar: die fuenf JSON-Codecs des Adapters fehlen in den Reflexionsmetadaten, weil die Sonde (`native-probe.sh`) nur einen `initialize`-Handshake fahrt. Reproduziert gegen das ausgelieferte `1.7.1-native` (`INTERNAL_AGENT_ERROR`, JVM dagegen sauber). Dazu Flyway (Scanner **und** fehlende Migrationen im Image), eine unerreichbare Log-Zeile und ein Nebenbefund zum fluessigen Inhalts-State. P1-P4; baut auf dem Native-E2E-Gate auf. |
| [`reader-treue-spatial-array-json.md`](reader-treue-spatial-array-json.md) | Entwurf mit Scope, aktivierungsbereit (Aktivierungsschnitt 2026-09-17) | Stille Verluste und Fremdes im Reverse und am Generator, aus einer Konsumentenmessung gegen 1.7.1 und dem Compare-Bau; zwei Muster: Fidelity (Verlust wird nicht gesagt) und Modell-Reinheit (der Reader nimmt zuviel auf). **Pakete:** P1 Oracle-SRID-Verlust melden, P2a/P2b Oracle-`MDRS_*` und PostGIS-Routinen filtern, P3 `search_path`-Hinweis, P4 PG `geography` als Geometrie, P5 MySQL-Array-Verlust (`W162`), P6 MySQL-Introducer, P7 SpatiaLite `NOT NULL` nativ statt ganzer Tabelle (A6, entschieden; Generate, Migrate und Reverse), P8 PG `json` laut (B3, entschieden, `R402`), P9 `numeric` ohne Präzision und unbekanntes Array-Element benannt, P10 `ALWAYS` ohne Entsprechung auf MySQL/SQLite (`W163`), P11 SQLite-Constraint-Namen aus der Quelle und schemaweit eindeutig (`fk_0`), P12 SQL-Server-Berechnungsausdruck ohne T-SQL-Quoting, P13 Typ berechneter SQL-Server-Spalten (erst messen). **P0** macht die 5x5-Compare-Matrix zur Abnahme (native Seeds, Silent-Loss-Check, Codes je Reverse und Generate). Reihenfolge: P6 → P0 → P12/P11 → P5/P10 → übrige; Codes reserviert. D3 (`varchar` ohne Länge) vertragsgleich, B4 widerlegt; der SRID-Abgleich bleibt wortgetreu. Gate: die halbe A1-Frage (Block-Seite) bleibt; zwei neue Eigner-Fragen (Regel für P13, Modellfrage `decimal` ohne Präzision), keine sperrt die Aktivierung; die Herkunftsprüfung für T-SQL-Quoting (`E053`) liegt außerhalb. |
| [`compare-toleranzprofil.md`](compare-toleranzprofil.md) | Entwurf mit Scope | Eigner-Auftrag 2026-09-17: die offenen Grenzfragen von `schema compare` als **opt-in-Toleranzen** über `compare.tolerances` (Datei), `--tolerate` (CLI) und `tolerances` (`schema_compare`, `schema_compare_start`) steuern; Default strikt, jedes Ergebnis nennt seine Toleranzen, ohne Wirkung auf `schema migrate`, Fingerabdruck und `CanonicalPayload`. Kandidaten K1 Schlüsselwort-Schreibung, K2 Identity-Modus, K3 `= ANY`/`IN`/`OR`-Kette, K4 `RESTRICT` gegen implizit; die Korrektheitsgrenzen und Herkunftsfelder des Compare-Slices werden ausdrücklich kein Schalter. Gate: neuer ADR (für K3 mit Statusänderung an ADR 0055 und 0056), sieben Eigner-Fragen, Graduation des Compare-Slices (erfüllt 2026-09-17). |
