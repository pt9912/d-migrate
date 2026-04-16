# Roadmap: d-migrate

**CLI-Tool für datenbankunabhängige Migrationen und Datenverwaltung**

---

## Übersicht

Diese Roadmap beschreibt den geplanten Entwicklungsverlauf von d-migrate in vier Phasen.
Sie basiert auf den Anforderungen des [Lastenhefts](./lastenheft-d-migrate.md), der
[Architektur](./architecture.md) und der [Neutrales-Modell-Spezifikation](./neutral-model-spec.md). Die Roadmap wird quartalsweise aktualisiert und ist
öffentlich auf GitHub Projects einsehbar.

```
Phase 1: MVP          Phase 2: Beta         Phase 3: Stable       Phase 4: Growth
0.1.0 - 0.5.5        0.6.0 - 0.9.5         1.0.0                 1.1.0 - 2.0.0
Monate 1-6            Monate 7-12           Monate 13-15          Monate 16-24
──────────────────────────────────────────────────────────────────────────────────▶
```

---

## Phase 1: MVP (0.1.0 - 0.5.5) — Monate 1-6

**Ziel**: Funktionaler Prototyp für Early Adopters

### Milestone 0.1.0 — Projekt-Grundgerüst ✅ (2026-04-05)

| Bereich | Aufgabe                                                 | LF-Ref | Status |
| ------- | ------------------------------------------------------- | ------ | ------ |
| Build   | Gradle Multi-Module Setup (core, cli, formats)          | —      | ✅ |
| Build   | CI/CD-Pipeline mit GitHub Actions (Build, Test, Kover)  | —      | ✅ |
| Core    | Neutrales Schema-Modell (Kotlin Data Classes)           | LF-001 | ✅ |
| Core    | YAML-Parser für Schema-Definitionen                     | LF-001 | ✅ |
| Core    | Basis-Validierung (E001-E018, W001)                     | LF-002 | ✅ |
| CLI     | Clikt-basiertes CLI-Grundgerüst                         | LF-012 | ✅ |
| CLI     | `d-migrate schema validate` Kommando                    | LF-002 | ✅ |
| Test    | Kotest Setup, 83 Tests, Kover Coverage >= 90%           | LN-043 | ✅ |

**Ergebnis**: Schema-Dateien können geladen und validiert werden.

### Milestone 0.2.0 — DDL-Generierung ✅ (2026-04-06)

| Bereich | Aufgabe                                                              | LF-Ref | Status |
| ------- | -------------------------------------------------------------------- | ------ | ------ |
| Core    | TypeMapper-Interface und Basislogik                                  | LF-003 | ✅ |
| Core    | AbstractDdlGenerator mit topologischer Sortierung                    | LF-003 | ✅ |
| Core    | ViewQueryTransformer (17 SQL-Funktionen)                             | LF-003 | ✅ |
| Driver  | PostgreSQL-Treiber: DDL-Generierung                                  | LF-003 | ✅ |
| Driver  | MySQL-Treiber: DDL-Generierung                                       | LF-003 | ✅ |
| Driver  | SQLite-Treiber: DDL-Generierung                                      | LF-003 | ✅ |
| CLI     | `d-migrate schema generate --target <db>`                            | LF-003 | ✅ |
| CLI     | `--output`, `--generate-rollback`, `--report` Flags                  | LF-003 | ✅ |
| CLI     | TransformationReportWriter (YAML-Sidecar)                            | LF-003 | ✅ |
| Test    | TypeMapper-Tests (100% Coverage, 3 Dialekte)                         | LN-045 | ✅ |
| Test    | DdlGenerator Unit-Tests (44+53+57 Tests)                             | LN-043 | ✅ |
| Test    | Golden-Master-Tests (4 Schemas × 3 Dialekte = 12)                    | LN-043 | ✅ |
| Test    | 374 Tests gesamt, Coverage >= 90%                                    | LN-045 | ✅ |

**Ergebnis**: Aus YAML-Schemas wird datenbankspezifisches DDL generiert.

### Milestone 0.3.0 — Datenexport ✅ (2026-04-06)

| Bereich | Aufgabe                                                              | LF-Ref | Status |
| ------- | -------------------------------------------------------------------- | ------ | ------ |
| Core    | Streaming-Pipeline (pull-basiert, chunk-weise)                       | LN-005 | ✅ |
| Driver  | JDBC-basierter DataReader (ResultSet-Streaming, PG/MySQL/SQLite)     | LF-008 | ✅ |
| Driver  | Connection-Management mit HikariCP + ConnectionUrlParser             | —      | ✅ |
| Formats | JSON-Codec (DSL-JSON statt Jackson — Performance)                    | LF-009 | ✅ |
| Formats | YAML-Codec (SnakeYAML Engine)                                        | LF-009 | ✅ |
| Formats | CSV-Codec (uniVocity-parsers, mit Encoding/BOM/Delimiter)            | LF-009 | ✅ |
| CLI     | `d-migrate data export` Kommando                                     | LF-008 | ✅ |
| CLI     | `NamedConnectionResolver` (`.d-migrate.yaml`, `${ENV_VAR}`)          | —      | ✅ |
| Test    | Export-Tests gegen Testcontainers (PostgreSQL 16, MySQL 8.0)         | LN-048 | ✅ |

**Ergebnis**: Daten können aus PostgreSQL, MySQL und SQLite streamingbasiert
in JSON, YAML oder CSV exportiert werden — chunk-weise, ohne die Tabelle in
den Speicher zu laden. CLI mit Named Connections, Roh-WHERE-Filter,
`--split-files`-Multi-Tabellen-Export, vollständige Exit-Code-Matrix
(0/2/4/5/7) und §6.17 Empty-Table-Vertrag pro Format.

### Milestone 0.4.0 — Datenimport und inkrementelle Datenpfade ✅ (2026-04-12)

| Bereich | Aufgabe                                                                                           | LF-Ref |
| ------- | ------------------------------------------------------------------------------------------------- | ------ |
| Core    | Transaktionale Import-Logik (Chunk-basiert)                                                       | LN-010 |
| Driver  | JDBC-basierter DataWriter (Batch-Insert)                                                          | LF-010 |
| Core    | Sequence-/Identity- und AUTO_INCREMENT-Konsistenz nach Import                                     | LF-010 |
| Driver  | Dialektspezifisches Trigger-Handling beim Import                                                  | LF-010 |
| Formats | Deserialisierung aus JSON/YAML/CSV (Streaming-Reader)                                             | LF-010 |
| Core    | Validierung gegen Schema-Definition beim Import (Target-Schema autoritativ)                       | LF-010 |
| Core    | Encoding-Unterstützung: BOM-Detection für UTF-8/UTF-16, alle anderen via `--encoding`-Flag        | LF-010 |
| CLI     | `d-migrate data import` Kommando                                                                  | LF-010 |
| Core    | Inkrementeller **Export** über explizite Marker-Spalte (`--since-column`, `--since`)              | LF-013 |
| CLI     | `--incremental` Flag für `data export`; idempotenter Import via `--on-conflict update`            | LF-013 |
| Test    | Round-Trip-Tests (Export → Import → Vergleich)                                                    | LN-043 |
| Test    | Import-Tests für Sequence-Reseeding und Trigger-Verhalten                                         | LN-043 |
| Test    | Inkrement-Round-Trip-Tests (initial export → delta export → idempotenter Import → Vergleich)      | LN-043 |

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

| Bereich | Aufgabe                                       | LF-Ref |
| ------- | --------------------------------------------- | ------ |
| Core    | Schema-Diff-Engine (Vergleich zweier Schemas, file-based MVP) | LF-015 |
| CLI     | `d-migrate schema compare` Kommando (Datei-zu-Datei, MVP-Slice) | LF-015 |
| CLI     | Fortschrittsanzeige für lange Operationen     | LN-017 |
| Docs    | Anwenderhandbuch (Basis)                      | —      |
| Docs    | CLI-Referenz                                  | —      |
| Build   | GitHub Releases (Fat JAR)                     | —      |
| Build   | Homebrew-Formula (Basis)                      | —      |
| QA      | Code-Coverage >= 80%                          | LN-045 |

**Ergebnis**: Öffentliches MVP-Release. Early Adopters können Schema-Verwaltung
und Daten-Export/Import nutzen. LF-015 ist in 0.5.0 bewusst nur teilweise
abgedeckt: `schema compare` vergleicht zwei neutrale Schema-Dateien; der volle
Vergleich zwischen Umgebungen bzw. Datenbanken folgt in 0.6.0.

Detaillierter Plan: [implementation-plan-0.5.0.md](./implementation-plan-0.5.0.md)

### Milestone 0.5.5 — Erweitertes Typsystem ✅ (2026-04-13)

| Bereich | Aufgabe                                                                             | LF-Ref |
| ------- | ----------------------------------------------------------------------------------- | ------ |
| Core    | Neutraler Typ `geometry` mit `geometry_type` und `srid`                             | LF-003 |
| Core    | Spatial-Profil als Generator-Option (`postgis`, `native`, `spatialite`, `none`)     | LF-003 |
| Core    | Validierungsregeln und Hinweis-Codes `E120`, `E121`, `W120`, `E052`                 | LF-002 |
| Driver  | PostgreSQL/PostGIS: `geometry(<type>, <srid>)`-Mapping                              | LF-003 |
| Driver  | MySQL: native Spatial Types (`POINT`, `POLYGON`, `MULTIPOLYGON`, ...)               | LF-003 |
| Driver  | SQLite/SpatiaLite: `AddGeometryColumn(...)`-Strategie                               | LF-003 |
| Core    | Erweiterte Typen im neutralen Modell: JSON, Arrays, Binary, UUID                    | LF-001 |
| CLI     | `--spatial-profile` Option für `schema generate`                                    | LF-003 |
| Test    | Golden-Master-Tests: `spatial.{postgresql,mysql,sqlite}.sql`                        | LN-043 |
| Test    | Negativtests für ungültige `geometry_type`/`srid` und Profil-Mismatches             | LN-043 |
| Docs    | CR `docs/change-request-spatial-types.md` in den Status _Approved_ überführen       | —      |
| Docs    | `docs/neutral-model-spec.md` und `docs/ddl-generation-rules.md` um Spatial erweitern | —      |

**Ergebnis**: Das neutrale Typsystem ist vor Reverse-Engineering (0.6.0) vollständig. Spatial-Daten können ohne Datenverlust zwischen PostgreSQL/PostGIS, MySQL und SQLite/SpatiaLite transportiert werden; JSON/Arrays/Binary/UUID sind modelliert und getestet.

Dieser Milestone basiert auf dem [Change Request Spatial Types](./change-request-spatial-types.md) (Phase 1) und zieht die vormals für 0.8.0 geplanten erweiterten Typen vor, damit `schema reverse` ab 0.6.0 von Anfang an gegen ein vollständiges Typsystem arbeiten kann.

---

## Phase 2: Beta (0.6.0 - 0.9.5) — Monate 7-12

**Ziel**: Production-ready für erste Projekte

### Milestone 0.6.0 — Reverse-Engineering ✅ (2026-04-14)

| Bereich | Aufgabe                                                         | LF-Ref |
| ------- | --------------------------------------------------------------- | ------ |
| Driver  | SchemaReader: Tabellen, Spalten, Constraints aus DB extrahieren | LF-004 |
| Driver  | SchemaReader: Indizes, Sequences, Custom Types                  | LF-004 |
| Driver  | SchemaReader: Stored Procedures, Functions, Triggers, Views     | LF-004 |
| Driver  | PostgreSQL-spezifisch: Extensions, JSONB, Array-Typen           | LF-004 |
| Driver  | MySQL-spezifisch: Engine, AUTO_INCREMENT, SET-Typen             | LF-004 |
| Driver  | SQLite-spezifisch: WITHOUT ROWID, Virtual Tables                | LF-004 |
| CLI     | `d-migrate schema reverse --source <db-url>`                    | LF-004 |
| CLI     | `schema compare` gegen Umgebungen/DBs auf Basis von `SchemaReader` vervollständigen | LF-015 |
| Core    | `StreamingTransfer`-Orchestrator: DB-zu-DB-Streaming ohne Zwischenformat | LF-025 |
| CLI     | `d-migrate data transfer --source <url> --target <url>` Kommando | LF-025 |
| Docs    | Beispiel-Projekte (E-Commerce-Schema)                           | —      |
| Test    | Reverse-Engineering gegen komplexe Test-Schemas                 | 8.4    |

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

| Bereich     | Aufgabe                                                   | LF-Ref | Status |
| ----------- | --------------------------------------------------------- | ------ | ------ |
| Core        | Phase A: DDL-Header-Determinismus, Adapter-Grundlagen     | LF-011 | ✅ |
| Core        | Phase B: Migrations-Bundle, Identitaet, Exportvertrag     | LF-011 | ✅ |
| Integration | Phase C: Tool-Adapter (Flyway, Liquibase, Django, Knex)   | LF-011 | ✅ |
| CLI         | Phase D: CLI- und Runner-Pfad fuer Tool-Export            | LF-011 | ✅ |
| Test        | Phase E: Runtime-Validierung gegen echte Tool-Projekte    | LF-011 | ✅ |
| Docs        | Phase F: Release-Dokumentation und Smoke-Tests            | LF-011 | ✅ |
| Core        | Migrations-Rollback-Generierung                           | LF-014 | ✅ |
| Test        | Generierte Migrations-Skripte ausführen und validieren    | 8.6    | ✅ |

**Ergebnis**: d-migrate integriert sich in bestehende Migrations-Toolchains.

### Milestone 0.7.5 — Daten-Profiling ✅ (2026-04-15)

| Bereich   | Aufgabe                                                                        | LF-Ref | Status |
| --------- | ------------------------------------------------------------------------------ | ------ | ------ |
| Profiling | Phase A: Modulgeruest und Scope                                                | —      | ✅ |
| Profiling | Phase B: Domaenenmodell, Typen und Rule-Engine                                 | —      | ✅ |
| Profiling | Phase C: Port-Vertraege und dialektspezifische Adapter                         | LF-004 | ✅ |
| Profiling | Phase D: Services und Runner                                                   | —      | ✅ |
| CLI       | Phase E: `d-migrate data profile` Kommando                                     | —      | ✅ |
| Docs      | Phase F: Doku, Smokes und Release-Pfade                                        | —      | ✅ |
| Test      | Unit-Tests (Rules, Services), Integration (SQLite, Testcontainers), E2E        | LN-043 | ✅ |

**Ergebnis**: Bestehende Datenbanken können vor einer Migration profiliert werden.
Spaltenweise Kennzahlen, Qualitätswarnungen und Zieltyp-Kompatibilität als
JSON/YAML-Report. Design: [profiling.md](./profiling.md).

> Hinweis: Die in [profiling.md §10](./profiling.md#10-semantische-analyse-llm-erweiterung-spätere-phase)
> beschriebene semantische Analyse ist bewusst **nicht** Teil von 0.7.5.
> Zuerst wird das deterministische Kern-Profiling stabilisiert; die opt-in
> LLM-Erweiterung folgt später auf Basis der allgemeinen KI-Provider-
> Infrastruktur in [1.1.0](#milestone-110--ki-integration).

### Milestone 0.8.0 — Internationalisierung ✅ (2026-04-16)

| Bereich | Aufgabe                                                          | LF-Ref | Status |
| ------- | ---------------------------------------------------------------- | ------ | ------ |
| Docs    | Phase A: Spezifikationsbereinigung und Scope-Fixierung           | —      | ✅ |
| i18n    | Phase B: I18n-Runtime und Config-Resolution                      | LF-006 | ✅ |
| i18n    | Phase C: ResourceBundles und lokalisierte CLI-Ausgaben            | LN-023 | ✅ |
| i18n    | Phase D: ICU4J-Integration für Unicode-Verarbeitung              | LF-005 | ✅ |
| Core    | Phase E: Zeitzonen- und Format-Policy (TemporalFormatPolicy)     | LF-007 | ✅ |
| Formats | Phase F: CSV-Encoding-/BOM-Konsolidierung                        | LF-010 | ✅ |
| Test    | Phase G: Tests und Dokumentation (inkl. Unicode-Integrität)      | 8.5    | ✅ |

**Ergebnis**: Vollständige Unicode-/i18n-Unterstützung und konsistentes Zeitzonen-Handling. (Die erweiterten neutralen Typen JSON/Arrays/Binary/UUID sowie Spatial wurden in [0.5.5](#milestone-055--erweitertes-typsystem) vorgezogen.)

> Stand 2026-04-16: Phasen A–G umgesetzt — Spezifikationsbereinigung,
> I18n-Runtime, ResourceBundles, ICU4J-Integration, Zeitzonen-/Format-
> Policy, CSV-Encoding-/BOM-Konsolidierung sowie Tests und
> Dokumentation inklusive Mindest-Testmatrix, `--lang`-Grenze 0.8.0/0.9.0
> und DE-Bundle-Fallback-Nachweis. Milestone 0.8.0 damit abgeschlossen.

### Milestone 0.9.0 — Beta: Resilienz und vollständige i18n-CLI (in Arbeit)

| Bereich | Aufgabe                                                                | LF-Ref |
| ------- | ---------------------------------------------------------------------- | ------ |
| Core    | Checkpoint/Resume für langläufige Operationen                          | LN-012 |
| CLI     | `--lang` Flag für Sprachauswahl (greift auf 0.8.0-ResourceBundles zu)  | LF-006 |

**Zielbild nach Abschluss von 0.9.0**: Langläufige Export-/Import-
Operationen sind unterbrechbar und können wieder aufgesetzt werden; die in
0.8.0 eingeführte i18n-Infrastruktur ist über `--lang` vom Nutzer steuerbar.
Damit ist die Kern-Funktionalität für Beta-Tester abgeschlossen.

> **Ist-Stand (2026-04-16)**: Phase A ist abgeschlossen — der sichtbare
> CLI-Vertrag ist fixiert (`--lang` aktiv, `--resume` und `--checkpoint-dir`
> deklariert, stdout/stdin-Preflight mit Exit 2,
> `docs/ImpPlan-0.9.0-A.md` §4.5). Die eigentliche Resume-Runtime
> (Checkpoint-Port, Manifest-Persistenz, Streaming-Wiederaufnahme) folgt in
> den Phasen B bis D. Solange die Runtime nicht steht, gibt der Runner bei
> `--resume` eine sichtbare Warnung aus und startet den Lauf von vorn.

> Hinweis: `--resume` ist in 0.9.0 bewusst **dateibasiert** zugeschnitten —
> stdout-Export und stdin-Import sind nicht Teil des Resume-Vertrags und
> enden mit Exit 2, wenn `--resume` kombiniert wird
> (`docs/ImpPlan-0.9.0-A.md` §4.4). `data transfer` erhaelt in 0.9.0
> kein Resume; ein entsprechender Scope wird spaeter bewertet.

> Hinweis: LF-013 (Inkrementeller Export/Import) wurde nach 0.4.0 vorverlegt
> — siehe Begründung dort. Docs und Pilot-QA sind in **0.9.5** ausgelagert,
> damit dieser Milestone klein und fokussiert bleibt.

### Milestone 0.9.1 — Beta: Library-Refactor und Integrationsschnitt

| Bereich | Aufgabe                                                                 | LF-Ref |
| ------- | ----------------------------------------------------------------------- | ------ |
| Core    | Read-/Write-Schnitt in `hexagon:ports` für externe Consumer schärfen    | —      |
| Driver  | Profiling-Adapter aus JDBC-Treiber-Kernmodulen extrahieren              | —      |
| Core    | FK-/Topo-Sort-Utility als wiederverwendbaren Helfer nach `hexagon:core` | —      |
| Arch    | Integrationsgrenzen für externe Library-Consumer schärfen               | —      |

**Ergebnis**: `d-migrate` ist intern so refaktoriert, dass die Kernmodule
sauberer als Libraries konsumierbar sind, ohne schon einen öffentlichen
Publish-Vertrag zu versprechen.

> Hinweis: Dieser Milestone bereitet wiederverwendbare Libraries für externe
> Consumer wie `d-browser` vor. Ein öffentlicher Publish-Vertrag inklusive
> Maven-Central-Portal-Workflow bleibt bewusst **1.0.0** vorbehalten, damit
> Modulgrenzen, Koordinaten und API-Flächen erst nach dem Refactor
> stabilisiert werden.

### Milestone 0.9.5 — Beta-Dokumentation und Pilot-Validierung

| Bereich | Aufgabe                                          | LF-Ref |
| ------- | ------------------------------------------------ | ------ |
| Docs    | Vollständiges Anwenderhandbuch                   | —      |
| Docs    | Administrationshandbuch                          | —      |
| Docs    | Migrations-Leitfaden                             | —      |
| Docs    | API-Dokumentation                                | —      |
| QA      | Performance-Benchmarks dokumentiert              | —      |
| QA      | Pilotanwender-Tests (mindestens 5 Tester)        | 9.2    |

**Ergebnis**: Die Beta-Dokumentation ist vollständig und Pilotanwender haben
das System gegen reale Datenbestände getestet. Bereit für den 1.0.0-RC-Cut.

> **Warum ein eigener Milestone für Docs und QA?** Dokumentations- und
> Pilot-Tasks haben eine deutlich andere Cadence als Code-Features (sie
> brauchen Reviews durch Domain-Experten und externe Tester). Wenn sie im
> 0.9.0-Code-Milestone hängen, verschiebt sich der Beta-Cut auf das
> langsamste Element. 0.9.5 entkoppelt das, ohne die Inhalte zu streichen.

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

| Bereich   | Aufgabe                                           | LF-Ref |
| --------- | ------------------------------------------------- | ------ |
| Build     | GraalVM Native Image (Linux, macOS, Windows)      | —      |
| Build     | Docker Image auf Docker Hub                       | —      |
| Build     | SDKMAN-Distribution                               | —      |
| Build     | Maven-Central-Portal Publish-Workflow für stabile Library-Artefakte | — |
| Security  | Externer Security-Audit                           | —      |
| QA        | 1 Mio. Datensätze Export/Import ohne Datenverlust | 8.1    |
| QA        | DDL-Generierung 1.000 Tabellen < 30 Sekunden      | 8.2    |
| QA        | Cross-DB Round-Trip: PostgreSQL → MySQL → SQLite  | 8.6    |
| Docs      | Best Practices Guide                              | —      |
| Docs      | Troubleshooting-Guide                             | —      |
| Community | Contributor Guide                                 | —      |
| Community | Code of Conduct                                   | —      |
| Community | Issue- und PR-Templates                           | —      |

**Ergebnis**: Stabile Version 1.0.0 — produktionsreif, performant, sicher.

---

## Phase 4: Growth (1.1.0 - 2.0.0) — Monate 16-24

**Ziel**: Feature-Completeness und Ökosystem-Wachstum

### Milestone 1.1.0 — KI-Integration

| Bereich | Aufgabe                                         | LF-Ref         |
| ------- | ----------------------------------------------- | -------------- |
| AI      | AiProvider-Interface und Plugin-System          | LF-017, LN-041 |
| AI      | OllamaProvider (lokale Modelle)                 | LF-017         |
| AI      | OpenAiProvider (GPT-4)                          | LF-017         |
| AI      | AnthropicProvider (Claude)                      | LF-017         |
| AI      | XaiProvider (Grok), GoogleProvider (Gemini)     | LF-017         |
| AI      | RuleBasedProvider (Fallback ohne KI)            | LN-035         |
| AI      | Stored Procedure → Markdown-Zwischenformat      | LF-017         |
| AI      | Markdown-Zwischenformat → Ziel-DB-Code          | LF-017         |
| AI      | SemanticAnalysisPort und strukturierte Profiling-Contracts fuer semantische Analyse | —              |
| AI      | Semantische Profiling-Vorschlaege fuer Mapping, Transformation und Normalisierung auf Basis verdichteter Profil-Summaries | —              |
| AI      | Datenschutz: prefer_local, allow_external Flags | LN-032, LN-033 |
| AI      | Caching häufiger Transformationen               | LN-035         |
| CLI     | `d-migrate transform procedure` Kommando        | LF-017         |
| CLI     | `d-migrate generate procedure` Kommando         | LF-017         |
| QA      | Semantische Äquivalenzprüfung (Testausführung)  | LN-034         |

**Ergebnis**: KI-gestützte Migration von Stored Procedures zwischen Datenbanken.
Zusätzlich wird die gemeinsame KI-Provider-Infrastruktur für opt-in
Profiling-Erweiterungen genutzt: semantische Vorschläge für Mapping,
Transformation und Normalisierung, während Kennzahlen, Warnungen und harte
Validierung deterministisch im Profiling-Kern bleiben.

> **Abgrenzung**: Die semantische Analyse ergänzt das Profiling nur um
> Vorschläge. Rohe Daten müssen dafür nicht an das Modell gegeben werden; der
> Input bleibt auf verdichtete Profil-Summaries begrenzt, konsistent mit
> Privacy-by-Design und der Trennung aus `docs/profiling.md` §10.

### Milestone 1.2.0 — Weitere Datenbanken

| Bereich | Aufgabe                                                  | LF-Ref |
| ------- | -------------------------------------------------------- | ------ |
| Driver  | Oracle-Treiber (JDBC, PL/SQL-Support)                    | LF-019 |
| Driver  | MS SQL Server-Treiber (JDBC, T-SQL-Support)              | LF-019 |
| Core    | Erweitertes Typsystem für Oracle/MSSQL-spezifische Typen | LF-019 |
| Test    | Cross-DB-Tests mit 5 Datenbanksystemen                   | 8.7    |

**Ergebnis**: Unterstützung für die 5 wichtigsten relationalen Datenbanken.

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

### Milestone 2.0.0 — Langfristige Vision

| Bereich   | Aufgabe                                        | LF-Ref |
| --------- | ---------------------------------------------- | ------ |
| API       | REST-API für Remote-Zugriff                    | LF-022 |
| GUI       | Grafische Benutzeroberfläche für Schema-Design | LF-018 |
| Core      | Schema-Optimierungsvorschläge                  | LF-020 |
| Core      | Rollenbasierte Zugriffskontrolle               | LN-028 |
| Community | Steering Committee etablieren                  | —      |
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
- Performance-Benchmarks eingehalten (siehe [Lastenheft 8.2](./lastenheft-d-migrate.md))
- Build-Dauer < 5 Minuten

### Projekt-Metriken (nach 1.0.0)

- Migrations-Aufwand um >= 40% reduziert (Pilotanwender-Feedback)
- >= 3 erfolgreiche Produktiv-Migrationen
- >= 80% positive Pilotanwender-Bewertung
- Aufnahme in >= 2 Enterprise-Entwicklungsstacks

---

**Version**: 3.19
**Stand**: 2026-04-16
**Status**: Milestone 0.1.0, 0.2.0, 0.3.0, 0.4.0, 0.5.0, 0.5.5, 0.6.0, 0.7.0, 0.7.5 und 0.8.0 abgeschlossen; Milestone 0.9.0 in Arbeit, 0.9.1 geplant
