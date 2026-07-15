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
| Core    | Neutrales Schema-Modell (Kotlin Data Classes)          | [`LF-001`](../../../spec/lastenheft-d-migrate.md#lf-001) | ✅      |
| Core    | YAML-Parser für Schema-Definitionen                    | [`LF-001`](../../../spec/lastenheft-d-migrate.md#lf-001) | ✅      |
| Core    | Basis-Validierung (E001-E018, W001)                    | [`LF-002`](../../../spec/lastenheft-d-migrate.md#lf-002) | ✅      |
| CLI     | Clikt-basiertes CLI-Grundgerüst                        | [`LF-012`](../../../spec/lastenheft-d-migrate.md#lf-012) | ✅      |
| CLI     | `d-migrate schema validate` Kommando                   | [`LF-002`](../../../spec/lastenheft-d-migrate.md#lf-002) | ✅      |
| Test    | Kotest Setup, 83 Tests, Kover Coverage >= 90%          | [`LN-043`](../../../spec/lastenheft-d-migrate.md#ln-043) | ✅      |

**Ergebnis**: Schema-Dateien können geladen und validiert werden.

### Milestone 0.2.0 — DDL-Generierung ✅ (2026-04-06)

| Bereich | Aufgabe                                             | LF-Ref | Status |
| ------- | --------------------------------------------------- | ------ | ------ |
| Core    | TypeMapper-Interface und Basislogik                 | [`LF-003`](../../../spec/lastenheft-d-migrate.md#lf-003) | ✅      |
| Core    | AbstractDdlGenerator mit topologischer Sortierung   | [`LF-003`](../../../spec/lastenheft-d-migrate.md#lf-003) | ✅      |
| Core    | ViewQueryTransformer (17 SQL-Funktionen)            | [`LF-003`](../../../spec/lastenheft-d-migrate.md#lf-003) | ✅      |
| Driver  | PostgreSQL-Treiber: DDL-Generierung                 | [`LF-003`](../../../spec/lastenheft-d-migrate.md#lf-003) | ✅      |
| Driver  | MySQL-Treiber: DDL-Generierung                      | [`LF-003`](../../../spec/lastenheft-d-migrate.md#lf-003) | ✅      |
| Driver  | SQLite-Treiber: DDL-Generierung                     | [`LF-003`](../../../spec/lastenheft-d-migrate.md#lf-003) | ✅      |
| CLI     | `d-migrate schema generate --target <db>`           | [`LF-003`](../../../spec/lastenheft-d-migrate.md#lf-003) | ✅      |
| CLI     | `--output`, `--generate-rollback`, `--report` Flags | [`LF-003`](../../../spec/lastenheft-d-migrate.md#lf-003) | ✅      |
| CLI     | TransformationReportWriter (YAML-Sidecar)           | [`LF-003`](../../../spec/lastenheft-d-migrate.md#lf-003) | ✅      |
| Test    | TypeMapper-Tests (100% Coverage, 3 Dialekte)        | [`LN-045`](../../../spec/lastenheft-d-migrate.md#ln-045) | ✅      |
| Test    | DdlGenerator Unit-Tests (44+53+57 Tests)            | [`LN-043`](../../../spec/lastenheft-d-migrate.md#ln-043) | ✅      |
| Test    | Golden-Master-Tests (4 Schemas × 3 Dialekte = 12)   | [`LN-043`](../../../spec/lastenheft-d-migrate.md#ln-043) | ✅      |
| Test    | 374 Tests gesamt, Coverage >= 90%                   | [`LN-045`](../../../spec/lastenheft-d-migrate.md#ln-045) | ✅      |

**Ergebnis**: Aus YAML-Schemas wird datenbankspezifisches DDL generiert.

### Milestone 0.3.0 — Datenexport ✅ (2026-04-06)

| Bereich | Aufgabe                                                          | LF-Ref | Status |
| ------- | ---------------------------------------------------------------- | ------ | ------ |
| Core    | Streaming-Pipeline (pull-basiert, chunk-weise)                   | [`LN-005`](../../../spec/lastenheft-d-migrate.md#ln-005) | ✅      |
| Driver  | JDBC-basierter DataReader (ResultSet-Streaming, PG/MySQL/SQLite) | [`LF-008`](../../../spec/lastenheft-d-migrate.md#lf-008) | ✅      |
| Driver  | Connection-Management mit HikariCP + ConnectionUrlParser         | —      | ✅      |
| Formats | JSON-Codec (DSL-JSON statt Jackson — Performance)                | [`LF-009`](../../../spec/lastenheft-d-migrate.md#lf-009) | ✅      |
| Formats | YAML-Codec (SnakeYAML Engine)                                    | [`LF-009`](../../../spec/lastenheft-d-migrate.md#lf-009) | ✅      |
| Formats | CSV-Codec (uniVocity-parsers, mit Encoding/BOM/Delimiter)        | [`LF-009`](../../../spec/lastenheft-d-migrate.md#lf-009) | ✅      |
| CLI     | `d-migrate data export` Kommando                                 | [`LF-008`](../../../spec/lastenheft-d-migrate.md#lf-008) | ✅      |
| CLI     | `NamedConnectionResolver` (`.d-migrate.yaml`, `${ENV_VAR}`)      | —      | ✅      |
| Test    | Export-Tests gegen Testcontainers (PostgreSQL 16, MySQL 8.0)     | [`LN-048`](../../../spec/lastenheft-d-migrate.md#ln-048) | ✅      |

**Ergebnis**: Daten können aus PostgreSQL, MySQL und SQLite streamingbasiert
in JSON, YAML oder CSV exportiert werden — chunk-weise, ohne die Tabelle in
den Speicher zu laden. CLI mit Named Connections, Roh-WHERE-Filter,
`--split-files`-Multi-Tabellen-Export, vollständige Exit-Code-Matrix
(0/2/4/5/7) und §6.17 Empty-Table-Vertrag pro Format.

### Milestone 0.4.0 — Datenimport und inkrementelle Datenpfade ✅ (2026-04-12)

| Bereich | Aufgabe                                                                                      | LF-Ref |
| ------- | -------------------------------------------------------------------------------------------- | ------ |
| Core    | Transaktionale Import-Logik (Chunk-basiert)                                                  | [`LN-010`](../../../spec/lastenheft-d-migrate.md#ln-010) |
| Driver  | JDBC-basierter DataWriter (Batch-Insert)                                                     | [`LF-010`](../../../spec/lastenheft-d-migrate.md#lf-010) |
| Core    | Sequence-/Identity- und AUTO_INCREMENT-Konsistenz nach Import                                | [`LF-010`](../../../spec/lastenheft-d-migrate.md#lf-010) |
| Driver  | Dialektspezifisches Trigger-Handling beim Import                                             | [`LF-010`](../../../spec/lastenheft-d-migrate.md#lf-010) |
| Formats | Deserialisierung aus JSON/YAML/CSV (Streaming-Reader)                                        | [`LF-010`](../../../spec/lastenheft-d-migrate.md#lf-010) |
| Core    | Validierung gegen Schema-Definition beim Import (Target-Schema autoritativ)                  | [`LF-010`](../../../spec/lastenheft-d-migrate.md#lf-010) |
| Core    | Encoding-Unterstützung: BOM-Detection für UTF-8/UTF-16, alle anderen via `--encoding`-Flag   | [`LF-010`](../../../spec/lastenheft-d-migrate.md#lf-010) |
| CLI     | `d-migrate data import` Kommando                                                             | [`LF-010`](../../../spec/lastenheft-d-migrate.md#lf-010) |
| Core    | Inkrementeller **Export** über explizite Marker-Spalte (`--since-column`, `--since`)         | [`LF-013`](../../../spec/lastenheft-d-migrate.md#lf-013) |
| CLI     | `--incremental` Flag für `data export`; idempotenter Import via `--on-conflict update`       | [`LF-013`](../../../spec/lastenheft-d-migrate.md#lf-013) |
| Test    | Round-Trip-Tests (Export → Import → Vergleich)                                               | [`LN-043`](../../../spec/lastenheft-d-migrate.md#ln-043) |
| Test    | Import-Tests für Sequence-Reseeding und Trigger-Verhalten                                    | [`LN-043`](../../../spec/lastenheft-d-migrate.md#ln-043) |
| Test    | Inkrement-Round-Trip-Tests (initial export → delta export → idempotenter Import → Vergleich) | [`LN-043`](../../../spec/lastenheft-d-migrate.md#ln-043) |

**Ergebnis**: Vollständiger Export/Import-Zyklus funktioniert. Inkrementell
ist explizit zweigeteilt: Export bekommt funktionale `--since-column`-Filter,
Import läuft über idempotenten UPSERT (`--on-conflict update`) — siehe
implementation-plan-0.4.0.md §6.12.

> **Begründung der [`LF-013`](../../../spec/lastenheft-d-migrate.md#lf-013)-Vorverlegung von 0.9.0 nach 0.4.0**: Inkrementeller
> Export/Import gehört semantisch zum Daten-Pfad, nicht zur Beta-Reife.
> Sobald `data import` ([`LF-010`](../../../spec/lastenheft-d-migrate.md#lf-010)) steht, ist die nötige Schreib-Infrastruktur
> vorhanden; die einfache Variante `--since-column updated_at --since
> "<timestamp>"` braucht weder Auto-Discovery noch einen vollständigen
> `SchemaReader` ([`LF-004`](../../../spec/lastenheft-d-migrate.md#lf-004) → 0.6.0). Eine spätere Erweiterung um automatische
> Marker-Spalten-Erkennung kann in 0.6.0 nachgezogen werden, ohne den
> 0.4.0-CLI-Vertrag zu brechen. Die ursprüngliche Einordnung in 0.9.0
> hätte [`LF-013`](../../../spec/lastenheft-d-migrate.md#lf-013) künstlich zurückgehalten, obwohl die fachlichen
> Voraussetzungen schon mit 0.4.0 erfüllt sind. Checkpoint/Resume ([`LN-012`](../../../spec/lastenheft-d-migrate.md#ln-012))
> bleibt in 0.9.0 — Inkrement und Checkpoint sind technisch unabhängig
> (Inkrement kennzeichnet *was* exportiert wird, Checkpoint *wo* der
> Export aufgesetzt hat).

### Milestone 0.5.0 — MVP-Release ✅ (2026-04-13)

| Bereich | Aufgabe                                                         | LF-Ref |
| ------- | --------------------------------------------------------------- | ------ |
| Core    | Schema-Diff-Engine (Vergleich zweier Schemas, file-based MVP)   | [`LF-015`](../../../spec/lastenheft-d-migrate.md#lf-015) |
| CLI     | `d-migrate schema compare` Kommando (Datei-zu-Datei, MVP-Slice) | [`LF-015`](../../../spec/lastenheft-d-migrate.md#lf-015) |
| CLI     | Fortschrittsanzeige für lange Operationen                       | [`LN-017`](../../../spec/lastenheft-d-migrate.md#ln-017) |
| Docs    | Anwenderhandbuch (Basis)                                        | —      |
| Docs    | CLI-Referenz                                                    | —      |
| Build   | GitHub Releases (Fat JAR)                                       | —      |
| Build   | Homebrew-Formula (Basis)                                        | —      |
| QA      | Code-Coverage >= 80%                                            | [`LN-045`](../../../spec/lastenheft-d-migrate.md#ln-045) |

**Ergebnis**: Öffentliches MVP-Release. Early Adopters können Schema-Verwaltung
und Daten-Export/Import nutzen. [`LF-015`](../../../spec/lastenheft-d-migrate.md#lf-015) ist in 0.5.0 bewusst nur teilweise
abgedeckt: `schema compare` vergleicht zwei neutrale Schema-Dateien; der volle
Vergleich zwischen Umgebungen bzw. Datenbanken folgt in 0.6.0.

Detaillierter Plan: [implementation-plan-0.5.0.md](../done-archive/implementation-plan-0.5.0.md)

### Milestone 0.5.5 — Erweitertes Typsystem ✅ (2026-04-13)

| Bereich | Aufgabe                                                                              | LF-Ref |
| ------- | ------------------------------------------------------------------------------------ | ------ |
| Core    | Neutraler Typ `geometry` mit `geometry_type` und `srid`                              | [`LF-003`](../../../spec/lastenheft-d-migrate.md#lf-003) |
| Core    | Spatial-Profil als Generator-Option (`postgis`, `native`, `spatialite`, `none`)      | [`LF-003`](../../../spec/lastenheft-d-migrate.md#lf-003) |
| Core    | Validierungsregeln und Hinweis-Codes `E120`, `E121`, `W120`, `E052`                  | [`LF-002`](../../../spec/lastenheft-d-migrate.md#lf-002) |
| Driver  | PostgreSQL/PostGIS: `geometry(<type>, <srid>)`-Mapping                               | [`LF-003`](../../../spec/lastenheft-d-migrate.md#lf-003) |
| Driver  | MySQL: native Spatial Types (`POINT`, `POLYGON`, `MULTIPOLYGON`, ...)                | [`LF-003`](../../../spec/lastenheft-d-migrate.md#lf-003) |
| Driver  | SQLite/SpatiaLite: `AddGeometryColumn(...)`-Strategie                                | [`LF-003`](../../../spec/lastenheft-d-migrate.md#lf-003) |
| Core    | Erweiterte Typen im neutralen Modell: JSON, Arrays, Binary, UUID                     | [`LF-001`](../../../spec/lastenheft-d-migrate.md#lf-001) |
| CLI     | `--spatial-profile` Option für `schema generate`                                     | [`LF-003`](../../../spec/lastenheft-d-migrate.md#lf-003) |
| Test    | Golden-Master-Tests: `spatial.{postgresql,mysql,sqlite}.sql`                         | [`LN-043`](../../../spec/lastenheft-d-migrate.md#ln-043) |
| Test    | Negativtests für ungültige `geometry_type`/`srid` und Profil-Mismatches              | [`LN-043`](../../../spec/lastenheft-d-migrate.md#ln-043) |
| Docs    | CR `docs/planning/done-archive/change-request-spatial-types.md` in den Status _Approved_ überführen        | —      |
| Docs    | `spec/neutral-model-spec.md` und `spec/ddl-generation-rules.md` um Spatial erweitern | —      |

**Ergebnis**: Das neutrale Typsystem ist vor Reverse-Engineering (0.6.0) vollständig. Spatial-Daten können ohne Datenverlust zwischen PostgreSQL/PostGIS, MySQL und SQLite/SpatiaLite transportiert werden; JSON/Arrays/Binary/UUID sind modelliert und getestet.

Dieser Milestone basiert auf dem [Change Request Spatial Types](../done-archive/change-request-spatial-types.md) (Phase 1) und zieht die vormals für 0.8.0 geplanten erweiterten Typen vor, damit `schema reverse` ab 0.6.0 von Anfang an gegen ein vollständiges Typsystem arbeiten kann.

---

## Phase 2: Beta (0.6.0 - 0.9.9) — Monate 7-12

**Ziel**: Production-ready für erste Projekte

### Milestone 0.6.0 — Reverse-Engineering ✅ (2026-04-14)

| Bereich | Aufgabe                                                                             | LF-Ref |
| ------- | ----------------------------------------------------------------------------------- | ------ |
| Driver  | SchemaReader: Tabellen, Spalten, Constraints aus DB extrahieren                     | [`LF-004`](../../../spec/lastenheft-d-migrate.md#lf-004) |
| Driver  | SchemaReader: Indizes, Sequences, Custom Types                                      | [`LF-004`](../../../spec/lastenheft-d-migrate.md#lf-004) |
| Driver  | SchemaReader: Stored Procedures, Functions, Triggers, Views                         | [`LF-004`](../../../spec/lastenheft-d-migrate.md#lf-004) |
| Driver  | PostgreSQL-spezifisch: Extensions, JSONB, Array-Typen                               | [`LF-004`](../../../spec/lastenheft-d-migrate.md#lf-004) |
| Driver  | MySQL-spezifisch: Engine, AUTO_INCREMENT, SET-Typen                                 | [`LF-004`](../../../spec/lastenheft-d-migrate.md#lf-004) |
| Driver  | SQLite-spezifisch: WITHOUT ROWID, Virtual Tables                                    | [`LF-004`](../../../spec/lastenheft-d-migrate.md#lf-004) |
| CLI     | `d-migrate schema reverse --source <db-url>`                                        | [`LF-004`](../../../spec/lastenheft-d-migrate.md#lf-004) |
| CLI     | `schema compare` gegen Umgebungen/DBs auf Basis von `SchemaReader` vervollständigen | [`LF-015`](../../../spec/lastenheft-d-migrate.md#lf-015) |
| Core    | `StreamingTransfer`-Orchestrator: DB-zu-DB-Streaming ohne Zwischenformat            | [`LF-025`](../../../spec/lastenheft-d-migrate.md#lf-025) |
| CLI     | `d-migrate data transfer --source <url> --target <url>` Kommando                    | [`LF-025`](../../../spec/lastenheft-d-migrate.md#lf-025) |
| Docs    | Beispiel-Projekte (E-Commerce-Schema)                                               | —      |
| Test    | Reverse-Engineering gegen komplexe Test-Schemas                                     | 8.4    |

**Ergebnis**: Bestehende Datenbanken können in das neutrale Format überführt
werden. Damit wird auch [`LF-015`](../../../spec/lastenheft-d-migrate.md#lf-015) vervollständigt: Vergleiche sind dann nicht nur
Datei-zu-Datei, sondern zwischen Umgebungen bzw. Datenbanken möglich.

`data transfer` nutzt den `SchemaReader` für automatische FK-Reihenfolge und
streamt Daten direkt von `DataReader` zu `DataWriter` — ohne Serialisierung
in ein Zwischenformat. Unterstützt `--tables`, `--filter`, `--truncate`,
`--on-conflict update`, `--since-column`/`--since` und `--trigger-mode` analog
zu den bestehenden Export-/Import-Flags. Grundlage für die Teil-Replikation
([`LF-025`](../../../spec/lastenheft-d-migrate.md#lf-025)) in 1.4.0; dort kommen Datenmaskierung und selektive Datensatzfilter
hinzu.

### Milestone 0.7.0 — Tool-Integrationen ✅ (2026-04-15)

| Bereich     | Aufgabe                                                 | LF-Ref | Status |
| ----------- | ------------------------------------------------------- | ------ | ------ |
| Core        | Phase A: DDL-Header-Determinismus, Adapter-Grundlagen   | [`LF-011`](../../../spec/lastenheft-d-migrate.md#lf-011) | ✅      |
| Core        | Phase B: Migrations-Bundle, Identitaet, Exportvertrag   | [`LF-011`](../../../spec/lastenheft-d-migrate.md#lf-011) | ✅      |
| Integration | Phase C: Tool-Adapter (Flyway, Liquibase, Django, Knex) | [`LF-011`](../../../spec/lastenheft-d-migrate.md#lf-011) | ✅      |
| CLI         | Phase D: CLI- und Runner-Pfad fuer Tool-Export          | [`LF-011`](../../../spec/lastenheft-d-migrate.md#lf-011) | ✅      |
| Test        | Phase E: Runtime-Validierung gegen echte Tool-Projekte  | [`LF-011`](../../../spec/lastenheft-d-migrate.md#lf-011) | ✅      |
| Docs        | Phase F: Release-Dokumentation und Smoke-Tests          | [`LF-011`](../../../spec/lastenheft-d-migrate.md#lf-011) | ✅      |
| Core        | Migrations-Rollback-Generierung                         | [`LF-014`](../../../spec/lastenheft-d-migrate.md#lf-014) | ✅      |
| Test        | Generierte Migrations-Skripte ausführen und validieren  | 8.6    | ✅      |

**Ergebnis**: d-migrate integriert sich in bestehende Migrations-Toolchains.

### Milestone 0.7.5 — Daten-Profiling ✅ (2026-04-15)

| Bereich   | Aufgabe                                                                 | LF-Ref | Status |
| --------- | ----------------------------------------------------------------------- | ------ | ------ |
| Profiling | Phase A: Modulgeruest und Scope                                         | —      | ✅      |
| Profiling | Phase B: Domaenenmodell, Typen und Rule-Engine                          | —      | ✅      |
| Profiling | Phase C: Port-Vertraege und dialektspezifische Adapter                  | [`LF-004`](../../../spec/lastenheft-d-migrate.md#lf-004) | ✅      |
| Profiling | Phase D: Services und Runner                                            | —      | ✅      |
| CLI       | Phase E: `d-migrate data profile` Kommando                              | —      | ✅      |
| Docs      | Phase F: Doku, Smokes und Release-Pfade                                 | —      | ✅      |
| Test      | Unit-Tests (Rules, Services), Integration (SQLite, Testcontainers), E2E | [`LN-043`](../../../spec/lastenheft-d-migrate.md#ln-043) | ✅      |

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
| i18n    | Phase B: I18n-Runtime und Config-Resolution                  | [`LF-006`](../../../spec/lastenheft-d-migrate.md#lf-006) | ✅      |
| i18n    | Phase C: ResourceBundles und lokalisierte CLI-Ausgaben       | [`LN-023`](../../../spec/lastenheft-d-migrate.md#ln-023) | ✅      |
| i18n    | Phase D: ICU4J-Integration für Unicode-Verarbeitung          | [`LF-005`](../../../spec/lastenheft-d-migrate.md#lf-005) | ✅      |
| Core    | Phase E: Zeitzonen- und Format-Policy (TemporalFormatPolicy) | [`LF-007`](../../../spec/lastenheft-d-migrate.md#lf-007) | ✅      |
| Formats | Phase F: CSV-Encoding-/BOM-Konsolidierung                    | [`LF-010`](../../../spec/lastenheft-d-migrate.md#lf-010) | ✅      |
| Test    | Phase G: Tests und Dokumentation (inkl. Unicode-Integrität)  | 8.5    | ✅      |

**Ergebnis**: Vollständige Unicode-/i18n-Unterstützung und konsistentes Zeitzonen-Handling. (Die erweiterten neutralen Typen JSON/Arrays/Binary/UUID sowie Spatial wurden in [0.5.5](#milestone-055--erweitertes-typsystem--2026-04-13) vorgezogen.)

> Stand 2026-04-16: Phasen A–G umgesetzt — Spezifikationsbereinigung,
> I18n-Runtime, ResourceBundles, ICU4J-Integration, Zeitzonen-/Format-
> Policy, CSV-Encoding-/BOM-Konsolidierung sowie Tests und
> Dokumentation inklusive Mindest-Testmatrix, `--lang`-Grenze 0.8.0/0.9.0
> und DE-Bundle-Fallback-Nachweis. Milestone 0.8.0 damit abgeschlossen.

### Milestone 0.9.0 — Beta: Resilienz und vollständige i18n-CLI (abgeschlossen)

| Bereich | Aufgabe                                                               | LF-Ref | Status |
| ------- | --------------------------------------------------------------------- | ------ | ------ |
| Core    | Checkpoint/Resume für langläufige Operationen                         | [`LN-012`](../../../spec/lastenheft-d-migrate.md#ln-012) | ✅      |
| CLI     | `--lang` Flag für Sprachauswahl (greift auf 0.8.0-ResourceBundles zu) | [`LF-006`](../../../spec/lastenheft-d-migrate.md#lf-006) | ✅      |

**Zielbild nach Abschluss von 0.9.0**: Langläufige Export-/Import-
Operationen sind unterbrechbar und können wieder aufgesetzt werden; die in
0.8.0 eingeführte i18n-Infrastruktur ist über `--lang` vom Nutzer steuerbar.
Damit ist die Kern-Funktionalität für Beta-Tester abgeschlossen.

> **Ist-Stand (2026-04-16)**: Phasen A und B sind abgeschlossen —
> sichtbarer CLI-Vertrag (`--lang` aktiv, `--resume`,
> `--checkpoint-dir`, stdout/stdin-Preflight mit Exit 2,
> `docs/planning/done-archive/ImpPlan-0.9.0-A.md` §4.5) plus technischer Unterbau:
> `CheckpointStore`-Port, versioniertes Manifest, dateibasierter Adapter
> mit atomarem Schreibpfad, `PipelineConfig` um `CheckpointConfig`
> erweitert, Merge-Helper, `operationId`-Feld in Progress-/Result-Typen
> und in der stderr-Summary (`docs/planning/done-archive/ImpPlan-0.9.0-B.md` §2.2). Die
> eigentliche Streaming-Wiederaufnahme (Runner-Verdrahtung,
> Manifest-Fortschreibung, Wiederaufnahme aus vorhandenem Manifest,
> Executor-Seam fuer `operationId`, Renderer-Anzeige) wurde in Phase C/D
> implementiert. `--resume` ist seit 0.9.0 fuer dateibasierte Export-
> und Import-Laeufe produktiv.

> Hinweis: `--resume` ist in 0.9.0 bewusst **dateibasiert** zugeschnitten —
> stdout-Export und stdin-Import sind nicht Teil des Resume-Vertrags und
> enden mit Exit 2, wenn `--resume` kombiniert wird
> (`docs/planning/done-archive/ImpPlan-0.9.0-A.md` §4.4). `data transfer` erhaelt in 0.9.0
> kein Resume; ein entsprechender Scope wird spaeter bewertet.

> Hinweis: [`LF-013`](../../../spec/lastenheft-d-migrate.md#lf-013) (Inkrementeller Export/Import) wurde nach 0.4.0 vorverlegt
> — siehe Begründung dort. Docs und Pilot-QA sind in **0.9.9** ausgelagert,
> damit dieser Milestone klein und fokussiert bleibt.

### Milestone 0.9.1 — Beta: Library-Refactor und Integrationsschnitt ✅ (2026-04-19)

| Bereich  | Aufgabe                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                 | LF-Ref | Status |
| -------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------ | ------ |
| Security | **Sicherheits-Härtung der Profiling-/Introspection-Adapter + Raw-SQL-Grenzen (`docs/user/quality.md`)** — zentrale Identifier-Quoting-Utility pro Dialekt, PreparedStatement für Metadaten-Literals, `--filter` / `constraint.expression` als dokumentierter Trusted-Input, Security-Tests mit böswilligen Bezeichnern                                                                                                                                                                                                                                                                                       | —      | ✅      |
| Refactor | **Zerlegung der großen Orchestrierungs- und Dialekt-Klassen** — `DataImport`/`DataExportRunner`, `StreamingImporter`, `SchemaComparator` in kleinere Dienste; DDL-Generatoren pro Objektart schneiden; `-- TODO: …`-Platzhalter durch strukturierte `ManualActionRequired`-Einträge ersetzen (`generatedStatements` vs. `manualActionsRequired`); dabei einen internen Phasen-/Objektschnitt vorbereiten, der einen späteren optionalen DDL-Output-Split (`pre-data`/`post-data`) sauber tragen kann, ohne den bestehenden `schema generate`-Default zu brechen (siehe `docs/planning/done-archive/ddl-output-split-plan.md`) | —      | ✅      |
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
| CLI     | Optionaler DDL-Split fuer `schema generate` (`--split single\|pre-post`) (AP 6.2)                                                    | [`LF-003`](../../../spec/lastenheft-d-migrate.md#lf-003) | ✅      |
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
`docs/planning/done-archive/ddl-output-split-plan.md`.

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
[`mysql-sequence-emulation-plan.md`](../done-archive/mysql-sequence-emulation-plan.md).

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
[`mysql-sequence-emulation-plan.md`](../done-archive/mysql-sequence-emulation-plan.md) Phase D+E;
Teilplaene: [`ImpPlan-0.9.4-6.1.md`](../done-archive/ImpPlan-0.9.4-6.1.md),
[`ImpPlan-0.9.4-6.2.md`](../done-archive/ImpPlan-0.9.4-6.2.md),
[`ImpPlan-0.9.4-6.3.md`](../done-archive/ImpPlan-0.9.4-6.3.md),
[`ImpPlan-0.9.4-6.4.md`](../done-archive/ImpPlan-0.9.4-6.4.md),
[`ImpPlan-0.9.4-6.5.md`](../done-archive/ImpPlan-0.9.4-6.5.md).

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
| Server  | Phase E: Async-Jobs, Idempotenz und Policy — Job-Start-Service fuer `schema_reverse_start`/`data_profile_start`/`schema_compare_start`, produktiver `job_cancel` mit Worker-Handle-Registry, Idempotency-Zustandsautomat, Policy/Approval-Grants, Quotas und Timeouts; produktive Persistenz und Async-Executor in den Sub-Plaenen **E2**/**E3** — siehe `../done-archive/ImpPlan-0.9.6-E.md` und `spec/port-atomicity.md` | —      | ✅ (2026-05-06) |
| Server  | Phase E2: Persistente Phase-E-Port-Adapter (JDBC/Postgres) — `JdbcTransactionRunner`, `JdbcIdempotencyStore`, `JdbcJobStore`, `JdbcJobStartTransaction`, `JdbcQuotaService`/`JdbcQuotaReservationOwnerStore` mit Flyway-Initial-Migration und Contract-Test-Lauf gegen Testcontainers-Postgres — siehe `../done-archive/ImpPlan-0.9.6-E2.md` und `spec/port-atomicity.md` | —      | ✅ (2026-05-06) |
| Server  | Phase E3: Async-Executor Production-Tuning — `BoundedAsyncJobExecutor` mit `JobExecutorLifecycle`, `JobDispatchAdmission`-Gate, Backpressure (`RateLimited` reason `EXECUTOR_SATURATED`), Cancel-while-queued, graceful Shutdown; `SyncExecutor` bleibt Default, Async ist opt-in via `server.jobs.executor.mode` — siehe `../done-archive/ImpPlan-0.9.6-E3.md` | —      | ✅ (2026-05-06) |
| Server  | Phase F: Policy-gesteuerte Datenoperationen (`data_import_start`, `data_transfer_start`) + policy-pflichtige `artifact_upload_init`-Variante mit Session-Metadaten und Approval-Flow + administrative Abort-Pipeline + STORED_ARTIFACT_BYTES-Quota + Finalisations-Timeout-Sweeper — siehe `../done-archive/ImpPlan-0.9.6-F.md` | —      | ✅ (2026-05-07) |
| Server  | Phase G: KI-nahe MCP-Tools (`procedure_transform_plan`, `procedure_transform_execute`, `testdata_plan`) + MCP-Prompts (`prompts/list`, `prompts/get`) + AiProviderPort/NoOp + Provider-Quota + Prompt-Hygiene + abschliessende Integrationstests — siehe `../done-archive/ImpPlan-0.9.6-G.md` | —      | ✅ (2026-05-07) |

**Ergebnis**: d-migrate ist als MCP-Server nutzbar und kann von KI-Agenten
ueber das Model Context Protocol gesteuert werden. 0.9.6 implementiert
`spec/ki-mcp.md` vollstaendig, inklusive beider Transportmodi,
kontrollierter Write-Tools, KI-naher Spezialtools und MCP-Prompts. Details:
[`ki-mcp.md`](../../../spec/ki-mcp.md).

### Milestone 0.9.7 — Refactoring, Hardening, Diff-basierte Migrationen und SQLite-Sequence-Emulation ✅ (2026-06-02)

| Bereich  | Aufgabe                                                                                                                                                                                                                                                                                                          | LF-Ref |
| -------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------ |
| Refactor | ✅ (2026-05-08) ICU4J hinter einen Hexagon-Port — `hexagon:application` haengt nicht mehr direkt von `com.ibm.icu:icu4j` ab; Unicode-Normalisierung und Grapheme-Counting laufen ueber adapterneutrale Abstraktion. Details: [`refactoring-icu4j.md`](../done-archive/refactoring-icu4j.md)                                | —      |
| Refactor | ✅ (2026-05-09) SHA-256 / Hex-Encoding konsolidiert — `ByteArray.toHex()` + `sha256Hex(...)` in `hexagon/core/…/util/HexEncoding.kt`; alle ~20 Fundstellen migriert (urspruenglich 11 + 8 mcp-Handler). Details: [`refactoring-sha256Hex.md`](../done-archive/refactoring-sha256Hex.md) | —      |
| Hardening | ✅ (2026-05-09) RFC-7662 Client-Auth fuer Token-Introspection — `--introspection-client-id`/`--introspection-client-secret` setzen jetzt einen RFC 6749 §2.3.1 Basic-`Authorization`-Header am Introspection-POST; `McpServerConfig.validate()` lehnt XOR-Konfiguration (nur eins von beiden) ab. | [`LN-025`](../../../spec/lastenheft-d-migrate.md#ln-025), [`LN-028`](../../../spec/lastenheft-d-migrate.md#ln-028) |
| Hardening | ✅ (2026-05-09) Idempotenter Replay fuer read-only Schema-Staging-Uploads — `UploadSessionStore.findActiveSchemaStagingByChecksum(...)`-Vertrag plus In-Memory-Impl; `ArtifactUploadInitHandler` foldet same-checksum Retries auf die Bestands-Session, ohne neue Quota-Reservierungen. AP 6.13 / `ImpPlan-0.9.6-B §5.3.5`. | —      |
| Docs    | ✅ (2026-05-09) Migrate Phase A: Spezifikations- und Namensbereinigung — `spec/cli-spec.md` §6.1 schema migrate/rollback voll spezifiziert, Exit-Code `8 = MIGRATION_BLOCKED` ergaenzt; `docs/archive/design.md` §7.2 mit DiffResult-Glossar; `SchemaComparator.DiffResult<N,D>` → `CollectionDiff<N,D>`; CHECK/EXCLUDE-Constraint-Detector als Phase-A-Entscheidung im Plan-Doc | —      |
| Core    | ✅ (2026-05-09) Migrate Phase B: Core-Vertrag — `DiffResult` / `DiffOperation` (31 Subtypen) / `DiffObjectRef` / `DiffPhase` / `Reversibility` / `OperationRisks` / `OperationIdFactory` (Slice 1); `ReverseMarkerNormalizer` + `MigrationFingerprint` v1 (Slice 2). `hexagon:core` 94.4%. | —      |
| Core    | ✅ (2026-05-09) Migrate Phase C: Planner — `DiffPlanner` orchestriert `OperationMapper` (SchemaDiff → 31 Operationen mit deterministischen IDs), `DependencyAnalyzer` (FK / View-Edges) und `TopologicalSorter` (`DiffPhase` als Tie-Breaker); `DiffOperation.withDependencies(deps)` ueber sealed interface; `CONSTRAINT_NOT_DIFFABLE`-Blocker aus Phase A umgesetzt; `hexagon:core` 95.3%.                  | —      |
| Driver  | ✅ (2026-05-09) Migrate Phase D: Dialekt-DDL erste Matrix — D.1 `MigrationDdlPort` (`hexagon:ports-read`), D.2 `PostgresDiffDdlGenerator` (Plan §6.2), D.3 `MysqlDiffDdlGenerator` (Plan §6.3, `DROP FOREIGN KEY`/`DROP INDEX … ON tbl`-Syntax), D.4.a SQLite simple-op + D.4.b RebuildTable-Pipeline (Plan §6.4: PRAGMA foreign_keys-Wrapping, BEGIN IMMEDIATE, deterministische Temp-Namen, Spaltenmapping mit CAST/DEFAULT/NULL-Fill, NOT-NULL-Backfill-Blocker, foreign_key_check). Down-Rebuild auf D.5 verschoben.                                                                                                                | —      |
| CLI     | ✅ (2026-05-09) Migrate Phase E: `SchemaMigrateRunner` und `SchemaRollbackRunner` — E.1 File-zu-File, E.2 Live-DB-Operanden, E.3 `--generate-rollback` mit `d-migrate rollback-sql v1`-Metadatenblock, E.4 `--execute` mit Post-Compare und Trace, E.5 strikter Rollback-Parser + dialekt/state Verifikation, E.6 Clikt-Wiring `schema migrate`/`schema rollback` + JdbcMigrationExecutor + Renderer-Registry. Recovery-Rollback-Artefakt und SQLite-Rebuild-Atomic-Execution sind als Phase-F-Carve-Outs dokumentiert.                                                          | —      |
| Test    | ✅ (2026-06-02 audit) Migrate Phase F: Tests und Smokes — Round-Trip Soll→DB (`{Postgres,Mysql,Sqlite}MigrateRoundTripIntegrationTest`), Drift-Pruefung (`MysqlDiffSequenceOpsDriftGateTest`, `SqliteSequenceCompareIntegrationTest`, `MatrixSweepTest`-Drift-Pfade), SQLite-Rebuild-Smoke (`SqliteRebuildH3a/H3b/H4Test`, `SqliteRebuildRendererTest`, `SqliteRebuildPlannerTest`), Cross-Dialekt-Matrix (`test/cross-dialect-matrix` mit `MatrixSweepTest` + Quality-Coverage-Expansion Phase B, `3545b646`/`3ae1bb20`)                                                                                             | —      |
| Driver  | SQLite-Seq Phase A: Vertrag — `--sqlite-named-sequences action_required\|helper_table` Option, Ledger fuer SQLite-spezifische Warn-Codes (analog W114–W117), `spec/ddl-generation-rules.md` §7 erweitern. ✅ Pre-Code-Kläurungen (§11 des Plan-Docs) abgeschlossen 2026-05-27: Min-SQLite-Version 3.35.0, `DefaultValue.SequenceNextVal` vorhanden, W114-Vertrag via [ADR-0003](../../adr/0003-cross-dialect-sequencing.md), Zwei-Trigger-Body gegen SQLite 3.53.1 prototyp-validiert. CLI-Plumbing (`SqliteNamedSequenceMode`-Enum + Flag) ist Teil von Phase B.1.       | —      |
| Driver  | SQLite-Seq Phase B: Generator — `helper_table`-Modus mit `dmg_sequences` und Trigger-basierter `nextval`-Emulation (kein Stored Function — Logik im Trigger-Body), `BEFORE INSERT`-Trigger pro Spalte. ✅ B.0 `DdlDialectContext`-Refactor (`48c7f01c`) + B.1 `SqliteNamedSequenceMode` + CLI-Plumbing (`84ba7ab7`) abgeschlossen 2026-05-27. ✅ B.2 Validator-Regeln abgeschlossen 2026-05-28: SequenceDefinition-internal-Regeln (`E125`, `25f59f73`) im `SchemaSequenceValidationRules` und helper_table-PK-Gate (`E059`, `09068f79`) über neuen `PreGenerationValidator`-Port (`SqliteHelperTableSequenceValidator` + `SqlitePreGenerationValidator`-Bridge, verdrahtet in `SchemaGenerateRunner` + `ToolExportRunner`). ✅ B.3 helper_table-DDL + `_bi`/`_ai`-Trigger-Paar (inkl. CHECK-`IS NOT NULL`-Auto-Suppression nach Plan-Doc §3.4) abgeschlossen 2026-05-29 plus Review-Findings 1/2/4/5a/5b/7/9/11/13 plus Scope-Carve-outs W121 (Conflict-Gap-INFO) und W122 (UPDATE-Trigger-Interferenz WARNING, konservativ wegen fehlender `UPDATE OF`-Modellierung): `SqliteSequenceDdlSupport` (state-pattern analog MySQL) mit `SqliteSequenceNaming` + `SqliteSequenceEmulationTemplates`; W114/W115/W117/W119/W121/W122, E056/E057/E124 verdrahtet; SqliteCapabilityDdlSupport delegiert; golden masters auf neue ManualActionRequired-Texte gezogen. ✅ B.4 `SequenceCapabilityDefaults.SQLite` flippen abgeschlossen 2026-05-29: 5 Render-Fidelity-Flags von `false` auf `true` (NamedSequences/Start/MinMaxValue/Cycle/Cache), `emitsCachePreallocationWarning` auf `true` (Single-Writer/W114-Vertrag analog MySQL); `supportsCurrentValuePreserve` bleibt `false`, `supportsOwnedBy` bleibt `false` (SQLite hat kein Ownership-Konzept). ✅ C Tests + Golden-Master für `helper_table`-Pfad abgeschlossen 2026-05-29: `full-featured.sqlite.helper-table{,.pre-data,.post-data}.sql`-Goldens; `SchemaGenerateRunnerErrorTest` deckt invalid-`--sqlite-named-sequences`-Wert; neuer `SqliteSequenceHelperTableIntegrationTest` mit 9 Tests gegen :memory:-SQLite (INSERT-Semantik Fall 1-4, Multi-Sequence, Boundary, Cycle, Erschöpfung, RAISE-Fehlerpfade). ✅ D Reverse abgeschlossen 2026-05-29: `SqliteSequenceReverseSupport` (Marker-Parser + Token-Body-Scanner + Snapshot-Pipeline) verdrahtet in `SqliteSchemaReader`. dmg_sequences-Erkennung, Trigger-Paar-Klassifikation primary/secondary/user, W116/W120/W124-Diagnosen, Filter der Hilfsobjekte aus dem neutralen Schema, Rekonstruktion sequence-getragener Spalten als `DefaultValue.SequenceNextVal`. 29 Unit-Tests + 3 Round-Trip-Integrationstests. ✅ E Compare + Stabilisierung abgeschlossen 2026-05-29: 4 Compare-Integrationstests (reverse-vs-reverse Round-Trip-Stabilität, sequencesChanged-Drift, Multi-Sequence-Disjunktion, Shared-Sequence). User-Doku in `docs/user/guide.md` ergänzt. SQLite-Sequence-Emulation-Plan damit komplett.       | —      |
| Test    | SQLite-Seq Phase C: Unit-Tests, Golden Masters und Integrationstests fuer beide Modi                                                                                                                          | —      |
| Driver  | SQLite-Seq Phase D: Reverse-Engineering — `dmg_sequences`-Tabelle, kanonische Trigger und Spalten-Defaults zurueck auf `SequenceDefinition`/`SequenceNextVal` falten                                           | —      |
| Core    | SQLite-Seq Phase E: Compare-Stabilisierung — Operandseitige Diagnose (analog `W116` MySQL), Hilfsobjekte aus dem Diff filtern                                                                                  | —      |

**Ergebnis**: Zwei verzahnte Schwerpunkte. (1) `schema migrate` liest
Ist-Zustand (DB oder Schema-Datei), diffed gegen Soll-Schema, plant
einen migrationsfaehigen Operationsplan (`DiffResult`) und rendert
dialektbewusste Up-DDL. `schema rollback` fuehrt den Down-Plan gegen
die Datenbank aus, mit Driftpruefung und Audit-Reports. Details:
[`diffresult-migration-plan-2.md`](../done-archive/diffresult-migration-plan-2.md).
(2) `schema generate --target sqlite` kann benannte Sequences optional
ueber kanonische Hilfsobjekte (`dmg_sequences` plus Trigger-basierte
Logik ohne Stored Functions) emulieren statt sie mit `E056` zu
ueberspringen. Reverse-Engineering und Compare folgen dem MySQL-0.9.4-
Muster. Details:
[`sqlite-sequence-emulation-plan.md`](../done-archive/sqlite-sequence-emulation-plan.md).

> Hinweis Migrate-Matrix: Tabellen/Spalten/Constraints/Indizes und
> einfache Views. Nicht enthalten: vollstaendige Routine-/Trigger-
> Migration, Sequence-Migrationen und automatische Daten-Transformationen.

> Hinweis SQLite-Sequence: Die Vollvariante ersetzt nicht
> `INTEGER PRIMARY KEY AUTOINCREMENT` (das bleibt direkt ueber
> `NeutralType.Identifier`) und migriert keine handgeschriebenen
> SQLite-Sequence-Loesungen.

#### Abschlussstand 0.9.7 (2026-06-02)

0.9.7 ist mit dem Release-Tag `v0.9.7` am 2026-06-02 abgeschlossen.
Die Tabelle fasst alle 22 Workstreams zusammen — alle Zeilen sind ✅
geliefert. Closure-Plan-Docs liegen in `docs/planning/done/`
(insbesondere `done-archive/diffresult-migration-plan-2.md`,
`done-archive/sequence-preserve-atomic-lock-plan.md`,
`done-archive/atomic-preserve-followups.md` plus 20 ImpPlan-0.9.7-*-Slice-
Closures).

| Workstream | Kurzbeschreibung | Status |
| ---------- | ---------------- | ------ |
| G | KI-nahe MCP-Tools / Prompts / Provider-Quotas | ✅ erledigt seit 2026-05-08 |
| A | Migrate-Spezifikation und Namensbereinigung | ✅ erledigt seit 2026-05-08 |
| B | PostgreSQL-`USING`-Overlays binden Up/Down getrennt, `PG_USING_OVERLAY_MISSING` blockiert ohne Nutzer-Overlay, `expressionSource` ist allowlist-beschraenkt; SQLite-Live-Cast-Preflight schreibt strukturierte `sqliteCastPreflights` in den Report | ✅ erledigt (2026-05-13) |
| C.1 Install-Policy | Konservative Extension-Install-Policy umgesetzt; MISSING/UNKNOWN/Privilege-Diagnostik separat unter C.1 Rest | ✅ erledigt seit 2026-05-08 |
| C.1 Rest | Renderer-Diagnostics trennen `EXTENSION_DEPENDENCY_MISSING` / `UNKNOWN` / `EXTENSION_INSTALL_PRIVILEGE_MISSING` / `_UNVERIFIED`; PostgreSQL-Reverse-Pfad trennt Installationsbefund (`R400`) vom Objektbefund (`R401`) | ✅ erledigt (2026-05-13) |
| C.2 | Planner-/Dependency-Slice | ✅ erledigt seit 2026-05-08 |
| D.1/D.2/D.3a | Erste dialektspezifische DDL-Matrix fuer PostgreSQL, MySQL und SQLite-Simple/Rebuild | ✅ erledigt seit 2026-05-08 |
| D.3b | Materialized-View-Migrationsvertrag vollstaendig implementiert: PG rendert Create/Replace/Drop diff-basiert; MySQL/SQLite blocken mit `MATERIALIZED_VIEW_NOT_SUPPORTED_BY_DIALECT`; Report und Dependency-Blocker sind umgesetzt | ✅ erledigt (2026-05-17) |
| E.1 | Routine-Migration fuer PostgreSQL/MySQL inklusive Body-Normalisierung, Scrubbing und Dependency-Sortierung. MySQL-Routine-Identity-Reverse-Read (`security`/`definer`/`sqlMode`) ✅ (2026-05-22, `41c62fe8`; Plan-Doc `docs/planning/done-archive/ImpPlan-0.9.7-mysql-routine-identity-reverse-read.md`). | ✅ erledigt (2026-05-15/16, MySQL-Identity-Reverse-Read als E.1-Folge-Slice am 2026-05-22) |
| E.2 | Trigger-Rendering fuer PostgreSQL, MySQL und SQLite inklusive Strict-Gap-Wiring, Body-Validierung, Namens-Kollisionsschutz und SQLite-Rebuild-Klassifikation | ✅ erledigt (2026-05-18) |
| E.3 | Erster PostgreSQL-Sequence-Slice; Preserve-/aktueller-Wert-Policy und Cross-Dialect-Sequencing-Schirm abgeschlossen (Sub-Slices A/B.0/B.1/C/D/E + Review-Findings; Plan-Doc `docs/planning/done-archive/ImpPlan-0.9.7-cross-dialect-sequencing.md`, ADR `docs/adr/0003-cross-dialect-sequencing.md`) | ✅ erledigt (2026-05-27) |
| E Rest | MySQL-Sequence-Diff-Migration ✅ (2026-05-20, Sub-Slices A–E; Plan-Doc `docs/planning/done-archive/ImpPlan-0.9.7-mysql-sequence-diff-migration.md`). MySQL-Sequence-Drift-Check ✅ (2026-05-20, Sub-Slices A–F + Review-Follow-ups 1–4; Live-DB-Probe-Adapter analog F.5 E.3; Plan-Doc `docs/planning/done-archive/ImpPlan-0.9.7-mysql-sequence-drift-check.md`). Sequence-preserveCurrentValue ✅ (2026-05-21, Sub-Slices A–E: Foundations + PG/MySQL-Renderer in A, PG-Probe in B, MySQL-Probe in C, Pipeline-Integration + Planner-Emit + CLI in D, Schema-Doku + Closing in E; Plan-Doc `docs/planning/done-archive/ImpPlan-0.9.7-sequence-preserve-current-value.md`). **SQLite-preserveCurrentValue-Folge-Slice ✅ (2026-05-29, Phasen A–F; commit `ff9fcc71`; Plan-Doc `docs/planning/done-archive/ImpPlan-0.9.7-sqlite-sequence-preserve-current-value.md`):** neuer `SqliteSequenceCurrentValueProbe`-Adapter (`dmg_sequences.next_value`-Read mit `managed_by`/`format_version`-Guard); `SequencePreserveStage` listet SQLite in der Allowlist und blockt ohne `--sqlite-named-sequences helper_table` mit dem neuen `SEQUENCE_PRESERVE_OPT_IN_REQUIRED`-Code (Classifier `MANUAL_ACTION_REQUIRED`) vor der Probe-Connection; `SqliteDiffSequenceOps.renderAlterSequenceCurrentValue` Down rendert deterministisch `UPDATE dmg_sequences SET next_value = <restoreValue> WHERE name = <probeRef>` statt No-Op; `SequenceCapabilityDefaults.SQLite.supportsCurrentValuePreserve` von `false` auf `true` geflippt; neue `--sqlite-named-sequences`-Option auf `schema migrate`. SQLite-Trigger-Reverse-Read ✅ (2026-05-22, Sub-Slices A–E: token-basierter `SqliteTriggerSqlParser` in A, SchemaReader-Routing + Löschen des Substring-Pfads in B, Round-Trip-Idempotenz in C, Live-DB-Integration in D, Closing in E; Plan-Doc `docs/planning/done-archive/ImpPlan-0.9.7-sqlite-trigger-reverse-read.md`). SQLite-Sequence-Emulation komplett abgeschlossen 2026-05-29 (Phasen A bis E, Plan-Doc `docs/planning/done-archive/sqlite-sequence-emulation-plan.md`): Phase A § 11 Pre-Code-Klärungen + [ADR-0003](../../adr/0003-cross-dialect-sequencing.md)-Anbindung; Phase B.0 `DdlDialectContext`-Refactor (`48c7f01c`) + B.1 `SqliteNamedSequenceMode` CLI-Plumbing (`84ba7ab7`) + B.2 Validator-Regeln (`E125`, `E059`) + B.3 helper_table-DDL + `_bi`/`_ai`-Trigger-Paar (`bce28e03`) + Review-2 Followup (`83124a3b`) + B.4 SequenceCapabilityDefaults-Flip (`89bd91b0`); Phase C Golden-Master + Runner + 9 Integration-Tests (`050f305a`); Phase D Reverse-Engineering mit Marker-Parser/Token-Body-Scanner/Pairing/W116/W120/W124 + 29 Unit + 3 Round-Trip-Integration (`92a5eb8b`); Phase E Compare + Stabilisierung + User-Doku. Carve-outs: W123 (Attached-DB-Rollback-Gate) bleibt plan-übergreifend offen; **atomare Probe + Restore unter Lock** Folge-Slice (`docs/planning/done-archive/sequence-preserve-atomic-lock-plan.md`) hat 2026-05-31 mit Phase A (Vertraege + Klassifier + Capability-Defaults: `SEQUENCE_PRESERVE_LOCK_TIMEOUT` + `SEQUENCE_PRESERVE_ATOMIC_UNSUPPORTED`-Codes, `supportsAtomicPreserve` / `supportsAtomicPreserveAllInPlan` / `transactionalProtectedSequenceOperations`-Felder mit per-Dialekt-Defaults; commit `6fa67e45`) und Phase B (B.1 `hexagon:ports-execute`-Modul + `AtomicSequencePreserveExecutor`-Port + Batch/Request/Result-Vertragstypen + `ProtectedOperationId`-Value-Class, commit `833d1796`; B.2 `PostgresAtomicSequencePreserveExecutor` mit `pg_advisory_xact_lock(hashtext(...))` nach Plan-Doc-Korrektur §4.1 — `LOCK TABLE` ist auf PG-Sequenzen nicht erlaubt, Risiko 8 dokumentiert die residuelle App-`nextval`-Race als bewusste Plan-Carve-out; commit `dc6d2ad6`; B.3 `MysqlAtomicSequencePreserveExecutor` mit `SELECT FOR UPDATE` auf `dmg_sequences`-Zeile + `SET SESSION innodb_lock_wait_timeout`, commit `24eb6e17`; B.4 `SqliteAtomicSequencePreserveExecutor` mit `BEGIN IMMEDIATE` + `PRAGMA busy_timeout` ueber raw-SQL-Transaktion bei `autoCommit=true`, commit `e882bcb1`) angefangen — alle drei Executoren mit je 5 Live-Container-/in-process-IT-Tests (Applied/Multi-Seq-Sort/NotFound/LockTimeout/Session-Timeout-Restore). Phase C ist abgeschlossen 2026-06-01 (Sub-Slices in Re-Cut-Reihenfolge C.2→C.4→C.3→C.1→C.5 + CI-Fix + Coverage-Puffer; Master ist auf dem Atomic-Runner-Pfad, 12/12 E2E-Live-IT pro Dialekt grün, `SequenceCurrentValueProbeRunner`-Dispatcher und `sequenceCurrentValueProbe`-Slot entfernt; Probe-Adapter-Implementierungen bleiben als toter Code für eigenen Dead-Code-Cleanup-Folge-Slice): `SequencePreserveStage` baut `AtomicSequencePreserveBatch` mit `renderRestore`-Closures + Capability-Gate (`SEQUENCE_PRESERVE_ATOMIC_UNSUPPORTED` bei kind-Allowlist-Miss); `ExecutableSegment`-Hierarchie in `:hexagon:ports-execute` mit `segmentForExecute(statements, atomicBatch)`-Projektion; `AtomicSequencePreserveDispatcher` + `AtomicSequencePreserveRunner` als CLI-Wiring-Slots; `SegmentAwareMigrationExecutor` als Execute-Runner mit Result-Mapping `Applied/NotFound/LockTimeout/Failed → ExecutionTrace`; Connection-Owner-Vertrag im Port-Companion (`requireOwnedConnection`, autoCommit=true required); `SchemaMigrateRequest.mysqlNamedSequences` analog zu `sqliteNamedSequences` plumbed; `AlterSequenceCurrentValue`-Follow-up bleibt als Audit-Marker mit `ATOMIC_PRESERVE_SENTINEL_CURRENT_VALUE = 0L` (no breaking core-API change); MySQL+SQLite Race-Reproducer auf Atomic-Pfad-positiv-Proof migriert; PG-Race-Test (Phase-D-Follow-up 2026-06-01) ebenfalls auf den `PostgresAtomicSequencePreserveExecutor` migriert und pinnt die residuelle App-`nextval`-Race als negativen Vertragstest (Risiko Nr. 8 — `pg_advisory_xact_lock` blockt App-`nextval` nicht). Phase D (2026-06-01) ergänzt drei Cross-Plan-Deadlock-Tests (`PostgresAtomicPreserveCrossPlanDeadlockTest`, `MysqlAtomicPreserveCrossPlanDeadlockTest`, `SqliteAtomicPreserveCrossPlanDeadlockTest` — PG/MySQL mit positivem Run + negativem Smoke via invertierter manueller Lock-Order; SQLite nur positiv, da DB-weite RESERVED-Lock Deadlock-Diamant konstruktiv unmöglich macht), flippt `supportsAtomicPreserveAllInPlan = true` pro Dialekt, und ergänzt den Stage-AllInPlan-Gate (`SEQUENCE_PRESERVE_ATOMIC_UNSUPPORTED` bei Multi-Seq-Plan + `false`-Flag; Unit-Test mit synthetischer Capability-Override). Phase E (2026-06-01) ist reine Docs-Sync: CHANGELOG-Entry für 0.9.7 (A+B+C-Sub-Phasen + Carve-Out-Sektion), User-Guide-Update („preserveCurrentValue atomar seit 0.9.7" inkl. PG-`nextval`-Race-Restrisiko + §3.2-Carve-Out-Liste), KDoc-Sync auf `SequencePreserveStage` (Restrictions-Block), `SequenceCapability` (C.4-Verweis korrigiert + neuer §3.2-Out-of-Scope-Block + Phase-D-AllInPlan-Update) und `SequenceCurrentValueProbe` (Status-Header: dead-code seit Phase C, Cleanup eigener Slice). Folge-Backlog in `docs/planning/done-archive/atomic-preserve-followups.md` (6 Code-Review-Findings + Dead-Code-Cleanup Probe-Adapter). | ✅ erledigt (2026-05-29); Folge-Slice atomic-preserve A+B+C+D+E komplett (2026-06-01); Followups in `in-progress/atomic-preserve-followups.md` |
| F.0-F.3 | Versionierte Plan-/Overlay-Vertraege, Reversibilitaets-Summaries, Rollback-v2-Header, Overlay-Secret-Diagnostik und `DataTransformationContract`-Default `NONE`; echte automatische Backfills bleiben Phase-1.x-Material | ✅ erste Slices erledigt |
| F.4 | Dependency-Re-Projection nach Rename ist vollstaendig (FK-Targets, View-/Trigger-/Index-/Default-Bindungen); View-/Trigger-/Routine-/Sequence-Renames sind ueber alle drei Dialekte umgesetzt | ✅ erledigt (2026-05-19) |
| F.4 G | Artefact-Producer-Wiring: `--plan-artefact <path>` emittiert signierte `migration-plan.v1`-JSON; `MigrationPlanArtifactBuilder` + Sink-Write + Runner-Emission live; `transactionScope`-Drift im Contract-Test gefixt; §E.3 DoD-Checkliste + §11 DoD Box (d) abgehakt. Plan-Doc: `docs/planning/done-archive/ImpPlan-0.9.7-F.4-G-artefact-producer-wiring.md` | ✅ erledigt (2026-05-19) |
| F.5 | CHECK-/EXCLUDE-Erstscheibe mit konservativem SQL-Textvergleich; unveraenderte Constraints blocken Tabellenops nicht mehr | ✅ erledigt (2026-05-19, Erstscheibe via Sub-Slice A) |
| F.5 Vollscheibe | Sub-Slices A–G der CHECK-/EXCLUDE-Vollscheibe abgeschlossen (Foundation + PG-/MySQL-/SQLite-Renderer + Daten-Preflight inkl. Probes + Stage + Pipeline + Report + CLI + Reversibility/Replace-Vertrag + Closing). PG rendert CHECK/EXCLUDE nativ und blockt nicht-whitelisted Operator-Klassen via `ExcludeOperatorClassGate`; MySQL gated CHECK ueber `MysqlCheckEnforcementCapability` + blockt EXCLUDE; SQLite faedelt CHECK durch Rebuild + blockt EXCLUDE. Live-Daten-Preflight liefert PASSED/FAILED/PROBE_RUNTIME_ERROR pro Op und der Renderer-Gate `CheckPreflightGate` setzt sie um. Replace-Paare (`DropConstraint + AddConstraint`) tragen eine gemeinsame `replacePairId` (Op-IDs bleiben eindeutig); Down-Pass surfaceiert `ROLLBACK_NOT_POSSIBLE` wenn die alte Expression fehlt. Plan-Doc: `docs/planning/done-archive/ImpPlan-0.9.7-F.5-check-exclude-vollscheibe.md`. | ✅ erledigt (2026-05-20) |
| Telemetry/Observability | Adaptervertrag, Gates und Port-Grenzen dokumentiert; produktives Metrics-/Tracing-Wiring ausserhalb 0.9.7 | ✅ Plan erledigt seit 2026-05-08 |
| Coverage/QA | MySQL-`AlterColumnNullability` ist als bewusster Blocker umgesetzt; Artifact-Compatibility (`UNKNOWN_FORMAT_VERSION` / `HASH_MISMATCH` / Secret-Scrubbing) + Overlay-Compatibility seit G-Slices vollstaendig gepinnt. **§11 DoD Box (a) Positiv+Blocker pro Workstream** ist ueber 22 Workstreams audit-sweep abgehakt (2026-05-19). **§11 DoD Box (b) Report-/Exit-Code-Erwartungen** ist mit Per-Exit-Code-Evidenz-Tabelle abgehakt (2026-05-19, alle sieben CLI-spec-Exit-Codes 0/2/3/4/5/7/8 pinned plus alle sieben primaryBlockedReason-Werte inkl. `OBJECT_RENAME_UNSUPPORTED` nach `PlannerBlockerClassifier`-Bridge). **§11 DoD Box (c) Rollback-Tests pro Workstream** ist mit Per-Workstream-Evidenz-Tabelle abgehakt (2026-05-19, 22 Workstreams; 15 Positiv-Down-Pfade + 5 NOT_REVERSIBLE-/ROLLBACK_NOT_POSSIBLE-Blocker-Pfade + 5 strukturelle Carve-outs wo der Blocker IST der Rollback-Vertrag, kein Rollback-Artefakt emittierbar). **§11 DoD ist damit komplett (a/b/c/d/e alle abgehakt).** **Quality-Coverage-Expansion** (Plan-Doc `docs/planning/done-archive/quality-coverage-expansion-plan.md`) komplett 2026-05-31: Phasen A + A-Vervollst + Review-Fixes (`af59567d`/`2e62370c`/`9c369d94`), B + B-Vervollst (`3545b646`/`3ae1bb20`), C + C-MCP (`a2195313`/`1bea5bed`), D N=100/1000 (`67d93ef8`) am 2026-05-30 gelandet; Phase E in vier Sub-Slices nachgezogen — E-Scaffold (`27db7cf4`), E.1 Disposition-Vertrag (`648beec6`), E.2 TBD-Promotion auf `adapter-coverage-uplift.md` (`68f917f9`), E.3 Aggregat-Asymmetrie geschlossen (`b3b7105f`), E.3-Review-Fixes (`8ceb2653`); F als Closure (`<dieser Commit>`). Geliefert: `PerfMeasure`/`PerfReport`-Lib in `hexagon:profiling` mit drei Phase-A-Hotpaths (SchemaMigrateRenderPipeline / DiffPlanner / RollbackArtefactBuilder-Roundtrip) + Bestands-Migration formats/streaming, `test/cross-dialect-matrix`-Sweep mit 7 gepinnten + 15+ permanenten Carve-outs (alle mit `ownerTests`-Verifikation), `test/integration-concurrency`-Race-Reproducer fuer PG/MySQL/SQLite (`knownRace=true` Legacy-Gate), `test:e2e-cli`-OperationalHarness gegen file-SQLite via `AiMcpRegistries.defaultComponents(AiMcpWiring(OperationalMcpWiring(...)))`-Override, `test/perf-large-schema`-Scales N=100/1000, Kover-Excludes-Ledger mit `Disposition`-Pflichtspalte (3-Wert-Vokabular + geschlossenes Token-Set fuer `permanent:` und `aggregate-carveout:`) und `make coverage-excludes-check` in `make docs-check`. D-N10k (N=10000 Nightly-Only) bleibt opt-in-Folge-Thema; `adapter-coverage-uplift` ist eigenes Folge-Plan-Doc (heute in `next/`). | ✅ erledigt (2026-05-31) |
| F.4 Renderer-Blocker-Bridge | `PlannerBlockerClassifier` mappt `DiffDiagnostic.code → MigrationBlockedReason`; PG/MySQL/SQLite-Renderer gruppieren planner-blockers per Reason; F.4-Mapper-Blocker surfacen jetzt als `primaryBlockedReason = OBJECT_RENAME_UNSUPPORTED` statt pauschal `DIALECT_UNSUPPORTED_OPERATION`. Plan-Doc: `docs/planning/done-archive/ImpPlan-0.9.7-F.4-renderer-blocker-bridge.md` | ✅ erledigt (2026-05-19) |

### Milestone 0.9.8 — Analytics- und Storage-Anschluss (Parquet Cut A + S3-Adapter + BI-Demo) ✅ (2026-06-14)

| Bereich | Aufgabe                                                                                                                                                                          | LF-Ref |
| ------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------ |
| Arch    | ✅ Parquet: JVM-Parquet-Bibliotheken gegen Lizenz, API und Streaming-Verhalten geprueft (→ produktiv in Cut A)                                                                                              | —      |
| Formats | ✅ Parquet: `ParquetChunkWriter` fuer `data export` mit Typmapping (Decimal, Temporal, Binary, UUID, JSON, Arrays, Geometry-Sidecar) — aus Prototyp produktiv in Cut A S3                             | —      |
| Test    | ✅ Parquet: gegen DuckDB lesen (`read_parquet`) und Typen inspizieren — produktiv als DuckDB-/Arrow-KV-Toleranztests (S9a/S9b)                                                                                               | —      |
| Formats | ✅ Parquet: Importpfad (`ParquetChunkReader`), chunk-weises Streaming und Schema-/Nullability-Erhalt — produktiv in Cut A S3/S7                                                             | —      |
| Arch    | ✅ Parquet: Manifest-Format fuer Multi-Table-Exports — produktiv: Bundle-Manifest (S3b) + Single-File-Footer-KV (S4)                                                                                | —      |
| Docs    | ✅ Parquet: Entscheidungsvorlage mit Aufwand, Risiken und empfohlenem Scope ([`parquet-decision-template.md`](../done-archive/parquet-decision-template.md))                                                                                                         | —      |
| Arch    | ✅ Object-Storage: Artefakt-/Checkpoint-Pfade inventarisiert — `ArtifactContentStore`/`UploadSegmentStore`/`ArtifactStore`-Ports + File-Impl existieren bereits aus 0.9.6 ([`object-storage-artifact-store.md`](../done-archive/object-storage-artifact-store.md) §3)                           | —      |
| Arch    | ✅ Object-Storage: S3-Client-Lib evaluiert + Config-/Security-Regeln skizziert + Job-Vertrag-Artifact-Ref-Migration geplant — Verdict **AWS SDK v2 + `url-connection-client`** ([`object-storage-s3-eval.md`](../done-archive/object-storage-s3-eval.md))         | —      |
| Formats | ✅ Object-Storage: S3-Adapter implementiert — Modul `adapters:driven:storage-s3` (`S3ArtifactContentStore` + `S3UploadSegmentStore`), AWS SDK v2 + `url-connection-client`, SeaweedFS-IT + MCP-E2E; `artifacts.store: s3` in der `.d-migrate.yaml`. **Abgeschlossen 2026-06-12** ([`ImpPlan-0.9.8-object-storage-s3.md`](../done-archive/ImpPlan-0.9.8-object-storage-s3.md))         | —      |
| Demo    | ✅ BI-Demo unter `examples/bi-demo/` mit Docker Compose: PostgreSQL + Metabase + SeaweedFS (S3-kompatibel) + optional `d-migrate`-CLI-Container, Beispiel-Schema, Seed-Daten           | —      |
| Demo    | ✅ BI-Demo: Smoke-Script (`examples/bi-demo/scripts/smoke.sh`) fuer Start, Healthcheck und minimale Demo-Kommandos (Reverse, Profiling, Transfer)                                                                         | —      |
| Refactor| ✅ (2026-06-02) Atomic-Preserve Service-Mode Sub-Slice A: `SchemaMigrateRunner.lockTimeoutMillis`-Konstruktor-Parameter + `SchemaMigrateRequest.lockTimeoutMillis`-Per-Request-Override + CLI-Flag `--lock-timeout-ms` mit Validation [10, 60_000] + Test-Decorator-Workaround (Finding #6) aus MySQL/SQLite-Atomic-Preserve-IT entfernt. Plan-Doc: [`../next/atomic-preserve-service-mode.md`](../next/atomic-preserve-service-mode.md) §5 A. | — |
| Refactor| ✅ (2026-06-02) Atomic-Preserve Service-Mode Sub-Slice E: `AtomicSequencePreserveExecutor.execute()` lernt optionalen `cancellationToken: CancellationToken`-Parameter (Default `none()`); neuer `AtomicSequencePreserveResult.Cancelled`-Sealed-Variant; drei Cancel-Checkpoints pro Dialekt-Adapter (pre-BEGIN / post-probe / post-protected) mit Rollback-Vertrag; Lambda-Plumbing durch `SegmentAwareExecutorFn` / `SegmentAwareMigrationExecutor` / `AtomicSequencePreserveRunner`; 6 IT-Cancel-Tests (PG/MySQL/SQLite × {pre-BEGIN, cancel-in-callback}); CLI-Pfad regressionsfrei via `CancellationToken.none()`. Plan-Doc: [`../next/atomic-preserve-service-mode.md`](../next/atomic-preserve-service-mode.md) §5 E. | — |
| Refactor| ✅ (2026-06-02) Atomic-Preserve Service-Mode E-Follow-up: CLI SIGINT/SIGTERM → `CancellationToken.cancel()`-Bridge in `SchemaMigrateWiring.executeInternal` analog `McpServerLifecycle`-Pattern. Ctrl-C während `schema migrate --execute` triggert jetzt sauberen Rollback an einem der drei Atomic-Preserve-Checkpoints + Operator-stderr-Breadcrumb statt hartem JVM-Kill. | — |
| Refactor| Atomic-Preserve Service-Mode Sub-Slice B (Idempotency-Hook): **Deferred — gefaltet in F**. Code-Audit 2026-06-02 ergab: CLI-Pfad hat keinen echten Replay-Wert (single-shot JVM); bestehender `IdempotencyStore` ist Job-Start-orientiert (`resultRef = Job-ID`, nicht `ExecutionTrace`). Wenn F gebaut wird, hängt der MCP-Handler den bestehenden Store direkt analog `data_transfer_start` ein. | — |
| Refactor| Atomic-Preserve Service-Mode Sub-Slice C (Connection-Sub-Pool) + D (Quota-Plumbing) + F (`schema_migrate`-Handler-Skeleton): warten auf externen Trigger (MCP-Migrate-Tool / gRPC 1.1.8 / REST 1.2.0). Pure Server-Mode-Infrastruktur ohne CLI-Nutzen. Plan-Doc: [`../next/atomic-preserve-service-mode.md`](../next/atomic-preserve-service-mode.md) §3.3. MCP-Tool-Plan 2026-06-03 nach `next/` promotet: [`../next/mcp-schema-migrate-tool.md`](../next/mcp-schema-migrate-tool.md) (Sub-Slices F.1-F.6, Wire-Vertrag V1). | — |

> **Ist-Stand 0.9.8 (2026-06-09):** Der Parquet-Track ist über die
> ursprüngliche Evaluierung hinausgewachsen — auf Basis der positiven
> Entscheidungsvorlage wurde Parquet **produktiv als „Cut A"** umgesetzt
> (Sub-Slices S0..S9b, alle closed auf `develop`): Reader-/Writer-Pfad,
> Bundle- und Single-File-Format, CLI-Wiring, Checkpoint/Resume und die
> Test-Familien sind vollständig. Der Parquet-Track-Closure
> (CHANGELOG-`[0.9.8]`-Eintrag + DoD §7) ist sein einziger offener Punkt —
> siehe [`parquet-productive-cut-a.md`](../done-archive/parquet-productive-cut-a.md).
> BI-Demo ist unter `examples/bi-demo/` geliefert; die Object-Storage-Eval
> (Reconciliation + Verdict AWS SDK v2) ist abgeschlossen
> ([`object-storage-s3-eval.md`](../done-archive/object-storage-s3-eval.md)).
> **Scope-Erweiterung 2026-06-09:** der S3-**Adapter** (Implementierung)
> wurde bewusst in 0.9.8 gezogen (vormals Phase 3/4). Damit ist 0.9.8
> **erst nach** dem S3-Bau + §8-Gate scope-complete; der Tag `v0.9.8`
> wartet entsprechend (nicht nur auf den Parquet-Closure).

**Ergebnis**: Drei verzahnte Anschluss-Tracks fuer Phase 4 vorbereitet.
(1) Parquet-Evaluierung liefert Prototyp, DuckDB-Kompatibilitaetsbeleg und
Entscheidungsvorlage fuer eine spaetere Vollumsetzung. Dies ist bewusst
keine Lakehouse-Implementierung — Iceberg/Delta/Hudi bleiben ausserhalb
des Scopes (siehe
[`parquet-export-import-evaluation.md`](../done-archive/parquet-export-import-evaluation.md) §3.2).
(2) Object-Storage-ArtifactStore-Plan erstellt einen minimalen
`ArtifactStore`-Port mit File-Referenz und S3-kompatibler Evaluierung,
plus die Migrationsskizze fuer MCP-/REST-/gRPC-Jobvertraege auf
Artifact-Refs (siehe
[`object-storage-artifact-store.md`](../done-archive/object-storage-artifact-store.md)).
(3) BI-Demo-Umgebung unter `examples/bi-demo/` zeigt `d-migrate` in einem
komponierbaren Analytics-Stack mit PostgreSQL, Metabase und SeaweedFS
(S3-kompatibel) als gemeinsamem Object-Storage-Endpunkt — als
reproduzierbares Beispiel,
nicht als Enterprise-BI-Plattform (siehe
[`bi-demo-compose.md`](../done-archive/bi-demo-compose.md)).

> Hinweis: Positive Parquet-Evaluierung fuehrt zu einem Folge-
> Implementierungsmilestone in Phase 4 (vermutlich neben 1.6.0 Metadata
> Catalog und Lakehouse Targets). Der Object-Storage-**S3-Adapter** wurde
> dagegen 2026-06-09 in 0.9.8 vorgezogen (Scope-Entscheidung); die
> BI-Demo-Plaene bleiben Grundlage fuer Showcase-Pfade ab Phase 3/4.

### Milestone 0.9.9 — Dokumentation, Pilot-Validierung und Blocker-Fixes ✅ (in Arbeit seit 2026-06-14; Doku abgeschlossen 2026-06-16; Pilot-Vorfilter 2026-06-16; alle Pilot-Blocker P1/P2/P3 behoben bis 2026-06-18; menschliche ≥5-Tester-Abnahme am 2026-07-08 erteilt — Milestone abgeschlossen)

> Status-Legende: ✅ erledigt · 🔮 geplant (späterer Milestone) · ⛔ ausstehend.

| Bereich | Aufgabe | LF-Ref | Status |
| ------- | ------- | ------ | ------ |
| Docs    | [Vollständiges Anwenderhandbuch](../../user/anwenderhandbuch.md) | — | ✅ |
| Docs    | [Administrationshandbuch](../../user/administrationshandbuch.md) | — | ✅ |
| Docs    | [Migrations-Leitfaden](../../user/migrations-leitfaden.md) | — | ✅ |
| Docs    | [API-Dokumentation](../../user/api-referenz.md) | — | ✅ |
| QA      | [Performance-Benchmarks dokumentiert](../../operations/performance-benchmarks.md) | — | ✅¹ |
| QA      | Pilotanwender-Tests (mindestens 5 Tester) | 9.2 | ✅² |
| QA      | [Pilot-Blocker beheben (P1/P2/P3)](../done-archive/pilot-validation-0.9.9.md) | — | ✅³ |
| QA      | Sample-DB-E2E-Harness (Phase 0–3 DoD; Phase 5 Spatial komplett) | — | ✅⁴ |

¹ Methodik + aktuelle Budgets dokumentiert. Die acceptance-grade Benchmarks
**LF 8.1** (1 Mio. Datensätze) und **LF 8.2** (1000 Tabellen < 30 s) waren zum
0.9.9-Stand noch 1.0.0-QA-Ziele; der Phase-4-Folge-Slice
([`tpc-performance-slice.md`](../done/tpc-performance-slice.md)) hatte seine
Decision-Blocker geklärt (Stand 2026-06-22): Workload-Sourcing in
[ADR 0017](../../adr/0017-tpc-benchmark-workload-sourcing.md) (accepted, DuckDB-`tpch`),
normierte Mess-Umgebung in [ADR 0018](../../adr/0018-normalized-perf-measurement-environment.md)
(accepted, Caps 2 CPU/4 GB + Acceptance-Tier + Kalibrierungs-Guard). **Beide
Benchmarks sind inzwischen gebaut und validiert** (TPC 4c/4d) — Status im
[Milestone 1.0.0](#milestone-100--stable-release).
² Der automatisierte Vorfilter ([Playbook](../../operations/pilot-validation-playbook.md))
lief am 2026-06-16 ([Report](../done-archive/pilot-validation-0.9.9.md), Verdikt **nicht RC-reif**);
alle daraus abgeleiteten Blocker wurden anschließend behoben (siehe ³). Die
menschliche ≥5-Tester-Abnahme ist am **2026-07-08 erteilt** — damit ist dieser
Punkt abgeschlossen.
³ Der Vorfilter fand 4 P1-Blocker (Transfer-Preflight-Strictness I-01, E009-
Temporal-Defaults I-02, MySQL-Enum-Lowercasing I-03, Enum-Transfer-Cast I-04)
plus 6 P2 (ungültige DDL). Entscheidung: in **0.9.9** beheben (nicht nach 1.0.0
verschoben). **Alle behoben & CI-grün:** P1 am 2026-06-16, P2 (I-05…I-10) am
2026-06-17; fünf Re-Validierungsläufe legten weitere P1/P2 frei (N1–N6, M2, M1,
K1, L1) — ebenfalls behoben. Die drei P3-Restbefunde (N7 Custom-Aggregate, N8
Index-Namen-Kollision, K2 Routinen-Ordering) sind am 2026-06-18 geliefert
([`pilot-rerun-p3-residuals.md`](../done/pilot-rerun-p3-residuals.md)); alle fünf
Pilot-Reports + der [P2-Tracker](../done-archive/pilot-blocker-p2-tracker.md)
liegen unter `../done-archive/`. **Kein P1/P2/P3-Cross-Dialect-Befund mehr offen.**

⁴ docker-compose-basierter E2E-Smoke-Harness (`examples/sample-db/`, [ADR 0014](../../adr/0014-sample-db-harness-fetch-and-compose.md));
ergänzende QA-Infrastruktur, **kein** RC-Gate-Kriterium. **Phase 0–3 DoD-komplett
(2026-06-21):** Pagila/PG-Round-Trip IDENTICAL (0 Diffs), Cross-Dialect Sakila
MySQL→PG + Pagila PG→MySQL, Chinook/SQLite-Round-Trip, Employees-Scale
(export-`--resume` + Chunking, Dual-Target). **Phase 5 Spatial KOMPLETT
(2026-06-22):** VA1 (Geometrie-Wert-Transfer als WKB), VA2 (SRID-Reverse + Daten-Bind
mit Ziel-SRID + Cross-Dialect-Achsenreihenfolge `axis-order=long-lat`), VA3 (räumliche
Indizes — MySQL `SPATIAL INDEX`, PostGIS `GiST`/`SP-GiST`), VA4 (SQLite/SpatiaLite inkl.
vollem `migrate --execute`-Round-Trip, ADR 0016), VA5 (Sample-Pins) und die Sub-Slices
**5a** (echtes PostGIS-nyc, EPSG:26918, via gepinntem gdal-Loader), **5b** (MySQL native),
**5c** (Cross-Dialect), **5d** (SpatiaLite) — alle live-verifiziert (`make
sample-db-spatial-smoke`, nyc opt-in `FETCH_NYC=1`). Begleitend gefixt: PG-Reverse
schließt Extension-Objekte aus (`pg_depend`). Slice-Docs:
[`sample-db-integration-harness.md`](../done/sample-db-integration-harness.md),
[`spatial-harness-slice.md`](../done/spatial-harness-slice.md).

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

> Status-Legende: ✅ erledigt · 🚧 teilweise (Grundlage vorhanden, Akzeptanz offen) · ⛔ ausstehend.
> Stand des Ist-Abgleichs: 2026-07-10 (RC ist der aktive Zyklus seit dem 0.9.9-Release).

| Bereich   | Aufgabe                                              | LF-Ref | Status |
| --------- | ---------------------------------------------------- | ------ | ------ |
| Streaming | Streaming-Pipeline Optimierung (kein OOM bei >10 TB) | [`LN-005`](../../../spec/lastenheft-d-migrate.md#ln-005) | ✅¹ |
| Streaming | Parallele Tabellenverarbeitung (`--parallel`, bounded ThreadPool) | [`LN-007`](../../../spec/lastenheft-d-migrate.md#ln-007) | ✅⁸ |
| Streaming | Partitionierte Tabellen: paralleler Export/Import    | [`LN-008`](../../../spec/lastenheft-d-migrate.md#ln-008) | ✅⁸ |
| Core      | SHA-256-Verifikation für Datenintegrität             | [`LN-009`](../../../spec/lastenheft-d-migrate.md#ln-009) | ✅² |
| Core      | Atomare Rollbacks auf Checkpoint-Ebene               | [`LN-013`](../../../spec/lastenheft-d-migrate.md#ln-013) | ✅⁷ |
| Security  | Verschlüsselte Credential-Speicherung (AES-256-GCM)  | [`LN-025`](../../../spec/lastenheft-d-migrate.md#ln-025) | ✅⁹ |
| Security  | Credential-Auflösung aus Quellen-Priorität (inkl. Store) | [`LN-049`](../../../spec/lastenheft-d-migrate.md#ln-049) | ✅¹⁰ |
| Security  | TLS/SSL für alle DB-Verbindungen                     | [`LN-026`](../../../spec/lastenheft-d-migrate.md#ln-026) | ✅³ |
| Security  | Audit-Logging aller Operationen                      | [`LN-027`](../../../spec/lastenheft-d-migrate.md#ln-027) | ✅⁴ |
| QA        | Property-Based Testing (kotest-property, [ADR 0029](../../adr/0029-property-based-testing-framework.md)) | [`LN-046`](../../../spec/lastenheft-d-migrate.md#ln-046) | ✅⁶ |
| QA        | Performance-Regression-Tests                         | [`LN-044`](../../../spec/lastenheft-d-migrate.md#ln-044) | ✅⁵ |

¹ Erledigt (2026-07-13): chunk-weises Pull-Streaming (`TableExporter`, `chunkSize=10_000`) +
Resume/Checkpoint sind ausgeliefert (Basis seit 0.3.0); das „>10 TB ohne OOM"-Akzeptanzkriterium
ist jetzt **validiert** durch einen heap-gedeckelten Datenpfad-Akzeptanztest (`test/perf-data-path`,
opt-in `make docker-perf`): ein synthetischer lazy `DataReader` treibt ~1 GiB durch den echten
Chunk-Loop + realen `CsvChunkWriter` unter `-Xmx 256m` (Volumen ~4× Heap) — eine „hält-alles"-
Regression OOMt, der bounded Pfad läuft durch. Zusätzlich gehärtet: konfigurierbarer `fetchSize`
([ADR 0033](../../adr/0033-konfigurierbarer-fetchsize-und-pipeline-tuning.md)), explizite
Parquet-Row-Group-Größe (R2) und ein Deckel auf die Import-`chunkFailures`-Detailliste (R4). Slice:
[`ln005-streaming-oom-hardening.md`](../done/ln005-streaming-oom-hardening.md).
² Erledigt (2026-07-12, [ADR 0030](../../adr/0030-datenwert-kanonisierung-verify.md),
ImpPlan [`ImpPlan-1.0.0-RC-ln009-sha256-verify.md`](../done/ImpPlan-1.0.0-RC-ln009-sha256-verify.md)):
nutzerseitiges `data transfer --verify` mit dialekt-neutraler, reihenfolge-
unabhängiger SHA-256-Quelle↔Ziel-Reconciliation je Tabelle (additive 256-bit-
Kombination mod 2²⁵⁶; `CanonicalValueCodec` je NeutralType inkl. JSON semantisch,
Array rekursiv, Geometry-WKB); Divergenz → Exit 3. Cross-dialect repräsentations-
transformierende Spalten (`text[]`→`json`, `tsvector`→`text`, tz→lokal) werden
familien-basiert mit W-Code ausgeschlossen (kein False-Positive), der Rest
byte-genau verglichen. Nicht-Ziel: `data export/import --verify`, inkrementeller/
Merge-Load. (Nicht mit dem QA-Punkt „1 Mio ohne Datenverlust" des
[Milestone 1.0.0](#milestone-100--stable-release) verwechseln — der belegt sich
über den Test-Harness, nicht über dieses Produktfeature.)
³ First-Class SSL erledigt (2026-07-11, ImpPlan
`docs/planning/done/ImpPlan-1.0.0-RC-ln026-ssl-first-class.md`, Minimal-Scope
typisiert + validiert): neutraler `SslMode` (`ConnectionConfig.ssl`), der
`ConnectionUrlParser` extrahiert + validiert PG `sslmode`/`sslrootcert` und MySQL
`sslMode`/`ssl` (ungültig → Fehler statt Passthrough), die JdbcUrlBuilder mappen
per-Dialekt korrekt über die neue `sslParams`-Naht. Offene Tiefenstufen (bewusst
Nicht-Scope): **Erzwingung** (require-SSL/fail-closed) und **Truststore/Keystore**
(inkl. MySQL-`VERIFY_*`-CA).
⁴ Erledigt (2026-07-11, ImpPlan `docs/planning/done/ImpPlan-1.0.0-RC-ln027-cli-audit.md`):
neben dem MCP-Dispatcher emittieren jetzt auch die CLI-DB-Operationen (`schema
reverse/migrate/compare[db]/rollback --execute`, `data export/import/transfer/profile`)
Audit-Events. Opt-in via `logging.audit.enabled: true` → JSONL nach
`logging.audit.file` (Default `.d-migrate/audit.log`); exit-code-getriebener
`CliAuditRecorder` (SUCCESS/FAILURE + `exitCode`), gescrubbte Refs, best-effort.
⁵ Geliefert via Perf-Acceptance-Infrastruktur: `.github/workflows/perf-acceptance.yml`
(Nightly) + `PerfMeasure`/`PerfReport`-Lib + Hotpath-PerfSpecs + `diff-planner`-
Kalibrier-Guard ([ADR 0018](../../adr/0018-normalized-perf-measurement-environment.md))
unter `PERF_GATE`. Offen bleibt nur der bewusste Provisional-Carve-Out (Runner-Pin
`PERF_RUNNER`/`CALIB_REFERENCE_MS`, Hart-Zeit-Gate scharf stellen).
⁶ kotest-property statt Jqwik ([ADR 0029](../../adr/0029-property-based-testing-framework.md)).
**Erledigt (2026-07-10, Phasen A–C):** A `ObjectKeyCodec`-Round-Trip; B geteilter
`Arb<NeutralType>` + `TypeMapper.toSql`-Totalität und Canonicalizer-Idempotenz
(PG/MySQL/SQLite); C das spec-genannte **Schema-Parsing** — `Arb<SchemaDefinition>`,
Parser-Robustheit (nie NPE), Fingerprint-Ordnungsunabhängigkeit + Metadaten-Ausschluss,
semantischer YAML-`write→read`-Round-Trip (PBT deckte einen degenerierten
Enum-Round-Trip-Fall auf, im Generator ausgeschlossen). `:check` durchweg grün. Slice:
[`property-based-testing-ln046.md`](../done/property-based-testing-ln046.md).

⁷ Erledigt (2026-07-12, [ADR 0031](../../adr/0031-atomic-clean-load-rollback.md),
ImpPlan [`ImpPlan-1.0.0-RC-ln013-atomic-clean-load.md`](../done/ImpPlan-1.0.0-RC-ln013-atomic-clean-load.md)):
`data import`/`data transfer --atomic` — Clean-Load-Kompensation. Bei einem Fehler
wird der vollständige Operations-Tabellensatz per `DataWriter.truncateTables` auf leer
zurückgesetzt („alle Tabellen oder keine"), O(1)-Metadaten-Kompensation statt
tx-/undo-log-skalierender Modelle (streaming-verträglich für >10 TB). `--atomic`
erfordert explizit `--truncate` (Exit 2) und ist inkompatibel mit `--resume`.
Nicht-Scope: Append-in-nicht-leeres-Ziel (Staging/Swap) und Kompensation bei Cancel.
⁸ Erledigt (2026-07-12, [ADR 0032](../../adr/0032-paralleler-datenpfad-tabellen-partitionen.md),
ImpPlan [`ImpPlan-1.0.0-RC-ln007-ln008-parallel-partition-data-path.md`](../done/ImpPlan-1.0.0-RC-ln007-ln008-parallel-partition-data-path.md)):
`data export`/`import`/`transfer --parallel N` verarbeitet unabhängige Tabellen — und die
Kind-Partitionen eines Parents — nebenläufig über einen begrenzten Worker-Pool (kein
`kotlinx-coroutines`; blockierendes JDBC → Thread-Pool), FK-sicher per Topo-Layer-Barriere.
Transfer/Import gruppieren FK-Ebenen; Export fächert einen partitionierten Parent in eine
Datei pro Kind. `--parallel 1` (Default) ist byte-identisch zum sequenziellen Pfad; SQLite
wird auf 1 geklemmt (Pool-Size 1); `--parallel > 1` ⊥ `--resume` (Exit 2). Nicht-Scope:
parallele `--resume`-Wiederaufnahme; Cross-Dialekt-Partitions-Fan-out (Fallback auf Parent).
⁹ Erledigt (2026-07-14, [ADR 0034](../../adr/0034-master-key-architektur-credential-store.md) „O2",
ImpPlan [`ImpPlan-1.0.0-RC-ln025-slice1-credential-store.md`](../done/ImpPlan-1.0.0-RC-ln025-slice1-credential-store.md)):
lokaler passphrase-verschlüsselter Store (`~/.d-migrate/credentials.enc`, AES-256-GCM,
PBKDF2-HMAC-SHA256/600k, Header als AAD, kein Key auf Platte) + `config credentials set`/`list`.
[`LN-025`](../../../spec/lastenheft-d-migrate.md#ln-025) fordert nur die verschlüsselte
**Speicherung** — die ist erfüllt. Das **Verwenden** gespeicherter Zugangsdaten beim
Verbindungsaufbau (Auflösung aus der Quellen-Priorität) ist eine eigene Anforderung
[`LN-049`](../../../spec/lastenheft-d-migrate.md#ln-049) (s. ¹⁰), nicht Teil dieser Zeile.
¹⁰ ✅ Erledigt (2026-07-15): Die Auflösungskette (connection-config-spec 4.1) ist auf dem CLI-Pfad
vollständig auflösbar — Stufe 1 (Inline-URL), Stufe 2 (`D_MIGRATE_DB_PASSWORD`, alle Ops), Stufe 3
(`${VAR}` = die „externe Secret-Referenz"), Stufe 4 (verschlüsselter Store — **alle 8 Ops** bei explizitem
Namen **plus** `data import` über `database.default_target`; prozess-weite Session = **ein** Master-Prompt)
und die interaktive Eingabe (über `config credentials set` → verschlüsselt abgelegt → konsumiert).
Maskierung durchgängig (Passwörter nie im Klartext). Verifiziert: Zwei-Agenten-Review (1 Major
profile-Env + 1 Minor Singleton-Wipe gefixt) + E2E-CLI gegen echte PG/MySQL (Testcontainers) grün. Der
**wörtliche connect-Zeit-Prompt** (spec-4.1 Stufe 5) ist bewusst **nicht** implementiert — er würde
passwortlose Auth (Postgres `peer`/`trust`/`.pgpass`) regressieren; die interaktive Eingabe erfolgt
stattdessen store-seitig. ImpPlan
[`ln049-credential-resolution`](../done/ImpPlan-1.0.0-RC-ln049-credential-resolution.md),
[ADR 0034](../../adr/0034-master-key-architektur-credential-store.md). (`credentialRef`/`providerRef` =
separater MCP-/Vault-Ausbau, keine der fünf Quellen der Anforderung.)

**Profiling-DataSketches** (aus `profiling-datasketches.md` ausgegliedert, ADR 0024):
gestaffelt — Phase 1 *Spike* (Ziel 0.9.9): HLL/CPC-Distinct-Count, KLL-Quantile,
Frequent Items, neuer `ProfilingRowStreamPort`, PostgreSQL-Adapter, Benchmark
approx. vs. exakt; Phase 2 *Produktives Modul* (Ziel 1.0.0-RC): stabiles Modul
`profiling-datasketches` mit CLI-Unterstützung.
> Status (Stand 2026-07-10): ⛔ **beide Phasen offen** — im Code existiert weder ein
> `profiling-datasketches`-Modul noch `ProfilingRowStreamPort`/Sketch-Klassen; der
> Phase-1-Spike (0.9.9-Ziel) ist nicht mehr im 0.9.9-Scope gelandet.

### Milestone 1.0.0 — Stable Release

> Status-Legende: ✅ erledigt · ⛔ ausstehend.

| Bereich   | Aufgabe                                                             | LF-Ref | Status |
| --------- | ------------------------------------------------------------------- | ------ | ------ |
| Build     | GraalVM Native Image (Linux, macOS, Windows)                        | —      | ⛔     |
| Build     | Docker Image auf Docker Hub                                         | —      | ⛔     |
| Build     | SDKMAN-Distribution                                                 | —      | ⛔     |
| Build     | Maven-Central-Portal Publish-Workflow für stabile Library-Artefakte | —      | ⛔     |
| Security  | Externer Security-Audit                                             | —      | ⛔     |
| QA        | 1 Mio. Datensätze Export/Import ohne Datenverlust                   | 8.1    | ✅¹    |
| QA        | DDL-Generierung 1.000 Tabellen < 30 Sekunden                        | 8.2    | ✅²    |
| QA        | Cross-DB Round-Trip: PostgreSQL → MySQL → SQLite                    | 8.6    | ⛔³    |
| Docs      | Best Practices Guide                                                | —      | ⛔     |
| Docs      | Troubleshooting-Guide                                               | —      | ⛔     |
| Community | Contributor Guide                                                   | —      | ⛔     |
| Community | Code of Conduct                                                     | —      | ⛔     |
| Community | Issue- und PR-Templates                                             | —      | ⛔     |

¹ Geliefert via TPC-Performance-Slice **4c** (Commit `9c4ffc3b`, Kalibrier-Guard
`1a8f609c`): datei-basierter Export→Import von **1,73 Mio. Zeilen** unter genormten
Caps (2 CPU/4 GB, [ADR 0018](../../adr/0018-normalized-perf-measurement-environment.md));
Verlustfreiheit **hart per kanonischem Inhalts-SHA-256** (roher Byte-Vergleich
untauglich, da `reverse` Spalten alphabetisiert). Slices:
[`tpc-performance-slice.md`](../done/tpc-performance-slice.md),
[`tpch-perf-result-artifact.md`](../done/tpch-perf-result-artifact.md).
² Geliefert via TPC-Slice **4d** (Commit `bcd2648e`): faithful
`ddl-1000-tables-ln004` generiert reine **1000 Tabellen in ~1,7 s ≪ 30 s**, hart
unter `PERF_GATE`. Slice: [`tpc-4d-ddl-1000-slice.md`](../done/tpc-4d-ddl-1000-slice.md).
³ Die **paarweisen** Cross-Dialect-Sprünge sind live + in CI (`sample-db-cross-smoke`
MySQL→PG, `sample-db-cross-smoke-pg2my` PG→MySQL, `sample-db-sqlite-smoke`
SQLite-Round-Trip). Die wörtliche **3-Hop-Kette** PG→MySQL→SQLite als *ein*
verketteter Test steht noch aus (alternativ Wortlaut auf „paarweise Cross-Dialect"
anpassen).

**Ergebnis**: Stabile Version 1.0.0 — produktionsreif, performant, sicher.

---

## Phase 4: Growth (1.1.0 - 2.0.0) — Monate 16-24

**Ziel**: Feature-Completeness und Ökosystem-Wachstum

### Milestone 1.1.0 — Trino-Federation (read-first)

| Bereich | Aufgabe                                                                                                                          | LF-Ref |
| ------- | -------------------------------------------------------------------------------------------------------------------------------- | ------ |
| Adapter | Neues Modul `adapters:driven:driver-trino`: `DatabaseDialect.TRINO` + Alias `trino`, URL `trino://user@host:port/catalog/schema` | —      |
| Adapter | Sealed `DialectConnectionContext` mit `TrinoConnectionContext` (Katalog, `httpScheme`, Session-Allowlist, Maskierung) — keine nullable `trino*`-Felder am generischen Port | —      |
| Read    | Read-only Pipelines: `schema reverse`, `schema compare` (read-only Ziel-Pfad), `data export`, `data transfer` (nur Source); Phase 1 ist write-frei | —      |
| Profil  | `adapters:driven:driver-trino-profiling` (fest verkabelt, Source-only) → `data profile --source trino://…`                       | —      |
| Test    | `test:integration-trino` Smoke gegen Testcontainers (Tranche 2; Vollausbau Phase 2)                                              | —      |

**Ergebnis**: d-migrate nutzt Trino als read-first Federation-Layer
(Reverse, Compare, Export, Profiling über heterogene Kataloge) — **nicht**
als OLTP-Migrationspfad. Schreibpfade, Transaktions-/MERGE-Semantik und
`schema generate` bleiben ausserhalb Phase 1 (spaetere Phasen). Details und
Tranchen-Schnitt: [`trino.md`](../next/trino.md).

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

**Einfuehrungsreihenfolge**: (1) Health + Schema-Validate/Generate + Job- und
Artifact-Service; (2) Reverse/Compare/Profile; (3) Export, Artefakt-Upload-RPCs,
Import, Transfer. (Aus `grpc-service.md` ausgegliedert, ADR 0024.)

### Milestone 1.2.0 — REST-API

| Bereich | Aufgabe                                                                                          | LF-Ref |
| ------- | ------------------------------------------------------------------------------------------------ | ------ |
| Server  | Synchrone Schema-Endpoints: Validate und Generate DDL mit Groessenlimit                          | [`LF-022`](../../../spec/lastenheft-d-migrate.md#lf-022) |
| Server  | Asynchrones Job-Modell: Reverse, Compare, Profile, Export mit 202-Acceptance und Location-Header | [`LF-022`](../../../spec/lastenheft-d-migrate.md#lf-022) |
| Server  | Idempotency: Alle Job-Start-Endpoints mit Idempotency-Key fuer Deduplizierung                    | [`LF-022`](../../../spec/lastenheft-d-migrate.md#lf-022) |
| Server  | Artefakt-Handling: Upload, Download, Register mit Multipart und Remote-Storage                   | [`LF-022`](../../../spec/lastenheft-d-migrate.md#lf-022) |
| Server  | Datenoperationen: Import, Export, Transfer, Profile als Jobs mit Filter-DSL (kein rohes SQL)     | [`LF-022`](../../../spec/lastenheft-d-migrate.md#lf-022) |
| Server  | Tool-Exports: Flyway, Liquibase, Django, Knex als async Jobs mit Artefakt-Ergebnis               | [`LF-022`](../../../spec/lastenheft-d-migrate.md#lf-022) |
| Test    | REST-API-Integrationstests und OpenAPI-Spec-Validierung                                          | [`LF-022`](../../../spec/lastenheft-d-migrate.md#lf-022) |

**Ergebnis**: d-migrate bietet eine vollstaendige REST-API fuer
programmatische Integration in CI/CD-Pipelines und Web-Frontends. Details:
[`rest-service.md`](../../../spec/rest-service.md).

**Einfuehrungsreihenfolge**: (1) Synchrone Basis: health, capabilities, schema
validate/generate; (2) Job-System inkl. SSE + reverse/compare/profile (Profiling
zuerst, da ohne Schreibzugriff auf die Ziel-DB); (3) Datenbewegung:
export/import/transfer + Artefakt-Download; (4) Integrationen: Tool-Exports,
KI-nahe Endpunkte nur bei echtem Bedarf. (Aus `rest-service.md` ausgegliedert, ADR 0024.)

### Milestone 1.3.0 — Testdaten-Generierung

| Bereich | Aufgabe                                                 | LF-Ref |
| ------- | ------------------------------------------------------- | ------ |
| Core    | Regelbasierte Testdaten-Generierung (Faker-Integration) | [`LF-024`](../../../spec/lastenheft-d-migrate.md#lf-024) |
| AI      | KI-gestützte Testdaten-Generierung (optional)           | [`LF-024`](../../../spec/lastenheft-d-migrate.md#lf-024) |
| Core    | Seed-basierte Reproduzierbarkeit                        | [`LF-024`](../../../spec/lastenheft-d-migrate.md#lf-024) |
| Core    | Mehrsprachige Testdaten (Namen, Adressen, etc.)         | [`LF-024`](../../../spec/lastenheft-d-migrate.md#lf-024) |
| CLI     | `d-migrate data seed` Kommando                          | [`LF-024`](../../../spec/lastenheft-d-migrate.md#lf-024) |

**Ergebnis**: Automatische Generierung realistischer Testdaten.

### Milestone 1.4.0 — Erweiterte Features

| Bereich | Aufgabe                                                  | LF-Ref         |
| ------- | -------------------------------------------------------- | -------------- |
| Core    | Datenmaskierung / Pseudonymisierung beim Export          | [`LF-021`](../../../spec/lastenheft-d-migrate.md#lf-021), [`LF-026`](../../../spec/lastenheft-d-migrate.md#lf-026) |
| Core    | Teil-Replikation (selektive Tabellen/Datensätze)         | [`LF-025`](../../../spec/lastenheft-d-migrate.md#lf-025)         |
| Core    | Automatische Dokumentationsgenerierung (ER-Diagramme)    | [`LF-016`](../../../spec/lastenheft-d-migrate.md#lf-016)         |
| Build   | Docker-Images mit vordefinierter DB-Struktur + Testdaten | [`LF-023`](../../../spec/lastenheft-d-migrate.md#lf-023)         |

### Milestone 1.5.0 — Oekosystem-Integrationen

| Bereich     | Aufgabe                                                                                         | LF-Ref |
| ----------- | ----------------------------------------------------------------------------------------------- | ------ |
| Profiling   | Profiling-Report-Exporter fuer Data-Quality-Tools wie Great Expectations, Soda und Pandera — siehe [`profiling-data-quality-export.md`](../next/profiling-data-quality-export.md) | —      |
| Integration | Orchestrator-Beispiele fuer Airflow, Dagster und Prefect dokumentieren und als Smoke-Pfade testen — siehe [`orchestrator-examples.md`](../next/orchestrator-examples.md) | —      |
| Demo        | BI-Demo-Umgebung unter `examples/bi-demo/` mit PostgreSQL, Metabase und d-migrate-Smoke-Pfad planen — siehe [`bi-demo-compose.md`](../done-archive/bi-demo-compose.md) | —      |

### Milestone 1.5.5 — KI-Integration

| Bereich | Aufgabe                                                                                                                   | LF-Ref         |
| ------- | ------------------------------------------------------------------------------------------------------------------------- | -------------- |
| AI      | AiProvider-Interface und Plugin-System                                                                                    | [`LF-017`](../../../spec/lastenheft-d-migrate.md#lf-017), [`LN-041`](../../../spec/lastenheft-d-migrate.md#ln-041) |
| AI      | OllamaProvider (lokale Modelle)                                                                                           | [`LF-017`](../../../spec/lastenheft-d-migrate.md#lf-017)         |
| AI      | OpenAiProvider (GPT-4)                                                                                                    | [`LF-017`](../../../spec/lastenheft-d-migrate.md#lf-017)         |
| AI      | AnthropicProvider (Claude)                                                                                                | [`LF-017`](../../../spec/lastenheft-d-migrate.md#lf-017)         |
| AI      | XaiProvider (Grok), GoogleProvider (Gemini)                                                                               | [`LF-017`](../../../spec/lastenheft-d-migrate.md#lf-017)         |
| AI      | RuleBasedProvider (Fallback ohne KI)                                                                                      | [`LN-035`](../../../spec/lastenheft-d-migrate.md#ln-035)         |
| AI      | Stored Procedure → Markdown-Zwischenformat                                                                                | [`LF-017`](../../../spec/lastenheft-d-migrate.md#lf-017)         |
| AI      | Markdown-Zwischenformat → Ziel-DB-Code                                                                                    | [`LF-017`](../../../spec/lastenheft-d-migrate.md#lf-017)         |
| AI      | SemanticAnalysisPort und strukturierte Profiling-Contracts fuer semantische Analyse                                       | —              |
| AI      | Semantische Profiling-Vorschlaege fuer Mapping, Transformation und Normalisierung auf Basis verdichteter Profil-Summaries | —              |
| AI      | Datenschutz: prefer_local, allow_external Flags                                                                           | [`LN-032`](../../../spec/lastenheft-d-migrate.md#ln-032), [`LN-033`](../../../spec/lastenheft-d-migrate.md#ln-033) |
| AI      | Caching häufiger Transformationen                                                                                         | [`LN-035`](../../../spec/lastenheft-d-migrate.md#ln-035)         |
| CLI     | `d-migrate transform procedure` Kommando                                                                                  | [`LF-017`](../../../spec/lastenheft-d-migrate.md#lf-017)         |
| CLI     | `d-migrate generate procedure` Kommando                                                                                   | [`LF-017`](../../../spec/lastenheft-d-migrate.md#lf-017)         |
| QA      | Semantische Äquivalenzprüfung (Testausführung)                                                                            | [`LN-034`](../../../spec/lastenheft-d-migrate.md#ln-034)         |

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
| Driver  | MS SQL Server-Treiber (JDBC, T-SQL-Support) | [`LF-019`](../../../spec/lastenheft-d-migrate.md#lf-019) |
| Test    | Cross-DB-Tests mit 4 Datenbanksystemen      | 8.7    |

**Ergebnis**: Unterstützung für MS SQL Server als viertes relationales
Datenbanksystem.

### Milestone 1.8.0 — Weitere Datenbanken (Oracle)

| Bereich | Aufgabe                                                  | LF-Ref |
| ------- | -------------------------------------------------------- | ------ |
| Driver  | Oracle-Treiber (JDBC, PL/SQL-Support)                    | [`LF-019`](../../../spec/lastenheft-d-migrate.md#lf-019) |
| Core    | Erweitertes Typsystem für Oracle/MSSQL-spezifische Typen | [`LF-019`](../../../spec/lastenheft-d-migrate.md#lf-019) |
| Test    | Cross-DB-Tests mit 5 Datenbanksystemen                   | 8.7    |

**Ergebnis**: Unterstützung für die 5 wichtigsten relationalen Datenbanken.

### Milestone 2.0.0 — Langfristige Vision

| Bereich   | Aufgabe                                        | LF-Ref |
| --------- | ---------------------------------------------- | ------ |
| GUI       | Grafische Benutzeroberfläche für Schema-Design | [`LF-018`](../../../spec/lastenheft-d-migrate.md#lf-018) |
| Core      | Schema-Optimierungsvorschläge                  | [`LF-020`](../../../spec/lastenheft-d-migrate.md#lf-020) |
| Core      | Rollenbasierte Zugriffskontrolle               | [`LN-028`](../../../spec/lastenheft-d-migrate.md#ln-028) |
| Community | LTS-Support für 1.x-Linie (24 Monate)          | —      |

**Ergebnis**: Feature-Complete, Enterprise-ready, aktive Community.

**Shadow-Migration** (Distributed CDC-Modus; aus `shadow-migration.md` ausgegliedert, ADR 0024):
gestaffelt — Phase 1 *Design* (Ziel 1.1): Contracts `ShadowMigrationJob`/`ShadowMigrationPlan`,
`ShadowExecutionBackendPort`, REST/gRPC/MCP-Entwurf, Readiness-Gates, Datenschutzregeln;
Phase 2 *Spike* (1.2): `FLINK_CDC_PIPELINE`-Adapter, Initial-Snapshot + CDC + Upsert-by-PK,
Lag-Metrik, Dead-Letter-Artefakt; Phase 3 *Experimental* (1.3): CLI
`shadow start/status/validate/readiness/stop`, REST-Job-API, Checkpoint/Resume,
Validierungsreports; Phase 4 *Stable* (2.0): stabiler Distributed-Modus, mehrere
DB-Kombinationen, Cutover-Readiness, Betriebs-/Failure-Recovery-Doku.

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

**Version**: 3.60
**Stand**: 2026-07-13 (**0.9.12 released** — Patch aus der 1.0.0-RC-Linie: paralleler Datenpfad [`LN-007`](../../../spec/lastenheft-d-migrate.md#ln-007)/[`LN-008`](../../../spec/lastenheft-d-migrate.md#ln-008) (`--parallel N`), atomarer Clean-Load [`LN-013`](../../../spec/lastenheft-d-migrate.md#ln-013) (`--atomic`) und Read-only-Quelle (`--read-only`); zuvor **0.9.11** mit [`LN-009`](../../../spec/lastenheft-d-migrate.md#ln-009) SHA-256-`--verify`, [`LN-026`](../../../spec/lastenheft-d-migrate.md#ln-026) First-Class SSL/TLS und [`LN-027`](../../../spec/lastenheft-d-migrate.md#ln-027) CLI-Audit-Logging; und 0.9.10-Patch SQLite-PK-NOT-NULL + Property-Based-Testing [`LN-046`](../../../spec/lastenheft-d-migrate.md#ln-046). Develop nach jedem Patch zurück auf `1.0.0-RC-SNAPSHOT`.)
**Status**: **0.9.11 (2026-07-12) und 0.9.12 (2026-07-13) sind als Patch-Releases aus der 1.0.0-RC-Linie veröffentlicht.** Milestone 0.1.0–0.9.7 abgeschlossen — 0.9.7 ist mit dem Release-Tag `v0.9.7` am 2026-06-02 veröffentlicht. **0.9.8 ist am 2026-06-14 als `v0.9.8` veröffentlicht** — produktiver Parquet „Cut A" (Sub-Slices S0..S9b closed), S3-kompatibler `ArtifactStore` (Verdict AWS SDK v2 + `url-connection-client`), BI-Demo unter `examples/bi-demo/`, plus die 0.9.8-Refactor-Slices (Atomic-Preserve Service-Mode A+E+SIGINT-Bridge, [`../next/atomic-preserve-service-mode.md`](../next/atomic-preserve-service-mode.md)); alle Closure-Plan-Docs in `docs/planning/done/` (Umbrella [`parquet-productive-cut-a.md`](../done-archive/parquet-productive-cut-a.md)). **0.9.9 ist am 2026-07-08 als `v0.9.9` veröffentlicht** — vollständige Beta-Dokumentation, menschliche ≥5-Tester-Pilot-Abnahme (LF 9.2) und alle P1/P2/P3-Cross-Dialect-Blocker aus fünf Pilot-Läufen behoben (strukturelle Transfer-Preflight, Array/`tsvector`-Bind, `CURRENT_DATE`-Defaults, View-Portabilität, Routinen-Emission, Post-Execute-Compare-Kanonisierung). **0.9.10 ist am 2026-07-11 als `v0.9.10` veröffentlicht** — Patch-Release aus der 1.0.0-Entwicklungslinie: SQLite-Round-Trip-Fix (PK-Spalten rendern jetzt `NOT NULL`, da SQLites `PRIMARY KEY` es — anders als PG/MySQL — nicht impliziert; m-trace-Consumer-Befund) plus Property-Based-Testing ([`LN-046`](../../../spec/lastenheft-d-migrate.md#ln-046), `kotest-property`, [ADR 0029](../../adr/0029-property-based-testing-framework.md)). Develop ist nach dem 0.9.10-Release auf `1.0.0-RC-SNAPSHOT` gebumpt; **1.0.0-RC ist jetzt der aktive Zyklus**. Inhalte 0.9.7: Refactoring/Hardening, Migrate A-E, erste PostgreSQL-Sequence-Abdeckung, konservative Extension-Install-Policy, Overlay-/Plan-Vertraege, CHECK-/EXCLUDE-Blocker, Telemetry-Plan-Gates, **D.3b Materialized-View-Vollscheibe (Sub-Slices A/B/C)**, **E.2 Trigger-Rendering-Vollscheibe (Sub-Slices A.1/A.2/A.3/B/C)**, **SQLite-Trigger-Reverse-Read (Sub-Slices A–E)** und **MySQL-Routine-Identity-Reverse-Read** sind umgesetzt; **Quality-Coverage-Expansion** komplett 2026-05-31 (Phasen A/B/C/D am 2026-05-30, E in vier Sub-Slices + Review-Fixes am 2026-05-31, F als Closure): `PerfMeasure`-Lib + 3 Hotpath-PerfSpecs + Bestands-Migration, Cross-Dialekt-Matrix-Sweep mit 7 gepinnten + permanenten Carve-outs (Phase F2 ergaenzt um `Kind.REPORT`/`ROLLBACK`/`FILE_MODE`), PG/MySQL/SQLite Sequence-Preserve-Race-Reproducer, Operational-MCP-Harness gegen file-SQLite mit `schema_compare_start` + MCP `resources/read` (Phase F1), Large-Schema-Scales N=100/1000 mit `HeapDumpOnOutOfMemoryError`-jvmArgs (Phase F5), Kover-Excludes-Ledger mit Disposition-Pflichtspalte + geschlossenem Token-Vokabular + fail-closed-Gradle-Scanner auf unbekannte Selectoren (Phase F4) + Formats-PerfTest-Migration auf `PerfMeasure`/`PerfReport` (Phase F3). D-N10k (N=10000 Nightly) bleibt opt-in-Folge-Thema. **Atomic-Preserve-Folge-Slice** zur 0.9.7-`preserveCurrentValue`-Serie ist 2026-06-01 mit Phasen A + B + C + D + E komplett geliefert: Probe + Restore + protected DDL in einer einzigen Transaktion unter Per-Dialekt-Lock (`pg_advisory_xact_lock` / `SELECT FOR UPDATE` / `BEGIN IMMEDIATE`), drei Cross-Plan-Deadlock-Tests pinnen die deterministische Lock-Reihenfolge, `supportsAtomicPreserveAllInPlan = true` pro Dialekt, Stage-AllInPlan-Gate, CHANGELOG + User-Guide + KDoc-Sync. Backlog-Tracker `docs/planning/done-archive/atomic-preserve-followups.md` mit allen 6 Code-Review-Findings + Dead-Code-Cleanup (Interface gelöscht, Adapter-Singletons live) ebenfalls abgehakt — wandert zusammen mit dem Plan-Doc zum 0.9.7-Release-Tag nach `done/`. Restpunkte siehe "Aktueller Arbeitsstand 0.9.7". Danach geplant: 0.9.8 (Parquet-Evaluierung + Object-Storage-Plan + BI-Demo), 0.9.9 (Doku/Pilot), 1.0.0-RC, 1.0.0; danach Phase 4 mit Trino-Federation (1.1.0), gRPC-API (1.1.8), REST-API (1.2.0), Testdaten (1.3.0), erweiterte Features (1.4.0), Oekosystem-Integrationen (1.5.0), KI-Integration (1.5.5), Metadata-Catalog (1.6.0), MS SQL Server (1.7.0), Oracle (1.8.0).
