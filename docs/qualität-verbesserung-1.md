# Qualitätsverbesserung 1

## Status & Einordnung

- Stand: `2026-04-19`, nach Abschluss von Arbeitspaket `6.6`.
- `docs/ImpPlan-0.9.2-6.6.md` ist umgesetzt:
  - Runner wurden in fachliche Phasen zerteilt.
  - Breite Executor-Parameterlisten wurden in Kontext-DTOs zusammengeführt.
- `docs/ImpPlan-0.9.2-6.7.md` ist ein Verifikationspaket.
  Die hier beschriebenen Maßnahmen sind **keine 6.7-Aufgaben** und gelten als Folge-Refactorings.

## Zielbild

Dieses Dokument bündelt Qualitätsmaßnahmen mit Fokus auf:

- Lesbarkeit
- Wartbarkeit
- Änderbarkeit bei gleichbleibender fachlicher Korrektheit
- Sicherheit und Verhaltenstransparenz im Vergleichspfad

## Leitprinzipien

- Keine Funktionsänderung ohne explizite Entscheidung (Refactoring vs. Verhaltensänderung).
- Jede Maßnahme bekommt messbare Akzeptanzkriterien.
- Keine große „Reinigungsaktion“; nur gezielte, risikoarme Schritte.
- Scope-Disziplin: fachliche Logikänderungen nur mit separatem Paket.

## Verbleibende Schwächen (Bestandsaufnahme)

- `hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/DataImportRunner.kt` (971 LOC)
- `hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/DataExportRunner.kt` (658 LOC)

Die `executeWithPool()`-Zerlegung und Executor-Schnittstellen sind vorhanden, aber weitere Extraktion der lokal gebündelten Logik lohnt sich weiterhin.

- `adapters/driven/driver-common/src/main/kotlin/dev/dmigrate/driver/AbstractDdlGenerator.kt` (488 LOC)

Das ist noch ein gemeinsamer Hotspot mit hoher Änderungsfrequenz.

- `hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/SchemaCompareRunner.kt`

~114 LOC (~28 %) der Datei sind Projektions-DTOs (Document, Summary, Change-Views), die keine Geschäftslogik enthalten.

- `adapters/driven/driver-*` (MySQL, Postgres, SQLite) besitzen Type-Mapping- und Sonderfall-Logik, die in mehreren Klassen jeweils branches enthält.

- `hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/DataTransferRunner.kt` besitzt eine eigene Filterlogik, die nicht vollständig mit der Export-Validierung harmoniert.

## Maßnahmenplan (konkret)

> **Bewertungsskala:** `Priorität` (P1 = hoch, P2 = mittel, P3 = niedrig), `Aufwand` (S/M/L), `Risiko` (niedrig/mittel/hoch).

### 1) Transfer-Filter vereinheitlichen (Verhalten + Sicherheit)

- **Status:** Erledigt  
- **Dateien:**  
  [DataTransferRunner.kt](/Development/d-migrate/hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/DataTransferRunner.kt)  
  [DataExportHelpers.kt](/Development/d-migrate/hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/DataExportHelpers.kt)
- **Priorität / Aufwand / Risiko:** P1 / M / mittel  
- **Ziel:** `DataTransferRunner` nutzt dieselbe Filter-Aufbereitung wie Export (dialektgerechtes Identifier-Quoting, `--since`-Literal-Normalisierung/Typinferenz, Vergleichssemantik im `--since`-Pfad).
- **Achtung:** Das ist eine **behaviorale Ausrichtung**, kein reines Refactoring.
- **Erwartete Code-Änderung:** `buildFilter()` (aktuell 5 LOC manuelles Quoting + untypisierter Parameterdurchgriff) delegiert an gemeinsame Helper-Funktion in `DataExportHelpers`; `validateFlags()` wird um Identifier-Preflight analog Export ergänzt (~10 LOC). Der Runner selbst bleibt bei ~200 LOC, die Konsolidierung erweitert `DataExportHelpers` um ~15–20 LOC gemeinsame Filter-API.
- **Konkrete Divergenzen:**  
  1. **Identifier-Quoting + Identifier-Preflight:** Transfer quotet manuell (`"\"${r.sinceColumn}\" > ?"`), Export nutzt `SqlIdentifiers.quoteQualifiedIdentifier()` plus CLI-Validierung für qualifizierte Identifier. Dadurch weichen bereits reguläre qualifizierte Namen wie `audit.updated_at` vom Export-Pfad ab; zusätzlich fehlen deterministische Fehlermeldungen für ungültige/leere Identifier.  
  2. **Vergleichsoperator:** Transfer verwendet `>`, Export verwendet `>=`. Das ist ein semantischer Unterschied im Filterergebnis.  
  3. **Fehlende Literal-Normalisierung:** Export ruft `parseSinceLiteral()` auf (konservative Typinferenz mit Fallback auf Rohstring), Transfer übergibt den `--since`-Wert immer als untypisierten String direkt als Parameter.
- **Akzeptanzkriterien:**  
  - `--since` verhält sich identisch wie im Export-Pfad (für gleiches Eingabeverhalten, inkl. konservativer Typinferenz und String-Fallback für nicht typisierbare Literale).  
  - Alle benannten Divergenzen sind aufgelöst (oder bewusst als Abweichung dokumentiert, inkl. Migrationshinweis).  
  - Fehlermeldungen bei ungültigen Eingaben (insb. Flag-Kombinationen und Identifiern) sind deterministisch und testenbar.
  - Neue/geänderte Klassen erreichen ≥ 90 % Line-Coverage.
- **DoD:** Bestehende Verhaltenstests ergänzt/angepasst; neue Regressionstestfälle für mindestens 3 bestehende Filter-Varianten.

### 2) Runner-Entkopplung fokussieren

- **Status:** Erledigt  
- **Dateien:**  
  [DataImportRunner.kt](/Development/d-migrate/hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/DataImportRunner.kt)  
  [DataExportRunner.kt](/Development/d-migrate/hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/DataExportRunner.kt)
- **Priorität / Aufwand / Risiko:** P1 / L / mittel  
- **Ziel:** Große lokale Blöcke in `Validation`, `Checkpoint`, `Fehlerabbildung`, `Context-Build` und lokale Hilfsklassen auslagern.
- **Geplante Extraktionsziele (mit LOC-Schätzung):**  
  - `ImportPreflightValidator` (~70 LOC) — Preflight-Checks und Optionsvalidierung aus `DataImportRunner`  
  - `ImportCheckpointManager` (~200 LOC) — Checkpoint-Lese-/Schreiblogik und Resume-Koordination  
  - `ExportPreflightValidator` (~100 LOC) — analog für `DataExportRunner`  
  - Runner-interne DTOs (~165 LOC) (`*ExecutionContext`, `*ExecutionOptions`, interne Step-Result-DTOs) in eigene Dateien  
  - **Gesamt:** ~435 LOC aus `DataImportRunner` (→ ~536 LOC), ~160 LOC aus `DataExportRunner` (→ ~498 LOC)  
- **Akzeptanzkriterien:**  
  - `DataExportRunner` sinkt unter 500 LOC; `DataImportRunner` sinkt deutlich und erreicht im ersten Schnitt ca. ~536 LOC. Weitere Absenkung unter 500 LOC bleibt ein optionaler Folge-Schritt, nicht Gate dieses Pakets.
  - `execute*`-Methoden kapseln nur Orchestrierung.
  - Neue Helferklassen haben fokussierte, testbare Schnittstellen.
  - Neue Klassen erreichen ≥ 90 % Line-Coverage.

### 3) `SchemaCompareRunner`-Projection trennen

- **Status:** Erledigt  
- **Datei:**  
  [SchemaCompareRunner.kt](/Development/d-migrate/hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/SchemaCompareRunner.kt)
- **Priorität / Aufwand / Risiko:** P2 / S / niedrig  
- **Ziel:** Projektions-DTOs (~114 LOC; Dokument-, Summary- und Change-DTOs) in eine eigene Datei verlagern. Runner sinkt dadurch von 403 auf ~289 LOC.
- **Akzeptanzkriterien:**  
  - Runner-Datei enthält klar erkennbare Orchestrierungslogik.
  - Neue DTO-Datei ist rein strukturell (keine Geschäftslogik; derived accessors wie `totalChanges`-Summe sind zulässig).
  - Keine Änderung am CLI-Output ohne zusätzliche Testanpassung.
  - Neue Klassen erreichen ≥ 90 % Line-Coverage.

### 4) DDL-Generator strukturieren

- **Status:** Teilweise erledigt (ViewPhaseClassifier + StatementInverter extrahiert, 488→311 LOC; ColumnSqlBuilder/ConstraintClauseBuilder als Folgeschritt offen)  
- **Dateien:**  
  [AbstractDdlGenerator.kt](/Development/d-migrate/adapters/driven/driver-common/src/main/kotlin/dev/dmigrate/driver/AbstractDdlGenerator.kt) (488 LOC)  
  [PostgresDdlGenerator.kt](/Development/d-migrate/adapters/driven/driver-postgresql/src/main/kotlin/dev/dmigrate/driver/postgresql/PostgresDdlGenerator.kt) (351 LOC)  
  [MysqlDdlGenerator.kt](/Development/d-migrate/adapters/driven/driver-mysql/src/main/kotlin/dev/dmigrate/driver/mysql/MysqlDdlGenerator.kt) (446 LOC)  
  [SqliteDdlGenerator.kt](/Development/d-migrate/adapters/driven/driver-sqlite/src/main/kotlin/dev/dmigrate/driver/sqlite/SqliteDdlGenerator.kt) (460 LOC)
- **Priorität / Aufwand / Risiko:** P1 / L / hoch  
- **Ziel:** DDL-Erzeugung in Phasen aufteilen: Basisschritt, Constraints/Indexes, Dialekt-Hooks.
- **Risikobegründung:** Die Aufspaltung ändert die Vererbungshierarchie des Template-Method-Patterns und betrifft gleichzeitig die gemeinsame Basisklasse plus drei Dialekt-Implementierungen (`AbstractDdlGenerator` + Postgres/MySQL/SQLite, zusammen 1745 LOC).
- **Geplante Extraktionsziele (mit LOC-Schätzung):**  
  - `ColumnSqlBuilder` pro Dialekt (~52–86 LOC je Override) — extrahiert gemischte Typvarianten aus `generateColumnSql()` (SERIAL/AUTO_INCREMENT, ENUM×2, DOMAIN, Geometry/SRID)  
  - `ConstraintClauseBuilder` pro Dialekt (~25–35 LOC je Override) — extrahiert die 4-Branch-`when`-Logik (CHECK, UNIQUE, EXCLUDE, FK) aus `generateConstraintClause()`  
  - `ViewPhaseClassifier` (~75 LOC) — extrahiert `classifyViewsByPhase()` + `inferViewFunctionDependencies()` aus `AbstractDdlGenerator`  
  - `StatementInverter` (~107 LOC gesamt: ~61 Abstract + ~25 MySQL-Override + ~21 SQLite-Override) — extrahiert `invertStatement()` in eigenständige Komponente  
  - **Gesamt:** ~400–450 LOC extrahiert; `AbstractDdlGenerator` sinkt auf ~350 LOC, Dialekt-Generatoren um jeweils ~80–120 LOC  
- **Akzeptanzkriterien:**  
  - Gemeinsame Erzeugungspfade sind in klar benannte Unterkomponenten zerlegt.
  - Unit-Tests für zentrale Pfade in neuer Unterstruktur ergänzt.
  - Alle bestehenden E2E-/Integrationstests und DDL-Snapshot-Tests bleiben ohne Anpassung grün.
  - Keine Verhaltensänderung im bestehenden Test-Snapshot.
  - Neue Klassen erreichen ≥ 90 % Line-Coverage.

### 5) Type-Mapping tabellarisch stabilisieren

- **Status:** Erledigt  
- **Dateien:**  
  [MysqlTypeMapping.kt](/Development/d-migrate/adapters/driven/driver-mysql/src/main/kotlin/dev/dmigrate/driver/mysql/MysqlTypeMapping.kt)  
  [PostgresTypeMapping.kt](/Development/d-migrate/adapters/driven/driver-postgresql/src/main/kotlin/dev/dmigrate/driver/postgresql/PostgresTypeMapping.kt)  
  [SqliteTypeMapping.kt](/Development/d-migrate/adapters/driven/driver-sqlite/src/main/kotlin/dev/dmigrate/driver/sqlite/SqliteTypeMapping.kt)
- **Priorität / Aufwand / Risiko:** P2 / M / niedrig  
- **Ziel:** Reine Zuordnungen (1:1 Mapping) in Tabellen/Listen auslagern, Sonderfälle im Code behalten.
- **Akzeptanzkriterien:**  
  - Alle `when`-Ausdrücke über Mapping-Typen sind exhaustiv (kein `else`-Fallthrough); jeder Zweig besitzt mindestens einen Unit-Test-Case.
  - Neue Typfälle benötigen nur einen Tabelleneintrag, sofern kein Sonderfall.
  - Neue Klassen erreichen ≥ 90 % Line-Coverage.

### 6) MySQL-Column-Derivate vereinheitlichen

- **Status:** Erledigt  
- **Datei:**  
  [MysqlDdlGenerator.kt](/Development/d-migrate/adapters/driven/driver-mysql/src/main/kotlin/dev/dmigrate/driver/mysql/MysqlDdlGenerator.kt)
- **Priorität / Aufwand / Risiko:** P2 / M / niedrig  
- **Ziel:** Spezialfälle in `generateColumnSql(...)` (Identifier, Enum, Domains, Geometry) in kleine Builder/Helper auslagern.
- **Akzeptanzkriterien:**  
  - Jede Sonderfallgruppe ist in klar benannte Hilfsfunktion.
  - Kolonnenattribute bauen über gemeinsame Basiskette auf.
  - Neue Klassen erreichen ≥ 90 % Line-Coverage.

## Umsetzung in Wellen

### Welle 1 (schnell, stabil, geringer Risikoanteil)

- Maßnahme 3  

### Welle 2 (Struktur, moderate Komplexität)

- Maßnahmen 6, 5, 2, 1  
- **Reihenfolge-Begründung:** Maßnahme 1 steht trotz P1 am Ende, weil sie als behaviorale Änderung von der in Maßnahme 2 extrahierten Validierungsinfrastruktur profitiert und ein stabileres Test-Fundament voraussetzt. Die reinen Refactorings (6, 5, 2) schaffen zuerst die strukturelle Basis.

### Welle 3 (hohes Risiko, breite Auswirkung)

- Maßnahme 4  

## Nicht Teil dieses Pakets

- Neue fachliche Logik in Export-/Import-/Transfer-/Generator-Flows.
- Großflächige Produktionsrefaktorierung allein zur Testabdeckung.
- Nicht priorisierte Performance- oder Architektur-Visionen ohne konkreten Ticket-Bezug.
- Nur weil ein Test angepasst werden muss: keine Architekturänderung als Nebenwirkung.
- Session-Duplikation (`PostgresTableImportSession` / `MysqlTableImportSession`): bekannter DRY-Verstoß (~200 LOC). Die Extraktion einer `AbstractTableImportSession` erfordert ein eigenes Arbeitspaket, weil: (a) die Klassen direkt die JDBC-Batch-Schreibpfade steuern, in denen dialektspezifische Unterschiede im Transaktions- und Error-Handling bestehen; (b) eine Regression im Write-Pfad Datenverlust verursachen kann; (c) trotz bereits vorhandener Testcontainers-Integrationsmodule und Writer-Integrationstests gegen echte Postgres-/MySQL-Datenbanken zusätzliche Refactoring-spezifische Absicherung für gemeinsame Session-Abstraktion nötig wäre.

## Hinweis zu Sicherheitsrelevanz

Für Maßnahmen mit möglicher Verhaltenseinwirkung (insb. Punkt 1) gilt:

- Änderungen werden als `behavioral` klassifiziert.
- Es wird vorab dokumentiert, ob es kompatible, warnende oder migrationspflichtige Anpassungen sind.
- Falls nötig, werden Migrationshinweise im Changelog ergänzt.

## Abhängigkeiten

- [docs/ImpPlan-0.9.2-6.6.md](/Development/d-migrate/docs/ImpPlan-0.9.2-6.6.md)
- [docs/ImpPlan-0.9.2-6.7.md](/Development/d-migrate/docs/ImpPlan-0.9.2-6.7.md)
