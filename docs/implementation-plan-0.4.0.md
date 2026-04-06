# Implementierungsplan: Milestone 0.4.0 — Datenimport und inkrementelle Datenpfade

> Dieses Dokument beschreibt den konkreten Implementierungsplan für Milestone 0.4.0.
> Es dient als Review-Grundlage vor Beginn der Umsetzung.
>
> **Status**: Entwurf — vor Beginn von Phase A reviewen.

---

## 1. Ziel

Daten aus JSON-, YAML- und CSV-Dateien wieder in PostgreSQL, MySQL und SQLite
einlesen — chunkbasiert, transaktional, mit korrekter Nachführung von
Sequenzen/Identity-Spalten und einem klaren Trigger-Vertrag. Außerdem die
inkrementelle Marker-Spalten-Variante des Exports (LF-013), die im
Roadmap-Update von 0.9.0 nach 0.4.0 vorgezogen wurde. Der Delta-Import läuft
über idempotenten UPSERT (`--on-conflict update`) — siehe §6.12.2.

```
Datei → d-migrate data import --target <url|name> --source <path> --table <name>      → DB
DB    → d-migrate data export ... --since-column updated_at --since "<ts>"             → Datei (delta)
Datei → d-migrate data import ... --table <name> --on-conflict update                  → DB (delta, idempotent)
```

**Was gehört zu 0.4.0:**

- Lese-Pfad in `d-migrate-formats`: `DataChunkReader` für JSON, YAML, CSV mit
  echtem Streaming (kein Volltext-Parsen)
- `DataWriter`-Port in `d-migrate-driver-api` plus JDBC-Adapter für PostgreSQL,
  MySQL und SQLite (Batch-`INSERT` per `PreparedStatement.addBatch`)
- `StreamingImporter` in `d-migrate-streaming` (Glue Reader → Writer, mit
  Chunk-Transaktionen und konfigurierbarer Fehler-Politik)
- Sequence- und `AUTO_INCREMENT`-Reseeding pro Dialekt (siehe
  [`design-import-sequences-triggers.md`](./design-import-sequences-triggers.md))
- Trigger-Modi `fire`/`disable`/`strict` mit dialektspezifischer Umsetzung
- Schema-Validierung des Targets vor dem Import (über JDBC `ResultSetMetaData`,
  ohne den noch nicht existierenden `SchemaReader` aus 0.6.0). Das
  Target-Schema ist die **autoritative Quelle** für Spalten-Typen und
  -Reihenfolge — der Reader liefert höchstens die Header-Namen aus dem File.
- Encoding-Unterstützung für die Eingabedateien: BOM-basierte Auto-Detection
  nur für UTF-8 und UTF-16 BE/LE; alle anderen Encodings (ISO-8859-1,
  Windows-1252, UTF-32 etc.) ausschließlich über expliziten `--encoding`-Flag
- CLI-Kommando `d-migrate data import` (siehe `cli-spec.md` §6.2 — der
  Platzhalter wird konkretisiert)
- LF-013 inkrementeller **Export** mit `--since-column` und `--since`. Auf
  der Import-Seite gibt es bewusst **keinen** `--incremental`-Flag —
  inkrementelle Imports sind fachlich identisch zu idempotenten
  UPSERT-Imports und werden über `--on-conflict update` abgewickelt
  (siehe §6.12).
- `database.default_target` in `.d-migrate.yaml` wird aktiv (war in 0.3.0
  noch ignoriert, siehe Plan-0.3.0 §6.14)
- Round-Trip-Integration-Tests Export → Import → Vergleich (LN-043), inkl.
  inkrementellem Round-Trip
- Testcontainers-E2E für `data import` analog zu Phase F aus 0.3.0

**Was bewusst NICHT zu 0.4.0 gehört:**

- Checkpoint/Resume und `--resume` (LN-012 → 0.9.0)
- Atomare Rollbacks auf Checkpoint-Ebene (LN-013 → 1.0.0)
- Parallele Tabellenverarbeitung (LN-007 → 1.0.0)
- Schema-Reverse-Engineering (`schema reverse`, LF-004 → 0.6.0) — der
  Importer arbeitet ohne `SchemaReader` mit JDBC-Metadaten der Zieltabelle
- Auto-Discovery von Marker-Spalten für `--incremental` auf der Export-Seite
  (kommt in 0.6.0, sobald der `SchemaReader` `updated_at`-Konventionen
  erkennen kann); 0.4.0 fordert die Spalte explizit per CLI-Flag
- Automatische Encoding-Erkennung ohne BOM (chardet, ICU). Wer ISO-8859-1
  oder Windows-1252 importieren will, gibt `--encoding` explizit an.
- Atomarer Tabellen-Replace (`--replace-table`): TRUNCATE plus Import in
  einer Tabellen-Transaktion. Komplexer als chunkbasiert und braucht ein
  eigenes Transaktionsmodell — kommt in 0.5.0 oder später, falls Bedarf
  gemeldet wird. `--truncate` in 0.4.0 ist explizit ein nicht-atomarer
  Pre-Step (siehe §6.14).
- Replikations-/CDC-spezifische Importpfade
- Datenformat-Migration zwischen Versionen (kein `data migrate-format`)

---

## 2. Neue Module

Kein neues Gradle-Modul. Alle Erweiterungen finden in den existierenden Modulen
statt — die Architektur aus 0.3.0 trägt: `formats` bekommt Reader-Klassen
parallel zu den Writern, `driver-api` einen `DataWriter`-Port parallel zum
`DataReader`, `streaming` einen `StreamingImporter` parallel zum
`StreamingExporter`, `cli` ein neues `DataImportCommand` und einen
`--incremental`-Flow für `DataExportCommand`.

> **Wichtig**: Wir vermeiden bewusst ein neues Modul „d-migrate-import". Die
> bestehenden Module sind nach **Schicht** organisiert, nicht nach Read-/Write-
> Richtung. Ein separates Import-Modul würde die hexagonale Aufteilung
> aufweichen.

---

## 3. Bestehende Module — Änderungen

### 3.1 `d-migrate-driver-api`

#### 3.1.1 Neuer Port `DataWriter`

```kotlin
package dev.dmigrate.driver.data

interface DataWriter {
    val dialect: DatabaseDialect

    /**
     * Bereitet einen Tabellen-Import vor: prüft Spalten, baut die
     * PreparedStatement-INSERT-Vorlage und liefert eine [TableImportSession]
     * zurück, die der Caller pro Chunk benutzt und am Ende schließt.
     *
     * Borgt sich pro Aufruf eine Connection aus dem Pool (analog §6.18 aus
     * Plan 0.3.0). Die Connection wird in der Session gehalten und beim
     * `close()` zurückgegeben.
     *
     * @throws ImportSchemaMismatchException wenn die Spalten der DataChunks
     *   nicht zur Zieltabelle passen (siehe §6.4).
     */
    fun openTable(
        pool: ConnectionPool,
        table: String,
        columns: List<ColumnDescriptor>,
        options: ImportOptions,
    ): TableImportSession
}

interface TableImportSession : AutoCloseable {
    /** Schreibt einen Chunk im aktuellen Transaktionskontext. */
    fun write(chunk: DataChunk): WriteResult

    /**
     * Bestätigt den letzten geschriebenen Chunk und beginnt den nächsten.
     * Implementiert das Chunk-Transaktionsmodell aus §6.5.
     */
    fun commitChunk()

    /**
     * Verwirft den letzten geschriebenen Chunk und liefert eine
     * Abbruch-Information zurück. Idempotent.
     */
    fun rollbackChunk()

    /**
     * Schließt die Session: führt etwaige Sequence-/Identity-Nachführung
     * (§6.6) und die Trigger-Reaktivierung (§6.7) aus, gibt die Connection
     * an den Pool zurück. Idempotent.
     */
    override fun close()
}

data class WriteResult(
    val rowsInserted: Long,
    val rowsSkipped: Long,
    val rowsFailed: Long,
)
```

#### 3.1.2 Erweiterung der bestehenden Registries

`DataReaderRegistry` bekommt ein Pendant: entweder `DataWriterRegistry` als
eigene `object`-Registry, oder die bestehende Registry wird zu
`DataAdapterRegistry` umbenannt. Wir entscheiden uns für **getrennte
Registries** (siehe §6.10), um die Symmetrie zu Reader/Writer in `formats`
beizubehalten und die Bootstrap-Pfade pro Treiber unverändert zu lassen.

```kotlin
object DataWriterRegistry {
    private val writers = mutableMapOf<DatabaseDialect, DataWriter>()
    fun registerDataWriter(writer: DataWriter)
    fun dataWriter(dialect: DatabaseDialect): DataWriter
    fun clear()
}
```

Treiber-Bootstrap-Objects (`PostgresDriver`, `MysqlDriver`, `SqliteDriver`)
ergänzen ihre `register()`-Methode um den jeweiligen `*DataWriter`.

#### 3.1.3 Sequence- und Trigger-Hooks

```kotlin
package dev.dmigrate.driver.data

/**
 * Dialekt-spezifische Operationen, die der StreamingImporter rund um den
 * Schreib-Zyklus aufruft. Der DataWriter wird per `getSchemaSync()`
 * gefragt, weil die konkrete Implementierung dialektabhängig ist.
 *
 * Plan §6.6 / §6.7 / design-import-sequences-triggers.md.
 */
interface SchemaSync {
    /**
     * Wird nach Abschluss aller Chunks einer Tabelle aufgerufen. Der
     * Implementierer ermittelt den höchsten importierten Wert der
     * Identity-/Sequence-Spalte und führt die Sequence darauf nach.
     *
     * @return Beschreibung der durchgeführten Anpassungen (für den
     *   Import-Report)
     */
    fun reseedGenerators(
        conn: Connection,
        table: String,
        importedColumns: List<ColumnDescriptor>,
    ): List<SequenceAdjustment>

    /**
     * Vor dem ersten Chunk einer Tabelle aufgerufen, wenn `--trigger-mode`
     * = `disable` ist. Wirft `UnsupportedTriggerModeException`, wenn der
     * Dialekt das nicht sicher unterstützt (Plan §6.7 / Design-Doc §6.2).
     */
    fun disableTriggers(conn: Connection, table: String)

    /**
     * Reaktiviert die in [disableTriggers] deaktivierten Trigger. Auch
     * im Fehlerpfad in `close()` aufgerufen — Ausführung muss idempotent
     * sein und Fehler hier sind harte Fehler (Design-Doc §7.3).
     */
    fun enableTriggers(conn: Connection, table: String)
}

data class SequenceAdjustment(
    val table: String,
    val column: String,
    val sequenceName: String?,    // PG: explizit; MySQL/SQLite: null
    val newValue: Long,
)
```

`DataWriter` erhält eine zusätzliche Methode `schemaSync(): SchemaSync`. Die
Default-Implementierung liefert eine No-Op-Variante; jeder Treiber
überschreibt sie mit seiner echten Logik.

### 3.2 `d-migrate-driver-postgresql`

```
d-migrate-driver-postgresql/src/main/kotlin/dev/dmigrate/driver/postgresql/
├── PostgresDataWriter.kt       # JDBC Batch-INSERT, COPY-Pfad als Optimierung in 1.0.0
├── PostgresSchemaSync.kt       # setval(), session_replication_role NICHT — siehe §6.7
└── PostgresDriver.kt           # erweitert um registerDataWriter
```

PG-spezifika:

- **Batch-INSERT**: `INSERT INTO "schema"."tab" ("c1", "c2") VALUES (?, ?)`
  mit `addBatch()` pro Row und `executeBatch()` einmal pro Chunk.
  COPY-FROM ist ~10× schneller, kommt aber erst in 1.0.0 als Performance-Pfad.
- **Sequence-Reseeding**: pro Identity-/SERIAL-Spalte die zugehörige Sequence
  via `pg_get_serial_sequence(quoted_table, column)` ermitteln und mit
  `setval(sequence, GREATEST(MAX(column), nextval(sequence)-1), true)`
  anheben. Der `setval`-Trick mit `GREATEST` schützt vor Race-Conditions
  zwischen Import-Ende und neuer Sequence-Vergabe.
- **Trigger-Disable** (User-Trigger): `ALTER TABLE … DISABLE TRIGGER USER`
  pro Tabelle. `session_replication_role = replica` ist nicht zulässig
  (siehe §6.7) — zu breite Wirkung, Sicherheitsrisiko.
- **FK-Constraints sind NICHT generisch deaktivierbar** (F42, siehe §6.8.1).
  `--disable-fk-checks` auf PG → Exit 2 mit Hinweis auf `--schema` (topo-Sort)
  oder `DEFERRABLE`-Constraints. PG bekommt damit weder
  `ALTER TABLE … DISABLE TRIGGER ALL` (Superuser) noch
  `session_replication_role` (zu breit, oft auf Managed-Diensten gesperrt).
- **`autoCommit=false`** für Chunk-Transaktionen (analog zu Read-Pfad
  Plan-0.3.0 §6.12).

### 3.3 `d-migrate-driver-mysql`

```
d-migrate-driver-mysql/src/main/kotlin/dev/dmigrate/driver/mysql/
├── MysqlDataWriter.kt
├── MysqlSchemaSync.kt
└── MysqlDriver.kt
```

MySQL-spezifika:

- **Batch-INSERT** mit `rewriteBatchedStatements=true`-Hint im JDBC-URL
  (vom `MysqlJdbcUrlBuilder` bereits gesetzt — Verifikation in Phase B).
- **AUTO_INCREMENT-Reseeding**: `ALTER TABLE \`table\` AUTO_INCREMENT = N`
  mit `N = MAX(autoinc_column) + 1`. Dialect-Quote ist Backtick.
- **Trigger-Disable**: in 0.4.0 **nicht generisch unterstützt**. Versuch,
  Trigger zu deaktivieren, wirft `UnsupportedTriggerModeException` mit
  Verweis auf Design-Doc §6.2. Default `fire` läuft normal.
- **Foreign-Key-Checks**: optional via `SET FOREIGN_KEY_CHECKS=0` für die
  Session — nur wenn `--disable-fk-checks` gesetzt ist (siehe §6.8).

### 3.4 `d-migrate-driver-sqlite`

```
d-migrate-driver-sqlite/src/main/kotlin/dev/dmigrate/driver/sqlite/
├── SqliteDataWriter.kt
├── SqliteSchemaSync.kt
└── SqliteDriver.kt
```

SQLite-spezifika:

- **Batch-INSERT** mit `addBatch()`. SQLite-jdbc unterstützt das nativ und
  ist für die typischen Importgrößen ausreichend schnell.
- **AUTOINCREMENT-Reseeding**: `INSERT OR REPLACE INTO sqlite_sequence
  (name, seq) VALUES ('table', N)` mit `N = MAX(rowid)`. Nur relevant für
  Tabellen mit `INTEGER PRIMARY KEY AUTOINCREMENT`. Für „normale"
  `INTEGER PRIMARY KEY`-Tabellen ist die Folge-ID-Vergabe nicht
  persistiert — kein Reseeding nötig (Design-Doc §6.3 offene Frage:
  geklärt → kein Eingriff).
- **Trigger-Disable**: nicht unterstützt (Design-Doc §6.3). `disable` →
  `UnsupportedTriggerModeException`.
- **Foreign-Key-Checks**: temporär `PRAGMA foreign_keys = OFF` während des
  Imports, am Ende `PRAGMA foreign_keys = ON` zurückgesetzt — nur mit
  `--disable-fk-checks`.

### 3.5 `d-migrate-formats`

#### 3.5.1 Reader-Pendant zum Writer-Trio

```
d-migrate-formats/src/main/kotlin/dev/dmigrate/format/data/
├── DataChunkReader.kt              # neu, Interface
├── DataChunkReaderFactory.kt       # neu, Interface
├── DefaultDataChunkReaderFactory.kt# neu, Implementierung
├── ImportOptions.kt                # neu, parallel zu ExportOptions
├── ValueDeserializer.kt            # neu, Inverse zu ValueSerializer
├── EncodingDetector.kt             # neu, BOM-Sniff (§6.9)
├── json/JsonChunkReader.kt         # neu, DSL-JSON Streaming
├── yaml/YamlChunkReader.kt         # neu, SnakeYAML Engine Streaming
└── csv/CsvChunkReader.kt           # neu, uniVocity CsvParser
```

```kotlin
interface DataChunkReader : AutoCloseable {
    /**
     * Liest die nächsten bis zu `chunkSize` Rows. Liefert `null`, wenn die
     * Eingabe vollständig konsumiert wurde.
     *
     * **Anders als beim Reader-Vertrag in 0.3.0** (Plan §6.17) gibt es hier
     * KEINE Pflicht, einen "Empty-Chunk mit Spalten" zu emittieren: bei
     * leeren selbstbeschreibenden Eingaben (`[]` in JSON/YAML, leere
     * CSV-Datei mit nur einer Header-Zeile) darf der Reader sofort `null`
     * liefern. Spalten-Metadaten holt der [StreamingImporter] aus dem
     * Zielschema (siehe §6.4) — der Reader ist NICHT die autoritative
     * Quelle dafür.
     *
     * Die im zurückgelieferten [DataChunk] enthaltenen `columns` sind
     * file-derived (nur Namen, `sqlTypeName = null`) und werden vom
     * Importer nur für Header-Validierung verwendet.
     */
    fun nextChunk(chunkSize: Int): DataChunk?

    /**
     * Optionale, file-derived Header-Spaltennamen. Wird vom [StreamingImporter]
     * benutzt, um Header-zu-Target-Mapping zu validieren (verfehlte Spalten
     * → Exit 3 mit klarer Meldung). Spalten-**Typen** kommen IMMER aus dem
     * Zielschema, nie aus dem Reader.
     *
     * Wird nach dem ersten erfolgreichen [nextChunk]-Aufruf populiert. Vor
     * dem ersten Aufruf bzw. bei Eingaben ohne Header-Information
     * (`csvNoHeader = true` oder leeres JSON/YAML-Array) ist der Wert `null`.
     */
    val headerColumns: List<String>?
}

interface DataChunkReaderFactory {
    fun create(
        format: DataExportFormat,
        input: InputStream,
        table: String,
        options: ImportOptions = ImportOptions(),
    ): DataChunkReader
}
```

**Streaming-Vertrag pro Format**:

| Format | Streaming-Mechanik | `headerColumns`-Quelle |
|---|---|---|
| JSON | DSL-JSON's pull-Parser (`JsonReader.next()`/`getNextToken()`) — wir parsen das Top-Level-Array tokenweise und produzieren `DataChunk`s zu je `chunkSize` Objekten. Kein Buffer-Aufbau über die Chunk-Größe hinaus. | Schlüssel des ersten Objekts; bei `[]` → `null` |
| YAML | SnakeYAML Engine `Parse` API (Event-basiert). Der bestehende `Dump`-Pfad ist Tree-basiert; für das Lesen benutzen wir den Event-Stream-Pfad, der auch sehr große Sequenzen verträgt. | Schlüssel des ersten Mappings; bei `[]` → `null` |
| CSV | uniVocity `CsvParser` mit `IterableResult` — schon nativ chunked. Wir wrappen das in unseren `DataChunkReader`-Vertrag. | Header-Zeile, sofern nicht `csvNoHeader = true` |

#### 3.5.2 `ValueDeserializer`

Inverse zur `ValueSerializer`-Mapping-Tabelle aus Plan-0.3.0 §6.4.1. Für jeden
Eingabe-Typ aus den drei Formaten bestimmt sie den entsprechenden Java-Wert,
den der `DataWriter` per `PreparedStatement.setObject(...)` schreiben kann.

| Format-Eingabe | Spalten-Hint (JDBC-Typ aus `ResultSetMetaData`) | Resultat |
|---|---|---|
| JSON String | `VARCHAR`/`TEXT`/`CLOB` | String |
| JSON String | `DATE` | `LocalDate.parse` |
| JSON String | `TIMESTAMP` | `LocalDateTime.parse` |
| JSON String | `NUMERIC` mit `precision > 18` | `BigDecimal` |
| JSON Number (integer) | `INTEGER`/`BIGINT` | Long |
| JSON Number (decimal) | `NUMERIC`/`DECIMAL` | `BigDecimal` (toPlainString-Roundtrip) |
| JSON `null` | beliebig | SQL NULL |
| JSON Array | `ARRAY` | `java.sql.Array` via `Connection.createArrayOf()` |
| YAML Block-Sequence | `ARRAY` | wie JSON Array |
| CSV `csvNullString` | beliebig | SQL NULL |
| CSV alles andere | bestimmt aus Spalten-Hint via Try-Parse-Kette | typisierter Wert oder Fehler |

CSV ist der unangenehmste Fall, weil alle Werte als String reinkommen — die
Typ-Inferenz hängt **vollständig** vom JDBC-Spalten-Hint ab. Wir holen den
Hint via `SELECT * FROM target LIMIT 0` einmal vor dem Import (siehe §6.4).

### 3.6 `d-migrate-streaming`

#### 3.6.1 `StreamingImporter`

```kotlin
class StreamingImporter(
    private val readerFactory: DataChunkReaderFactory,
    private val writerRegistry: DataWriterRegistry,
) {
    fun import(
        pool: ConnectionPool,
        input: ImportInput,
        format: DataExportFormat,
        options: ImportOptions = ImportOptions(),
        config: PipelineConfig = PipelineConfig(),
    ): ImportResult
}
```

Das Spiegelbild von `StreamingExporter`. Die wichtigen Vertragspunkte:

- Pro Tabelle: `reader.nextChunk()` → `session.write(chunk)` → bei Erfolg
  `session.commitChunk()`, bei Fehler `session.rollbackChunk()` plus
  `--on-error`-Auswertung
- Pro Tabelle wird genau eine `TableImportSession` über alle Chunks hinweg
  benutzt — die `SchemaSync.reseedGenerators(...)` läuft in `session.close()`
- Multi-Tabellen-Imports laufen sequentiell; Parallelisierung ist 1.0.0
- `ImportInput` ist eine sealed class analog zu `ExportOutput`. Das CLI muss
  vor der Konstruktion entscheiden, in welche Zieltabelle die Daten gehören
  — `--table` (singular, Pflicht) für Stdin und SingleFile, `--tables`
  (plural, optional) als Subset-Filter für Directory:

```kotlin
sealed class ImportInput {
    /** Eine Tabelle, Daten von Stdin. CLI-Pflicht: `--table <name>`. */
    data class Stdin(val table: String) : ImportInput()

    /**
     * Eine Tabelle, Daten aus einer Datei. CLI-Pflicht: `--table <name>`.
     * Format wird aus der Dateiendung oder `--format` abgeleitet (siehe §6.3).
     * Kein automatisches Mapping vom Dateinamen auf den Tabellennamen — das
     * wäre eine Convenience-Erweiterung in einem späteren Milestone.
     */
    data class SingleFile(val table: String, val path: Path) : ImportInput()

    /**
     * Mehrere Tabellen aus einem Verzeichnis. Die Tabellen-zu-Datei-Zuordnung
     * folgt dem Schema `<table>.<format>` (siehe `ExportOutput.fileNameFor`
     * aus 0.3.0 Phase D). `tableFilter` ist optional und beschränkt den
     * Import auf eine Untermenge der gefundenen Dateien.
     */
    data class Directory(
        val path: Path,
        val tableFilter: List<String>? = null,
    ) : ImportInput()
}
```

Diese Trennung löst die Mehrdeutigkeit, die in der ersten Plan-Fassung
`--tables` als Multipurpose-Option für Single- und Multi-Quellen behandelt
hat. Für `Stdin` und `SingleFile` gibt es **immer genau eine** Zieltabelle
und sie muss vom Aufrufer benannt werden.

#### 3.6.2 `ImportResult` und `TableImportSummary`

Spiegelbild zu `ExportResult`. Zusätzlich pro Tabelle:

- `rowsInserted`, `rowsSkipped`, `rowsFailed`
- `sequenceAdjustments: List<SequenceAdjustment>` (für den Report)
- `triggerMode: TriggerMode` (welcher Modus tatsächlich angewendet wurde)
- `error: String?`

### 3.7 `d-migrate-cli`

#### 3.7.1 Neues `DataImportCommand`

```kotlin
class DataImportCommand : CliktCommand(name = "import") {
    // F47: --target ist NICHT hart .required() — der NamedConnectionResolver
    // löst es zusammen mit `database.default_target` aus .d-migrate.yaml auf.
    // Pflicht ist es nur, wenn weder --target noch ein default_target greift
    // (siehe §3.7.3 Auflösungstabelle).
    val target by option("--target")
    val source by option("--source").required()                        // Pfad oder "-" für Stdin
    val format by option("--format").choice("json", "yaml", "csv")     // Default: aus Endung
    // F40: Tabellen-Auflösung ist explizit mehrere Pfade:
    val table by option("--table")                                     // Pflicht für Stdin/SingleFile
    val tables by option("--tables").split(",")                        // Subset-Filter für Directory
    val schema by option("--schema").path()                            // Optional Pre-Validation
    val onError by option("--on-error").choice("abort", "skip", "log").default("abort")
    val onConflict by option("--on-conflict").choice("abort", "skip", "update").default("abort")
    val triggerMode by option("--trigger-mode").choice("fire", "disable", "strict").default("fire")
    val truncate by option("--truncate").flag()                        // §6.14 — nicht atomar
    val disableFkChecks by option("--disable-fk-checks").flag()        // §6.8 — PG nicht supported
    val reseedSequences by option("--reseed-sequences").flag(default = true)
    val encoding by option("--encoding").default("auto")               // Default: BOM-Detect für UTF-*
    val chunkSize by option("--chunk-size").int().default(10_000)
}
```

**`--table` vs `--tables` Auflösungsregeln** (F40):

| `--source` | `--table` | `--tables` | Resultat |
|---|---|---|---|
| `-` (Stdin) | gesetzt | leer | `ImportInput.Stdin(table)` |
| `-` (Stdin) | leer | beliebig | **Exit 2** — Stdin braucht `--table` |
| `-` (Stdin) | gesetzt | gesetzt | **Exit 2** — `--tables` ist nur für Directory |
| Datei | gesetzt | leer | `ImportInput.SingleFile(table, path)` |
| Datei | leer | leer | **Exit 2** — SingleFile braucht `--table` |
| Datei | gesetzt | gesetzt | **Exit 2** — `--tables` ist nur für Directory |
| Verzeichnis | leer | leer | `ImportInput.Directory(path, null)` (alle Dateien) |
| Verzeichnis | leer | gesetzt | `ImportInput.Directory(path, tables)` (Subset-Filter) |
| Verzeichnis | gesetzt | beliebig | **Exit 2** — `--table` ist nur für Single/Stdin |

> **Bewusste Entscheidung**: keine automatische Ableitung der Zieltabelle
> aus dem Dateinamen in 0.4.0. Sowohl `users.json` als auch
> `dump-2026-04.json` sehen aus Dateisystem-Perspektive gleich legitim aus,
> und ein impliziter Mapping-Algorithmus würde Edge-Cases produzieren
> (Hyphens, Umlaute, fehlende Endung). User schreibt explizit
> `--table users` — kostet einen Token, gibt dafür einen klaren Fehler statt
> einer stillen Fehlinterpretation. Eine `--auto-table-from-filename`-
> Convenience kann später additiv kommen.

**Bewusst nicht enthalten**:

- `--incremental` / `--since-column` — auf der Import-Seite gibt es keinen
  inkrementellen Modus (F44). „Inkrementell importieren" ist fachlich
  identisch zu „idempotent UPSERT-en" und wird ausschließlich über
  `--on-conflict update` abgebildet (siehe §6.12).
- `--resume` — Checkpoint-Resume kommt mit LN-012 in 0.9.0.

Registrierung: `DataCommand` aus 0.3.0 bekommt zusätzlich
`subcommands(DataExportCommand(), DataImportCommand())`.

#### 3.7.2 Erweiterung von `DataExportCommand` für LF-013

```kotlin
val sinceColumn by option("--since-column")
val since by option("--since")     // ISO-Datum/Datetime/Number — abhängig vom Spalten-Typ
val incremental by option("--incremental").flag()
```

`--incremental` ist ein „Convenience-Schalter": wenn gesetzt, müssen
`--since-column` und `--since` ebenfalls gesetzt sein, sonst Exit 2. Der
Filter wird intern als `DataFilter.WhereClause("\"<col>\" >= ?")` gebaut —
mit **Parameter-Binding** statt String-Konkatenation, weil hier ein vom
Nutzer kommender Wert in ein SQL-Statement landet (siehe §6.7 im 0.3.0-Plan
hat `--filter` die Trust-Boundary „lokale Shell" akzeptiert; für `--since`
ziehen wir die Grenze enger und parametrisieren).

> **Konsequenz**: `DataFilter.WhereClause` muss um eine optionale
> `params: List<Any?>`-Variante erweitert werden, oder es gibt ein neues
> `DataFilter.ParameterizedClause(sql, params)`. Wir entscheiden uns in
> Phase A für `ParameterizedClause`, weil das den existierenden
> `WhereClause`-Vertrag nicht bricht.

#### 3.7.3 `NamedConnectionResolver` aktiviert `database.default_target`

In 0.3.0 wurde `default_source` gelesen aber für `data export` ignoriert.
Für 0.4.0 wird `default_target` aktiv für `data import` — die Quelle bleibt
Pflichtparameter (eine Datei), das Ziel kann aus `default_target` kommen.

**Auflösungstabelle (autoritativ — F47)**: Das CLI-Snippet in §3.7.1 macht
`--target` bewusst **nicht** mit `.required()` pflichtig. Stattdessen
entscheidet diese Tabelle, ob ein effektives Ziel bestimmbar ist:

| `--target` | `default_target` in `.d-migrate.yaml` | Verhalten |
|---|---|---|
| gesetzt | irrelevant | wird benutzt |
| nicht gesetzt | gesetzt, ist URL | URL wird benutzt |
| nicht gesetzt | gesetzt, ist Connection-Name | über `connections`-Map auflösen |
| nicht gesetzt | nicht gesetzt | **Exit 2** mit Meldung „`--target` ist Pflicht, wenn `database.default_target` nicht gesetzt ist" |

`DataImportCommand.run()` ruft den `NamedConnectionResolver` mit dem
optionalen `target`-Wert; der Resolver wird in 0.4.0 um eine
`resolveTarget(target: String?)`-Methode erweitert, die die obige Tabelle
implementiert. Damit ist der „Pflicht"-Status von `--target` an genau einer
Stelle festgelegt — der Auflösungstabelle — und nicht doppelt im
Clikt-Snippet plus im Fließtext.

### 3.8 `d-migrate-core`

Daten-Modell aus 0.3.0 bleibt unverändert. Wir ergänzen einen neuen Filter-Typ:

```kotlin
sealed class DataFilter {
    data class WhereClause(val sql: String) : DataFilter()
    data class ColumnSubset(val columns: List<String>) : DataFilter()
    data class Compound(val parts: List<DataFilter>) : DataFilter()
    /** Neu in 0.4.0: parametrisierte WHERE-Klausel für --since (LF-013). */
    data class ParameterizedClause(val sql: String, val params: List<Any?>) : DataFilter()
}
```

`AbstractJdbcDataReader.buildSelectSql` und `prepareStatement` werden so
erweitert, dass `ParameterizedClause`-Parameter über `setObject(idx, value)`
gebunden werden, statt in den SQL-String konkateniert. Das ist auch der
einzige Pfad, der `setObject(...)` für Daten-Reader braucht — der
Volltext-Export benutzt `setObject` nicht.

### 3.9 `gradle.properties` und `build.gradle.kts`

Keine neuen externen Dependencies. JSON/YAML/CSV-Lib-Versionen aus 0.3.0
reichen aus — die Reader-Pfade benutzen die gleichen Bibliotheken.

```properties
# Keine neuen Versions-Properties — alle Bibliotheken aus 0.3.0 wiederverwendet.
```

---

## 4. Implementierungsreihenfolge

### Phase A: Reader-Vertrag und Daten-Modell-Erweiterungen

1. `DataChunkReader`-Interface + `DataChunkReaderFactory`
2. `ImportOptions` (parallel zu `ExportOptions`)
3. `EncodingDetector` mit BOM-Sniff nur für UTF-8/UTF-16 BE/LE; alle anderen
   Encodings (ISO-8859-1, Windows-1252, …) ausschließlich über
   `--encoding`-Fallback (siehe §6.9)
4. `ValueDeserializer` mit der Mapping-Tabelle aus §3.5.2 plus Tests
5. `DataFilter.ParameterizedClause` und Erweiterung von
   `AbstractJdbcDataReader` für parametrisierte WHERE-Klauseln

### Phase B: Format-Reader

6. `JsonChunkReader` mit DSL-JSON Pull-Parser, Streaming-Tests gegen
   ein 100-MB-Fixture (kein Buffer-Aufbau über Chunk-Größe hinaus)
7. `YamlChunkReader` mit SnakeYAML Engine Event-API
8. `CsvChunkReader` mit uniVocity `CsvParser`, inkl. Header-Auflösung
9. `DefaultDataChunkReaderFactory`
10. Golden-Master-Reader-Tests pro Format mit Round-Trip gegen die
    Phase-D-Writer aus 0.3.0 (Schreiben → Lesen → Vergleich)

### Phase C: DataWriter-Port und JDBC-Treiber

11. `DataWriter`/`TableImportSession` Interfaces in `driver-api`
12. `SchemaSync` Interface + `SequenceAdjustment`
13. `DataWriterRegistry` (object) mit `clear()` für Tests
14. `PostgresDataWriter` + `PostgresSchemaSync` (setval, ALTER TABLE
    DISABLE TRIGGER USER)
15. `MysqlDataWriter` + `MysqlSchemaSync` (ALTER TABLE … AUTO_INCREMENT,
    `disable` → UnsupportedTriggerModeException)
16. `SqliteDataWriter` + `SqliteSchemaSync` (sqlite_sequence, `disable`
    nicht unterstützt)
17. Treiber-Bootstrap-Objects um `registerDataWriter` ergänzt

### Phase D: StreamingImporter

18. `StreamingImporter` in `d-migrate-streaming`
19. `ImportInput` sealed class (Stdin / SingleFile / Directory)
20. `ImportResult` + `TableImportSummary`
21. Chunk-Transaktionsmodell (§6.5) mit `--on-error`-Politik
22. Round-Trip-Tests gegen SQLite (kein Container nötig)

### Phase E: CLI-Integration

23. `DataImportCommand` mit allen Flags aus §3.7.1
24. `DataExportCommand` Erweiterung um `--incremental`/`--since-column`/
    `--since` (Phase E aus 0.3.0 + dieser Schritt)
25. `NamedConnectionResolver` aktiviert `default_target`
26. Exit-Code-Mapping für `data import` (§6.11)
27. CLI-Integration-Tests gegen SQLite — JSON/YAML/CSV-Round-Trips,
    `--truncate`, `--on-conflict update`, `--trigger-mode disable`
    (mit erwarteter UnsupportedTriggerModeException für SQLite)

### Phase F: End-to-End-Tests mit Testcontainers

28. `DataImportE2EPostgresTest` analog zur Export-E2E aus 0.3.0:
    Schema anlegen, Datei einlesen, Daten verifizieren, Sequence-Reseeding
    via `nextval()` checken
29. `DataImportE2EMysqlTest` mit `AUTO_INCREMENT`-Verifikation
30. SQLite-E2E direkt ohne Container
31. Inkrementeller Round-Trip-E2E: initial export → 2× delta export →
    delta import → Vergleich
32. `integration.yml` läuft schon generisch (siehe F39 aus 0.3.0) — keine
    Workflow-Anpassung nötig

---

## 5. Abhängigkeiten zwischen Modulen

```
d-migrate-core
└── (unverändert)

d-migrate-driver-api
├── api(d-migrate-core)
├── api("com.zaxxer:HikariCP")
└── neu: DataWriter / TableImportSession / SchemaSync / DataWriterRegistry

d-migrate-driver-postgresql      # implementation(driver-api) + JDBC
d-migrate-driver-mysql           # dito
d-migrate-driver-sqlite          # dito

d-migrate-formats
├── implementation(d-migrate-core)
├── implementation("com.dslplatform:dsl-json-java8")     # bereits aus 0.3.0
├── implementation("com.univocity:univocity-parsers")    # bereits aus 0.3.0
└── implementation("org.snakeyaml:snakeyaml-engine")     # bereits aus 0.3.0

d-migrate-streaming
├── api(d-migrate-core)
├── api(d-migrate-driver-api)
├── api(d-migrate-formats)
└── neu: StreamingImporter, ImportInput, ImportResult

d-migrate-cli
├── implementation(d-migrate-streaming)
└── alle Treiber wie in 0.3.0
```

Keine neuen Modul-Kanten. Die hexagonale Architektur bleibt: `formats` ist
weiterhin JDBC-frei, `streaming` koordiniert nur Reader und Writer.

---

## 6. Zentrale Design-Entscheidungen

### 6.1 Symmetrie zu 0.3.0 als oberste Designregel

Wo immer möglich, folgt der Import-Pfad dem Export-Pfad symmetrisch:

| Export (0.3.0) | Import (0.4.0) |
|---|---|
| `DataReader` | `DataWriter` |
| `ChunkSequence` | `TableImportSession` |
| `DataChunkWriter` | `DataChunkReader` |
| `DataChunkWriterFactory` | `DataChunkReaderFactory` |
| `ExportOutput` | `ImportInput` |
| `ExportOptions` | `ImportOptions` |
| `ExportResult` / `TableExportSummary` | `ImportResult` / `TableImportSummary` |
| `StreamingExporter` | `StreamingImporter` |
| `DataExportCommand` | `DataImportCommand` |
| `DataReaderRegistry` | `DataWriterRegistry` |

Damit ist jede Architekturfrage, die in 0.3.0 schon entschieden wurde,
automatisch auch hier entschieden — vor allem §6.18 Connection-Ownership
(Pool durchgereicht), §6.13 treiberinterne Tuning-Konstanten und §6.16
Tag-basierte Testcontainers-Trennung. Wir referenzieren den 0.3.0-Plan
explizit, statt die Entscheidungen zu wiederholen.

### 6.2 Streaming statt Buffer beim Lesen

Die Reader-Implementierungen MÜSSEN echtes Streaming machen, nicht
„parse-then-iterate". Konkret:

- **JSON**: DSL-JSON's `JsonReader` über `InputStream`. Wir lesen die
  Top-Level-`[`-Klammer, dann pro `getNextToken()`-Iteration ein Objekt,
  bauen daraus eine `Array<Any?>`-Row und sammeln `chunkSize` Rows zu einem
  `DataChunk`. Wenn `]` erreicht ist → Stream zu Ende.
- **YAML**: SnakeYAML Engine `Parse(LoadSettings)` liefert einen
  `Iterable<Event>`. Wir tracken den Document-/Sequence-/Mapping-Stack und
  emittieren beim Schließen jedes Top-Level-Mappings eine Row.
- **CSV**: uniVocity ist nativ chunk-fähig — nur ein Wrapper.

**Test-Vertrag**: Pro Reader gibt es einen Spec-Test, der eine 100k-Row-
Datei liest und garantiert, dass die Heap-Auslastung nie deutlich über die
Chunk-Größe steigt (`Runtime.getRuntime().freeMemory()`-Sample reicht für
0.4.0 — echtes Heap-Profiling kommt in 0.5.0/1.0.0).

### 6.3 Keine Auto-Format-Detection ohne Endung

Wenn `--format` nicht gesetzt ist, leitet das CLI das Format aus der
Dateiendung ab (`.json`, `.yaml`/`.yml`, `.csv`). Bei Stdin oder einer Datei
ohne erkennbare Endung → Exit 2 mit klarer Meldung. **Keine** Content-Sniffing-
Heuristik (zu viele Edge-Cases, falsche Positivmeldungen bei JSON-in-CSV-
Strings etc.).

### 6.4 Schema-Validierung ohne SchemaReader

Der `SchemaReader` (LF-004) kommt erst in 0.6.0. Bis dahin ist das **Target-
Schema die einzige autoritative Quelle** für Spalten-Typen und -Reihenfolge
beim Import. `DataWriter.openTable()` holt die Spalten der Zieltabelle über

```sql
SELECT * FROM "schema"."table" WHERE 1=0
```

und liest `ResultSetMetaData` aus. Daraus entsteht die
`List<ColumnDescriptor>`, die für PreparedStatement-Bindings, Type-Hints
beim `ValueDeserializer` und für die Aufteilung der Insert-Spalten benutzt
wird.

**Was der Reader beiträgt**: nur die `headerColumns: List<String>?` aus
§3.5.1 — eine Liste von Spaltennamen, die das File explizit kennt. Daraus
entsteht eine **Header-Validierung**, kein Typ-System:

1. Wenn `headerColumns` gesetzt ist (CSV mit Header / JSON oder YAML mit
   mindestens einem Objekt), muss jeder Header-Name eine namensgleiche Spalte
   in der Zieltabelle haben. (Zusätzliche Spalten im Target sind erlaubt
   und bekommen DEFAULT-Werte oder NULL.)
2. Wenn `headerColumns` `null` ist (leeres JSON-/YAML-Array, oder
   `csvNoHeader = true`), wird die Header-Validierung übersprungen — dann
   muss die Reihenfolge der `Array<Any?>`-Werte im Chunk implizit der
   Spalten-Reihenfolge der Zieltabelle entsprechen. Bei CSV ohne Header
   ist das die einzige Möglichkeit; bei leerem JSON/YAML gibt es schlicht
   keine Rows zu importieren — der Import endet mit `0 rows inserted`.
3. JDBC-Typkompatibilität wird **nur** als Warnung geprüft, nicht als
   Fehler — der `ValueDeserializer` versucht den Cast und fällt sonst auf
   die `--on-error`-Politik zurück.
4. Wenn `--schema <path>` gesetzt ist, wird zusätzlich gegen die neutrale
   Schema-Definition aus 0.1.0/0.2.0 validiert: Spaltennamen, Nullability,
   Constraints (siehe `SchemaValidator` aus core). Diese Pre-Validierung
   ist optional, weil viele Importe gegen DBs laufen, deren Schema
   nicht in einem `.yaml` versioniert ist.

> **F43-Konsequenz**: Der Reader liefert NIE einen `ColumnDescriptor` mit
> `sqlTypeName` — diese Information existiert in den drei Format-Dateien
> nicht. Alle Typ-bezogenen Entscheidungen (Casting, Bind-Index,
> Nullability-Check) hängen am Target-Schema, nie am File.

### 6.5 Chunk-Transaktionsmodell

Pro Tabelle eine Connection mit `autoCommit=false`. Pro Chunk:

```
session.write(chunk)            -- batch Insert
  ├── Erfolg → conn.commit()    -- chunk persistiert
  └── Fehler → conn.rollback()  -- chunk verworfen
                ├── --on-error abort → Tabellen-Import-Abbruch, Exit 5
                ├── --on-error skip  → next Chunk
                └── --on-error log   → next Chunk + im Report vermerken
```

**Wichtig**: Der vorherige Chunk bleibt persistiert. Kein „alles oder
nichts" auf Tabellen-Ebene — das wäre ein Checkpoint/Resume-Problem (LN-012,
0.9.0). Anwender, die atomare Importe brauchen, müssen `--chunk-size 0`
setzen (= ein Chunk pro Tabelle), das ist in 0.4.0 nicht wirklich sinnvoll
für große Tabellen, aber legitim für Test-Daten.

### 6.6 Sequence-Reseeding-Trigger

Nach dem letzten erfolgreich committed Chunk pro Tabelle (in
`session.close()`):

1. Pro Spalte aus `importedColumns` prüfen: ist sie eine Generator-Spalte
   im Ziel? (PG: `pg_get_serial_sequence`; MySQL: Spalten-Metadata
   `EXTRA = 'auto_increment'`; SQLite: `INTEGER PRIMARY KEY AUTOINCREMENT`
   in `sqlite_master`)
2. Wenn ja: höchsten importierten Wert ermitteln (`SELECT MAX(col) FROM
   target` mit der gleichen Connection — die Statistik-Lock ist akzeptabel,
   weil wir noch in der Tabellen-Transaktion sind)
3. Sequence/AUTO_INCREMENT/sqlite_sequence auf `MAX + 1` anheben
4. Ein `SequenceAdjustment` ins `ImportResult` aufnehmen

**Abschalten**: `--reseed-sequences=false` überspringt Schritt 1–3
komplett. Default ist `true`. Begründung: stilles Auslassen wäre genau die
„gefährlichste Klasse" aus dem Design-Doc §11.

### 6.7 Trigger-Modi

Direkter Übernahme des Vertrags aus `design-import-sequences-triggers.md`
§5.1, mit folgenden Konkretisierungen:

| Modus | PG | MySQL | SQLite |
|---|---|---|---|
| `fire` (Default) | nativ | nativ | nativ |
| `disable` | `ALTER TABLE ... DISABLE TRIGGER USER` pro Tabelle | **nicht unterstützt** → Exit 2 | **nicht unterstützt** → Exit 2 |
| `strict` | Tabelle hat Trigger? → Exit 2 mit Hinweis | dito | dito |

`disable` ist **explizit nicht** über `session_replication_role = replica`
implementiert (zu breit, Sicherheitsrisiko). Pro Tabelle `ALTER TABLE`
ist deutlich enger und reverskompatibel.

`strict` benutzt `pg_trigger`/`information_schema.triggers`/`sqlite_master`
um zu prüfen, ob die Tabelle Trigger hat — und bricht ab, wenn ja.

### 6.8 Foreign-Key-Checks während Import

Multi-Tabellen-Imports mit Fremdschlüsseln scheitern oft am Lade-Reihenfolge-
Problem: Tabelle B referenziert A, aber B steht in der Eingabe vor A.
0.4.0 unterstützt drei Pfade — der vierte (PG `--disable-fk-checks` als
generischer Mechanismus) ist explizit aus Sicherheitsgründen ausgeschlossen
(F42).

#### 6.8.1 PostgreSQL

PG bekommt **bewusst keinen generischen `--disable-fk-checks`-Pfad** in
0.4.0. Die nahe liegenden Mechanismen funktionieren in der Praxis nicht
zuverlässig:

- `SET session_replication_role = replica` ist zu breit, ändert
  Sicherheits- und Replikations-Semantik global, und ist auf Managed-PG-
  Diensten oft komplett gesperrt.
- `ALTER TABLE … DISABLE TRIGGER ALL` umfasst zwar die System-Trigger für
  FK-Constraints, braucht aber **Superuser-Rechte** (`pg_authid.rolsuper`)
  und scheitert bei normalen App-Connections — also genau bei den Imports,
  die das Feature bräuchten. Auch der `pg_trigger.tgenabled`-Switch wird
  von einigen Managed-Diensten blockiert.

Für PG werden in 0.4.0 stattdessen zwei sichere Wege unterstützt:

| Pfad | Voraussetzung | Implementierung |
|---|---|---|
| **(a)** Topologische Sortierung der Tabellen | `--schema <path>` mit der neutralen Schema-Definition | Wiederverwendung der Kahn-Sortierung aus `AbstractDdlGenerator` (0.2.0). Importe laufen in FK-respektierender Reihenfolge, FK-Checks bleiben aktiv. |
| **(b)** `SET CONSTRAINTS ALL DEFERRED` | Constraints sind im Ziel-Schema mit `DEFERRABLE` deklariert | Pro Tabellen-Transaktion vor dem ersten Insert ausgeführt. Erfordert keine Sonderrechte; wirkt aber nur auf bereits-deferrable Constraints (sonst No-Op mit Warnung im Report). |

Wer FK-Checks **wirklich** deaktivieren muss, läuft den Import als
Superuser oder bereitet die Constraints vorab als `DEFERRABLE` vor. Beide
Wege sind dem User aktiv überlassen; d-migrate stellt nicht stillschweigend
einen unsicheren Pfad bereit.

`--disable-fk-checks` auf PG → **Exit 2** mit Hinweis-Text:

```
--disable-fk-checks is not supported on PostgreSQL in 0.4.0.
Use --schema <path> for topological table ordering, or declare your
foreign-key constraints as DEFERRABLE and they will be deferred
automatically per table transaction.
```

#### 6.8.2 MySQL und SQLite

Beide Dialekte haben **session-lokale, nicht privilegierte** Schalter und
sind damit safe für `--disable-fk-checks`:

| Dialect | Schalter |
|---|---|
| MySQL | `SET FOREIGN_KEY_CHECKS=0` für die Session, am Ende `SET FOREIGN_KEY_CHECKS=1` |
| SQLite | `PRAGMA foreign_keys = OFF` für die Connection, am Ende `PRAGMA foreign_keys = ON` |

`--disable-fk-checks` ohne Setzen des Flags → Default: FK-Checks bleiben
aktiv, User muss die Eingabe-Reihenfolge selbst stimmig machen.

#### 6.8.3 Topologische Sortierung mit `--schema`

Wenn `--schema <path>` gesetzt ist, wird **immer** topologisch sortiert
(unabhängig vom Dialekt). Das ist der einzige Weg, der ohne jeden
FK-Disable-Trick funktioniert und auf allen Dialekten gleichermaßen sauber
ist. Implementierung kommt direkt aus dem 0.2.0
`AbstractDdlGenerator.kahnSort()` — derselbe Algorithmus, andere Eingabe
(Tabellen statt DDL-Statements).

### 6.9 Encoding-Unterstützung (BOM-Detection nur für UTF-Varianten)

`EncodingDetector` ist **kein** universeller Charset-Detector — er erkennt
ausschließlich BOM-markierte UTF-Streams. Alles andere ist Sache eines
expliziten `--encoding`-Flags (F45).

`EncodingDetector` liest die ersten 4 Bytes des `InputStream` per
`PushbackInputStream` und entscheidet:

| Bytes | Encoding | Verhalten |
|---|---|---|
| `EF BB BF` | UTF-8 (BOM) | erkannt, BOM wird übersprungen |
| `FE FF` | UTF-16 BE (BOM) | erkannt, BOM wird übersprungen |
| `FF FE` | UTF-16 LE (BOM) | erkannt, BOM wird übersprungen |
| `00 00 FE FF` | UTF-32 BE (BOM) | nicht supported, Exit 2 mit Hinweis auf `--encoding` |
| `FF FE 00 00` | UTF-32 LE (BOM) | nicht supported, Exit 2 mit Hinweis auf `--encoding` |
| sonstige | (keine BOM) | Fallback auf den Wert von `--encoding` (Default UTF-8) |

Die geprüften Bytes werden via `unread()` zurück in den Stream gelegt, so
dass der eigentliche Reader sie noch sehen kann — der CSV-Parser zum Beispiel
verschluckt das BOM nicht zuverlässig, wir müssen es selbst überspringen.

**Keine Heuristik** für Non-BOM-Dateien (chardet, ICU). Konkret bedeutet
das:

- **UTF-8-Dateien ohne BOM**: werden vom UTF-8-Default automatisch korrekt
  gelesen — keine User-Aktion nötig.
- **UTF-16-Dateien ohne BOM**: gelten als nicht-portabel und werden in 0.4.0
  als reguläre Bytes gelesen, die der UTF-8-Decoder dann ablehnt. User muss
  `--encoding utf-16le`/`utf-16be` explizit setzen.
- **ISO-8859-1, Windows-1252, CP-437 etc.**: ausschließlich über
  `--encoding iso-8859-1` (oder den jeweiligen Java-Charset-Namen). Es gibt
  **keine** automatische Erkennung — der Lastenheft-Eintrag LF-010, der von
  „Encoding-Erkennung" spricht, wird in 0.4.0 als „Encoding-Unterstützung
  mit explizitem Override" interpretiert.

Wenn der Default `auto` keine BOM findet und kein expliziter Wert gesetzt
ist, fällt der Detector auf UTF-8 zurück und protokolliert eine Hinweis-
Zeile auf stderr (`Hint: input file has no BOM, using UTF-8. Set --encoding
to override.`). Das verhindert, dass ein still falsch dekodierter ISO-8859-1-
Import als „funktioniert" durchgewunken wird, ohne dass jemand Mojibake
bemerkt.

### 6.10 Getrennte Reader/Writer-Registries

Wir behalten `DataReaderRegistry` aus 0.3.0 unverändert und legen
`DataWriterRegistry` als zweite, unabhängige Registry an. Alternative wäre
eine zusammengelegte `DataAdapterRegistry` mit beiden Maps — aber:

- die existierenden Tests (~30 Stück) referenzieren `DataReaderRegistry`
  direkt und müssten alle umgeschrieben werden
- die Trennung macht die Bootstrap-Reihenfolge in `Main.kt` nicht
  komplizierter — `*Driver.register()` ruft beide Registries
- Symmetrie zur `formats`-Faktorisierung (`DataChunkReaderFactory` und
  `DataChunkWriterFactory` sind ebenfalls getrennt)

### 6.11 Exit-Code-Matrix für `data import`

| Code | Trigger | Beispiel-Meldung |
|---|---|---|
| `0` | Erfolgreicher Import, alle Tabellen geschrieben | `Imported 3 tables (12 345 rows) in 4.2 s; reseeded 2 sequences` |
| `2` | CLI-Fehler, ungültige Optionen, fehlendes `--target` oder `--table`, `--trigger-mode disable` auf MySQL/SQLite, `--disable-fk-checks` auf PG, unbekannte Endung ohne `--format`, PK-lose Tabelle mit `--on-conflict update` | `--trigger-mode disable is not supported for dialect MYSQL` |
| `3` | Schema-Validierung gegen `--schema <path>` fehlgeschlagen | `Column 'orders.total' not found in target schema` |
| `4` | Connection-Fehler (HikariCP, fehlendes Target) | `Connection refused: localhost:5432` |
| `5` | Import-Fehler während Streaming (SQL-Constraint-Verletzung, IO-Exception, Sequence-Reseeding gescheitert) | `Failed to import table 'orders' at chunk 47: duplicate key value violates unique constraint` |
| `7` | Konfigurationsfehler (URL-Parser, `.d-migrate.yaml`, fehlende ENV-Variable) | wie 0.3.0 |

Code 3 ist in 0.3.0 für `data export` nicht relevant gewesen, kommt hier
für die Schema-Validierung zurück (analog zu `schema validate` aus 0.1.0).

### 6.12 Inkrementeller Pfad (LF-013)

LF-013 ist in 0.4.0 **explizit zweigeteilt**:

- **Export-Seite**: bekommt funktionale Inkrement-Flags (`--incremental`,
  `--since-column`, `--since`), die einen WHERE-Filter ans SELECT anhängen.
- **Import-Seite**: bekommt **keine** Inkrement-Flags. Wer einen
  Delta-Datensatz idempotent einspielen will, benutzt `--on-conflict update`
  — das ist mechanisch identisch zu „inkrementeller Import" und verhält
  sich auch dann korrekt, wenn der Anwender denselben Delta-Export zweimal
  einspielt.

Diese Asymmetrie ist absichtlich (F44): „inkrementell" ist auf der Lese-Seite
ein **Filter-Konzept** (welche Rows extrahiere ich aus der DB?) und auf der
Schreib-Seite ein **Konflikt-Auflösungs-Konzept** (was passiert mit Rows,
die schon existieren?). Ein gemeinsames `--incremental` auf beiden Seiten
würde diese zwei Konzepte falsch verschmelzen.

#### 6.12.1 Export-Seite

```bash
d-migrate data export --source local_pg --format json \
    --tables orders --since-column updated_at --since "2026-01-01"
```

Intern:

1. `--since-column` strikt validieren wie `--tables` (Plan-0.3.0 §6.7
   Identifier-Pattern)
2. `--since`-Wert nicht typkonvertieren — als String parametrisieren und
   den DB-Typ im SQL-Cast übernehmen
3. `DataFilter.ParameterizedClause("\"<col>\" >= ?", listOf(since))`
4. Reader baut: `SELECT ... FROM ... WHERE "updated_at" >= ?`
5. Wenn der Anwender zusätzlich `--filter` setzt, werden beide via
   `DataFilter.Compound([WhereClause(filter), ParameterizedClause(...)])`
   kombiniert (der bestehende `AbstractJdbcDataReader.collectWhereClauses`
   trägt das schon)

`--incremental` ohne `--since-column` und `--since` → Exit 2.

#### 6.12.2 Import-Seite: idempotenter UPSERT statt eigenem Modus

```bash
# Vorher exportierter Delta-Stand (oder der letzte Voll-Export):
d-migrate data import --target local_pg --source orders.delta.json \
    --format json --table orders --on-conflict update
```

Inkrementelle Imports brauchen **idempotente Schreiblogik**, sonst entstehen
Duplikate bei der zweiten Ausführung desselben Deltas. Der einzige
unterstützte Pfad in 0.4.0 ist `--on-conflict update`, der pro Dialekt einen
UPSERT baut:

| Dialect | UPSERT-Syntax |
|---|---|
| PG | `INSERT INTO ... VALUES (...) ON CONFLICT ("pk_col") DO UPDATE SET col1 = EXCLUDED.col1, ...` |
| MySQL | `INSERT INTO ... VALUES (...) ON DUPLICATE KEY UPDATE col1 = VALUES(col1), ...` |
| SQLite | `INSERT INTO ... VALUES (...) ON CONFLICT(pk_col) DO UPDATE SET col1 = excluded.col1, ...` |

Die Primärschlüssel-Spalten werden über JDBC `DatabaseMetaData.getPrimaryKeys()`
erkannt, ähnlich wie der Spalten-Hint in §6.4. Composite-PKs sind unterstützt;
PK-lose Tabellen können **nicht** mit `--on-conflict update` importiert
werden → Exit 2 mit klarer Meldung.

**Bewusst nicht enthalten** (F44):

- Kein `--incremental`-Flag auf `data import`. Der User soll explizit
  `--on-conflict update` setzen — das beschreibt das tatsächliche
  Verhalten und ist nicht durch einen anders klingenden Synonym-Flag
  verschleiert.
- Kein `--since-column` auf `data import`. Die Idee aus der ersten
  Plan-Fassung, den Marker als „informative" Report-Information zu
  protokollieren, war eine Brückenkonstruktion ohne fachlichen Mehrwert
  und wurde gestrichen.
- Keine Auswertung des „höchsten Markers im File" beim Import. Wer den
  letzten exportierten Marker tracken will, macht das auf der Export-Seite
  (z.B. via Shell-Variable mit Verweis auf den Datei-Namen).

#### 6.12.3 Was 0.4.0 NICHT macht

- Kein State-File mit „letzter exportierter Marker" — User trackt das
  selbst über Shell-Variablen oder Pipeline-State. State-Files sind
  Checkpoint-Territory (0.9.0).
- Keine Auto-Discovery von `updated_at`/`modified_at`/`changed_at`-Spalten —
  Spalte muss explizit angegeben werden (kommt mit `SchemaReader` 0.6.0)
- Keine Soft-Delete-Behandlung (`deleted_at`)
- Keine Tombstone-Synchronisation (Löschen über Inkrement)

### 6.13 Schreibperformance: Batch-Insert ohne COPY

Für 0.4.0 reicht JDBC `addBatch()` + `executeBatch()`. Erwartete Größenordnung
auf einer typischen lokalen PG-Instanz:

| Dialect | rows/s (Single-Thread, Batch=1000) |
|---|---|
| PostgreSQL | ~30 000 |
| MySQL | ~25 000 (mit `rewriteBatchedStatements=true`) |
| SQLite | ~50 000 (in-memory), ~10 000 (file mit WAL) |

Das deckt die typischen Use-Cases (Daten-Migration zwischen DBs,
Test-Daten-Import) komplett ab. **PostgreSQL `COPY FROM` ist ~10× schneller**
und kommt in 1.0.0 als optionaler Performance-Pfad — bis dahin reichen die
~30k rows/s.

### 6.14 `--truncate` als nicht-atomarer Pre-Step

```bash
d-migrate data import --target local_pg --source orders.json --format json \
    --table orders --truncate
```

`--truncate` läuft als **destruktiver Pre-Step** vor dem ersten Chunk: ein
einzelnes `TRUNCATE TABLE` (PG/MySQL) bzw. `DELETE FROM` (SQLite — kein
TRUNCATE) wird in einer eigenen, sofort committeten Transaktion ausgeführt.
Erst danach beginnt der reguläre chunkbasierte Import-Pfad aus §6.5.

> **Wichtig — Nicht-Atomarität (F41)**: Wenn der nachfolgende Import nach
> dem TRUNCATE scheitert (Connection-Abbruch, Constraint-Verletzung,
> Disk-Voll, …), bleibt die Tabelle im **leeren** Zustand zurück. Die alten
> Daten sind weg. Das ist die direkte Konsequenz aus dem
> Chunk-Transaktionsmodell (§6.5) plus der Tatsache, dass die meisten
> Dialekte TRUNCATE in einer impliziten DDL-Transaktion ausführen, die wir
> nicht über mehrere Chunks hinweg offen halten können.
>
> Wer **atomares** Replace braucht ("entweder die ganze Tabelle ist neu
> oder die alte ist unverändert"), wartet auf das geplante
> `--replace-table` in einem späteren Milestone (§11). Das wird intern als
> Single-Transaction `DELETE FROM` plus chunkweisen Import in **derselben**
> Connection implementiert — komplexer und mit eigenen Locking-
> Konsequenzen, deshalb nicht in 0.4.0.

`--truncate` ist eine destruktive Operation und braucht User-Bestätigung im
interaktiven Modus (per `--yes` oder `D_MIGRATE_ASSUME_YES=true` automatisch
akzeptiert, analog zu cli-spec.md §8.2).

`--truncate` ist nicht gleichbedeutend mit `--on-conflict update`:

- `--truncate` → Tabelle wird VOR dem Import geleert → reiner Insert-Modus
- `--on-conflict update` → UPSERT, behält bestehende Zeilen
- `--on-conflict skip` → Insert mit `ON CONFLICT DO NOTHING`
- `--on-conflict abort` (Default) → Erste Constraint-Verletzung killt den Chunk

Kombinationen:

| `--truncate` | `--on-conflict` | Verhalten |
|---|---|---|
| an | irgendwas | TRUNCATE-Pre-Step + reiner Insert (ON CONFLICT-Klausel ignoriert) |
| aus | abort (Default) | klassischer Insert, scheitert bei Constraint-Verletzung |
| aus | skip | Insert mit Skip-on-Conflict |
| aus | update | UPSERT |

### 6.15 Reporting

Der `ImportResult` wird vom CLI als ProgressSummary (analog `data export`)
auf stderr ausgegeben:

```
Imported 3 tables (12 345 rows, 1 245 skipped) in 4.2 s
  customers: 5 000 inserted, 0 skipped, 0 failed
  orders:    7 200 inserted, 1 245 skipped (on-conflict), 0 failed
  products:    145 inserted, 0 skipped, 0 failed
Sequence adjustments: 2
  customers.id  → 5001
  products.id   → 146
Trigger mode:    fire
```

`--quiet` unterdrückt die Summary (wie in 0.3.0); `--no-progress` ebenfalls.
Für eine maschinenlesbare Variante: `--output-format json` am Root-Command
liefert das `ImportResult` als JSON auf stdout.

---

## 7. Spec- und Doku-Pflege

| Datei | Änderung |
|---|---|
| `docs/cli-spec.md` §6.2 `data import` | Vollständiger Block analog zu 0.3.0-`data export`: alle Flags aus §3.7.1, Output-Resolver (Stdin/SingleFile/Directory), Exit-Code-Matrix 0/2/3/4/5/7, Beispiele mit Round-Trip und Inkrement |
| `docs/cli-spec.md` §6.2 `data export` | LF-013-Block ergänzen: `--incremental`, `--since-column`, `--since` mit Beispielen |
| `docs/connection-config-spec.md` §6.14.2 | „Status pro Feature"-Tabelle aktualisieren: `default_target` ist ab 0.4.0 aktiv |
| `docs/design-import-sequences-triggers.md` | Status `Draft` → `Approved`; offene Entscheidungen aus §10 mit den Antworten aus §6.6/§6.7/§6.8 dieses Plans befüllen |
| `docs/roadmap.md` | Milestone 0.4.0 als „in Arbeit" markieren bei Phase A; bei jedem abgeschlossenen Phase-Schritt aktualisieren |
| `CHANGELOG.md` | `[Unreleased]`-Block schon vorhanden (im 0.3.0-Post-Release-Bump angelegt); pro Phase Einträge nachziehen |

---

## 8. Coverage-Ziele

Pro Modul wie in 0.3.0:

| Modul | Ziel | Anmerkung |
|---|---|---|
| `d-migrate-core` | 90% | `DataFilter.ParameterizedClause` direkt testen |
| `d-migrate-driver-api` | 90% | DataWriter-Vertrag inline mit Stubs; SchemaSync-Defaults |
| `d-migrate-driver-postgresql` | 90% via Testcontainers | `PostgresDataWriter` + `PostgresSchemaSync` Branch-Coverage |
| `d-migrate-driver-mysql` | 90% via Testcontainers | dito; `disable`-Trigger-Pfad als Negativ-Test |
| `d-migrate-driver-sqlite` | 90% inline | `sqlite_sequence` Reseeding mit File-DB |
| `d-migrate-streaming` | 90% | `StreamingImporter` mit Fake Reader/Writer |
| `d-migrate-formats` | 90% | je Reader Golden-Master-Round-Trip mit Phase-D-Writern aus 0.3.0; `EncodingDetector` mit allen 5 BOM-Varianten plus Fallback |
| `d-migrate-cli` | 60% (wie 0.3.0) | DataImportCommand-Pfade |

---

## 9. Verifikation

Vor dem 0.4.0-Release müssen die folgenden Round-Trip-Tests grün sein
(Phase F + zusätzliche Verifikations-Suite):

1. **Voll-Roundtrip pro Format pro Dialect** (3×3 = 9 Permutationen):
   `data export → data import → SELECT-Vergleich`
   - 1000 Rows, alle 18 neutralen Typen aus 0.5.5 (sofern bis dahin gemerged,
     sonst die 0.3.0-Subset-Typen)
   - Ergebnis: SHA-256-Hash der Original- und Re-Export-Datei muss
     identisch sein
2. **Inkrementeller Round-Trip**:
   - Initial-Export (10 000 Rows)
   - 100 Updates + 50 Inserts in der Source
   - Delta-Export mit `--since-column updated_at --since "<initial-ts>"`
     liefert genau 150 Rows
   - Delta-Import mit `--on-conflict update` ins Ziel
   - SELECT-Vergleich Source vs. Ziel: identisch
3. **Sequence-Reseeding-Verifikation**:
   - Tabelle mit 100 Rows, höchste ID = 100, importieren
   - Anschließend `INSERT INTO ... DEFAULT VALUES` muss ID 101 vergeben,
     nicht 1 oder 2 (wäre der Fall ohne Reseeding)
4. **Trigger-Modus-Verifikation**:
   - Tabelle mit `BEFORE INSERT`-Trigger, der `value = value * 2` setzt
   - Import mit `--trigger-mode fire`: Werte sind verdoppelt
   - Import mit `--trigger-mode disable` (PG): Werte sind unverändert
   - Import mit `--trigger-mode disable` (MySQL/SQLite): Exit 2 mit
     `UnsupportedTriggerModeException`
5. **`--truncate`-Pre-Step ist NICHT atomar** (F41 — explizit dokumentiert):
   - Tabelle mit 1000 Rows, Import mit `--truncate` und einer absichtlich
     fehlerhaften Row → der Test asserted, dass die Tabelle danach **leer**
     ist (NICHT wieder mit den 1000 Rows gefüllt). Der Test schützt damit
     das in §6.14 dokumentierte Verhalten gegen versehentliche Atomarisierung
     in einer späteren Refactoring-Runde.

---

## 10. Risiken

| Risiko | Mitigation |
|---|---|
| **Streaming-Reader leakt Speicher** bei großen Eingaben | Phase B Schritt 6: 100-MB-Heap-Sample-Test pro Reader |
| **Sequence-Reseeding race** zwischen Import-Ende und nächster INSERT | PG: `setval(seq, GREATEST(MAX(col), nextval(seq)-1), true)`; MySQL: `ALTER TABLE ... AUTO_INCREMENT` ist atomar |
| **Trigger-Reaktivierung scheitert** nach Fehler im Import | Design-Doc §7.3: harter Fehler mit Tabellen-Name; `session.close()` versucht trotz vorheriger Exceptions die Reaktivierung |
| **`--on-conflict update` ohne PK** wird stillschweigend zu Insert | Pre-Flight-Check via `DatabaseMetaData.getPrimaryKeys()`; Tabelle ohne PK + `update` → Exit 2 |
| **Encoding-Auto-Fallback verschluckt Mojibake** | Wenn `auto` ohne BOM auf UTF-8 fällt, stderr-Hinweis ausgeben; in den Tests prüfen, dass ISO-8859-1 ohne expliziten Flag mit „Decoding error"-Meldung scheitert |
| **MySQL `disable` Trigger-Pfad** wird vom User erwartet, ist aber nicht implementiert | Klare Fehlermeldung mit Verweis auf design-import-sequences-triggers.md §6.2 |
| **`--truncate` löscht produktive Daten** ohne Rückfrage | `--yes` Pflicht im non-interaktiven Modus; cli-spec.md §8 dokumentiert das schon |
| **Inkrementeller Import läuft mehrfach und führt zu doppelten UPSERTs** (= idempotent, also OK) — aber der User bekommt kein Feedback, dass nichts geändert wurde | `WriteResult.rowsInserted vs rowsUpdated` getrennt zählen und im Report ausgeben |
| **`ParameterizedClause` aus User-Eingabe** wird falsch parametrisiert | Strikte Identifier-Validierung für `--since-column` (gleiches Pattern wie `--tables`); `--since` als String an JDBC, kein String-Concat |

---

## 11. Out-of-Scope für 0.4.0

Folgende Punkte sind absichtlich vertagt — bitte nicht aus Versehen
mitziehen:

| Punkt | Wohin |
|---|---|
| `--resume` / Checkpoint-State-File | 0.9.0 (LN-012) |
| Atomare Multi-Tabellen-Transaktionen über Chunks hinweg | 1.0.0 (LN-013) |
| **`--replace-table`** (atomares DELETE+Import in einer Tabellen-Transaktion) | 0.5.0 oder später (§6.14, F41) |
| **PG `--disable-fk-checks`** als generischer Mechanismus | nie — F42, siehe §6.8.1; Nutzer wählen `--schema` oder `DEFERRABLE`-Constraints |
| Parallele Tabellen-Imports | 1.0.0 (LN-007) |
| `schema reverse` und Auto-Marker-Discovery für `--incremental` | 0.6.0 (LF-004) |
| `--incremental` als Flag auf der Import-Seite | nie — F44, siehe §6.12.2; idempotenter Import läuft über `--on-conflict update` |
| Auto-Mapping vom Dateinamen auf die Zieltabelle (`users.json` → `users`) | später (additiv) — F40, siehe §3.7.1; 0.4.0 verlangt explizit `--table` |
| Auto-detect Encoding ohne BOM (ICU/chardet) | nie — F45; User setzt `--encoding` für Non-UTF-Streams |
| PG `COPY FROM` Performance-Pfad | 1.0.0 |
| Tombstone-/Soft-Delete-Synchronisation für Inkrement | 1.0.0+ |
| `data seed` (Test-Daten-Generierung) | 1.3.0 |
| Replikations-/CDC-Modi | 1.1.0+ |
| Encrypted credentials in `.d-migrate.yaml` | 1.0.0 |

---

## 12. Aufgelöste Review-Fragen und Findings

### 12.1 Initiale offene Fragen

- **Soll `--format` aus der Endung abgeleitet werden?** Ja, mit Fallback auf
  Exit 2 wenn keine Endung erkennbar (§6.3).
- **Default-Trigger-Modus?** `fire`, wie im Design-Doc §11 empfohlen
  (§6.7).
- **Sequence-Reseeding immer oder optional?** Default `true`, abschaltbar
  via `--reseed-sequences=false` (§6.6).
- **Wie wird `--since` gegen SQL-Injection geschützt?** Via
  `DataFilter.ParameterizedClause` und JDBC-Parameter-Binding (§3.8 + §6.12).
- **Foreign-Key-Checks während Import?** Default an; `--disable-fk-checks`
  als getrenntes Flag — aber NICHT auf PG (siehe §6.8.1, F42).
- **PK-lose Tabellen + UPSERT?** Pre-Flight-Check, Exit 2 mit klarer
  Meldung (§6.12.2).

### 12.2 Erste Review-Runde — Findings F40–F45

| ID | Finding | Auflösung |
|---|---|---|
| F40 (Hoch) | Single-File/Stdin-Import hatte keine eindeutige Tabellen-Auflösung — `--tables` ist als Liste mehrdeutig für Single-Source-Pfade | `--table` (singular) ist Pflicht für `Stdin` und `SingleFile`; `--tables` (plural) bleibt Subset-Filter nur für `Directory`. Tabelle der Auflösungsregeln in §3.7.1. Auto-Mapping vom Dateinamen ist explizit out-of-scope (§11) und kann später additiv kommen. |
| F41 (Hoch) | `--truncate`-Vertrag war in sich widersprüchlich — chunkbasierte Commits + atomarer Pre-Truncate gehen nicht generisch zusammen | `--truncate` ist explizit ein **nicht-atomarer Pre-Step** (§6.14). Wenn der Import nach dem TRUNCATE scheitert, bleibt die Tabelle leer. Verifikationstest 5 wurde umgedreht und prüft jetzt aktiv das nicht-atomare Verhalten. Atomares Replace landet als zukünftiges `--replace-table` in §11. |
| F42 (Hoch) | PG `--disable-fk-checks` über `ALTER TABLE … DISABLE TRIGGER ALL` braucht Superuser → unzuverlässig in der Praxis | PG bekommt **keinen** generischen `--disable-fk-checks`-Pfad in 0.4.0 (§6.8.1). Stattdessen zwei sichere Wege: topologische Sortierung mit `--schema` und `SET CONSTRAINTS ALL DEFERRED` für bereits-deferrable Constraints. `--disable-fk-checks` auf PG → Exit 2 mit Hinweis-Text. MySQL und SQLite behalten ihre session-lokalen Schalter (§6.8.2). |
| F43 (Mittel) | Reader-Vertrag „erster Chunk hat immer columns" ist für leere JSON/YAML nicht erfüllbar | `nextChunk()` darf für leere selbstbeschreibende Eingaben sofort `null` liefern (§3.5.1). Spalten-Metadaten kommen aus dem Target-Schema (§6.4). Reader liefert nur `headerColumns: List<String>?` als file-derived Header-Namen — die Typen kommen IMMER aus JDBC-`ResultSetMetaData`, nie aus dem Reader. |
| F44 (Mittel) | LF-013 Import-Seite war mit halb-funktionalem `--since-column` doppelt belegt | `--incremental` und `--since-column` sind komplett aus `DataImportCommand` entfernt (§3.7.1, §6.12.2). Inkrementeller Import = idempotenter UPSERT-Import via `--on-conflict update`. Roadmap-Zeile entsprechend angepasst. |
| F45 (Niedrig) | „Encoding-Erkennung" war irreführend — ISO-8859-1 wird nicht erkannt | Wortlaut auf „Encoding-Unterstützung" geändert; in §1, §6.9 und der Roadmap explizit dokumentiert: BOM-Detection nur für UTF-8/UTF-16, alles andere via `--encoding`. |

