# Roadmap: d-migrate

**CLI-Tool für datenbankunabhängige Migrationen und Datenverwaltung**

---

## Übersicht

Diese Roadmap beschreibt den geplanten Entwicklungsverlauf von d-migrate in vier Phasen.
Sie basiert auf den Anforderungen des [Lastenhefts](../../../spec/lastenheft-d-migrate.md), der
[Architektur](../../../spec/architecture.md) und der [Neutrales-Modell-Spezifikation](../../../spec/neutral-model-spec.md). Die Roadmap wird quartalsweise aktualisiert und ist
öffentlich auf GitHub Projects einsehbar.

```
Phase 1: MVP          Phase 2: Beta         Phase 3: Stable       Phase 4: Growth
0.1.0 - 0.5.5        0.6.0 - 0.9.9        1.0.0                 1.1.0 - 2.0.0
Monate 1-6            Monate 7-12           Monate 13-15          Monate 16-24
──────────────────────────────────────────────────────────────────────────────────▶
```

---

## Phase 1: MVP (0.1.0 - 0.5.5) — Monate 1-6

**Ziel**: Funktionaler Prototyp für Early Adopters

### Milestone 0.1.0 — Projekt-Grundgerüst ✅ (2026-04-05)

| Bereich | Aufgabe                                                | LF-Ref | Status |
| ------- | ------------------------------------------------------ | ------ | ------ |
| Build   | Gradle Multi-Module Setup (core, cli, formats)         | —      | ✅      |
| Build   | CI/CD-Pipeline mit GitHub Actions (Build, Test, Kover) | —      | ✅      |
| Core    | Neutrales Schema-Modell (Kotlin Data Classes)          | LF-001 | ✅      |
| Core    | YAML-Parser für Schema-Definitionen                    | LF-001 | ✅      |
| Core    | Basis-Validierung (E001-E018, W001)                    | LF-002 | ✅      |
| CLI     | Clikt-basiertes CLI-Grundgerüst                        | LF-012 | ✅      |
| CLI     | `d-migrate schema validate` Kommando                   | LF-002 | ✅      |
| Test    | Kotest Setup, 83 Tests, Kover Coverage >= 90%          | LN-043 | ✅      |

**Ergebnis**: Schema-Dateien können geladen und validiert werden.

### Milestone 0.2.0 — DDL-Generierung ✅ (2026-04-06)

| Bereich | Aufgabe                                             | LF-Ref | Status |
| ------- | --------------------------------------------------- | ------ | ------ |
| Core    | TypeMapper-Interface und Basislogik                 | LF-003 | ✅      |
| Core    | AbstractDdlGenerator mit topologischer Sortierung   | LF-003 | ✅      |
| Core    | ViewQueryTransformer (17 SQL-Funktionen)            | LF-003 | ✅      |
| Driver  | PostgreSQL-Treiber: DDL-Generierung                 | LF-003 | ✅      |
| Driver  | MySQL-Treiber: DDL-Generierung                      | LF-003 | ✅      |
| Driver  | SQLite-Treiber: DDL-Generierung                     | LF-003 | ✅      |
| CLI     | `d-migrate schema generate --target <db>`           | LF-003 | ✅      |
| CLI     | `--output`, `--generate-rollback`, `--report` Flags | LF-003 | ✅      |
| CLI     | TransformationReportWriter (YAML-Sidecar)           | LF-003 | ✅      |
| Test    | TypeMapper-Tests (100% Coverage, 3 Dialekte)        | LN-045 | ✅      |
| Test    | DdlGenerator Unit-Tests (44+53+57 Tests)            | LN-043 | ✅      |
| Test    | Golden-Master-Tests (4 Schemas × 3 Dialekte = 12)   | LN-043 | ✅      |
| Test    | 374 Tests gesamt, Coverage >= 90%                   | LN-045 | ✅      |

**Ergebnis**: Aus YAML-Schemas wird datenbankspezifisches DDL generiert.

### Milestone 0.3.0 — Datenexport ✅ (2026-04-06)

| Bereich | Aufgabe                                                          | LF-Ref | Status |
| ------- | ---------------------------------------------------------------- | ------ | ------ |
| Core    | Streaming-Pipeline (pull-basiert, chunk-weise)                   | LN-005 | ✅      |
| Driver  | JDBC-basierter DataReader (ResultSet-Streaming, PG/MySQL/SQLite) | LF-008 | ✅      |
| Driver  | Connection-Management mit HikariCP + ConnectionUrlParser         | —      | ✅      |
| Formats | JSON-Codec (DSL-JSON statt Jackson — Performance)                | LF-009 | ✅      |
| Formats | YAML-Codec (SnakeYAML Engine)                                    | LF-009 | ✅      |
| Formats | CSV-Codec (uniVocity-parsers, mit Encoding/BOM/Delimiter)        | LF-009 | ✅      |
| CLI     | `d-migrate data export` Kommando                                 | LF-008 | ✅      |
| CLI     | `NamedConnectionResolver` (`.d-migrate.yaml`, `${ENV_VAR}`)      | —      | ✅      |
| Test    | Export-Tests gegen Testcontainers (PostgreSQL 16, MySQL 8.0)     | LN-048 | ✅      |

**Ergebnis**: Daten können aus PostgreSQL, MySQL und SQLite streamingbasiert
in JSON, YAML oder CSV exportiert werden — chunk-weise, ohne die Tabelle in
den Speicher zu laden. CLI mit Named Connections, Roh-WHERE-Filter,
`--split-files`-Multi-Tabellen-Export, vollständige Exit-Code-Matrix
(0/2/4/5/7) und §6.17 Empty-Table-Vertrag pro Format.

### Milestone 0.4.0 — Datenimport und inkrementelle Datenpfade ✅ (2026-04-12)

| Bereich | Aufgabe                                                                                      | LF-Ref |
| ------- | -------------------------------------------------------------------------------------------- | ------ |
| Core    | Transaktionale Import-Logik (Chunk-basiert)                                                  | LN-010 |
| Driver  | JDBC-basierter DataWriter (Batch-Insert)                                                     | LF-010 |
| Core    | Sequence-/Identity- und AUTO_INCREMENT-Konsistenz nach Import                                | LF-010 |
| Driver  | Dialektspezifisches Trigger-Handling beim Import                                             | LF-010 |
| Formats | Deserialisierung aus JSON/YAML/CSV (Streaming-Reader)                                        | LF-010 |
| Core    | Validierung gegen Schema-Definition beim Import (Target-Schema autoritativ)                  | LF-010 |
| Core    | Encoding-Unterstützung: BOM-Detection für UTF-8/UTF-16, alle anderen via `--encoding`-Flag   | LF-010 |
| CLI     | `d-migrate data import` Kommando                                                             | LF-010 |
| Core    | Inkrementeller **Export** über explizite Marker-Spalte (`--since-column`, `--since`)         | LF-013 |
| CLI     | `--incremental` Flag für `data export`; idempotenter Import via `--on-conflict update`       | LF-013 |
| Test    | Round-Trip-Tests (Export → Import → Vergleich)                                               | LN-043 |
| Test    | Import-Tests für Sequence-Reseeding und Trigger-Verhalten                                    | LN-043 |
| Test    | Inkrement-Round-Trip-Tests (initial export → delta export → idempotenter Import → Vergleich) | LN-043 |

**Ergebnis**: Vollständiger Export/Import-Zyklus funktioniert. Inkrementell
ist explizit zweigeteilt: Export bekommt funktionale `--since-column`-Filter,
Import läuft über idempotenten UPSERT (`--on-conflict update`) — siehe
implementation-plan-0.4.0.md §6.12.

> **Begründung der LF-013-Vorverlegung von 0.9.0 nach 0.4.0**: Inkrementeller
> Export/Import gehört semantisch zum Daten-Pfad, nicht zur Beta-Reife.
> Sobald `data import` (LF-010) steht, ist die nötige Schreib-Infrastruktur
> vorhanden; die einfache Variante `--since-column updated_at --since
> "<timestamp>"` braucht weder Auto-Discovery noch einen vollständigen
> `SchemaReader` (LF-004 → 0.6.0). Eine spätere Erweiterung um automatische
> Marker-Spalten-Erkennung kann in 0.6.0 nachgezogen werden, ohne den
> 0.4.0-CLI-Vertrag zu brechen. Die ursprüngliche Einordnung in 0.9.0
> hätte LF-013 künstlich zurückgehalten, obwohl die fachlichen
> Voraussetzungen schon mit 0.4.0 erfüllt sind. Checkpoint/Resume (LN-012)
> bleibt in 0.9.0 — Inkrement und Checkpoint sind technisch unabhängig
> (Inkrement kennzeichnet *was* exportiert wird, Checkpoint *wo* der
> Export aufgesetzt hat).

### Milestone 0.5.0 — MVP-Release ✅ (2026-04-13)

| Bereich | Aufgabe                                                         | LF-Ref |
| ------- | --------------------------------------------------------------- | ------ |
| Core    | Schema-Diff-Engine (Vergleich zweier Schemas, file-based MVP)   | LF-015 |
| CLI     | `d-migrate schema compare` Kommando (Datei-zu-Datei, MVP-Slice) | LF-015 |
| CLI     | Fortschrittsanzeige für lange Operationen                       | LN-017 |
| Docs    | Anwenderhandbuch (Basis)                                        | —      |
| Docs    | CLI-Referenz                                                    | —      |
| Build   | GitHub Releases (Fat JAR)                                       | —      |
| Build   | Homebrew-Formula (Basis)                                        | —      |
| QA      | Code-Coverage >= 80%                                            | LN-045 |

**Ergebnis**: Öffentliches MVP-Release. Early Adopters können Schema-Verwaltung
und Daten-Export/Import nutzen. LF-015 ist in 0.5.0 bewusst nur teilweise
abgedeckt: `schema compare` vergleicht zwei neutrale Schema-Dateien; der volle
Vergleich zwischen Umgebungen bzw. Datenbanken folgt in 0.6.0.

Detaillierter Plan: [implementation-plan-0.5.0.md](../done/implementation-plan-0.5.0.md)

### Milestone 0.5.5 — Erweitertes Typsystem ✅ (2026-04-13)

| Bereich | Aufgabe                                                                              | LF-Ref |
| ------- | ------------------------------------------------------------------------------------ | ------ |
| Core    | Neutraler Typ `geometry` mit `geometry_type` und `srid`                              | LF-003 |
| Core    | Spatial-Profil als Generator-Option (`postgis`, `native`, `spatialite`, `none`)      | LF-003 |
| Core    | Validierungsregeln und Hinweis-Codes `E120`, `E121`, `W120`, `E052`                  | LF-002 |
| Driver  | PostgreSQL/PostGIS: `geometry(<type>, <srid>)`-Mapping                               | LF-003 |
| Driver  | MySQL: native Spatial Types (`POINT`, `POLYGON`, `MULTIPOLYGON`, ...)                | LF-003 |
| Driver  | SQLite/SpatiaLite: `AddGeometryColumn(...)`-Strategie                                | LF-003 |
| Core    | Erweiterte Typen im neutralen Modell: JSON, Arrays, Binary, UUID                     | LF-001 |
| CLI     | `--spatial-profile` Option für `schema generate`                                     | LF-003 |
| Test    | Golden-Master-Tests: `spatial.{postgresql,mysql,sqlite}.sql`                         | LN-043 |
| Test    | Negativtests für ungültige `geometry_type`/`srid` und Profil-Mismatches              | LN-043 |
| Docs    | CR `docs/planning/change-request-spatial-types.md` in den Status _Approved_ überführen        | —      |
| Docs    | `spec/neutral-model-spec.md` und `spec/ddl-generation-rules.md` um Spatial erweitern | —      |

**Ergebnis**: Das neutrale Typsystem ist vor Reverse-Engineering (0.6.0) vollständig. Spatial-Daten können ohne Datenverlust zwischen PostgreSQL/PostGIS, MySQL und SQLite/SpatiaLite transportiert werden; JSON/Arrays/Binary/UUID sind modelliert und getestet.

Dieser Milestone basiert auf dem [Change Request Spatial Types](../done/change-request-spatial-types.md) (Phase 1) und zieht die vormals für 0.8.0 geplanten erweiterten Typen vor, damit `schema reverse` ab 0.6.0 von Anfang an gegen ein vollständiges Typsystem arbeiten kann.

---

## Phase 2: Beta (0.6.0 - 0.9.9) — Monate 7-12

**Ziel**: Production-ready für erste Projekte

### Milestone 0.6.0 — Reverse-Engineering ✅ (2026-04-14)

| Bereich | Aufgabe                                                                             | LF-Ref |
| ------- | ----------------------------------------------------------------------------------- | ------ |
| Driver  | SchemaReader: Tabellen, Spalten, Constraints aus DB extrahieren                     | LF-004 |
| Driver  | SchemaReader: Indizes, Sequences, Custom Types                                      | LF-004 |
| Driver  | SchemaReader: Stored Procedures, Functions, Triggers, Views                         | LF-004 |
| Driver  | PostgreSQL-spezifisch: Extensions, JSONB, Array-Typen                               | LF-004 |
| Driver  | MySQL-spezifisch: Engine, AUTO_INCREMENT, SET-Typen                                 | LF-004 |
| Driver  | SQLite-spezifisch: WITHOUT ROWID, Virtual Tables                                    | LF-004 |
| CLI     | `d-migrate schema reverse --source <db-url>`                                        | LF-004 |
| CLI     | `schema compare` gegen Umgebungen/DBs auf Basis von `SchemaReader` vervollständigen | LF-015 |
| Core    | `StreamingTransfer`-Orchestrator: DB-zu-DB-Streaming ohne Zwischenformat            | LF-025 |
| CLI     | `d-migrate data transfer --source <url> --target <url>` Kommando                    | LF-025 |
| Docs    | Beispiel-Projekte (E-Commerce-Schema)                                               | —      |
| Test    | Reverse-Engineering gegen komplexe Test-Schemas                                     | 8.4    |

**Ergebnis**: Bestehende Datenbanken können in das neutrale Format überführt
werden. Damit wird auch LF-015 vervollständigt: Vergleiche sind dann nicht nur
Datei-zu-Datei, sondern zwischen Umgebungen bzw. Datenbanken möglich.

`data transfer` nutzt den `SchemaReader` für automatische FK-Reihenfolge und
streamt Daten direkt von `DataReader` zu `DataWriter` — ohne Serialisierung
in ein Zwischenformat. Unterstützt `--tables`, `--filter`, `--truncate`,
`--on-conflict update`, `--since-column`/`--since` und `--trigger-mode` analog
zu den bestehenden Export-/Import-Flags. Grundlage für die Teil-Replikation
(LF-025) in 1.4.0; dort kommen Datenmaskierung und selektive Datensatzfilter
hinzu.

### Milestone 0.7.0 — Tool-Integrationen ✅ (2026-04-15)

| Bereich     | Aufgabe                                                 | LF-Ref | Status |
| ----------- | ------------------------------------------------------- | ------ | ------ |
| Core        | Phase A: DDL-Header-Determinismus, Adapter-Grundlagen   | LF-011 | ✅      |
| Core        | Phase B: Migrations-Bundle, Identitaet, Exportvertrag   | LF-011 | ✅      |
| Integration | Phase C: Tool-Adapter (Flyway, Liquibase, Django, Knex) | LF-011 | ✅      |
| CLI         | Phase D: CLI- und Runner-Pfad fuer Tool-Export          | LF-011 | ✅      |
| Test        | Phase E: Runtime-Validierung gegen echte Tool-Projekte  | LF-011 | ✅      |
| Docs        | Phase F: Release-Dokumentation und Smoke-Tests          | LF-011 | ✅      |
| Core        | Migrations-Rollback-Generierung                         | LF-014 | ✅      |
| Test        | Generierte Migrations-Skripte ausführen und validieren  | 8.6    | ✅      |

**Ergebnis**: d-migrate integriert sich in bestehende Migrations-Toolchains.

### Milestone 0.7.5 — Daten-Profiling ✅ (2026-04-15)

| Bereich   | Aufgabe                                                                 | LF-Ref | Status |
| --------- | ----------------------------------------------------------------------- | ------ | ------ |
| Profiling | Phase A: Modulgeruest und Scope                                         | —      | ✅      |
| Profiling | Phase B: Domaenenmodell, Typen und Rule-Engine                          | —      | ✅      |
| Profiling | Phase C: Port-Vertraege und dialektspezifische Adapter                  | LF-004 | ✅      |
| Profiling | Phase D: Services und Runner                                            | —      | ✅      |
| CLI       | Phase E: `d-migrate data profile` Kommando                              | —      | ✅      |
| Docs      | Phase F: Doku, Smokes und Release-Pfade                                 | —      | ✅      |
| Test      | Unit-Tests (Rules, Services), Integration (SQLite, Testcontainers), E2E | LN-043 | ✅      |

**Ergebnis**: Bestehende Datenbanken können vor einer Migration profiliert werden.
Spaltenweise Kennzahlen, Qualitätswarnungen und Zieltyp-Kompatibilität als
JSON/YAML-Report. Design: [profiling.md](../../../spec/profiling.md).

> Hinweis: Die in [profiling.md §10](../../../spec/profiling.md#10-semantische-analyse-llm-erweiterung-spätere-phase)
> beschriebene semantische Analyse ist bewusst **nicht** Teil von 0.7.5.
> Zuerst wird das deterministische Kern-Profiling stabilisiert; die opt-in
> LLM-Erweiterung folgt später auf Basis der allgemeinen KI-Provider-
> Infrastruktur in [1.5.5](#milestone-155--ki-integration).

### Milestone 0.8.0 — Internationalisierung ✅ (2026-04-16)

| Bereich | Aufgabe                                                      | LF-Ref | Status |
| ------- | ------------------------------------------------------------ | ------ | ------ |
| Docs    | Phase A: Spezifikationsbereinigung und Scope-Fixierung       | —      | ✅      |
| i18n    | Phase B: I18n-Runtime und Config-Resolution                  | LF-006 | ✅      |
| i18n    | Phase C: ResourceBundles und lokalisierte CLI-Ausgaben       | LN-023 | ✅      |
| i18n    | Phase D: ICU4J-Integration für Unicode-Verarbeitung          | LF-005 | ✅      |
| Core    | Phase E: Zeitzonen- und Format-Policy (TemporalFormatPolicy) | LF-007 | ✅      |
| Formats | Phase F: CSV-Encoding-/BOM-Konsolidierung                    | LF-010 | ✅      |
| Test    | Phase G: Tests und Dokumentation (inkl. Unicode-Integrität)  | 8.5    | ✅      |

**Ergebnis**: Vollständige Unicode-/i18n-Unterstützung und konsistentes Zeitzonen-Handling. (Die erweiterten neutralen Typen JSON/Arrays/Binary/UUID sowie Spatial wurden in [0.5.5](#milestone-055--erweitertes-typsystem) vorgezogen.)

> Stand 2026-04-16: Phasen A–G umgesetzt — Spezifikationsbereinigung,
> I18n-Runtime, ResourceBundles, ICU4J-Integration, Zeitzonen-/Format-
> Policy, CSV-Encoding-/BOM-Konsolidierung sowie Tests und
> Dokumentation inklusive Mindest-Testmatrix, `--lang`-Grenze 0.8.0/0.9.0
> und DE-Bundle-Fallback-Nachweis. Milestone 0.8.0 damit abgeschlossen.

### Milestone 0.9.0 — Beta: Resilienz und vollständige i18n-CLI (abgeschlossen)

| Bereich | Aufgabe                                                               | LF-Ref | Status |
| ------- | --------------------------------------------------------------------- | ------ | ------ |
| Core    | Checkpoint/Resume für langläufige Operationen                         | LN-012 | ✅      |
| CLI     | `--lang` Flag für Sprachauswahl (greift auf 0.8.0-ResourceBundles zu) | LF-006 | ✅      |

**Zielbild nach Abschluss von 0.9.0**: Langläufige Export-/Import-
Operationen sind unterbrechbar und können wieder aufgesetzt werden; die in
0.8.0 eingeführte i18n-Infrastruktur ist über `--lang` vom Nutzer steuerbar.
Damit ist die Kern-Funktionalität für Beta-Tester abgeschlossen.

> **Ist-Stand (2026-04-16)**: Phasen A und B sind abgeschlossen —
> sichtbarer CLI-Vertrag (`--lang` aktiv, `--resume`,
> `--checkpoint-dir`, stdout/stdin-Preflight mit Exit 2,
> `docs/planning/ImpPlan-0.9.0-A.md` §4.5) plus technischer Unterbau:
> `CheckpointStore`-Port, versioniertes Manifest, dateibasierter Adapter
> mit atomarem Schreibpfad, `PipelineConfig` um `CheckpointConfig`
> erweitert, Merge-Helper, `operationId`-Feld in Progress-/Result-Typen
> und in der stderr-Summary (`docs/planning/ImpPlan-0.9.0-B.md` §2.2). Die
> eigentliche Streaming-Wiederaufnahme (Runner-Verdrahtung,
> Manifest-Fortschreibung, Wiederaufnahme aus vorhandenem Manifest,
> Executor-Seam fuer `operationId`, Renderer-Anzeige) wurde in Phase C/D
> implementiert. `--resume` ist seit 0.9.0 fuer dateibasierte Export-
> und Import-Laeufe produktiv.

> Hinweis: `--resume` ist in 0.9.0 bewusst **dateibasiert** zugeschnitten —
> stdout-Export und stdin-Import sind nicht Teil des Resume-Vertrags und
> enden mit Exit 2, wenn `--resume` kombiniert wird
> (`docs/planning/ImpPlan-0.9.0-A.md` §4.4). `data transfer` erhaelt in 0.9.0
> kein Resume; ein entsprechender Scope wird spaeter bewertet.

> Hinweis: LF-013 (Inkrementeller Export/Import) wurde nach 0.4.0 vorverlegt
> — siehe Begründung dort. Docs und Pilot-QA sind in **0.9.9** ausgelagert,
> damit dieser Milestone klein und fokussiert bleibt.

### Milestone 0.9.1 — Beta: Library-Refactor und Integrationsschnitt ✅ (2026-04-19)

| Bereich  | Aufgabe                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                 | LF-Ref | Status |
| -------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------ | ------ |
| Security | **Sicherheits-Härtung der Profiling-/Introspection-Adapter + Raw-SQL-Grenzen (`docs/user/quality.md`)** — zentrale Identifier-Quoting-Utility pro Dialekt, PreparedStatement für Metadaten-Literals, `--filter` / `constraint.expression` als dokumentierter Trusted-Input, Security-Tests mit böswilligen Bezeichnern                                                                                                                                                                                                                                                                                       | —      | ✅      |
| Refactor | **Zerlegung der großen Orchestrierungs- und Dialekt-Klassen** — `DataImport`/`DataExportRunner`, `StreamingImporter`, `SchemaComparator` in kleinere Dienste; DDL-Generatoren pro Objektart schneiden; `-- TODO: …`-Platzhalter durch strukturierte `ManualActionRequired`-Einträge ersetzen (`generatedStatements` vs. `manualActionsRequired`); dabei einen internen Phasen-/Objektschnitt vorbereiten, der einen späteren optionalen DDL-Output-Split (`pre-data`/`post-data`) sauber tragen kann, ohne den bestehenden `schema generate`-Default zu brechen (siehe `docs/planning/ddl-output-split-plan.md`) | —      | ✅      |
| Core     | Read-/Write-Schnitt in `hexagon:ports` für externe Consumer schärfen                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                    | —      | ✅      |
| Driver   | Profiling-Adapter aus JDBC-Treiber-Kernmodulen extrahieren                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                              | —      | ✅      |
| Core     | FK-/Topo-Sort-Utility als wiederverwendbaren Helfer nach `hexagon:core`                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                 | —      | ✅      |
| Arch     | Integrationsgrenzen für externe Library-Consumer schärfen                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                               | —      | ✅      |

**Ergebnis**: `d-migrate` ist intern so refaktoriert, dass die Kernmodule
sauberer als Libraries konsumierbar sind, ohne schon einen öffentlichen
Publish-Vertrag zu versprechen. Die 1.0.0-Artefaktklassifikation
(Foundation, Ports, Driver Runtime, Optional Extensions, Internal
Tooling) ist dokumentiert und die Publish-Landkarte vorbereitet. Die Sicherheits-Findings aus
`docs/user/quality.md` (Injection-Fläche in Profiling/Introspection,
offengehaltene Raw-SQL-Pfade) sind abgearbeitet, und die wartungs-
kritischen Orchestrierungs-/Dialekt-Hotspots (`Data*Runner`,
`StreamingImporter`, `SchemaComparator`, DDL-Generatoren mit
`-- TODO:`-Platzhaltern) sind zerlegt, bevor 1.0.0 einen Publish-
Vertrag festschreibt.

> Hinweis: Dieser Milestone bereitet wiederverwendbare Libraries für externe
> Consumer wie `d-browser` vor. Ein öffentlicher Publish-Vertrag inklusive
> Maven-Central-Portal-Workflow bleibt bewusst **1.0.0** vorbehalten, damit
> Modulgrenzen, Koordinaten und API-Flächen erst nach dem Refactor
> stabilisiert werden.

### Milestone 0.9.2 — Beta: DDL-Phasen und importfreundliche Schema-Artefakte

| Bereich | Aufgabe                                                                                                                              | LF-Ref | Status |
| ------- | ------------------------------------------------------------------------------------------------------------------------------------ | ------ | ------ |
| Core    | Phasenbezogenes DDL-Modell fuer `pre-data` und `post-data` (AP 6.1)                                                                  | —      | ✅      |
| CLI     | Optionaler DDL-Split fuer `schema generate` (`--split single\|pre-post`) (AP 6.2)                                                    | LF-003 | ✅      |
| Driver  | Objektzuordnung pro Phase (inkl. Trigger/Funktionen/Procedures sowie Views mit Routinen-Abhaengigkeiten) (AP 6.3)                    | —      | ✅      |
| CLI     | JSON-Ausgabe fuer Split-Fall ueber `ddl_parts` statt Typwechsel im bestehenden `ddl`-Feld (AP 6.4)                                   | —      | ✅      |
| Formats | Phase-Attribution fuer Notes und `skipped_objects` in Report- und JSON-Ausgabe (AP 6.4)                                              | —      | ✅      |
| Test    | Golden-Master-, CLI- und Fehlerpfad-Tests fuer Split-Ausgabe, View-/Routinen-Abhaengigkeiten und Rueckwaertskompatibilitaet (AP 6.7) | —      | ✅      |
| Test    | E2E-Round-Trip-Test: DB→Export→Format→Import→DB→Schema-Vergleich (AP 6.7)                                                            | —      | ✅      |
| Quality | DDL-Interpolation systematisch absichern: CHECK-Constraints, Partitions, Trigger-Bedingungen, SpatiaLite (AP 6.5)                    | —      | ✅      |
| Quality | MySQL-TODO-Platzhalter: verbleibende 4 `-- TODO` in MysqlDdlGenerator durch ManualActionRequired ersetzen (AP 6.5)                   | —      | ✅      |
| Quality | Runner-Zerlegung: `executeWithPool()` in Schrittfunktionen (477→26 / 446→24 LOC) (AP 6.6)                                            | —      | ✅      |
| Quality | Executor-Parameter gruppieren: ExportExecutor (16→4) / ImportExecutor (14→4) → Kontext-DTOs (AP 6.6)                                 | —      | ✅      |
| Quality | Fehlercodes E006-E121 gegen Validierungsmatrix testen (AP 6.7)                                                                       | —      | ✅      |
| Docs    | Spezifikation und CLI-Doku fuer importfreundliche Schema-Artefakte aktualisieren                                                     | —      | ✅      |

**Ergebnis**: `schema generate` kann optional importfreundliche
Schema-Artefakte als `pre-data` und `post-data` erzeugen, ohne den bisherigen
Default-Output zu brechen. Damit erhalten vor allem MySQL- und SQLite-
Workflows einen sauberen Weg, Trigger erst nach einem Datenimport zu
aktivieren. Details und offener Implementierungsvertrag: siehe
`docs/planning/ddl-output-split-plan.md`.

> Hinweis: Dieser Milestone baut fachlich auf dem in **0.9.1** vorbereiteten
> internen Phasen-/Objektschnitt auf, ist aber bewusst als separater
> Nutzer-Feature-Milestone geschnitten. So bleiben Library-Refactor und
> sichtbarer CLI-/Output-Vertrag getrennt planbar.

### Milestone 0.9.3 — Beta: Filter-Haertung und MySQL-Sequence-Emulation (Generator)

| Bereich  | Aufgabe                                                                                                                                          | LF-Ref | Status |
| -------- | ------------------------------------------------------------------------------------------------------------------------------------------------ | ------ | ------ |
| Security | `--filter` gehaertet: geschlossene DSL mit Bind-Parametern (AP 6.1)                                                                              | —      | ✅      |
| Core     | Phase A: MySQL-Sequence-Emulationsvertrag, Enum, CLI-Option, Ledger W114–W117, Version 0.9.3 (AP 6.2)                                            | —      | ✅      |
| Core     | `DefaultValue.SequenceNextVal` im neutralen Modell; Audit aller `when(defaultValue)`-Stellen (AP 6.3)                                            | —      | ✅      |
| Driver   | Phase B: `MysqlDdlGenerator` — `helper_table`-Modus mit `dmg_sequences`, `dmg_nextval`/`dmg_setval`, kanonische `BEFORE INSERT`-Trigger (AP 6.4) | —      | ✅      |
| CLI      | `--mysql-named-sequences action_required\|helper_table` Option (opt-in, Default bleibt `action_required`)                                        | —      | ✅      |
| Test     | Phase C: Unit-Tests, Golden Masters und Integrationstests fuer beide Modi                                                                        | —      | ✅      |
| Docs     | Doku- und Fixture-Nachzug: CHANGELOG, Guide, Roadmap, Fixture-Erweiterung, Golden Masters fuer `helper_table` (AP 6.5)                           | —      | ✅      |

**Ergebnis**: `schema generate --target mysql` kann benannte Sequences optional
ueber kanonische Hilfsobjekte (`dmg_sequences`, Routinen, Trigger) emulieren
statt sie mit `E056` zu ueberspringen. Der Modus ist opt-in (`helper_table`).
Reverse-Engineering und Compare folgen in 0.9.4. Details:
[`mysql-sequence-emulation-plan.md`](../done/mysql-sequence-emulation-plan.md).

### Milestone 0.9.4 — Beta: MySQL-Sequence Reverse-Engineering und Compare ✅ (2026-04-21)

| Bereich | Aufgabe                                                                                                       | LF-Ref | Status |
| ------- | ------------------------------------------------------------------------------------------------------------- | ------ | ------ |
| Driver  | Phase D1: Reader-Vertrag und Metadatenzugriff (AP 6.1)                                                        | —      | ✅      |
| Driver  | Phase D2: Sequence-Reverse aus `dmg_sequences` (AP 6.2)                                                       | —      | ✅      |
| Driver  | Phase D3: Sequence-Default-Reverse ueber Trigger (AP 6.3)                                                     | —      | ✅      |
| Core    | Phase E1: Compare-Stabilisierung — Renderer-Nachzug und Exit-Code-Vertrag (AP 6.4)                            | —      | ✅      |
| Docs    | Phase E2: Doku- und Vertragsnachzug (AP 6.5)                                                                  | —      | ✅      |
| Test    | Round-Trip-Tests: neutral → MySQL-DDL → MySQL reverse → neutral (sequence-stabil)                             | —      | ✅      |
| Test    | Integrationstests gegen echte MySQL-DB (Reverse, Compare, degradierter Zustand bei fehlenden Supportobjekten) | —      | ✅      |

**Ergebnis**: MySQL-Sequence-Emulation ist vollstaendig: generieren, reverse-en
und vergleichen. Hilfsobjekte werden beim Reverse sauber auf `SequenceDefinition`
zurueckgefaltet und tauchen nicht als Rauschen im Diff auf. Degradierte
Zustaende (fehlende Support-Routinen oder Trigger) erzeugen `W116` als
Diagnosemeldung; `schema compare` bleibt dabei exit-code-stabil und zeigt
`W116` als operandseitige Diagnose, nicht als eigenen Diff. Details:
[`mysql-sequence-emulation-plan.md`](../done/mysql-sequence-emulation-plan.md) Phase D+E;
Teilplaene: [`ImpPlan-0.9.4-6.1.md`](../done/ImpPlan-0.9.4-6.1.md),
[`ImpPlan-0.9.4-6.2.md`](../done/ImpPlan-0.9.4-6.2.md),
[`ImpPlan-0.9.4-6.3.md`](../done/ImpPlan-0.9.4-6.3.md),
[`ImpPlan-0.9.4-6.4.md`](../done/ImpPlan-0.9.4-6.4.md),
[`ImpPlan-0.9.4-6.5.md`](../done/ImpPlan-0.9.4-6.5.md).

### Milestone 0.9.5 — Qualitätsverbesserung ✅ (2026-04-24)

| Bereich | Aufgabe            | LF-Ref |
| ------- | ------------------ | ------ |
| Quality | Quality verbessern | —      |

### Milestone 0.9.6 — MCP-Server ✅ (2026-05-08)

| Bereich | Aufgabe                                                                                                                                          | LF-Ref | Status         |
| ------- | ------------------------------------------------------------------------------------------------------------------------------------------------ | ------ | -------------- |
| Server  | Phase A: Gemeinsamer Serverkern (Kernmodelle, Store-Ports, Byte-Stores, Fingerprint, Approval, Quota, Error, Audit) — siehe `ImpPlan-0.9.6-A.md` | —      | ✅ (2026-04-26) |
| Server  | Phase B: MCP-v1-Server ueber `stdio` und streambares HTTP mit Initialize/Capability-Negotiation und Principal-Kontext — siehe `ImpPlan-0.9.6-B.md` und `spec/mcp-server.md` | —      | ✅ (2026-05-01) |
| Server  | Phase C: Read-only Schema-Tools (Validate, Compare, Generate DDL) + read-only Schema-Staging-Subset von `artifact_upload_init`/`artifact_upload`/`artifact_upload_abort` + `artifact_chunk_get` + `job_status_get` — siehe `ImpPlan-0.9.6-C.md` | —      | ✅ (2026-05-04) |
| Server  | Phase D: Discovery-Tools (`job_list`, `artifact_list`, `schema_list`, `profile_list`, `diff_list`) + MCP-Standard-Discovery (`resources/list`, `resources/templates/list`, `resources/read`) + Resource-Resolver/Stores + Connection-Refs + adapterneutraler Connection-Bootstrap — siehe `ImpPlan-0.9.6-D.md` | —      | ✅ (2026-05-04) |
| Server  | Phase E0: Cancel-Gate fuer bestehende Runner (Cancel-Vertrag in `hexagon:core`, Token-Propagation durch Reverse/Profile/Import/Transfer, Cancel-Checkpoints + Exit-130-Mapping, Side-Effect-Matrix, Driver-Vertrags-Klassifikation) — siehe `ImpPlan-0.9.6-E0.md`, `ImpPlan-0.9.6-E0-Side-Effect-Matrix.md`, `ImpPlan-0.9.6-E0-Gate-Decision.md` (Verdict `Go`) | —      | ✅ (2026-05-05) |
| Server  | Phase E0.7: Driver-Adapter-Timeout-Konfiguration (`PoolSettings.statementTimeoutMs`/`networkTimeoutMs`, driver-spezifischer `connectionInitSql`, common `TimeoutDecoratedConnection`-Layer, Bench-Tests pro Driver) — siehe `ImpPlan-0.9.6-E0.7.md` | —      | ✅ (2026-05-05) |
| Server  | Phase E: Async-Jobs, Idempotenz und Policy — Job-Start-Service fuer `schema_reverse_start`/`data_profile_start`/`schema_compare_start`, produktiver `job_cancel` mit Worker-Handle-Registry, Idempotency-Zustandsautomat, Policy/Approval-Grants, Quotas und Timeouts; produktive Persistenz und Async-Executor in den Sub-Plaenen **E2**/**E3** — siehe `../done/ImpPlan-0.9.6-E.md` und `spec/phase-e-port-atomicity.md` | —      | ✅ (2026-05-06) |
| Server  | Phase E2: Persistente Phase-E-Port-Adapter (JDBC/Postgres) — `JdbcTransactionRunner`, `JdbcIdempotencyStore`, `JdbcJobStore`, `JdbcJobStartTransaction`, `JdbcQuotaService`/`JdbcQuotaReservationOwnerStore` mit Flyway-Initial-Migration und Contract-Test-Lauf gegen Testcontainers-Postgres — siehe `../done/ImpPlan-0.9.6-E2.md` und `spec/phase-e-port-atomicity.md` | —      | ✅ (2026-05-06) |
| Server  | Phase E3: Async-Executor Production-Tuning — `BoundedAsyncJobExecutor` mit `JobExecutorLifecycle`, `JobDispatchAdmission`-Gate, Backpressure (`RateLimited` reason `EXECUTOR_SATURATED`), Cancel-while-queued, graceful Shutdown; `SyncExecutor` bleibt Default, Async ist opt-in via `server.jobs.executor.mode` — siehe `../done/ImpPlan-0.9.6-E3.md` | —      | ✅ (2026-05-06) |
| Server  | Phase F: Policy-gesteuerte Datenoperationen (`data_import_start`, `data_transfer_start`) + policy-pflichtige `artifact_upload_init`-Variante mit Session-Metadaten und Approval-Flow + administrative Abort-Pipeline + STORED_ARTIFACT_BYTES-Quota + Finalisations-Timeout-Sweeper — siehe `../done/ImpPlan-0.9.6-F.md` | —      | ✅ (2026-05-07) |
| Server  | Phase G: KI-nahe MCP-Tools (`procedure_transform_plan`, `procedure_transform_execute`, `testdata_plan`) + MCP-Prompts (`prompts/list`, `prompts/get`) + AiProviderPort/NoOp + Provider-Quota + Prompt-Hygiene + abschliessende Integrationstests — siehe `../done/ImpPlan-0.9.6-G.md` | —      | ✅ (2026-05-07) |

**Ergebnis**: d-migrate ist als MCP-Server nutzbar und kann von KI-Agenten
ueber das Model Context Protocol gesteuert werden. 0.9.6 implementiert
`spec/ki-mcp.md` vollstaendig, inklusive beider Transportmodi,
kontrollierter Write-Tools, KI-naher Spezialtools und MCP-Prompts. Details:
[`ki-mcp.md`](../../../spec/ki-mcp.md).

### Milestone 0.9.7 — Refactoring, Hardening, Diff-basierte Migrationen und SQLite-Sequence-Emulation

| Bereich  | Aufgabe                                                                                                                                                                                                                                                                                                          | LF-Ref |
| -------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------ |
| Refactor | ✅ (2026-05-08) ICU4J hinter einen Hexagon-Port — `hexagon:application` haengt nicht mehr direkt von `com.ibm.icu:icu4j` ab; Unicode-Normalisierung und Grapheme-Counting laufen ueber adapterneutrale Abstraktion. Details: [`refactoring-icu4j.md`](../done/refactoring-icu4j.md)                                | —      |
| Refactor | SHA-256 / Hex-Encoding konsolidieren — zentrale Top-Level-Extension in `hexagon:core`, ersetzt 11 Fundstellen mit vier verschiedenen Schreibweisen (`%02x`-Format, Lookup-Table, joinToString-Varianten). Details: [`refactoring-sha256Hex.md`](../open/refactoring-sha256Hex.md)                                 | —      |
| Hardening | RFC-7662 Client-Auth fuer Token-Introspection (`IntrospectionAuthValidator.kt:48`) — `Authorization`-Header (Basic / client_secret) am Introspection-POST setzen, damit Production-Deployments mit `client_credentials` LN-025/LN-028-konform laufen                                                              | LN-025, LN-028 |
| Hardening | Idempotency-Key fuer read-only Schema-Staging-Uploads (`ArtifactUploadInitHandler.kt:88`, AP 6.13 / `ImpPlan-0.9.6-B §5.3.5`) — same-checksum Replay trifft die bestehende Session statt eine neue zu minten und Quota zu verbrennen                                                                              | —      |
| Docs    | Migrate Phase A: Spezifikations- und Namensbereinigung — `spec/cli-spec.md` fuer `schema migrate`/`schema rollback`, Exit-Code-Tabelle (Migrations-Blocker `8`, DDL-Fehler `5`), `spec/design.md` um `DiffResult` | —      |
| Core    | Migrate Phase B: Core-Vertrag — `DiffResult`, `DiffOperation`, `DiffObjectRef`, `DiffPhase`, `Reversibility`, `OperationRisks`, stabile Operation-IDs, Operation-Payloads, kanonische Fingerprint-Projektion   | —      |
| Core    | Migrate Phase C: Planner — `DiffPlanner` mit Mapping `SchemaDiff` → Operationen, Dependency-/Phasen-Sortierung, inverse Down-Sortierung, Reversibilitaetsklassifizierung, blockierende Diagnose                | —      |
| Driver  | Migrate Phase D: Dialekt-DDL erste Matrix — PostgreSQL (Tabellen/Spalten/Constraints/Indizes/Views), MySQL (analog mit Dependency-Vorbedingungen), SQLite (RebuildTable mit `PRAGMA foreign_keys`-Vertrag)     | —      |
| CLI     | Migrate Phase E: `SchemaMigrateRunner` und `SchemaRollbackRunner` — Operand-Aufloesung, Reverse-Normalisierung, `--plan-only`, `--rollback-output`, `--execute --report` (Pflicht), `--dry-run`, Rollback-Guard | —      |
| Test    | Migrate Phase F: Tests und Smokes — Round-Trip Soll→DB, Drift-Pruefung, SQLite-Rebuild-Smoke, Cross-Dialekt-Matrix                                                                                             | —      |
| Driver  | SQLite-Seq Phase A: Vertrag — `--sqlite-named-sequences action_required\|helper_table` Option, Ledger fuer SQLite-spezifische Warn-Codes (analog W114–W117), `spec/ddl-generation-rules.md` §7 erweitern       | —      |
| Driver  | SQLite-Seq Phase B: Generator — `helper_table`-Modus mit `dmg_sequences` und Trigger-basierter `nextval`-Emulation (kein Stored Function — Logik im Trigger-Body), `BEFORE INSERT`-Trigger pro Spalte          | —      |
| Test    | SQLite-Seq Phase C: Unit-Tests, Golden Masters und Integrationstests fuer beide Modi                                                                                                                          | —      |
| Driver  | SQLite-Seq Phase D: Reverse-Engineering — `dmg_sequences`-Tabelle, kanonische Trigger und Spalten-Defaults zurueck auf `SequenceDefinition`/`SequenceNextVal` falten                                           | —      |
| Core    | SQLite-Seq Phase E: Compare-Stabilisierung — Operandseitige Diagnose (analog `W116` MySQL), Hilfsobjekte aus dem Diff filtern                                                                                  | —      |

**Ergebnis**: Zwei verzahnte Schwerpunkte. (1) `schema migrate` liest
Ist-Zustand (DB oder Schema-Datei), diffed gegen Soll-Schema, plant
einen migrationsfaehigen Operationsplan (`DiffResult`) und rendert
dialektbewusste Up-DDL. `schema rollback` fuehrt den Down-Plan gegen
die Datenbank aus, mit Driftpruefung und Audit-Reports. Details:
[`diffresult-migration-plan.md`](../open/diffresult-migration-plan.md).
(2) `schema generate --target sqlite` kann benannte Sequences optional
ueber kanonische Hilfsobjekte (`dmg_sequences` plus Trigger-basierte
Logik ohne Stored Functions) emulieren statt sie mit `E056` zu
ueberspringen. Reverse-Engineering und Compare folgen dem MySQL-0.9.4-
Muster. Details:
[`sqlite-sequence-emulation-plan.md`](../open/sqlite-sequence-emulation-plan.md).

> Hinweis Migrate-Matrix: Tabellen/Spalten/Constraints/Indizes und
> einfache Views. Nicht enthalten: vollstaendige Routine-/Trigger-
> Migration, Sequence-Migrationen und automatische Daten-Transformationen.

> Hinweis SQLite-Sequence: Die Vollvariante ersetzt nicht
> `INTEGER PRIMARY KEY AUTOINCREMENT` (das bleibt direkt ueber
> `NeutralType.Identifier`) und migriert keine handgeschriebenen
> SQLite-Sequence-Loesungen.

### Milestone 0.9.8 — Analytics- und Storage-Anschluss (Evaluierungen + BI-Demo)

| Bereich | Aufgabe                                                                                                                                                                          | LF-Ref |
| ------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------ |
| Arch    | Parquet: JVM-Parquet-Bibliotheken gegen Lizenz, API und Streaming-Verhalten pruefen                                                                                              | —      |
| Formats | Parquet: `ParquetChunkWriter`-Prototyp fuer `data export` mit minimalem Typmapping (Decimal, Temporal, Binary, UUID, JSON, Arrays, Geometry-Sidecar)                             | —      |
| Test    | Parquet: Prototyp gegen DuckDB lesen lassen (`read_parquet`) und Typen inspizieren                                                                                               | —      |
| Formats | Parquet: Importpfad (`ParquetChunkReader`) pruefen, chunk-weises Streaming und Schema-/Nullability-Erhalt validieren                                                             | —      |
| Arch    | Parquet: Manifest-Format fuer Multi-Table-Exports skizzieren (stabile Dateinamen, Schema-Sidecar)                                                                                | —      |
| Docs    | Parquet: Entscheidungsvorlage mit Aufwand, Risiken und empfohlenem Scope                                                                                                         | —      |
| Arch    | Object-Storage: Bestehende Artefakt- und Checkpoint-Pfade inventarisieren, minimalen `ArtifactStore`-Port entwerfen, File-Implementierung als Referenz                           | —      |
| Arch    | Object-Storage: S3-kompatible Implementierung evaluieren, Konfigurations-/Security-Regeln skizzieren, Migration des MCP-/REST-/gRPC-Jobvertrags auf Artifact-Refs planen         | —      |
| Demo    | BI-Demo unter `examples/bi-demo/` mit Docker Compose: PostgreSQL + Metabase + MinIO (S3-kompatibel) + optional `d-migrate`-CLI-Container, Beispiel-Schema, Seed-Daten             | —      |
| Demo    | BI-Demo: Smoke-Script fuer Start, Healthcheck und minimale Demo-Kommandos (Reverse, Profiling, Transfer)                                                                         | —      |

**Ergebnis**: Drei verzahnte Anschluss-Tracks fuer Phase 4 vorbereitet.
(1) Parquet-Evaluierung liefert Prototyp, DuckDB-Kompatibilitaetsbeleg und
Entscheidungsvorlage fuer eine spaetere Vollumsetzung. Dies ist bewusst
keine Lakehouse-Implementierung — Iceberg/Delta/Hudi bleiben ausserhalb
des Scopes (siehe
[`parquet-export-import-evaluation.md`](../open/parquet-export-import-evaluation.md) §3.2).
(2) Object-Storage-ArtifactStore-Plan erstellt einen minimalen
`ArtifactStore`-Port mit File-Referenz und S3-kompatibler Evaluierung,
plus die Migrationsskizze fuer MCP-/REST-/gRPC-Jobvertraege auf
Artifact-Refs (siehe
[`object-storage-artifact-store.md`](../open/object-storage-artifact-store.md)).
(3) BI-Demo-Umgebung unter `examples/bi-demo/` zeigt `d-migrate` in einem
komponierbaren Analytics-Stack mit PostgreSQL, Metabase und MinIO als
gemeinsamem Object-Storage-Endpunkt — als reproduzierbares Beispiel,
nicht als Enterprise-BI-Plattform (siehe
[`bi-demo-compose.md`](../open/bi-demo-compose.md)).

> Hinweis: Positive Parquet-Evaluierung fuehrt zu einem Folge-
> Implementierungsmilestone in Phase 4 (vermutlich neben 1.6.0 Metadata
> Catalog und Lakehouse Targets). Object-Storage- und BI-Demo-Plaene
> bilden die Grundlage fuer Storage- und Showcase-Pfade ab Phase 3/4.

### Milestone 0.9.9 — Dokumentation und Pilot-Validierung

| Bereich | Aufgabe                                   | LF-Ref |
| ------- | ----------------------------------------- | ------ |
| Docs    | Vollständiges Anwenderhandbuch            | —      |
| Docs    | Administrationshandbuch                   | —      |
| Docs    | Migrations-Leitfaden                      | —      |
| Docs    | API-Dokumentation                         | —      |
| QA      | Performance-Benchmarks dokumentiert       | —      |
| QA      | Pilotanwender-Tests (mindestens 5 Tester) | 9.2    |

**Ergebnis**: Die Beta-Dokumentation ist vollständig und Pilotanwender haben
das System gegen reale Datenbestände getestet. Bereit für den 1.0.0-RC-Cut.

> Hinweis: Eine kuratierte Liste geeigneter Beispiel- und Testdatenbanken fuer
> Pilot-Validierung, Resume-/Streaming-Proben und Performance-nahe Laeufe
> steht in
> [`test-database-candidates.md`](../open/test-database-candidates.md).

> **Warum ein eigener Milestone für Docs und QA?** Dokumentations- und
> Pilot-Tasks haben eine deutlich andere Cadence als Code-Features (sie
> brauchen Reviews durch Domain-Experten und externe Tester). Wenn sie im
> 0.9.0-Code-Milestone hängen, verschiebt sich der Beta-Cut auf das
> langsamste Element. 0.9.9 entkoppelt das, ohne die Inhalte zu streichen.

---

## Phase 3: Stable (1.0.0) — Monate 13-15

**Ziel**: Enterprise-ready Release

### Milestone 1.0.0-RC — Release Candidate

| Bereich   | Aufgabe                                              | LF-Ref |
| --------- | ---------------------------------------------------- | ------ |
| Streaming | Streaming-Pipeline Optimierung (kein OOM bei >10 TB) | LN-005 |
| Streaming | Parallele Tabellenverarbeitung (Coroutines)          | LN-007 |
| Streaming | Partitionierte Tabellen: paralleler Export/Import    | LN-008 |
| Core      | SHA-256-Verifikation für Datenintegrität             | LN-009 |
| Core      | Atomare Rollbacks auf Checkpoint-Ebene               | LN-013 |
| Security  | Verschlüsselte Credential-Speicherung (AES-256)      | LN-025 |
| Security  | TLS/SSL für alle DB-Verbindungen                     | LN-026 |
| Security  | Audit-Logging aller Operationen                      | LN-027 |
| QA        | Property-Based Testing (Jqwik)                       | LN-046 |
| QA        | Performance-Regression-Tests                         | LN-044 |

### Milestone 1.0.0 — Stable Release

| Bereich   | Aufgabe                                                             | LF-Ref |
| --------- | ------------------------------------------------------------------- | ------ |
| Build     | GraalVM Native Image (Linux, macOS, Windows)                        | —      |
| Build     | Docker Image auf Docker Hub                                         | —      |
| Build     | SDKMAN-Distribution                                                 | —      |
| Build     | Maven-Central-Portal Publish-Workflow für stabile Library-Artefakte | —      |
| Security  | Externer Security-Audit                                             | —      |
| QA        | 1 Mio. Datensätze Export/Import ohne Datenverlust                   | 8.1    |
| QA        | DDL-Generierung 1.000 Tabellen < 30 Sekunden                        | 8.2    |
| QA        | Cross-DB Round-Trip: PostgreSQL → MySQL → SQLite                    | 8.6    |
| Docs      | Best Practices Guide                                                | —      |
| Docs      | Troubleshooting-Guide                                               | —      |
| Community | Contributor Guide                                                   | —      |
| Community | Code of Conduct                                                     | —      |
| Community | Issue- und PR-Templates                                             | —      |

**Ergebnis**: Stabile Version 1.0.0 — produktionsreif, performant, sicher.

---

## Phase 4: Growth (1.1.0 - 2.0.0) — Monate 16-24

**Ziel**: Feature-Completeness und Ökosystem-Wachstum

### Milestone 1.1.8 — gRPC-API

| Bereich | Aufgabe                                                                          | LF-Ref |
| ------- | -------------------------------------------------------------------------------- | ------ |
| Server  | Schema-Service: Validate, Generate, Reverse, Compare mit Protobuf-Typisierung    | —      |
| Server  | Bidirektionales Artefakt-Streaming: Upload mit Chunk-Acknowledgements und Resume | —      |
| Server  | Job-Watching: Fortschritt und Events ueber WatchJob-Stream statt Polling         | —      |
| Server  | Fuenf Services: Health, Schema, Data, Job, Artifact mit separaten RPCs           | —      |
| Server  | Data-Service: Export, Import, Transfer, Profile als Jobs mit Artefakt-Referenzen | —      |
| Test    | gRPC-Integrationstests und Protobuf-Error-Mapping mit d-migrate-Fehlercodes      | —      |

**Ergebnis**: d-migrate bietet eine performante gRPC-API fuer
Low-Latency-Integration und Streaming-Szenarien. Details:
[`grpc-service.md`](../../../spec/grpc-service.md).

### Milestone 1.2.0 — REST-API

| Bereich | Aufgabe                                                                                          | LF-Ref |
| ------- | ------------------------------------------------------------------------------------------------ | ------ |
| Server  | Synchrone Schema-Endpoints: Validate und Generate DDL mit Groessenlimit                          | LF-022 |
| Server  | Asynchrones Job-Modell: Reverse, Compare, Profile, Export mit 202-Acceptance und Location-Header | LF-022 |
| Server  | Idempotency: Alle Job-Start-Endpoints mit Idempotency-Key fuer Deduplizierung                    | LF-022 |
| Server  | Artefakt-Handling: Upload, Download, Register mit Multipart und Remote-Storage                   | LF-022 |
| Server  | Datenoperationen: Import, Export, Transfer, Profile als Jobs mit Filter-DSL (kein rohes SQL)     | LF-022 |
| Server  | Tool-Exports: Flyway, Liquibase, Django, Knex als async Jobs mit Artefakt-Ergebnis               | LF-022 |
| Test    | REST-API-Integrationstests und OpenAPI-Spec-Validierung                                          | LF-022 |

**Ergebnis**: d-migrate bietet eine vollstaendige REST-API fuer
programmatische Integration in CI/CD-Pipelines und Web-Frontends. Details:
[`rest-service.md`](../../../spec/rest-service.md).

### Milestone 1.3.0 — Testdaten-Generierung

| Bereich | Aufgabe                                                 | LF-Ref |
| ------- | ------------------------------------------------------- | ------ |
| Core    | Regelbasierte Testdaten-Generierung (Faker-Integration) | LF-024 |
| AI      | KI-gestützte Testdaten-Generierung (optional)           | LF-024 |
| Core    | Seed-basierte Reproduzierbarkeit                        | LF-024 |
| Core    | Mehrsprachige Testdaten (Namen, Adressen, etc.)         | LF-024 |
| CLI     | `d-migrate data seed` Kommando                          | LF-024 |

**Ergebnis**: Automatische Generierung realistischer Testdaten.

### Milestone 1.4.0 — Erweiterte Features

| Bereich | Aufgabe                                                  | LF-Ref         |
| ------- | -------------------------------------------------------- | -------------- |
| Core    | Datenmaskierung / Pseudonymisierung beim Export          | LF-021, LF-026 |
| Core    | Teil-Replikation (selektive Tabellen/Datensätze)         | LF-025         |
| Core    | Automatische Dokumentationsgenerierung (ER-Diagramme)    | LF-016         |
| Build   | Docker-Images mit vordefinierter DB-Struktur + Testdaten | LF-023         |

### Milestone 1.5.0 — Oekosystem-Integrationen

| Bereich     | Aufgabe                                                                                         | LF-Ref |
| ----------- | ----------------------------------------------------------------------------------------------- | ------ |
| Profiling   | Profiling-Report-Exporter fuer Data-Quality-Tools wie Great Expectations, Soda und Pandera — siehe [`profiling-data-quality-export.md`](../open/profiling-data-quality-export.md) | —      |
| Integration | Orchestrator-Beispiele fuer Airflow, Dagster und Prefect dokumentieren und als Smoke-Pfade testen — siehe [`orchestrator-examples.md`](../open/orchestrator-examples.md) | —      |
| Demo        | BI-Demo-Umgebung unter `examples/bi-demo/` mit PostgreSQL, Metabase und d-migrate-Smoke-Pfad planen — siehe [`bi-demo-compose.md`](../open/bi-demo-compose.md) | —      |

### Milestone 1.5.5 — KI-Integration

| Bereich | Aufgabe                                                                                                                   | LF-Ref         |
| ------- | ------------------------------------------------------------------------------------------------------------------------- | -------------- |
| AI      | AiProvider-Interface und Plugin-System                                                                                    | LF-017, LN-041 |
| AI      | OllamaProvider (lokale Modelle)                                                                                           | LF-017         |
| AI      | OpenAiProvider (GPT-4)                                                                                                    | LF-017         |
| AI      | AnthropicProvider (Claude)                                                                                                | LF-017         |
| AI      | XaiProvider (Grok), GoogleProvider (Gemini)                                                                               | LF-017         |
| AI      | RuleBasedProvider (Fallback ohne KI)                                                                                      | LN-035         |
| AI      | Stored Procedure → Markdown-Zwischenformat                                                                                | LF-017         |
| AI      | Markdown-Zwischenformat → Ziel-DB-Code                                                                                    | LF-017         |
| AI      | SemanticAnalysisPort und strukturierte Profiling-Contracts fuer semantische Analyse                                       | —              |
| AI      | Semantische Profiling-Vorschlaege fuer Mapping, Transformation und Normalisierung auf Basis verdichteter Profil-Summaries | —              |
| AI      | Datenschutz: prefer_local, allow_external Flags                                                                           | LN-032, LN-033 |
| AI      | Caching häufiger Transformationen                                                                                         | LN-035         |
| CLI     | `d-migrate transform procedure` Kommando                                                                                  | LF-017         |
| CLI     | `d-migrate generate procedure` Kommando                                                                                   | LF-017         |
| QA      | Semantische Äquivalenzprüfung (Testausführung)                                                                            | LN-034         |

**Ergebnis**: KI-gestützte Migration von Stored Procedures zwischen Datenbanken.
Zusätzlich wird die gemeinsame KI-Provider-Infrastruktur für opt-in
Profiling-Erweiterungen genutzt: semantische Vorschläge für Mapping,
Transformation und Normalisierung, während Kennzahlen, Warnungen und harte
Validierung deterministisch im Profiling-Kern bleiben.

> **Abgrenzung**: Die semantische Analyse ergänzt das Profiling nur um
> Vorschläge. Rohe Daten müssen dafür nicht an das Modell gegeben werden; der
> Input bleibt auf verdichtete Profil-Summaries begrenzt, konsistent mit
> Privacy-by-Design und der Trennung aus `spec/profiling.md` §10.

### Milestone 1.6.0 — Metadata Catalog und Lakehouse Targets

| Bereich     | Aufgabe                                                                                         | LF-Ref |
| ----------- | ----------------------------------------------------------------------------------------------- | ------ |
| Metadata    | Catalog-Publisher fuer Schema-Snapshots, Diffs, Profiling-Reports und Artefaktrefs planen — siehe [`catalog-publisher-lakehouse-targets.md`](../../../spec/catalog-publisher-lakehouse-targets.md) | —      |
| Lakehouse   | Iceberg-/Delta-Zieladapter als spaetere Option evaluieren, abhaengig von Parquet- und Object-Storage-Grundlagen | —      |

### Milestone 1.7.0 — Weitere Datenbanken (MS SQL Server)

| Bereich | Aufgabe                                     | LF-Ref |
| ------- | ------------------------------------------- | ------ |
| Driver  | MS SQL Server-Treiber (JDBC, T-SQL-Support) | LF-019 |
| Test    | Cross-DB-Tests mit 4 Datenbanksystemen      | 8.7    |

**Ergebnis**: Unterstützung für MS SQL Server als viertes relationales
Datenbanksystem.

### Milestone 1.8.0 — Weitere Datenbanken (Oracle)

| Bereich | Aufgabe                                                  | LF-Ref |
| ------- | -------------------------------------------------------- | ------ |
| Driver  | Oracle-Treiber (JDBC, PL/SQL-Support)                    | LF-019 |
| Core    | Erweitertes Typsystem für Oracle/MSSQL-spezifische Typen | LF-019 |
| Test    | Cross-DB-Tests mit 5 Datenbanksystemen                   | 8.7    |

**Ergebnis**: Unterstützung für die 5 wichtigsten relationalen Datenbanken.

### Milestone 2.0.0 — Langfristige Vision

| Bereich   | Aufgabe                                        | LF-Ref |
| --------- | ---------------------------------------------- | ------ |
| GUI       | Grafische Benutzeroberfläche für Schema-Design | LF-018 |
| Core      | Schema-Optimierungsvorschläge                  | LF-020 |
| Core      | Rollenbasierte Zugriffskontrolle               | LN-028 |
| Community | LTS-Support für 1.x-Linie (24 Monate)          | —      |

**Ergebnis**: Feature-Complete, Enterprise-ready, aktive Community.

---

## Release-Strategie

| Typ           | Kadenz                | Beschreibung                              |
| ------------- | --------------------- | ----------------------------------------- |
| Patch (x.x.1) | Monatlich             | Bugfixes, Security-Patches                |
| Minor (x.1.0) | Quartalsweise         | Neue Features, rückwärtskompatibel        |
| Major (2.0.0) | Nach Bedarf           | Breaking Changes, mit Migrationsleitfaden |
| RC / Beta     | Vor jedem Minor/Major | Testphase für Community-Feedback          |

### Versionierung

- **Semantic Versioning (SemVer 2.0)** für alle Releases
- **Deprecation-Policy**: Mindestens 2 Minor-Versionen Vorlaufzeit
- **LTS**: Major-Versionen werden 24 Monate unterstützt

---

## Abhängigkeiten und Risiken

| Risiko                              | Auswirkung                                 | Mitigation                                                          |
| ----------------------------------- | ------------------------------------------ | ------------------------------------------------------------------- |
| GraalVM Native Image Kompatibilität | Verzögerung bei Single-Binary-Distribution | Fat JAR als Fallback, Native Image schrittweise einführen           |
| JDBC-Treiber-Qualität für SQLite    | Einschränkungen bei erweiterten Features   | SQLite-spezifische Workarounds, direkte C-Anbindung als Option      |
| KI-API-Kosten und Verfügbarkeit     | Abhängigkeit von externen Diensten         | Lokale Modelle (Ollama) als Default, regelbasierter Fallback        |
| Community-Aufbau                    | Langsame Adoption                          | Early-Adopter-Programm, Konferenz-Talks, Blog-Posts                 |
| Oracle/MSSQL JDBC-Lizenzierung      | Einschränkungen bei Distribution           | Treiber als separate optionale Module, Nutzer stellt Treiber bereit |

---

## Metriken und Erfolgskriterien

### Technische Metriken (pro Release)

- Code-Coverage >= 80% (Type-Mapping: 100%)
- Keine kritischen/hohen Sicherheitslücken
- Performance-Benchmarks eingehalten (siehe [Lastenheft 8.2](../../../spec/lastenheft-d-migrate.md))
- Build-Dauer < 5 Minuten

### Projekt-Metriken (nach 1.0.0)

- Migrations-Aufwand um >= 40% reduziert (Pilotanwender-Feedback)
- >= 3 erfolgreiche Produktiv-Migrationen
- >= 80% positive Pilotanwender-Bewertung
- Aufnahme in >= 2 Enterprise-Entwicklungsstacks

---

**Version**: 3.43
**Stand**: 2026-05-08
**Status**: Milestone 0.1.0–0.9.6 abgeschlossen — der MCP-Server-Milestone ist veröffentlicht. Geplant: 0.9.7 (Refactorings + Diff-basierte Migrationen + SQLite-Sequence-Emulation), 0.9.8 (Parquet-Evaluierung + Object-Storage-Plan + BI-Demo), 0.9.9 (Doku/Pilot), 1.0.0-RC, 1.0.0; danach Phase 4 mit gRPC-API (1.1.8), REST-API (1.2.0), Testdaten (1.3.0), erweiterte Features (1.4.0), Oekosystem-Integrationen (1.5.0), KI-Integration (1.5.5), Metadata-Catalog (1.6.0), MS SQL Server (1.7.0), Oracle (1.8.0).
