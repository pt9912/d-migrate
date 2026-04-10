# Implementierungsplan: Phase C – Schritt 12 – DataWriter/TableImportSession Interfaces

> **Milestone**: 0.4.0 — Datenimport und inkrementelle Datenpfade
> **Phase**: C (DataWriter-Port und JDBC-Treiber)
> **Schritt**: 12
> **Status**: Offen
> **Referenz**: `implementation-plan-0.4.0.md` §3.1.1, §4 Phase C Schritt 12

---

## 1. Ziel

Definition der Writer-seitigen Port-Interfaces `DataWriter` und
`TableImportSession` samt aller zugehörigen Typen in
`hexagon:ports`. Dies ist das symmetrische Gegenstück zum
bestehenden `DataReader`/`ChunkSequence`-Port und bildet den
vollständigen Vertrag ab, gegen den die konkreten Treiber-Writer
(Schritt 15–17) und der `StreamingImporter` (Phase D) implementieren.

Enthaltene Typen (alle in `dev.dmigrate.driver.data`):

| Typ | Art |
|---|---|
| `DataWriter` | Interface |
| `TableImportSession` | Interface (extends `AutoCloseable`) |
| `WriteResult` | Data Class |
| `FinishTableResult` | Sealed Class |
| `TargetColumn` | Data Class |
| `UnsupportedTriggerModeException` | Exception |

Zusätzlich werden `SchemaSync` (Interface) und `SequenceAdjustment`
(Data Class) als Minimal-Definitionen angelegt, da `DataWriter.schemaSync()`
und `FinishTableResult.Success` sie referenzieren. Die vollständige
Vertragsdokumentation und Tests für diese beiden Typen folgen in
Schritt 13.

**Vorbereitende Refaktorierung**: `ImportOptions` (samt `TriggerMode`,
`OnConflict`, `OnError`) wird von `adapters:driven:formats` nach
`hexagon:ports` verschoben (siehe §4.1).

---

## 2. Vorbedingungen

| Abhängigkeit | Schritt | Status |
|---|---|---|
| `ImportOptions`, `TriggerMode`, `OnConflict`, `OnError` | Phase A Schritt 2 | ✅ Vorhanden (in `adapters:driven:formats`, wird nach `hexagon:ports` verschoben) |
| `ImportSchemaMismatchException` | Phase A Schritt 3 | ✅ Vorhanden (in `hexagon:core`) |
| `DataReader` / `DataReaderRegistry` (Symmetrie-Vorlage) | 0.3.0 | ✅ Vorhanden |
| `ConnectionPool` | 0.3.0 | ✅ Vorhanden |
| `DatabaseDialect` | 0.2.0 | ✅ Vorhanden |
| `DataChunk`, `ColumnDescriptor` | 0.3.0 core | ✅ Vorhanden |

---

## 3. Betroffene Dateien

### 3.1 Neue Dateien

| Datei | Zweck |
|---|---|
| `hexagon/ports/src/main/kotlin/dev/dmigrate/driver/data/DataWriter.kt` | Writer-Port-Interface |
| `hexagon/ports/src/main/kotlin/dev/dmigrate/driver/data/TableImportSession.kt` | Session-Interface mit State-Maschine |
| `hexagon/ports/src/main/kotlin/dev/dmigrate/driver/data/WriteResult.kt` | Per-Chunk-Ergebnis |
| `hexagon/ports/src/main/kotlin/dev/dmigrate/driver/data/FinishTableResult.kt` | Sealed Result für `finishTable()` |
| `hexagon/ports/src/main/kotlin/dev/dmigrate/driver/data/TargetColumn.kt` | Writer-side Spalten-Metadaten |
| `hexagon/ports/src/main/kotlin/dev/dmigrate/driver/data/UnsupportedTriggerModeException.kt` | Exception für nicht-unterstützte Trigger-Modi |
| `hexagon/ports/src/main/kotlin/dev/dmigrate/driver/data/SchemaSync.kt` | Minimal-Interface (Detailvertrag in Schritt 13) |
| `hexagon/ports/src/main/kotlin/dev/dmigrate/driver/data/SequenceAdjustment.kt` | Data Class (Detailtests in Schritt 13) |
| `adapters/driven/driver-common/src/test/kotlin/dev/dmigrate/driver/data/WriteResultTest.kt` | Unit-Tests WriteResult |
| `adapters/driven/driver-common/src/test/kotlin/dev/dmigrate/driver/data/FinishTableResultTest.kt` | Unit-Tests FinishTableResult |
| `adapters/driven/driver-common/src/test/kotlin/dev/dmigrate/driver/data/TargetColumnTest.kt` | Unit-Tests TargetColumn |
| `adapters/driven/driver-common/src/test/kotlin/dev/dmigrate/driver/data/TableImportSessionContractTest.kt` | State-Maschine-Vertragstests mit Stub-Writer |

### 3.2 Verschobene Dateien

| Von | Nach | Grund |
|---|---|---|
| `adapters/driven/formats/src/main/kotlin/dev/dmigrate/format/data/ImportOptions.kt` | `hexagon/ports/src/main/kotlin/dev/dmigrate/driver/data/ImportOptions.kt` | Zirkuläre Abhängigkeit vermeiden; Port-Schicht ist der architektonisch korrekte Ort (siehe §4.1) |

### 3.3 Geänderte Dateien

| Datei | Änderung |
|---|---|
| `adapters/driven/formats/src/main/kotlin/dev/dmigrate/format/data/DataChunkReaderFactory.kt` | Import-Pfad: `dev.dmigrate.format.data.ImportOptions` → `dev.dmigrate.driver.data.ImportOptions` |
| `adapters/driven/formats/src/main/kotlin/dev/dmigrate/format/data/DefaultDataChunkReaderFactory.kt` | Import-Pfad analog |
| `adapters/driven/formats/src/main/kotlin/dev/dmigrate/format/data/csv/CsvChunkReader.kt` | Import-Pfad analog |
| `adapters/driven/formats/src/main/kotlin/dev/dmigrate/format/data/json/JsonChunkReader.kt` | Import-Pfad analog |
| `adapters/driven/formats/src/main/kotlin/dev/dmigrate/format/data/yaml/YamlChunkReader.kt` | Import-Pfad analog |
| Alle Test-Dateien, die `ImportOptions` referenzieren | Import-Pfad analog |
| `adapters/driven/driver-common/build.gradle.kts` | Kover-Excludes für neue pure Interfaces ergänzen |

---

## 4. Design

### 4.1 ImportOptions lebt jetzt in `hexagon:ports`

`DataWriter.openTable()` nimmt laut Plan §3.1.1 `ImportOptions` als
Parameter. `ImportOptions` lebte ursprünglich in `adapters:driven:formats`
(`dev.dmigrate.format.data`). Durch die hexagonale Modul-Umstrukturierung
wurde `ImportOptions` (als Port-Interface/Konfiguration) nach
`hexagon:ports` verschoben — das ist der architektonisch korrekte Ort
im neuen Modulschnitt.

**Architektonische Begründung** (vgl. `docs/architecture.md` §1.2):

| Schicht | Modul | Rolle |
|---------|-------|-------|
| Domain Core | `hexagon:core` | Neutrales Modell — keine Import-/Export-Konfiguration |
| Ports | `hexagon:ports` | SPI-Interfaces für Datenbank-Zugriff |
| Driven Adapter | `adapters:driven:formats` | Serialisierung/Deserialisierung |

`ImportOptions` enthält Port-Level-Konfiguration: Trigger-Modi,
FK-Check-Steuerung, Conflict-Verhalten, Sequence-Reseeding — das sind
Vertragsparameter für den Writer-Port, nicht domänenneutrale
Modelltypes. Eine Platzierung in `hexagon:core` würde die
Domain-Core-Schicht mit adapterspezifischer Konfiguration
verschmutzen und gegen den Architekturstil „Neutrales Modell —
keine externen Deps" verstoßen.

`hexagon:ports` ist als Port-Schicht der architektonisch korrekte
Ort: Es definiert die SPI-Interfaces (`DataReader`, `DataWriter`) und
die zugehörige Konfiguration. `adapters:driven:formats` hängt bereits von
`hexagon:ports` ab und kann `ImportOptions` daher ohne neue Abhängigkeit
konsumieren:

```
core  ← ports (ImportOptions lebt hier)
  ↑       ↑
  └─── formats   (konsumiert ImportOptions über bestehende Dep)
```

Die Verschiebung ist ein reiner Package-Move. Die Datei-Inhalte bleiben
unverändert; nur Package-Deklaration und Import-Statements in
Consumer-Klassen werden angepasst.

### 4.2 DataWriter Interface

Symmetrisch zu `DataReader`, aber mit Session-basiertem Schreibzyklus
statt Sequence-basiertem Lesezyklus:

```
DataReader.streamTable(pool, table, ...) → ChunkSequence (pull)
DataWriter.openTable(pool, table, options)  → TableImportSession (push)
```

Aus dem Plan §3.1.1:

```kotlin
interface DataWriter {
    val dialect: DatabaseDialect
    fun schemaSync(): SchemaSync
    fun openTable(
        pool: ConnectionPool,
        table: String,
        options: ImportOptions,
    ): TableImportSession
}
```

- `dialect` — Symmetrie zu `DataReader.dialect`, für Registry-Lookup.
- `schemaSync()` — pflichtig, keine Default-No-Op-Implementierung.
  Rückgabetyp `SchemaSync` wird als Vorab-Definition aus Schritt 13
  mit angelegt.
- `openTable(...)` — borgt Connection aus Pool, baut
  PreparedStatement, liefert Session. **H1-Cleanup-Vertrag**: bei
  Exception nach bereits ausgeführten Side-Effects (disableTriggers)
  muss der Writer intern aufräumen, bevor er wirft (siehe Plan §3.1.1
  H1-Dokumentation).

### 4.3 TableImportSession State-Maschine (M1)

Die Session implementiert eine strenge State-Maschine mit fünf
Zuständen:

```
OPEN     --write(chunk)--------------> WRITTEN
OPEN     --finishTable() [0-Chunk]---> FINISHED
OPEN     --markTruncatePerformed()---> OPEN       (idempotent)
OPEN     --close()------------------> CLOSED

WRITTEN  --commitChunk() ok---------> OPEN
WRITTEN  --rollbackChunk() ok-------> OPEN
WRITTEN  --commitChunk() wirft------> FAILED
WRITTEN  --rollbackChunk() wirft----> FAILED
WRITTEN  --close()------------------> CLOSED

FAILED   --close()------------------> CLOSED      (einzige erlaubte Op)

FINISHED --close()------------------> CLOSED

CLOSED   --close()------------------> CLOSED      (idempotent)
```

Jede nicht aufgeführte Transition wirft `IllegalStateException` — das
sind Importer-Bugs, keine tolerierten Edge-Cases. Die State-Maschine
wird in Phase D mit einem Fake-Writer vollständig abgefahren; Phase C
definiert hier den Vertrag und testet ihn mit einem minimalen Stub.

### 4.4 WriteResult und FinishTableResult

**WriteResult** (Data Class):

```kotlin
data class WriteResult(
    val rowsInserted: Long,
    val rowsUpdated: Long,
    val rowsSkipped: Long,
    val rowsUnknown: Long = 0L,
)
```

- `rowsUnknown` — R10: MySQL `SUCCESS_NO_INFO (-2)`-Fallback
- Invariante: alle Felder `≥ 0`

**FinishTableResult** (Sealed Class):

```kotlin
sealed class FinishTableResult {
    data class Success(
        val adjustments: List<SequenceAdjustment>,
    ) : FinishTableResult()

    data class PartialFailure(
        val adjustments: List<SequenceAdjustment>,
        val cause: Throwable,
    ) : FinishTableResult()
}
```

- `Success` — Reseeding + Trigger-Reenable erfolgreich
- `PartialFailure` — Reseeding ok, Trigger-Reenable gescheitert (H4)

### 4.5 TargetColumn

Writer-seitige Spalten-Metadaten mit JDBC-Typcode. Lebt bewusst in
`hexagon:ports` (nicht in `hexagon:core`), weil `core.ColumnDescriptor` JDBC-frei
bleiben soll (L15).

```kotlin
data class TargetColumn(
    val name: String,
    val nullable: Boolean,
    val jdbcType: Int,
    val sqlTypeName: String? = null,
)
```

Die Konversion `TargetColumn → JdbcTypeHint` erfolgt erst im Phase-D
`StreamingImporter`, der beide Module kennt.

### 4.6 UnsupportedTriggerModeException

```kotlin
class UnsupportedTriggerModeException(message: String) :
    RuntimeException(message)
```

Geworfen von `SchemaSync.disableTriggers()` auf MySQL/SQLite (Plan
§3.3/§3.4).

### 4.7 Vorab-Definitionen für Schritt 13

Damit `DataWriter` und `FinishTableResult` kompilieren, werden
`SchemaSync` und `SequenceAdjustment` bereits als Minimal-Typen
angelegt. Die vollständige Vertragsdokumentation, Tests und die
`Connection`-Parameter-Signatur werden in Schritt 13 finalisiert.

```kotlin
// Minimal für Schritt 12 — Schritt 13 ergänzt KDoc und Tests
interface SchemaSync {
    fun reseedGenerators(
        conn: java.sql.Connection,
        table: String,
        importedColumns: List<ColumnDescriptor>,
    ): List<SequenceAdjustment>

    fun disableTriggers(conn: java.sql.Connection, table: String)
    fun assertNoUserTriggers(conn: java.sql.Connection, table: String)
    fun enableTriggers(conn: java.sql.Connection, table: String)
}

data class SequenceAdjustment(
    val table: String,
    val column: String,
    val sequenceName: String?,
    val newValue: Long,
)
```

---

## 5. Implementierung

### 5.1 ImportOptions-Verschiebung

1. `ImportOptions.kt` von `adapters/driven/formats/.../format/data/` nach
   `hexagon/ports/.../driver/data/` verschieben.
2. Package-Deklaration ändern: `dev.dmigrate.format.data` →
   `dev.dmigrate.driver.data`.
3. `import java.nio.charset.Charset` bleibt unverändert (JDK-Klasse,
   keine neue Abhängigkeit für `hexagon:ports`).
4. Alle Import-Statements in Consumer-Klassen (Reader, Tests) anpassen:
   `dev.dmigrate.format.data.ImportOptions` →
   `dev.dmigrate.driver.data.ImportOptions` (analog für `TriggerMode`,
   `OnConflict`, `OnError`).
5. Build verifizieren: `docker build --target build
   --build-arg GRADLE_TASKS=":adapters:driven:driver-common:test :adapters:driven:formats:test"
   -t d-migrate:c12-prep .`

### 5.2 `DataWriter.kt`

```kotlin
package dev.dmigrate.driver.data

import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.connection.ConnectionPool

/**
 * Port: Tabellen-Import über eine Session-basierte Push-API.
 *
 * Symmetrisches Gegenstück zu [DataReader] (Pull-basierter Lese-Port).
 *
 * Connection-Ownership (symmetrisch zu DataReader, §6.18):
 * - Der Writer bekommt einen [ConnectionPool], NICHT eine fertige Connection.
 * - Pro [openTable]-Aufruf borgt der Writer sich eine eigene Connection
 *   und hält sie für die Lifetime der zurückgegebenen [TableImportSession].
 * - Der Caller besitzt nie eine JDBC-Connection.
 *
 * Konkrete Treiber (PostgreSQL, MySQL, SQLite) implementieren dieses
 * Interface direkt (kein AbstractJdbcDataWriter in 0.4.0 — die
 * Writer-Mechanik ist hinreichend dialektspezifisch).
 */
interface DataWriter {
    val dialect: DatabaseDialect

    /**
     * Liefert die dialektspezifische Sequence-/Trigger-Synchronisation.
     * Es gibt bewusst KEINE Default-No-Op-Implementierung: jeder Treiber
     * muss den Vertrag explizit erfüllen.
     */
    fun schemaSync(): SchemaSync

    /**
     * Bereitet einen Tabellen-Import vor: prüft Spalten, baut die
     * PreparedStatement-INSERT-Vorlage und liefert eine [TableImportSession]
     * zurück, die der Caller pro Chunk benutzt und am Ende schließt.
     *
     * Borgt sich pro Aufruf eine Connection aus dem Pool.
     * Die Connection wird in der Session gehalten und beim
     * `close()` zurückgegeben.
     *
     * **Cleanup-Vertrag (H1)**: Führt vor dem Return ggf.
     * `disableTriggers(...)` oder `assertNoUserTriggers(...)` aus. Wirft
     * die Methode danach noch, MUSS der Writer intern aufräumen (Trigger
     * re-enablen, Connection zurückgeben) bevor die Exception
     * weiterreicht. Sekundäre Cleanup-Fehler werden per
     * `addSuppressed()` angehängt.
     *
     * @throws dev.dmigrate.core.data.ImportSchemaMismatchException bei
     *   Target-seitigen Metadaten-/Schemafehlern
     */
    fun openTable(
        pool: ConnectionPool,
        table: String,
        options: ImportOptions,
    ): TableImportSession
}
```

### 5.3 `TableImportSession.kt`

```kotlin
package dev.dmigrate.driver.data

import dev.dmigrate.core.data.DataChunk

/**
 * Session für den Import einer einzelnen Tabelle. Implementiert eine
 * strikte State-Maschine (M1) mit den Zuständen OPEN, WRITTEN, FAILED,
 * FINISHED, CLOSED.
 *
 * Lifecycle:
 * ```
 * val session = writer.openTable(pool, table, options)
 * session.use {
 *     for (chunk in chunks) {
 *         it.write(chunk)
 *         it.commitChunk()   // oder rollbackChunk() bei Fehler
 *     }
 *     it.finishTable()
 * }
 * ```
 *
 * **F3**: `close()` wirft nicht — Sekundärfehler werden strukturiert
 * gemeldet, nicht als Exception propagiert.
 */
interface TableImportSession : AutoCloseable {

    /**
     * Autoritative Target-Spalten in Binding-Reihenfolge. Vom Writer
     * beim Öffnen der Tabelle über JDBC-Metadaten eingelesen (§6.4).
     */
    val targetColumns: List<TargetColumn>

    /**
     * Schreibt einen Chunk im aktuellen Transaktionskontext.
     *
     * State-Maschine: OPEN → WRITTEN.
     * Aus jedem anderen Zustand: IllegalStateException.
     */
    fun write(chunk: DataChunk): WriteResult

    /**
     * Bestätigt den letzten geschriebenen Chunk.
     *
     * State-Maschine: WRITTEN → OPEN.
     * Wirft commitChunk() selbst (H-R1): WRITTEN → FAILED.
     */
    fun commitChunk()

    /**
     * Verwirft den letzten geschriebenen Chunk.
     *
     * State-Maschine: WRITTEN → OPEN.
     * Wirft rollbackChunk() selbst (H-R1): WRITTEN → FAILED.
     */
    fun rollbackChunk()

    /**
     * Truncate-Signal pro Tabelle (R3). Idempotent.
     *
     * State-Maschine: erlaubt aus OPEN (vor erstem write).
     * Nach write: IllegalStateException.
     */
    fun markTruncatePerformed()

    /**
     * Regulärer Erfolgsabschluss: Reseeding + Trigger-Reenable.
     *
     * State-Maschine: OPEN → FINISHED.
     * 0-Chunk-Pfad (F1) ist gültig (OPEN ohne vorangegangenen write).
     * Aus WRITTEN: IllegalStateException (Importer MUSS vorher committen).
     */
    fun finishTable(): FinishTableResult

    /**
     * Cleanup: Rollback offener Transaktion, autoCommit-Reset,
     * Trigger-Reenable (idempotent), Connection zurückgeben.
     *
     * Wirft NICHT (F3). Idempotent.
     * R6-Cleanup-Reihenfolge: rollback → autoCommit → enableTriggers
     * → FK-Reset → Connection-Return.
     */
    override fun close()
}
```

### 5.4 `WriteResult.kt`

```kotlin
package dev.dmigrate.driver.data

/**
 * Per-Chunk-Ergebnis eines [TableImportSession.write].
 *
 * Alle Felder sind nicht-negativ. [rowsUnknown] ist > 0 nur im
 * MySQL-Pfad bei `Statement.SUCCESS_NO_INFO` (R10/M-R8).
 */
data class WriteResult(
    val rowsInserted: Long,
    val rowsUpdated: Long,
    val rowsSkipped: Long,
    val rowsUnknown: Long = 0L,
) {
    init {
        require(rowsInserted >= 0) { "rowsInserted must be >= 0, got $rowsInserted" }
        require(rowsUpdated >= 0) { "rowsUpdated must be >= 0, got $rowsUpdated" }
        require(rowsSkipped >= 0) { "rowsSkipped must be >= 0, got $rowsSkipped" }
        require(rowsUnknown >= 0) { "rowsUnknown must be >= 0, got $rowsUnknown" }
    }

    /** Gesamtzahl verarbeiteter Rows in diesem Chunk. */
    val totalRows: Long get() = rowsInserted + rowsUpdated + rowsSkipped + rowsUnknown
}
```

### 5.5 `FinishTableResult.kt`

```kotlin
package dev.dmigrate.driver.data

/**
 * Ergebnis von [TableImportSession.finishTable].
 *
 * - [Success]: Reseeding und Trigger-Reenable erfolgreich.
 * - [PartialFailure]: Reseeding hat geklappt, Trigger-Reenable
 *   gescheitert (H4). [cause] ist der originale Throwable.
 *
 * Wenn `reseedGenerators` selbst wirft, reicht `finishTable()` die
 * Exception direkt durch — es gibt dann keinen FinishTableResult.
 */
sealed class FinishTableResult {
    data class Success(
        val adjustments: List<SequenceAdjustment>,
    ) : FinishTableResult()

    data class PartialFailure(
        val adjustments: List<SequenceAdjustment>,
        val cause: Throwable,
    ) : FinishTableResult()
}
```

### 5.6 `TargetColumn.kt`

```kotlin
package dev.dmigrate.driver.data

/**
 * Writer-side Spalten-Metadaten für eine Import-Zieltabelle.
 *
 * Lebt in `hexagon:ports` (nicht in `hexagon:core`), weil [jdbcType] semantisch
 * JDBC-coupled ist. `core.ColumnDescriptor` bleibt JDBC-frei (L15).
 *
 * Die Konversion zu `formats.JdbcTypeHint` erfolgt im Phase-D
 * `StreamingImporter`, der beide Module kennt.
 *
 * @property name Spaltenname
 * @property nullable Ob die Spalte NULL erlaubt
 * @property jdbcType JDBC-Typcode aus `ResultSetMetaData.getColumnType()`
 * @property sqlTypeName Dialekt-spezifischer Type-Name (sekundärer Hint
 *   für mehrdeutige jdbcType-Werte, z.B. PG `Types.OTHER`)
 */
data class TargetColumn(
    val name: String,
    val nullable: Boolean,
    val jdbcType: Int,
    val sqlTypeName: String? = null,
)
```

### 5.7 `UnsupportedTriggerModeException.kt`

```kotlin
package dev.dmigrate.driver.data

/**
 * Geworfen von [SchemaSync.disableTriggers], wenn der Dialekt den
 * Trigger-Disable-Modus nicht sicher unterstützt (MySQL, SQLite).
 */
class UnsupportedTriggerModeException(message: String) :
    RuntimeException(message)
```

### 5.8 `SchemaSync.kt` (Vorab-Definition)

```kotlin
package dev.dmigrate.driver.data

import dev.dmigrate.core.data.ColumnDescriptor
import java.sql.Connection

/**
 * Dialekt-spezifische Operationen rund um den Schreib-Zyklus.
 *
 * Vollständige Vertragsdokumentation und Tests: Schritt 13.
 */
interface SchemaSync {
    fun reseedGenerators(
        conn: Connection,
        table: String,
        importedColumns: List<ColumnDescriptor>,
    ): List<SequenceAdjustment>

    fun disableTriggers(conn: Connection, table: String)
    fun assertNoUserTriggers(conn: Connection, table: String)
    fun enableTriggers(conn: Connection, table: String)
}
```

### 5.9 `SequenceAdjustment.kt` (Vorab-Definition)

```kotlin
package dev.dmigrate.driver.data

/**
 * Beschreibung einer durchgeführten Sequence-/Identity-Nachführung.
 *
 * @property table Tabellenname
 * @property column Identity-/SERIAL-Spaltenname
 * @property sequenceName PG: expliziter Sequence-Name; MySQL/SQLite: null
 * @property newValue Neuer Sequence-/AUTO_INCREMENT-Wert
 */
data class SequenceAdjustment(
    val table: String,
    val column: String,
    val sequenceName: String?,
    val newValue: Long,
)
```

---

## 6. Tests

### 6.1 Teststrategie

Drei Achsen:

1. **Data-Class-Invarianten**: `WriteResult` Validierung, `FinishTableResult`
   Sealed-Class-Dispatch, `TargetColumn` Gleichheit.
2. **State-Maschine-Vertrag**: `TableImportSession`-Kontrakt wird über
   einen minimalen `StubTableImportSession` abgefahren. Jede erlaubte und
   verbotene Transition wird geprüft.
3. **Smoke-Check**: `DataWriter`, `SchemaSync` und `UnsupportedTriggerModeException`
   sind reine Interfaces/Exceptions ohne eigene Logik — kein
   separater Unit-Test nötig (Coverage-Exclude, siehe §7).

### 6.2 Testfälle — `WriteResultTest.kt`

| # | Testname | Prüfung |
|---|---|---|
| 1 | `basic construction` | Felder korrekt gesetzt, `totalRows` berechnet |
| 2 | `default rowsUnknown is 0` | Drei-Arg-Konstruktor → `rowsUnknown == 0` |
| 3 | `totalRows sums all fields` | Alle vier Felder aufsummiert |
| 4 | `rejects negative rowsInserted` | `IllegalArgumentException` |
| 5 | `rejects negative rowsUpdated` | `IllegalArgumentException` |
| 6 | `rejects negative rowsSkipped` | `IllegalArgumentException` |
| 7 | `rejects negative rowsUnknown` | `IllegalArgumentException` |
| 8 | `all-zero is valid` | `WriteResult(0,0,0,0)` → `totalRows == 0` |
| 9 | `data class equality` | Gleiche Werte → `equals == true` |
| 10 | `data class copy` | `copy(rowsUpdated = 5)` ändert nur dieses Feld |

### 6.3 Testfälle — `FinishTableResultTest.kt`

| # | Testname | Prüfung |
|---|---|---|
| 1 | `Success carries adjustments` | `Success(listOf(adj))` → `adjustments` korrekt |
| 2 | `Success with empty adjustments` | `Success(emptyList())` → gültig |
| 3 | `PartialFailure carries adjustments and cause` | Felder korrekt gesetzt |
| 4 | `sealed dispatch via when` | Exhaustiver `when`-Block kompiliert und dispatcht korrekt |

### 6.4 Testfälle — `TargetColumnTest.kt`

| # | Testname | Prüfung |
|---|---|---|
| 1 | `basic construction` | Alle Felder korrekt |
| 2 | `sqlTypeName defaults to null` | Drei-Arg-Konstruktor → `sqlTypeName == null` |
| 3 | `data class equality` | Gleiche Werte → equals |
| 4 | `different jdbcType → not equal` | Unterschiedlicher Typcode → not equals |

### 6.5 Testfälle — `TableImportSessionContractTest.kt`

State-Maschine-Vertragstests mit einem minimalen `StubTableImportSession`,
der die State-Transitions als In-Memory-Enum verwaltet, ohne
JDBC-Abhängigkeit.

| # | Testname | Prüfung |
|---|---|---|
| 1 | `initial state is OPEN` | Frisch erzeugte Session akzeptiert `write()` |
| 2 | `OPEN → write → WRITTEN` | `write()` liefert `WriteResult` |
| 3 | `WRITTEN → commitChunk → OPEN` | Nach Commit wieder bereit für nächsten Chunk |
| 4 | `WRITTEN → rollbackChunk → OPEN` | Nach Rollback wieder bereit |
| 5 | `double write without commit throws` | `write()` aus WRITTEN → `IllegalStateException` |
| 6 | `commitChunk from OPEN throws` | Kein vorangegangener Write → `IllegalStateException` |
| 7 | `rollbackChunk from OPEN throws` | Analog |
| 8 | `OPEN → finishTable → FINISHED` | 0-Chunk-Pfad (F1) gültig |
| 9 | `finishTable from WRITTEN throws` | Implizites Commit verhindern → `IllegalStateException` |
| 10 | `FINISHED → close → CLOSED` | Normaler Abschluss |
| 11 | `close is idempotent` | Mehrfacher `close()` wirft nicht |
| 12 | `write after close throws` | `IllegalStateException` |
| 13 | `commitChunk after close throws` | `IllegalStateException` |
| 14 | `finishTable after close throws` | `IllegalStateException` |
| 15 | `markTruncatePerformed from OPEN` | Idempotent, kein State-Wechsel |
| 16 | `markTruncatePerformed after write throws` | Aus WRITTEN → `IllegalStateException` |
| 17 | `FAILED state — only close allowed` | Simulierter commitChunk-Fehler → nur `close()` erlaubt |
| 18 | `write from FAILED throws` | `IllegalStateException` |
| 19 | `finishTable from FAILED throws` | `IllegalStateException` |
| 20 | `markTruncatePerformed from FAILED throws` | `IllegalStateException` |
| 21 | `write-commit-write-commit-finish cycle` | Multi-Chunk Happy Path |
| 22 | `close from OPEN without any write` | Cleanup-Pfad ohne Daten |

### 6.6 Stub-Design für State-Maschine-Tests

```kotlin
/**
 * Minimaler Stub, der die State-Maschine aus §3.1.1 (M1)
 * implementiert. Kein JDBC, kein I/O — nur State-Tracking.
 */
private class StubTableImportSession(
    override val targetColumns: List<TargetColumn> = listOf(
        TargetColumn("id", false, java.sql.Types.INTEGER),
    ),
    private val commitWillThrow: Boolean = false,
    private val rollbackWillThrow: Boolean = false,
) : TableImportSession {

    enum class State { OPEN, WRITTEN, FAILED, FINISHED, CLOSED }

    var state: State = State.OPEN
        private set

    var truncatePerformed: Boolean = false
        private set

    private var hasWritten: Boolean = false

    override fun write(chunk: DataChunk): WriteResult {
        check(state == State.OPEN) {
            "write() requires OPEN, current state: $state"
        }
        state = State.WRITTEN
        hasWritten = true
        return WriteResult(chunk.rows.size.toLong(), 0, 0)
    }

    override fun commitChunk() {
        check(state == State.WRITTEN) {
            "commitChunk() requires WRITTEN, current state: $state"
        }
        if (commitWillThrow) {
            state = State.FAILED
            throw RuntimeException("simulated commit failure")
        }
        state = State.OPEN
    }

    override fun rollbackChunk() {
        check(state == State.WRITTEN) {
            "rollbackChunk() requires WRITTEN, current state: $state"
        }
        if (rollbackWillThrow) {
            state = State.FAILED
            throw RuntimeException("simulated rollback failure")
        }
        state = State.OPEN
    }

    override fun markTruncatePerformed() {
        check(state == State.OPEN && !hasWritten) {
            "markTruncatePerformed() requires OPEN before any write, " +
                "current state: $state, hasWritten: $hasWritten"
        }
        truncatePerformed = true
    }

    override fun finishTable(): FinishTableResult {
        check(state == State.OPEN) {
            "finishTable() requires OPEN, current state: $state"
        }
        state = State.FINISHED
        return FinishTableResult.Success(emptyList())
    }

    override fun close() {
        if (state == State.CLOSED) return  // idempotent
        state = State.CLOSED
    }
}
```

Dieser Stub wird ausschließlich in den Vertragstests verwendet. Die
konkreten Treiber-Writer (Schritt 15–17) bauen ihre eigene
State-Maschinen-Implementierung; Phase D fährt sie dann mit einem
Fake-Writer ab.

---

## 7. Coverage-Ziel

| Typ | Coverage | Anmerkung |
|---|---|---|
| `WriteResult` | 100 % | `require`-Validierung + `totalRows` |
| `FinishTableResult` | 100 % | Sealed-Class-Dispatch |
| `TargetColumn` | 100 % | Data-Class-Felder |
| `StubTableImportSession` | 100 % | Wird vollständig über Vertragstests abgefahren |
| `DataWriter` | Exclude | Reines Interface ohne Default-Methoden |
| `TableImportSession` | Exclude | Reines Interface ohne Default-Methoden |
| `SchemaSync` | Exclude | Reines Interface (Tests in Schritt 13) |
| `SequenceAdjustment` | Exclude | Pure Data Class (Tests in Schritt 13) |
| `UnsupportedTriggerModeException` | Exclude | Triviale Exception-Subclass |

Kover-Excludes in `build.gradle.kts` ergänzen:

```kotlin
kover {
    reports {
        filters {
            excludes {
                classes(
                    "dev.dmigrate.driver.DdlGenerator",
                    "dev.dmigrate.driver.TypeMapper",
                    "dev.dmigrate.driver.connection.PoolSettings",
                    // Phase C Schritt 12: pure Interfaces
                    "dev.dmigrate.driver.data.DataWriter",
                    "dev.dmigrate.driver.data.TableImportSession",
                    "dev.dmigrate.driver.data.SchemaSync",
                    // Schritt 13 entfernt diesen Exclude wieder,
                    // wenn SequenceAdjustment eigene Tests bekommt
                    "dev.dmigrate.driver.data.SequenceAdjustment",
                    "dev.dmigrate.driver.data.UnsupportedTriggerModeException",
                )
            }
        }
        verify {
            rule {
                minBound(90)
            }
        }
    }
}
```

Gesamtziel pro Modul: ≥ 90 % (Plan §8).

---

## 8. Build & Verifizierung

```bash
# 1. ImportOptions-Verschiebung verifizieren
docker build --target build \
  --build-arg GRADLE_TASKS=":adapters:driven:driver-common:test :adapters:driven:formats:test" \
  -t d-migrate:c12-prep .

# 2. Neue Interfaces + Tests in hexagon:ports / adapters:driven:driver-common
docker build --target build \
  --build-arg GRADLE_TASKS=":adapters:driven:driver-common:test" \
  -t d-migrate:c12 .

# 3. Vollständiger Build inkl. Coverage-Verification
docker build -t d-migrate:dev .
```

---

## 9. Abnahmekriterien

- [ ] `ImportOptions.kt` lebt in `hexagon:ports` (`dev.dmigrate.driver.data`)
- [ ] Alle bestehenden Tests grün nach der Verschiebung
- [ ] `DataWriter` Interface in `hexagon:ports`
- [ ] `TableImportSession` Interface mit dokumentierter State-Maschine (M1)
- [ ] `WriteResult` mit `require`-Validierung und `totalRows`
- [ ] `FinishTableResult` als Sealed Class (Success/PartialFailure)
- [ ] `TargetColumn` mit `jdbcType` (L15)
- [ ] `UnsupportedTriggerModeException`
- [ ] `SchemaSync` und `SequenceAdjustment` als Vorab-Definitionen
- [ ] State-Maschine-Vertragstests (≥ 22 Tests) grün
- [ ] WriteResult-Tests (≥ 10 Tests) grün
- [ ] FinishTableResult-Tests (≥ 4 Tests) grün
- [ ] TargetColumn-Tests (≥ 4 Tests) grün
- [ ] Kover-Excludes für pure Interfaces gesetzt
- [ ] Coverage ≥ 90 % für `adapters:driven:driver-common`
- [ ] Docker-Build (`docker build -t d-migrate:dev .`) ist grün

---

## 10. Offene Punkte

Keine. Die Design-Entscheidungen folgen direkt aus Plan §3.1.1. Die
ImportOptions-Verschiebung nach `hexagon:ports` ist die einzige Abweichung
vom Plan und sowohl architektonisch (Port-Schicht gemäß
`architecture.md` §1.2) als auch technisch (Zyklus-Vermeidung)
zwingend.
