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
0.1.0 - 0.5.5        0.6.0 - 0.9.0         1.0.0                 1.1.0 - 2.0.0
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

### Milestone 0.3.0 — Datenexport

| Bereich | Aufgabe                                               | LF-Ref |
| ------- | ----------------------------------------------------- | ------ |
| Core    | Streaming-Pipeline (Basis)                            | LN-005 |
| Driver  | JDBC-basierter DataReader (ResultSet-Streaming)       | LF-008 |
| Driver  | Connection-Management mit HikariCP                    | —      |
| Formats | JSON-Codec (Jackson)                                  | LF-009 |
| Formats | YAML-Codec                                            | LF-009 |
| Formats | CSV-Codec (mit Encoding-Optionen)                     | LF-009 |
| CLI     | `d-migrate data export` Kommando                      | LF-008 |
| Test    | Export-Tests gegen Testcontainers (PostgreSQL, MySQL) | LN-048 |

**Ergebnis**: Daten können aus Datenbanken in JSON/YAML/CSV exportiert werden.

### Milestone 0.4.0 — Datenimport

| Bereich | Aufgabe                                         | LF-Ref |
| ------- | ----------------------------------------------- | ------ |
| Core    | Transaktionale Import-Logik (Chunk-basiert)     | LN-010 |
| Driver  | JDBC-basierter DataWriter (Batch-Insert)        | LF-010 |
| Formats | Deserialisierung aus JSON/YAML/CSV              | LF-010 |
| Core    | Validierung gegen Schema-Definition beim Import | LF-010 |
| Core    | Encoding-Erkennung (UTF-8, UTF-16, ISO-8859-1)  | LF-010 |
| CLI     | `d-migrate data import` Kommando                | LF-010 |
| Test    | Round-Trip-Tests (Export → Import → Vergleich)  | LN-043 |

**Ergebnis**: Vollständiger Export/Import-Zyklus funktioniert.

### Milestone 0.5.0 — MVP-Release

| Bereich | Aufgabe                                       | LF-Ref |
| ------- | --------------------------------------------- | ------ |
| Core    | Schema-Diff-Engine (Vergleich zweier Schemas) | LF-015 |
| CLI     | `d-migrate schema compare` Kommando           | LF-015 |
| CLI     | Fortschrittsanzeige für lange Operationen     | LN-017 |
| Docs    | Anwenderhandbuch (Basis)                      | —      |
| Docs    | CLI-Referenz                                  | —      |
| Docs    | Beispiel-Projekte (E-Commerce-Schema)         | —      |
| Build   | GitHub Releases (Fat JAR)                     | —      |
| Build   | Homebrew-Formula (Basis)                      | —      |
| QA      | Code-Coverage >= 80%                          | LN-045 |

**Ergebnis**: Öffentliches MVP-Release. Early Adopters können Schema-Verwaltung und Daten-Export/Import nutzen.

### Milestone 0.5.5 — Erweitertes Typsystem

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

## Phase 2: Beta (0.6.0 - 0.9.0) — Monate 7-12

**Ziel**: Production-ready für erste Projekte

### Milestone 0.6.0 — Reverse-Engineering

| Bereich | Aufgabe                                                         | LF-Ref |
| ------- | --------------------------------------------------------------- | ------ |
| Driver  | SchemaReader: Tabellen, Spalten, Constraints aus DB extrahieren | LF-004 |
| Driver  | SchemaReader: Indizes, Sequences, Custom Types                  | LF-004 |
| Driver  | SchemaReader: Stored Procedures, Functions, Triggers, Views     | LF-004 |
| Driver  | PostgreSQL-spezifisch: Extensions, JSONB, Array-Typen           | LF-004 |
| Driver  | MySQL-spezifisch: Engine, AUTO_INCREMENT, SET-Typen             | LF-004 |
| Driver  | SQLite-spezifisch: WITHOUT ROWID, Virtual Tables                | LF-004 |
| CLI     | `d-migrate schema reverse --source <db-url>`                    | LF-004 |
| Test    | Reverse-Engineering gegen komplexe Test-Schemas                 | 8.4    |

**Ergebnis**: Bestehende Datenbanken können in das neutrale Format überführt werden.

### Milestone 0.7.0 — Tool-Integrationen

| Bereich     | Aufgabe                                                   | LF-Ref |
| ----------- | --------------------------------------------------------- | ------ |
| Integration | Flyway-Adapter: SQL-Migrations-Skripte generieren         | LF-011 |
| Integration | Liquibase-Adapter: Changesets generieren                  | LF-011 |
| Integration | Django-Adapter: Python-Migrations generieren              | LF-011 |
| Integration | Knex.js-Adapter: JavaScript-Migrations generieren         | LF-011 |
| CLI         | `d-migrate export flyway/liquibase/django/knex` Kommandos | LF-011 |
| Core        | Migrations-Rollback-Generierung                           | LF-014 |
| Test        | Generierte Migrations-Skripte ausführen und validieren    | 8.6    |

**Ergebnis**: d-migrate integriert sich in bestehende Migrations-Toolchains.

### Milestone 0.8.0 — Internationalisierung

| Bereich | Aufgabe                                                          | LF-Ref |
| ------- | ---------------------------------------------------------------- | ------ |
| i18n    | ResourceBundle-Architektur (Deutsch, Englisch)                   | LF-006 |
| i18n    | Lokalisierte CLI-Meldungen und Fehlertexte                       | LN-023 |
| i18n    | ICU4J-Integration für Unicode-Verarbeitung                       | LF-005 |
| i18n    | Grapheme-aware String-Längenberechnung                           | LN-021 |
| Core    | Zeitzonen-Handling (UTC als Standard, konfigurierbar)            | LF-007 |
| Formats | BOM-Erkennung und -Behandlung bei CSV                            | LF-010 |
| Test    | Unicode-Integritätstests (Emoji, Kyrillisch, CJK)                | 8.5    |

**Ergebnis**: Vollständige Unicode-/i18n-Unterstützung und konsistentes Zeitzonen-Handling. (Die erweiterten neutralen Typen JSON/Arrays/Binary/UUID sowie Spatial wurden in [0.5.5](#milestone-055--erweitertes-typsystem) vorgezogen.)

### Milestone 0.9.0 — Beta-Release

| Bereich | Aufgabe                                       | LF-Ref |
| ------- | --------------------------------------------- | ------ |
| Core    | Checkpoint/Resume für langläufige Operationen | LN-012 |
| Core    | Inkrementeller Export/Import                  | LF-013 |
| CLI     | `--lang` Flag für Sprachauswahl               | LF-006 |
| Docs    | Vollständiges Anwenderhandbuch                | —      |
| Docs    | Administrationshandbuch                       | —      |
| Docs    | Migrations-Leitfaden                          | —      |
| Docs    | API-Dokumentation                             | —      |
| QA      | Performance-Benchmarks dokumentiert           | —      |
| QA      | Pilotanwender-Tests (mindestens 5 Tester)     | 9.2    |

**Ergebnis**: Feature-Complete für Kernfunktionalität. Bereit für Produktivtests.

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
| AI      | Datenschutz: prefer_local, allow_external Flags | LN-032, LN-033 |
| AI      | Caching häufiger Transformationen               | LN-035         |
| CLI     | `d-migrate transform procedure` Kommando        | LF-017         |
| CLI     | `d-migrate generate procedure` Kommando         | LF-017         |
| QA      | Semantische Äquivalenzprüfung (Testausführung)  | LN-034         |

**Ergebnis**: KI-gestützte Migration von Stored Procedures zwischen Datenbanken.

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

**Version**: 1.3
**Stand**: 2026-04-06
**Status**: Milestone 0.1.0 und 0.2.0 abgeschlossen, 0.5.5 neu aufgenommen, weitere Milestones in Planung
