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

Es gibt noch ein großes Bündel von Projektions-DTOs in dieser Datei.

- `adapters/driven/driver-*` (MySQL, Postgres, SQLite) besitzen Type-Mapping- und Sonderfall-Logik, die in mehreren Klassen jeweils branches enthält.

- `hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/DataTransferRunner.kt` besitzt eine eigene Filterlogik, die nicht vollständig mit der Export-Validierung harmoniert.

## Maßnahmenplan (konkret)

> **Bewertungsskala:** `Priorität` (P1 = hoch, P2 = mittel, P3 = niedrig), `Aufwand` (S/M/L), `Risiko` (niedrig/mittel/hoch).

### 1) Transfer-Filter vereinheitlichen (Verhalten + Sicherheit)

- **Status:** Offen  
- **Dateien:**  
  [DataTransferRunner.kt](/Development/d-migrate/hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/DataTransferRunner.kt)  
  [DataExportHelpers.kt](/Development/d-migrate/hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/DataExportHelpers.kt)
- **Priorität / Aufwand / Risiko:** P1 / M / mittel  
- **Ziel:** `DataTransferRunner` nutzt dieselbe Filter-Aufbereitung wie Export (dialektgerechtes Identifier-Quoting, `--since`-Literal-Normalisierung/Typinferenz, Vergleichssemantik im `--since`-Pfad).
- **Achtung:** Das ist eine **behaviorale Ausrichtung**, kein reines Refactoring.
- **Konkrete Divergenzen:**  
  1. **Identifier-Quoting:** Transfer quotet manuell (`”\”${r.sinceColumn}\” > ?”`), Export nutzt `SqlIdentifiers.quoteQualifiedIdentifier()`. Bei `sinceColumn`-Werten mit eingebettetem `”` bricht das Quoting.  
  2. **Vergleichsoperator:** Transfer verwendet `>`, Export verwendet `>=`. Das ist ein semantischer Unterschied im Filterergebnis.  
  3. **Fehlende Literal-Normalisierung:** Export ruft `parseSinceLiteral()` auf (konservative Typinferenz mit Fallback auf Rohstring), Transfer übergibt den `--since`-Wert immer als untypisierten String direkt als Parameter.
- **Akzeptanzkriterien:**  
  - `--since` verhält sich identisch wie im Export-Pfad (für gleiches Eingabeverhalten, inkl. konservativer Typinferenz und String-Fallback für nicht typisierbare Literale).  
  - Alle drei Divergenzen sind aufgelöst (oder bewusst als Abweichung dokumentiert, inkl. Migrationshinweis).  
  - Fehlermeldungen bei ungültigen Eingaben (insb. Flag-Kombinationen und Identifiern) sind deterministisch und testenbar.
- **DoD:** Bestehende Verhaltenstests ergänzt/angepasst; neue Regressionstestfälle für mindestens 3 bestehende Filter-Varianten.

### 2) Runner-Entkopplung fokussieren

- **Status:** Offen  
- **Dateien:**  
  [DataImportRunner.kt](/Development/d-migrate/hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/DataImportRunner.kt)  
  [DataExportRunner.kt](/Development/d-migrate/hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/DataExportRunner.kt)
- **Priorität / Aufwand / Risiko:** P1 / L / mittel  
- **Ziel:** Große lokale Blöcke in `Validation`, `Checkpoint`, `Fehlerabbildung`, `Context-Build` und lokale Hilfsklassen auslagern.
- **Geplante Extraktionsziele:**  
  - `ImportPreflightValidator` — Preflight-Checks und Optionsvalidierung aus `DataImportRunner`  
  - `ImportCheckpointManager` — Checkpoint-Lese-/Schreiblogik und Resume-Koordination  
  - `ExportPreflightValidator` — analog für `DataExportRunner`  
  - Runner-interne DTOs (`*ExecutionContext`, `*ExecutionOptions`) in eigene Dateien  
- **Akzeptanzkriterien:**  
  - Jede betroffene Klasse sinkt unter 500 LOC (Ziel; nicht als hartes Sicherheitslimit, sondern als Refactoring-Heuristik).
  - `execute*`-Methoden kapseln nur Orchestrierung.
  - Neue Helferklassen haben fokussierte, testbare Schnittstellen.

### 3) `SchemaCompareRunner`-Projection trennen

- **Status:** Offen  
- **Datei:**  
  [SchemaCompareRunner.kt](/Development/d-migrate/hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/SchemaCompareRunner.kt)
- **Priorität / Aufwand / Risiko:** P2 / S / niedrig  
- **Ziel:** Projektions-DTOs (Dokument-, Summary- und Change-DTOs) in eine eigene Datei verlagern.
- **Akzeptanzkriterien:**  
  - Runner-Datei enthält klar erkennbare Orchestrierungslogik.
  - Neue DTO-Datei ist ausschließlich statisch/strukturell (keine Geschäftslogik).
  - Keine Änderung am CLI-Output ohne zusätzliche Testanpassung.

### 4) DDL-Generator strukturieren

- **Status:** Offen  
- **Dateien:**  
  [AbstractDdlGenerator.kt](/Development/d-migrate/adapters/driven/driver-common/src/main/kotlin/dev/dmigrate/driver/AbstractDdlGenerator.kt) (488 LOC)  
  [PostgresDdlGenerator.kt](/Development/d-migrate/adapters/driven/driver-postgresql/src/main/kotlin/dev/dmigrate/driver/postgresql/PostgresDdlGenerator.kt) (351 LOC)  
  [MysqlDdlGenerator.kt](/Development/d-migrate/adapters/driven/driver-mysql/src/main/kotlin/dev/dmigrate/driver/mysql/MysqlDdlGenerator.kt) (446 LOC)  
  [SqliteDdlGenerator.kt](/Development/d-migrate/adapters/driven/driver-sqlite/src/main/kotlin/dev/dmigrate/driver/sqlite/SqliteDdlGenerator.kt) (460 LOC)
- **Priorität / Aufwand / Risiko:** P1 / L / hoch  
- **Ziel:** DDL-Erzeugung in Phasen aufteilen: Basisschritt, Constraints/Indexes, Dialekt-Hooks.
- **Risikobegründung:** Die Aufspaltung ändert die Vererbungshierarchie des Template-Method-Patterns und betrifft gleichzeitig die gemeinsame Basisklasse plus drei Dialekt-Implementierungen (`AbstractDdlGenerator` + Postgres/MySQL/SQLite, zusammen 1745 LOC).
- **Akzeptanzkriterien:**  
  - Gemeinsame Erzeugungspfade sind in klar benannte Unterkomponenten zerlegt.
  - Unit-Tests für zentrale Pfade in neuer Unterstruktur ergänzt.
  - Keine Verhaltensänderung im bestehenden Test-Snapshot.

### 5) Type-Mapping tabellarisch stabilisieren

- **Status:** Offen  
- **Dateien:**  
  [MysqlTypeMapping.kt](/Development/d-migrate/adapters/driven/driver-mysql/src/main/kotlin/dev/dmigrate/driver/mysql/MysqlTypeMapping.kt)  
  [PostgresTypeMapping.kt](/Development/d-migrate/adapters/driven/driver-postgresql/src/main/kotlin/dev/dmigrate/driver/postgresql/PostgresTypeMapping.kt)  
  [SqliteTypeMapping.kt](/Development/d-migrate/adapters/driven/driver-sqlite/src/main/kotlin/dev/dmigrate/driver/sqlite/SqliteTypeMapping.kt)
- **Priorität / Aufwand / Risiko:** P2 / M / niedrig  
- **Ziel:** Reine Zuordnungen (1:1 Mapping) in Tabellen/Listen auslagern, Sonderfälle im Code behalten.
- **Akzeptanzkriterien:**  
  - Kein Mapping-Case fällt durch `else`-Strukturen/`when` ungetestet.
  - Neue Typfälle benötigen nur einen Tabelleneintrag, sofern kein Sonderfall.

### 6) MySQL-Column-Derivate vereinheitlichen

- **Status:** Offen  
- **Datei:**  
  [MysqlDdlGenerator.kt](/Development/d-migrate/adapters/driven/driver-mysql/src/main/kotlin/dev/dmigrate/driver/mysql/MysqlDdlGenerator.kt)
- **Priorität / Aufwand / Risiko:** P2 / M / niedrig  
- **Ziel:** Spezialfälle in `generateColumnSql(...)` (Identifier, Enum, Domains, Geometry) in kleine Builder/Helper auslagern.
- **Akzeptanzkriterien:**  
  - Jede Sonderfallgruppe ist in klar benannte Hilfsfunktion.
  - Kolonnenattribute bauen über gemeinsame Basiskette auf.

## Umsetzung in Wellen

### Welle 1 (schnell, stabil, geringer Risikoanteil)

- Maßnahmen 3, 6  

### Welle 2 (Struktur, moderate Komplexität)

- Maßnahmen 5, 2, 1  

### Welle 3 (hohes Risiko, breite Auswirkung)

- Maßnahme 4  

## Nicht Teil dieses Pakets

- Neue fachliche Logik in Export-/Import-/Transfer-/Generator-Flows.
- Großflächige Produktionsrefaktorierung allein zur Testabdeckung.
- Nicht priorisierte Performance- oder Architektur-Visionen ohne konkreten Ticket-Bezug.
- Nur weil ein Test angepasst werden muss: keine Architekturänderung als Nebenwirkung.
- Session-Duplikation (`PostgresTableImportSession` / `MysqlTableImportSession`): bekannter DRY-Verstoß (~200 LOC), aber Extraktion einer `AbstractTableImportSession` betrifft den I/O-kritischen Write-Pfad und erfordert eigenes Arbeitspaket mit Integrationstests.

## Hinweis zu Sicherheitsrelevanz

Für Maßnahmen mit möglicher Verhaltenseinwirkung (insb. Punkt 1) gilt:

- Änderungen werden als `behavioral` klassifiziert.
- Es wird vorab dokumentiert, ob es kompatible, warnende oder migrationspflichtige Anpassungen sind.
- Falls nötig, werden Migrationshinweise im Changelog ergänzt.

## Abhängigkeiten

- [docs/ImpPlan-0.9.2-6.6.md](/Development/d-migrate/docs/ImpPlan-0.9.2-6.6.md)
- [docs/ImpPlan-0.9.2-6.7.md](/Development/d-migrate/docs/ImpPlan-0.9.2-6.7.md)
