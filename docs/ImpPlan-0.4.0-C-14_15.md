# Implementierungsplan: Phase C – Schritte 14+15 – Writer-Lookup und PostgreSQL-Writer

> **Milestone**: 0.4.0 — Datenimport und inkrementelle Datenpfade
> **Phase**: C (DataWriter-Port und JDBC-Treiber)
> **Schritte**: 14 + 15
> **Status**: Geplant
> **Referenz**: `implementation-plan-0.4.0.md` §3.1.2, §3.2, §4 Phase C Schritte 14–15, §6.6, §6.7

---

## 1. Ziel

In der aktuellen Codebasis werden die ursprünglichen Schritte 14 und 15 als
ein **atomarer** Change umgesetzt:

1. Writer-Lookup über das bestehende Aggregat `DatabaseDriver` /
   `DatabaseDriverRegistry`
2. erster echter Dialekt-Writer: PostgreSQL

Der Grund ist technisch zwingend: Sobald `DatabaseDriver` um
`dataWriter(): DataWriter` erweitert wird, müssen mindestens ein realer Writer
und ein realer Driver diesen Port im selben Change erfüllen. Ein isolierter
„nur Registry"-Zwischenschritt wäre nicht kompilierbar.

Dieser kombinierte Plan liefert daher:

- `DatabaseDriver.dataWriter()`
- Lookup über `DatabaseDriverRegistry.get(dialect).dataWriter()`
- `PostgresDataWriter`
- `PostgresSchemaSync`
- `PostgresDriver.dataWriter()`
- fail-fast-Brücken in `MysqlDriver.dataWriter()` und `SqliteDriver.dataWriter()`
  bis Schritt 16/17 die echten Writer liefern
- Registry-/Integrationstests für den ersten vollständigen Writer-Pfad

MySQL und SQLite folgen weiterhin separat in Schritt 16 bzw. 17.

---

## 2. Vorbedingungen

| Abhängigkeit | Schritt | Status |
|---|---|---|
| `DataWriter`, `TableImportSession`, `WriteResult`, `FinishTableResult` | Phase C Schritt 12 | ✅ Vorhanden |
| `SchemaSync`, `SequenceAdjustment` | Phase C Schritt 13 | ✅ Vorhanden |
| `DatabaseDriver`, `DatabaseDriverRegistry` | Bestand | ✅ Vorhanden |
| `PostgresDriver`, `PostgresJdbcUrlBuilder`, `ConnectionPool` | Bestand | ✅ Vorhanden |
| `ValueDeserializer`, `TargetColumn`, `ImportOptions` | Phase A / C12 | ✅ Vorhanden |

---

## 3. Architekturentscheidung

### 3.1 Keine separate `DataWriterRegistry`

Der 0.4.0-Masterplan spricht noch von einer separaten `DataWriterRegistry`.
Die aktuelle Codebasis ist aber bereits auf ein zentrales Driver-Aggregat
konsolidiert:

- `DatabaseDriver` bündelt Dialekt-Fähigkeiten
- `DatabaseDriverRegistry` ist der zentrale Lookup-Punkt
- `Main.kt` registriert pro Dialekt genau einen Driver

Darum wird **keine** zweite Writer-Registry eingeführt. Stattdessen wird das
bestehende Aggregat ergänzt.

### 3.2 Atomarer Aggregat-Change

Für dieses Repo gilt:

- `DatabaseDriver.dataWriter()` darf nicht separat landen
- die Interface-Erweiterung wird zusammen mit `PostgresDataWriter` und
  `PostgresDriver.dataWriter()` eingeführt
- `MysqlDriver` und `SqliteDriver` müssen im selben Change zumindest
  kompilierbare fail-fast-Implementierungen erhalten
- `DatabaseDriverRegistry.clear()` bleibt der einzige Test-Reset

Damit wird Schritt 14 fachlich erfüllt, ohne die existierende Architektur
aufzubrechen.

---

## 4. Betroffene Dateien

### 4.1 Geänderte Dateien

| Datei | Änderung |
|---|---|
| `hexagon/ports/src/main/kotlin/dev/dmigrate/driver/DatabaseDriver.kt` | um `fun dataWriter(): DataWriter` erweitern |
| `hexagon/ports/src/main/kotlin/dev/dmigrate/driver/DatabaseDriverRegistry.kt` | KDoc auf Writer-Lookup erweitern |
| `adapters/driven/driver-postgresql/src/main/kotlin/dev/dmigrate/driver/postgresql/PostgresDriver.kt` | `dataWriter()` ergänzen |
| `adapters/driven/driver-mysql/src/main/kotlin/dev/dmigrate/driver/mysql/MysqlDriver.kt` | temporäres fail-fast-`dataWriter()` ergänzen |
| `adapters/driven/driver-sqlite/src/main/kotlin/dev/dmigrate/driver/sqlite/SqliteDriver.kt` | temporäres fail-fast-`dataWriter()` ergänzen |

### 4.2 Neue Dateien

| Datei | Zweck |
|---|---|
| `adapters/driven/driver-postgresql/src/main/kotlin/dev/dmigrate/driver/postgresql/PostgresDataWriter.kt` | PostgreSQL-Writer |
| `adapters/driven/driver-postgresql/src/main/kotlin/dev/dmigrate/driver/postgresql/PostgresSchemaSync.kt` | PG Sequence-/Trigger-Synchronisation |
| `adapters/driven/driver-postgresql/src/test/kotlin/dev/dmigrate/driver/postgresql/PostgresDriverWriterLookupTest.kt` | Writer-Lookup-Test über das Driver-Aggregat mit echtem `PostgresDriver` |
| `adapters/driven/driver-postgresql/src/test/kotlin/dev/dmigrate/driver/postgresql/PostgresDataWriterIntegrationTest.kt` | PG-Writer-Integration |
| `adapters/driven/driver-postgresql/src/test/kotlin/dev/dmigrate/driver/postgresql/PostgresSchemaSyncIntegrationTest.kt` | PG Reseed-/Trigger-/Quoting-Integration |

---

## 5. Design

### 5.1 `DatabaseDriver`-Erweiterung

Das Aggregat wird um einen zusätzlichen Port ergänzt:

```kotlin
interface DatabaseDriver {
    val dialect: DatabaseDialect

    fun ddlGenerator(): DdlGenerator
    fun dataReader(): DataReader
    fun tableLister(): TableLister
    fun dataWriter(): DataWriter
    fun urlBuilder(): JdbcUrlBuilder
}
```

Damit bleibt der Lookup konsistent:

- Export: `DatabaseDriverRegistry.get(dialect).dataReader()`
- Import: `DatabaseDriverRegistry.get(dialect).dataWriter()`
- Schema: `DatabaseDriverRegistry.get(dialect).ddlGenerator()`

Da MySQL- und SQLite-Writer erst in Schritt 16/17 folgen, erhalten
`MysqlDriver` und `SqliteDriver` in diesem Schritt nur eine explizite
fail-fast-Bridge:

```kotlin
override fun dataWriter(): DataWriter =
    throw UnsupportedOperationException(
        "Data import for MYSQL is planned for phase C step 16"
    )
```

Analog für SQLite mit Verweis auf Schritt 17. So bleibt der Aggregat-Change
kompilierbar, ohne eine zweite Registry einzuführen oder Fake-Writer zu bauen.

### 5.2 PostgreSQL-Writer

Der erste echte Writer folgt direkt den PG-Vorgaben aus dem Masterplan:

- Batch-INSERT via `PreparedStatement.addBatch()` / `executeBatch()`
- `autoCommit = false` im Chunk-Pfad
- `OVERRIDING SYSTEM VALUE` **nur** für `GENERATED ALWAYS AS IDENTITY`
- JSON/JSONB- und INTERVAL-Binding über PG-spezifische JDBC-Pfade
- `TargetColumn`-Metadata aus JDBC

### 5.3 PostgreSQL-`SchemaSync`

`PostgresSchemaSync` übernimmt:

- Sequence-Lookup via `pg_get_serial_sequence(...)`
- Reseeding via `setval(...)`
- `ALTER TABLE ... DISABLE TRIGGER USER`
- Trigger-Pre-Flight für `strict`
- `enableTriggers(...)` symmetrisch zum Disable-Pfad

Die zentrale Quoting-Anforderung aus dem Masterplan bleibt verbindlich:

- nicht-`public`-Schema
- Tabellenname mit Sonderzeichen / Mixed-Case
- Tabellenname mit eingebettetem Doublequote

### 5.4 Transaktionsgrenze für Trigger-Disable

PostgreSQL-Trigger-Disable läuft bewusst **außerhalb** der Chunk-Transaktionen:

1. `openTable(...)` setzt `autoCommit = true`
2. `disableTriggers(...)`
3. eigenes `commit()`
4. danach `autoCommit = false` für den Chunk-Pfad

`enableTriggers(...)` läuft beim Erfolgsabschluss bzw. Cleanup symmetrisch
wieder in eigener Mini-Transaktion.

### 5.5 Reseed-Semantik

`PostgresSchemaSync.reseedGenerators(...)` muss die in Schritt 13 definierte
Report-Semantik einhalten:

- `SequenceAdjustment.newValue` ist der **nächste implizit generierte Wert**
- bei `setval(seq, maxValue, true)` also `maxValue + 1`
- No-op bei `MAX(...) = NULL`
- kein Reseeding aus `close()`

---

## 6. Implementierung

### 6.1 `DatabaseDriver.kt` und `DatabaseDriverRegistry.kt`

- `DatabaseDriver` um `dataWriter()` erweitern
- `DatabaseDriverRegistry` funktional unverändert lassen
- KDoc ergänzen: Registry liefert jetzt auch Writer-seitige Fähigkeiten

### 6.2 `PostgresDriver.kt`

`PostgresDriver` implementiert zusätzlich:

- `override fun dataWriter(): DataWriter = PostgresDataWriter()`

Damit wird der Writer-Lookup automatisch durch den bestehenden
`DatabaseDriverRegistry.register(PostgresDriver())`-Pfad abgedeckt.

### 6.2a `MysqlDriver.kt` und `SqliteDriver.kt`

Beide Driver werden im selben Change auf das neue Aggregat gehoben:

- `override fun dataWriter(): DataWriter = throw UnsupportedOperationException(...)`
- Fehlermeldung benennt Dialekt und verweist auf den noch ausstehenden Schritt
- keine Fake-Writer, keine zusätzliche Registry

Diese Bridge ist rein technisch motiviert, damit das erweiterte
`DatabaseDriver`-Interface sofort in allen Consumern kompilierbar bleibt.

### 6.3 `PostgresDataWriter.kt`

Der Writer ist für den Tabellenimport verantwortlich:

- Connection aus `ConnectionPool` borgen
- Target-Metadaten lesen und `targetColumns` aufbauen
- SQL-INSERT mit korrektem PG-Identifier-Quoting erzeugen
- bei `GENERATED ALWAYS` den Insert um `OVERRIDING SYSTEM VALUE` ergänzen
- `TableImportSession` mit Chunk-Commit/Rollback liefern

### 6.4 `PostgresSchemaSync.kt`

Pflichten:

- Generator-Spalten aus `importedColumns` auflösen
- `pg_get_serial_sequence(quoted_table, column)` korrekt quotiert aufrufen
- `setval(...)` auf Basis von `MAX(col)` ausführen
- `disableTriggers`, `assertNoUserTriggers`, `enableTriggers` gemäß PG-Vertrag

### 6.5 Tests

#### `PostgresDriverWriterLookupTest.kt`

| # | Testname | Prüfung |
|---|---|---|
| 1 | `returns driver writer for registered dialect` | `get(dialect).dataWriter()` liefert Writer |
| 2 | `clear removes writer lookup together with driver` | `clear()` entfernt den Lookup |
| 3 | `missing dialect error still lists registered dialects` | Fehlermeldung bleibt hilfreich |

#### `PostgresDataWriterIntegrationTest.kt`

| # | Testname | Prüfung |
|---|---|---|
| 1 | `writes single chunk into target table` | Grundpfad |
| 2 | `writes multiple chunks with commit boundaries` | Chunk-Verhalten |
| 3 | `rollbackChunk discards current chunk only` | Chunk-Transaktionsmodell |
| 4 | `finishTable from OPEN supports zero chunk path` | F1 |
| 5 | `generated always uses overriding system value` | K1-Pflichtfall |
| 6 | `jsonb binding uses PostgreSQL specific path` | JSON/JSONB-Binding |
| 7 | `interval binding uses PostgreSQL specific path` | INTERVAL-Binding |

#### `PostgresSchemaSyncIntegrationTest.kt`

| # | Testname | Prüfung |
|---|---|---|
| 1 | `reseed serial column updates next generated value` | SERIAL |
| 2 | `reseed generated by default identity` | IDENTITY BY DEFAULT |
| 3 | `reseed generated always identity` | IDENTITY ALWAYS |
| 4 | `no adjustment when max is null` | No-op |
| 5 | `disable trigger mode uses disable trigger user` | PG disable-Pfad |
| 6 | `strict mode fails when table has user trigger` | Pre-Flight |
| 7 | `enable triggers is executed after disable path` | symmetrischer Cleanup |
| 8 | `pg_get_serial_sequence quoting works for non public schema` | L2-Fall 1 |
| 9 | `pg_get_serial_sequence quoting works for whitespace and mixed case` | L2-Fall 2 |
| 10 | `pg_get_serial_sequence quoting escapes embedded double quotes` | L2-Fall 3 |

---

## 7. Tests

### 7.1 Teststrategie

Der kombinierte Schritt hat drei Ebenen:

- Aggregat-/Registry-Tests ohne DB
- PostgreSQL-Integrationstests für Writer und SchemaSync
- Compile-/CLI-Verifikation des erweiterten Driver-Aggregats

Die PG-Registry-Tests liegen bewusst im PostgreSQL-Modul, damit der echte
`PostgresDriver` ohne zusätzliche Test-Abhängigkeiten verwendet werden kann.

### 7.2 Nicht Teil dieses Schritts

- MySQL-Writer
- SQLite-Writer
- `StreamingImporter`
- CLI-Import-Kommando

Diese Themen bleiben in Schritt 16, 17 und Phase D/E.

---

## 8. Build & Verifizierung

```bash
# Atomarer Aggregat + PostgreSQL-Writer Change
docker build --target build \
  --build-arg GRADLE_TASKS=":hexagon:ports:compileKotlin :adapters:driven:driver-common:test :adapters:driven:driver-postgresql:test -PintegrationTests :adapters:driven:driver-mysql:compileKotlin :adapters:driven:driver-sqlite:compileKotlin :adapters:driving:cli:test" \
  -t d-migrate:c14-15 .

# Optional kompletter Build
docker build -t d-migrate:dev .
```

---

## 9. Abnahmekriterien

- [ ] `DatabaseDriver` enthält `dataWriter()`
- [ ] es gibt **keine** zusätzliche `DataWriterRegistry`
- [ ] `DatabaseDriverRegistry.clear()` bleibt der einzige Reset-Pfad
- [ ] `PostgresDriver` implementiert `dataWriter()`
- [ ] `MysqlDriver` und `SqliteDriver` sind im selben Change auf das neue Interface angehoben
- [ ] MySQL/SQLite bleiben dabei bewusst fail-fast bis Schritt 16/17
- [ ] `PostgresDataWriter` schreibt Chunks erfolgreich in PostgreSQL
- [ ] `PostgresSchemaSync` erfüllt Reseed-/Trigger-Vertrag
- [ ] `SequenceAdjustment.newValue` wird im PG-Pfad als nächster impliziter Generatorwert reported
- [ ] die drei L2-Quoting-Pflichttests für `pg_get_serial_sequence(...)` sind vorhanden
- [ ] der Writer-Lookup-Test verwendet den echten `PostgresDriver` in einem Modul mit passender Abhängigkeit
- [ ] der Aggregat-Change kompiliert in Ports, Driver- und CLI-Consumern
- [ ] die PG-Testcontainers-Integrationstests laufen mit `-PintegrationTests`

---

## 10. Offene Punkte

- Der 0.4.0-Masterplan sollte später nachgezogen werden: Aus den Schritten 14
  und 15 wird in der realen Codebasis sinnvollerweise ein atomarer
  Aggregat+PostgreSQL-Writer-Change statt zweier isolierter Schritte.
