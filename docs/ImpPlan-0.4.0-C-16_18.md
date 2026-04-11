# Implementierungsplan: Phase C – Schritte 16–18 – MySQL-/SQLite-Writer und Bootstrap-Abschluss

> **Milestone**: 0.4.0 — Datenimport und inkrementelle Datenpfade
> **Phase**: C (DataWriter-Port und JDBC-Treiber)
> **Schritte**: 16 + 17 + 18
> **Status**: Geplant
> **Referenz**: `implementation-plan-0.4.0.md` §3.3, §3.4, §4 Phase C Schritte 16–18, §6.6, §6.8.2, §6.12.2

---

## 1. Ziel

Nach `C-14_15` existiert der `DataWriter`-Port bereits produktiv für
PostgreSQL. MySQL und SQLite hängen noch an expliziten fail-fast-Brücken in
`MysqlDriver.dataWriter()` bzw. `SqliteDriver.dataWriter()`.

Dieser Plan schließt die verbleibende Dialekt-Lücke in einem kombinierten
Change:

1. `MysqlDataWriter` + `MysqlSchemaSync`
2. `SqliteDataWriter` + `SqliteSchemaSync`
3. Abschluss von Schritt 18 in der **realen** Codebasis: keine separate
   `registerDataWriter`-Registry, sondern vollständiger Writer-Bootstrap über
   `DatabaseDriver` / `DatabaseDriverRegistry`

Schritt 18 wird dabei bewusst an den tatsächlichen Stand des Repos angepasst.
Die ältere Masterplan-Formulierung mit separaten Bootstrap-Objects passt nicht
mehr zur inzwischen konsolidierten Driver-Architektur.

---

## 2. Vorbedingungen

| Abhängigkeit | Schritt | Status |
|---|---|---|
| `DataWriter`, `TableImportSession`, `WriteResult`, `FinishTableResult` | Phase C Schritt 12 | ✅ Vorhanden |
| `SchemaSync`, `SequenceAdjustment` | Phase C Schritt 13 | ✅ Vorhanden |
| `DatabaseDriver.dataWriter()` | Phase C Schritte 14+15 | ✅ Vorhanden |
| `PostgresDataWriter`, `PostgresSchemaSync` | Phase C Schritte 14+15 | ✅ Vorhanden |
| `MysqlDriver.dataWriter()` / `SqliteDriver.dataWriter()` fail-fast | Phase C Schritte 14+15 | ✅ Vorhanden |
| `ImportOptions`, `UnsupportedTriggerModeException` | Bestand | ✅ Vorhanden |

---

## 3. Architekturentscheidung

### 3.1 Schritte 16–18 als ein zusammenhängender Abschluss

Schritt 18 kann in der aktuellen Architektur nicht separat umgesetzt werden.
Der Bootstrap ist bereits in `DatabaseDriver` integriert; offen ist nur noch,
dass MySQL und SQLite echte Writer statt Platzhalter liefern.

Darum werden die drei Masterplan-Schritte praktisch so abgebildet:

- Schritt 16: MySQL-Writer + MySQL-`SchemaSync`
- Schritt 17: SQLite-Writer + SQLite-`SchemaSync`
- Schritt 18: Abschluss des Driver-Bootstraps durch Ersetzen der fail-fast-
  Brücken in `MysqlDriver` und `SqliteDriver`

### 3.2 Keine zusätzliche Writer-Registry

Es wird weiterhin **keine** `DataWriterRegistry` und kein
`registerDataWriter(...)` eingeführt.

Der vollständige Lookup bleibt:

- `DatabaseDriverRegistry.get(DatabaseDialect.POSTGRESQL).dataWriter()`
- `DatabaseDriverRegistry.get(DatabaseDialect.MYSQL).dataWriter()`
- `DatabaseDriverRegistry.get(DatabaseDialect.SQLITE).dataWriter()`

Damit bleibt das Aggregat einheitlich, und Schritt 18 ist mit der aktuellen
Codebasis konsistent statt historisches Plan-Vokabular nachzubauen.

### 3.3 Shared Session-Vertrag, dialektspezifische SQL

Beide neuen Writer folgen dem bereits eingeführten Session-Vertrag aus
`TableImportSession`:

- Connection pro `openTable(...)` aus dem Pool borgen
- Chunk-Transaktionen über `commitChunk()` / `rollbackChunk()`
- Cleanup in `close()` ohne Throw
- Reseed nur in `finishTable()`
- FK-Check-Reset explizit auf derselben Connection vor Rückgabe an den Pool

Nicht vereinheitlicht werden dagegen die SQL-Details:

- MySQL: `INSERT IGNORE`, `ON DUPLICATE KEY UPDATE`,
  `SET FOREIGN_KEY_CHECKS`
- SQLite: `ON CONFLICT DO NOTHING`, `ON CONFLICT (...) DO UPDATE`,
  `PRAGMA foreign_keys`, `sqlite_sequence`

---

## 4. Betroffene Dateien

### 4.1 Geänderte Dateien

| Datei | Änderung |
|---|---|
| `adapters/driven/driver-mysql/src/main/kotlin/dev/dmigrate/driver/mysql/MysqlDriver.kt` | fail-fast-`dataWriter()` durch echten Writer ersetzen |
| `adapters/driven/driver-sqlite/src/main/kotlin/dev/dmigrate/driver/sqlite/SqliteDriver.kt` | fail-fast-`dataWriter()` durch echten Writer ersetzen |
| `adapters/driven/driver-mysql/build.gradle.kts` | ggf. Test-/Coverage-Konfiguration für Writer ergänzen |
| `adapters/driven/driver-sqlite/build.gradle.kts` | ggf. Test-/Coverage-Konfiguration für Writer ergänzen |

### 4.2 Neue Dateien

| Datei | Zweck |
|---|---|
| `adapters/driven/driver-mysql/src/main/kotlin/dev/dmigrate/driver/mysql/MysqlDataWriter.kt` | MySQL-Writer |
| `adapters/driven/driver-mysql/src/main/kotlin/dev/dmigrate/driver/mysql/MysqlSchemaSync.kt` | AUTO_INCREMENT- und FK-/Trigger-Sync |
| `adapters/driven/driver-sqlite/src/main/kotlin/dev/dmigrate/driver/sqlite/SqliteDataWriter.kt` | SQLite-Writer |
| `adapters/driven/driver-sqlite/src/main/kotlin/dev/dmigrate/driver/sqlite/SqliteSchemaSync.kt` | `sqlite_sequence`- und FK-/Trigger-Sync |
| `adapters/driven/driver-mysql/src/test/kotlin/dev/dmigrate/driver/mysql/MysqlDataWriterIntegrationTest.kt` | MySQL-Writer-Integration |
| `adapters/driven/driver-mysql/src/test/kotlin/dev/dmigrate/driver/mysql/MysqlSchemaSyncIntegrationTest.kt` | MySQL-Reseed-/FK-Integration |
| `adapters/driven/driver-sqlite/src/test/kotlin/dev/dmigrate/driver/sqlite/SqliteDataWriterTest.kt` | SQLite-Writer mit lokaler DB |
| `adapters/driven/driver-sqlite/src/test/kotlin/dev/dmigrate/driver/sqlite/SqliteSchemaSyncTest.kt` | SQLite-Reseed-/FK-Tests |

---

## 5. Design

### 5.1 MySQL-Writer

`MysqlDataWriter` folgt strukturell dem PostgreSQL-Writer, ersetzt aber die
SQL-Semantik dialektspezifisch:

- Batch-Insert via `PreparedStatement.addBatch()` / `executeBatch()`
- `--on-conflict abort`: normales `INSERT`
- `--on-conflict skip`: `INSERT IGNORE`
- `--on-conflict update`: `INSERT ... ON DUPLICATE KEY UPDATE ...`
- Primärschlüssel-Lookup via `DatabaseMetaData.getPrimaryKeys(...)`, strikt
  nach `KEY_SEQ` sortiert
- PK-lose Tabellen werden bei `onConflict=update` mit klarer Fehlermeldung
  abgewiesen

Wichtig für MySQL:

- `triggerMode=disable` und `triggerMode=strict` bleiben **unsupported** und
  werfen `UnsupportedTriggerModeException`
- `disableFkChecks=true` nutzt `SET FOREIGN_KEY_CHECKS=0` auf der Session und
  setzt den Wert im Cleanup auf derselben Connection wieder auf `1`
- der Writer hält dafür ein internes `fkChecksDisabled`-Flag
- der FK-Schalter wird in `openTable(...)` gesetzt, **bevor** der eigentliche
  Chunk-Transaktionspfad startet, damit der Import desselben Sessionscopes
  tatsächlich ohne FK-Prüfung laufen kann und der Reset in `close()` sauber auf
  derselben Connection erfolgt

### 5.2 MySQL-`SchemaSync`

`MysqlSchemaSync` verantwortet nur die in 0.4.0 erlaubten Pfade:

- AUTO_INCREMENT-Reseed via
  `ALTER TABLE \`table\` AUTO_INCREMENT = nextValue`
- `SequenceAdjustment.sequenceName = null`
- `SequenceAdjustment.newValue` bleibt der **nächste** implizit generierte Wert
- `assertNoUserTriggers(...)` und `disableTriggers(...)` werfen
  `UnsupportedTriggerModeException`

Der H5-Sonderfall aus dem Masterplan bleibt verbindlich:

- wenn `markTruncatePerformed()` gesetzt wurde
- und `MAX(auto_increment_col) = NULL`
- dann muss `AUTO_INCREMENT = 1` gesetzt werden
- ohne `truncatePerformed` bleibt `MAX = NULL` ein No-op

### 5.3 MySQL-UPSERT- und Skip-Zählung

MySQL bleibt der einzige Dialekt mit möglichem `Statement.SUCCESS_NO_INFO`
im Batch-Pfad. Deshalb gilt:

- `1 -> rowsInserted`
- `2 -> rowsUpdated`
- `0 -> rowsUpdated` im UPSERT-Pfad
- `0 -> rowsSkipped` im Skip-Pfad
- `SUCCESS_NO_INFO (-2)` wandert in `rowsUnknown`, nicht in `rowsInserted`

Zusätzlich wird der strukturelle `rewriteBatchedStatements=true`-Pfad geprüft:

- bevorzugt per Unwrap auf den MySQL-Treiber
- mit Fallback auf JDBC-URL-Assertion

Für den PK-Lookup gelten die Masterplan-Pflichten aus §6.12.2 weiterhin
vollständig:

- `KEY_SEQ` ist die alleinige kanonische PK-Reihenfolge
- die Metadaten-Normalisierung folgt `lower_case_table_names`
- dafür gibt es explizite Phase-C-Tests mit Composite-PK und Mixed-Case-Namen

### 5.4 SQLite-Writer

`SqliteDataWriter` bleibt nahe an der PG-Struktur, aber ohne Container- und
Server-spezifische Annahmen:

- Batch-Insert via JDBC-Batch
- `--on-conflict abort`: normales `INSERT`
- `--on-conflict skip`: `INSERT ... ON CONFLICT DO NOTHING`
- `--on-conflict update`:
  `INSERT ... ON CONFLICT(pk...) DO UPDATE SET ...`
- Primärschlüssel-Lookup anhand der SQLite-Metadaten, stabil in PK-Reihenfolge
- PK-lose Tabellen werden bei `onConflict=update` abgewiesen

Für SQLite gilt außerdem:

- `triggerMode=disable` und `triggerMode=strict` bleiben unsupported
- `disableFkChecks=true` nutzt `PRAGMA foreign_keys = OFF`
- Cleanup setzt `PRAGMA foreign_keys = ON` auf derselben Connection zurück
- kein Schema-Konzept; Tabellennamen bleiben unqualifiziert
- das `PRAGMA foreign_keys = OFF` wird vor dem Chunk-Transaktionspfad gesetzt,
  damit der folgende Schreibpfad tatsächlich ohne FK-Prüfung läuft und der
  Reset anschließend denselben Connection-Scope wieder in den Defaultzustand
  zurückführt

### 5.5 SQLite-`SchemaSync`

`SqliteSchemaSync` unterstützt genau den 0.4.0-Pfad:

- Reseed nur für `INTEGER PRIMARY KEY AUTOINCREMENT`
- Eingriff über `sqlite_sequence`
- `SequenceAdjustment.sequenceName = null`
- `newValue` beschreibt auch hier den nächsten generierten Wert

F4-Sonderfall:

- wenn `markTruncatePerformed()` gesetzt wurde
- und die Tabelle nach `DELETE FROM` leer bleibt
- dann wird `DELETE FROM sqlite_sequence WHERE name = ?` ausgeführt
- Report-Semantik: `newValue = 1`

Für Tabellen ohne `AUTOINCREMENT` bleibt der Reseed-Pfad No-op.

Für den PK-Lookup gelten auch hier die Masterplan-Pflichten aus §6.12.2:

- Composite-PKs werden strikt nach `KEY_SEQ` verarbeitet
- Mixed-Case-/Case-insensitive Lookup-Pfade werden explizit getestet
- dieselbe Tabellen-Normalisierung wird für Spalten- und PK-Lookup verwendet

### 5.6 Schritt 18 in der realen Architektur

Der Bootstrap-Abschluss besteht in diesem Repo konkret aus drei Punkten:

1. `MysqlDriver.dataWriter()` liefert `MysqlDataWriter()`
2. `SqliteDriver.dataWriter()` liefert `SqliteDataWriter()`
3. alle drei Produktiv-Dialekte sind über
   `DatabaseDriverRegistry.get(dialect).dataWriter()` erreichbar

Mehr ist für Schritt 18 nicht nötig. Ein künstliches Nachbauen separater
Bootstrap-Objekte wäre doppelte Architektur.

---

## 6. Implementierung

### 6.1 `MysqlDriver.kt`

- fail-fast-Implementierung entfernen
- `override fun dataWriter(): DataWriter = MysqlDataWriter()`

### 6.2 `MysqlDataWriter.kt`

Pflichten:

- Target-Spalten via JDBC laden
- PK-Spalten stabil via `KEY_SEQ` auflösen
- Insert-/Skip-/UPSERT-SQL bauen
- Batch-Rückgabewerte korrekt auf `WriteResult` normalisieren
- FK-Check-Schalter im Cleanup sicher zurücksetzen
- `markTruncatePerformed()`-Signal in `finishTable()` berücksichtigen

### 6.3 `MysqlSchemaSync.kt`

Pflichten:

- AUTO_INCREMENT-Spalte für die Tabelle identifizieren
- `MAX(col)` ermitteln
- `ALTER TABLE ... AUTO_INCREMENT = N` setzen
- Unsupported-Triggerpfade explizit mit
  `UnsupportedTriggerModeException` markieren

### 6.4 `SqliteDriver.kt`

- fail-fast-Implementierung entfernen
- `override fun dataWriter(): DataWriter = SqliteDataWriter()`

### 6.5 `SqliteDataWriter.kt`

Pflichten:

- Target-/PK-Metadaten aus SQLite lesen
- Insert-/Skip-/UPSERT-Pfad mit SQLite-Syntax bauen
- FK-PRAGMA im Cleanup sicher zurücksetzen
- `markTruncatePerformed()`-Signal in `finishTable()` berücksichtigen

### 6.6 `SqliteSchemaSync.kt`

Pflichten:

- AUTOINCREMENT-Tabellen erkennen
- `sqlite_sequence` lesen und pflegen
- Truncate-leer-Sonderfall per `DELETE FROM sqlite_sequence` behandeln
- Unsupported-Triggerpfade explizit markieren

---

## 7. Tests

### 7.1 MySQL

#### `MysqlDataWriterIntegrationTest.kt`

| # | Testname | Prüfung |
|---|---|---|
| 1 | `writes single chunk into target table` | Grundpfad |
| 2 | `writes multiple chunks with commit boundaries` | Chunk-Verhalten |
| 3 | `rollbackChunk discards current chunk only` | Chunk-Transaktionsmodell |
| 4 | `finishTable from OPEN supports zero chunk path` | leere Tabelle |
| 5 | `onConflict update upserts rows and reports inserted vs updated` | UPSERT-Pfad |
| 6 | `onConflict update rejects target tables without primary key` | PK-Pflicht |
| 7 | `onConflict skip reports inserted vs skipped` | `INSERT IGNORE` |
| 8 | `disable fk checks allows importing child rows before parent rows` | funktionaler FK-Disable-Pfad |
| 9 | `disable fk checks is reset before pooled connection is reused` | H-R3 |
| 10 | `rewriteBatchedStatements is enabled for writer connection` | L14 |
| 11 | `onConflict update supports composite primary keys in key sequence order` | M-R4 |
| 12 | `primary key lookup respects lower_case_table_names normalization` | F5 |

#### `MysqlSchemaSyncIntegrationTest.kt`

| # | Testname | Prüfung |
|---|---|---|
| 1 | `reseed auto increment uses next max plus one` | Standard-Reseed |
| 2 | `no adjustment when max is null without truncate signal` | No-op |
| 3 | `truncate empty table resets auto increment to one` | H5 |
| 4 | `trigger disable mode is unsupported` | Unsupported-Pfad |
| 5 | `trigger strict mode is unsupported` | Unsupported-Pfad |

### 7.2 SQLite

#### `SqliteDataWriterTest.kt`

| # | Testname | Prüfung |
|---|---|---|
| 1 | `writes single chunk into target table` | Grundpfad |
| 2 | `writes multiple chunks with commit boundaries` | Chunk-Verhalten |
| 3 | `rollbackChunk discards current chunk only` | Chunk-Transaktionsmodell |
| 4 | `finishTable from OPEN supports zero chunk path` | leere Tabelle |
| 5 | `onConflict update upserts rows and reports inserted vs updated` | UPSERT-Pfad |
| 6 | `onConflict update rejects target tables without primary key` | PK-Pflicht |
| 7 | `onConflict skip reports inserted vs skipped` | DO NOTHING |
| 8 | `disable fk checks allows importing child rows before parent rows` | funktionaler FK-Disable-Pfad |
| 9 | `disable fk checks is reset before pooled connection is reused` | H-R3 |
| 10 | `onConflict update supports composite primary keys in key sequence order` | M-R4 |
| 11 | `primary key lookup handles mixed case table names consistently` | F5 |

#### `SqliteSchemaSyncTest.kt`

| # | Testname | Prüfung |
|---|---|---|
| 1 | `reseed autoincrement table updates next generated value` | Standard-Reseed |
| 2 | `no adjustment for table without autoincrement` | No-op |
| 3 | `truncate empty autoincrement table clears sqlite_sequence` | F4 |
| 4 | `trigger disable mode is unsupported` | Unsupported-Pfad |
| 5 | `trigger strict mode is unsupported` | Unsupported-Pfad |

### 7.3 Bootstrap-Abschluss

Zusätzlich zu den Dialekt-Tests wird abgesichert:

- `registerDrivers()` registriert weiterhin PostgreSQL, MySQL und SQLite
- `DatabaseDriverRegistry.get(...).dataWriter()` liefert nach dem Bootstrap für
  alle drei Dialekte echte Writer
- die bisherigen fail-fast-Fehlermeldungen verschwinden aus dem
  Produktionspfad

Die Absicherung liegt deshalb sinnvoll im CLI-Modul oder in einem Test, der den
echten `registerDrivers()`-Pfad verwendet statt nur direkte Driver-Instanzen zu
konstruieren.

---

## 8. Build & Verifizierung

```bash
# MySQL- und SQLite-Writer inkl. Bootstrap-Abschluss
docker build --target build \
  --build-arg GRADLE_TASKS=":adapters:driven:driver-mysql:test -PintegrationTests :adapters:driven:driver-sqlite:test :adapters:driven:driver-postgresql:test :adapters:driving:cli:test" \
  -t d-migrate:c16-18 .

# Optional gezielt
./scripts/test-integration-docker.sh -PintegrationTests :adapters:driven:driver-mysql:test
./gradlew :adapters:driven:driver-sqlite:test
```

---

## 9. Abnahmekriterien

- [ ] `MysqlDriver.dataWriter()` liefert einen echten `MysqlDataWriter`
- [ ] `SqliteDriver.dataWriter()` liefert einen echten `SqliteDataWriter`
- [ ] es gibt weiterhin **keine** separate `DataWriterRegistry`
- [ ] Schritt 18 ist in der realen Architektur als Bootstrap-Abschluss über
      `DatabaseDriver` dokumentiert und umgesetzt
- [ ] `MysqlDataWriter` unterstützt `abort`, `skip` und `update`
- [ ] `SqliteDataWriter` unterstützt `abort`, `skip` und `update`
- [ ] PK-lose Tabellen werden bei `onConflict=update` in beiden Dialekten klar abgewiesen
- [ ] MySQL-`rowsInserted` / `rowsUpdated` / `rowsSkipped` / `rowsUnknown` werden korrekt normalisiert
- [ ] MySQL `rewriteBatchedStatements=true` ist im Writer-Pfad strukturell verifiziert
- [ ] `disableFkChecks` ist in MySQL und SQLite nicht nur reset-sicher, sondern
      funktional im Schreibpfad wirksam getestet
- [ ] `disableFkChecks` wird in MySQL und SQLite auf derselben Connection sauber zurückgesetzt
- [ ] `triggerMode=disable` und `triggerMode=strict` bleiben in MySQL und SQLite explizit unsupported
- [ ] `MysqlSchemaSync` setzt `AUTO_INCREMENT` korrekt nach
- [ ] `SqliteSchemaSync` pflegt `sqlite_sequence` korrekt
- [ ] Composite-PKs werden in MySQL und SQLite strikt nach `KEY_SEQ`
      verarbeitet und explizit getestet
- [ ] Mixed-Case-/Identifier-Normalisierung für PK-Lookup ist in MySQL und
      SQLite explizit getestet
- [ ] der Truncate-leer-Sonderfall ist in MySQL (`newValue = 1`) und SQLite
      (`newValue = 1`) explizit abgedeckt
- [ ] `SequenceAdjustment.newValue` bleibt in beiden Dialekten konsistent als
      nächster generierter Wert definiert
- [ ] MySQL-Integrationstests laufen mit `-PintegrationTests`
- [ ] SQLite-Tests laufen ohne Container lokal im Standard-Testpfad
- [ ] der echte CLI-/Bootstrap-Pfad registriert nach wie vor Writer für alle
      drei Dialekte
- [ ] CLI- und Driver-Consumer kompilieren gegen alle drei realen Writer

---

## 10. Offene Punkte

- Der 0.4.0-Masterplan sollte später textlich nachgezogen werden:
  „Schritt 18 = `registerDataWriter`" ist in der aktuellen Architektur
  historisch überholt. Real umgesetzt wird stattdessen der Abschluss des
  bestehenden `DatabaseDriver`-Aggregats.
