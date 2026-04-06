# Implementierungsplan: Milestone 0.3.0 — Datenexport

> Dieses Dokument beschreibt den konkreten Implementierungsplan für Milestone 0.3.0.
> Es dient als Review-Grundlage vor Beginn der Umsetzung.

---

## 1. Ziel

Daten aus PostgreSQL, MySQL und SQLite per CLI streaming-basiert in JSON, YAML
und CSV exportieren — ohne die Tabelleninhalte vollständig in den Speicher zu
laden.

```
DB → d-migrate data export --source <url> --format <fmt> [--output <path>] → file/stdout
```

**Was gehört zu 0.3.0:**

- Connection-Layer: URL-Parser, ConnectionConfig, HikariCP Connection-Pool
- DataReader-Port mit JDBC-Implementierungen für PostgreSQL, MySQL, SQLite
- Streaming-Pipeline (Pull-basiert, chunk-weise, **ohne** Checkpoint/Resume)
- Format-Codecs für Datenexport: JSON, YAML, CSV (encoding/BOM-fähig)
- CLI-Kommando `data export` (siehe `cli-spec.md` §6.2)
- Testcontainers-Tests für PostgreSQL und MySQL (LN-048)

**Was bewusst NICHT zu 0.3.0 gehört:**

- Datenimport (`data import` → 0.4.0, LF-010)
- Inkrementeller Export (`--incremental` → 0.4.0, LF-013)
- Checkpoint-Erstellung und Resume (LN-012/LN-013 → 0.5.0)
- Parallele Tabellenverarbeitung (LN-007 → 1.0.0)
- Schema-Reader / Reverse-Engineering (LF-004 → 0.6.0)
- Partition-aware Export (LN-008 → 1.0.0)

---

## 2. Neue Module

### 2.1 `d-migrate-streaming`

Neues Modul gemäß Architektur §2.1. Enthält für 0.3.0 nur die Pull-basierte
Export-Orchestrierung — Checkpoint, Parallel-Executor und Delta-Detector
folgen in späteren Milestones.

```
d-migrate-streaming/
└── src/main/kotlin/dev/dmigrate/streaming/
    ├── StreamingExporter.kt        # Orchestrierung Reader → Writer
    ├── PipelineConfig.kt           # chunkSize, fetchSize (read-only Defaults für 0.3.0)
    └── ExportResult.kt             # Statistiken: rows, chunks, durationMs, bytesWritten
```

```kotlin
class StreamingExporter(
    private val reader: DataReader,
    private val writerFactory: DataChunkWriterFactory
) {
    fun export(
        connection: DatabaseConnection,
        tables: List<String>,
        output: ExportOutput,                  // file/stdout + format options
        config: PipelineConfig
    ): ExportResult
}

data class PipelineConfig(
    val chunkSize: Int = 10_000,
    val fetchSize: Int = 1_000               // JDBC ResultSet fetchSize
)

data class ExportResult(
    val tables: List<TableExportSummary>,
    val totalRows: Long,
    val totalChunks: Long,
    val totalBytes: Long,
    val durationMs: Long
)
```

**Begründung für ein eigenes Modul** (statt Inline-Code in CLI):
- Architektur §2.1 sieht es so vor; spätere Erweiterungen (Pipeline.kt,
  ChunkProcessor, Checkpoint, ParallelExecutor) sollen ohne Refactoring
  hineinpassen.
- Hängt nur von `driver-api` und `formats` ab, kennt keinen CLI-Code.

## 3. Bestehende Module — Änderungen

### 3.1 `d-migrate-driver-api`

Erweitert um den Connection- und Daten-Lese-Layer. **Kein** neues Aggregat-
Interface `DatabaseDriver` (siehe Architektur §3.1) — für 0.3.0 reicht ein
schlanker `DataReader`-Port; das Aggregat folgt in 0.4.0/0.6.0, wenn auch
`DataWriter` und `SchemaReader` dazukommen.

```
d-migrate-driver-api/
└── src/main/kotlin/dev/dmigrate/driver/
    ├── connection/
    │   ├── ConnectionConfig.kt          # URL, credentials, pool settings
    │   ├── ConnectionUrlParser.kt       # parse → ConnectionConfig
    │   ├── DatabaseConnection.kt        # AutoCloseable wrapper around java.sql.Connection
    │   ├── ConnectionPool.kt            # AutoCloseable wrapper around HikariDataSource
    │   └── HikariConnectionPoolFactory.kt
    └── data/
        ├── DataReader.kt                # Port-Interface (row streaming)
        ├── TableLister.kt               # Port-Interface (Tabellen-Auflistung; Übergangslösung bis 0.6.0)
        ├── DataChunk.kt
        ├── ColumnDescriptor.kt
        ├── DataFilter.kt
        └── DataReaderRegistry.kt        # dialect → DataReader / TableLister (0.6.0: ServiceLoader)
```

```kotlin
interface DataReader {
    val dialect: DatabaseDialect

    /**
     * Single-use, pull-basierter Stream über die Daten einer Tabelle.
     *
     * Vertrag (siehe §6.1, §6.12):
     *   - Die zurückgegebene Sequence darf GENAU EINMAL iteriert werden.
     *     Eine zweite Iteration wirft IllegalStateException.
     *   - Der Iterator hält die Connection (autoCommit=false) und das
     *     ResultSet bis zur Erschöpfung offen.
     *   - Der Caller MUSS die Sequence vollständig konsumieren oder den
     *     erhaltenen AutoCloseable-Wrapper via use {} schließen — andernfalls
     *     bleibt die Connection in offener Read-Only-Transaktion stehen.
     *   - Die fetchSize-Tuningkonstante ist Treiber-intern und nicht
     *     parametrisiert (siehe §6.13).
     */
    fun streamTable(
        connection: DatabaseConnection,
        table: String,
        filter: DataFilter? = null,
        chunkSize: Int = 10_000
    ): ChunkSequence
}

/**
 * Single-use Sequence + AutoCloseable.
 * Erlaubt sowohl `for (chunk in stream) { ... }` als auch
 * `stream.use { it.forEach { ... } }`.
 */
interface ChunkSequence : Sequence<DataChunk>, AutoCloseable

/**
 * Eigenständiger Port für Tabellen-Auflistung. Wird in 0.6.0 durch den
 * vollständigen SchemaReader (LF-004) abgelöst, ohne dass DataReader
 * angepasst werden muss (SRP).
 */
interface TableLister {
    val dialect: DatabaseDialect
    fun listTables(connection: DatabaseConnection): List<String>
}

data class DataChunk(
    val table: String,
    val columns: List<ColumnDescriptor>,     // wiederholt sich pro Chunk; günstig für Streaming-Writer
    val rows: List<Array<Any?>>,
    val chunkIndex: Long
)

data class ColumnDescriptor(
    val name: String,
    val jdbcType: Int,                       // java.sql.Types
    val javaTypeName: String,                // ResultSetMetaData#getColumnClassName
    val nullable: Boolean
    // neutralType: NeutralType? folgt in 0.6.0 mit dem Reverse-Mapper
)

sealed class DataFilter {
    data class WhereClause(val sql: String) : DataFilter()    // wird parametrisiert eingebunden
    data class ColumnSubset(val columns: List<String>) : DataFilter()
    data class Compound(val parts: List<DataFilter>) : DataFilter()
}

data class ConnectionConfig(
    val dialect: DatabaseDialect,
    val host: String?,
    val port: Int?,
    val database: String,
    val user: String?,
    val password: String?,                   // sensitiv — niemals loggen
    val params: Map<String, String> = emptyMap(),
    val pool: PoolSettings = PoolSettings()
)

data class PoolSettings(
    val maximumPoolSize: Int = 10,
    val minimumIdle: Int = 2,
    val connectionTimeoutMs: Long = 10_000,
    val idleTimeoutMs: Long = 300_000,
    val maxLifetimeMs: Long = 600_000,
    val keepaliveTimeMs: Long = 60_000
)
```

**ConnectionUrlParser** implementiert das Format aus `connection-config-spec.md` §1:

```kotlin
object ConnectionUrlParser {
    /**
     * Parst URLs der Form <dialect>://[user[:pwd]@]host[:port]/database[?params]
     * sowie SQLite-Sonderformen (sqlite:///path, sqlite::memory:).
     */
    fun parse(url: String): ConnectionConfig
}
```

**HikariConnectionPoolFactory**:

```kotlin
object HikariConnectionPoolFactory {
    fun create(config: ConnectionConfig): ConnectionPool
    // SQLite-Sonderfall: maximumPoolSize=1 (kein paralleles Schreiben)
}
```

### 3.2 `d-migrate-driver-postgresql`

```
d-migrate-driver-postgresql/
└── src/main/kotlin/dev/dmigrate/driver/postgresql/
    ├── PostgresJdbcUrlBuilder.kt    # ConnectionConfig → JDBC-URL
    ├── PostgresDataReader.kt
    └── PostgresTableLister.kt       # information_schema.tables (current_schema)
```

PostgreSQL-Spezifika (siehe §6.13 für die Streaming-Tuning-Konstanten):
- Cursor-basiertes Streaming via `Statement#setFetchSize(N)` + `setAutoCommit(false)`
  + finales `rollback()` (siehe §6.12)
- Identifier-Quoting für `--tables` Parameter
- `application_name=d-migrate` über die ConnectionConfig-Params setzen

### 3.3 `d-migrate-driver-mysql`

```
d-migrate-driver-mysql/
└── src/main/kotlin/dev/dmigrate/driver/mysql/
    ├── MysqlJdbcUrlBuilder.kt
    ├── MysqlDataReader.kt
    └── MysqlTableLister.kt          # information_schema.tables (current schema)
```

MySQL-Spezifika (siehe §6.13):
- **Streaming-Strategie für 0.3.0: `useCursorFetch=true` + realer `fetchSize`**
  (serverseitiger Cursor). Begründung gegen das alte
  `Statement#setFetchSize(Integer.MIN_VALUE)`-Idiom:
    - row-by-row Protokoll-Overhead (jede Row einzelner Round-Trip)
    - inkompatibel mit langlaufenden HikariCP-Connections (kein Mehrfach-Statement)
    - in Connector/J 9.x ist `useCursorFetch` der dokumentierte Default-Pfad
- `useCursorFetch=true` wird vom `MysqlJdbcUrlBuilder` automatisch gesetzt,
  außer der Nutzer überschreibt es explizit über die URL-Parameter
- Connector/J-Default-Charset `utf8mb4`

### 3.4 `d-migrate-driver-sqlite`

```
d-migrate-driver-sqlite/
└── src/main/kotlin/dev/dmigrate/driver/sqlite/
    ├── SqliteJdbcUrlBuilder.kt
    ├── SqliteDataReader.kt
    └── SqliteTableLister.kt         # SELECT name FROM sqlite_master WHERE type='table'
```

SQLite-Spezifika:
- Kein echtes Streaming nötig — SQLite hält die DB ohnehin im Prozess; einfacher
  ResultSet-Iterator reicht
- HikariCP Pool-Size = 1 (siehe `connection-config-spec.md` §2.2)
- `foreign_keys=true` als Default-Pragma

### 3.5 `d-migrate-formats`

Erweitert um Streaming-Writer für Datenexport. Jackson ist bereits Dependency
(0.1.0), neu hinzu: `jackson-dataformat-csv`.

```
d-migrate-formats/
└── src/main/kotlin/dev/dmigrate/format/
    └── data/
        ├── DataChunkWriter.kt           # Port-Interface (Streaming)
        ├── DataChunkWriterFactory.kt    # format → writer
        ├── DataExportFormat.kt          # enum: JSON, YAML, CSV
        ├── ExportOptions.kt             # encoding, BOM, csvDelimiter, csvQuote, ...
        ├── json/JsonChunkWriter.kt
        ├── yaml/YamlChunkWriter.kt
        └── csv/CsvChunkWriter.kt
```

```kotlin
interface DataChunkWriter : AutoCloseable {
    /** Wird einmal vor dem ersten Chunk aufgerufen. */
    fun begin(table: String, columns: List<ColumnDescriptor>)

    /** Wird pro Chunk aufgerufen. Schreibt die Rows direkt in den Output-Stream. */
    fun write(chunk: DataChunk)

    /** Wird nach dem letzten Chunk aufgerufen. Schließt offene Container (z.B. JSON-Array). */
    fun end()
}
```

**Format-spezifische Streaming-Strategien:**

| Format | Strategie | Container | NULL |
|---|---|---|---|
| JSON | JsonGenerator (Jackson Streaming-API), Array-of-Objects | `[\n  {...},\n  {...}\n]` | `null` |
| YAML | YAMLGenerator, Sequence-of-Maps | `- col1: ...\n  col2: ...\n- col1: ...` | `~` |
| CSV | CsvSchema dynamisch aus columns, header optional | Header + Datenzeilen | `--null-string` (Default: leerer String) |

Detaillierte Java-Klasse → Format-Mapping in §6.4.

### 3.6 `d-migrate-cli`

Neues Top-Level-Kommando `data` mit Subcommand `export`:

```
d-migrate-cli/src/main/kotlin/dev/dmigrate/cli/commands/
└── DataCommands.kt        # DataCommand + DataExportCommand
```

```kotlin
class DataCommand : CliktCommand(name = "data") {
    init { subcommands(DataExportCommand()) }
}

class DataExportCommand : CliktCommand(name = "export") {
    val source by option("--source").required()                // URL (named connections → 0.4.0, siehe §6.14)
    val format by option("--format").choice("json", "yaml", "csv").required()  // PFLICHT (siehe §6.15)
    val output by option("--output").path()                    // default: stdout
    val tables by option("--tables").split(",")                // optional, default: alle
    val filter by option("--filter")                           // SQL WHERE clause (siehe §6.7)
    val encoding by option("--encoding").default("utf-8")
    val chunkSize by option("--chunk-size").int().default(10_000)
    val splitFiles by option("--split-files").flag()           // siehe §6.9
    val csvDelimiter by option("--csv-delimiter").default(",")
    val csvBom by option("--csv-bom").flag()
    val nullString by option("--null-string").default("")      // CSV-NULL-Repräsentation

    override fun run() {
        // 1. URL parsen — wenn kein "://" enthalten: Exit 7 mit Hinweis
        //    "Named connections not yet supported in 0.3.0, pass URL directly" (§6.14)
        // 2. ConnectionPool erzeugen (HikariCP)
        // 3. DataReader + TableLister für Dialect aus DataReaderRegistry holen
        // 4. tables ermitteln (entweder aus --tables oder via TableLister.listTables)
        //    Mehr-Tabellen-Output ohne --split-files → Exit 2 (siehe §6.9)
        // 5. DataChunkWriter aus format + options erzeugen
        // 6. StreamingExporter.export(...) aufrufen
        // 7. ExportResult als ProgressSummary ausgeben (stderr)
    }
}
```

Registrierung in `Main.kt` (analog zu `SchemaCommand` in 0.2.0).

**Tabellen-Auflistung wenn `--tables` fehlt**: erfolgt über den eigenständigen
`TableLister`-Port (siehe §3.1). Der CLI-Code kennt nur das Interface, die
dialektspezifischen SQL-Queries (`information_schema.tables` für PG/MySQL,
`sqlite_master` für SQLite) liegen in den jeweiligen `*TableLister`-Adaptern
in den Treibermodulen. In 0.6.0 wird dieser Port nahtlos durch den
vollständigen `SchemaReader` (LF-004) abgelöst.

### 3.7 `d-migrate-core`

**Keine Änderungen.** `DataChunk`, `ColumnDescriptor` und `ConnectionConfig`
sind JDBC-/Treiber-Konzepte und gehören in `driver-api`. `core` bleibt
JDBC-frei.

### 3.8 `settings.gradle.kts`

```kotlin
include("d-migrate-streaming")
```

### 3.9 `gradle.properties` und `build.gradle.kts`

Neue Versions-Properties:

```properties
coroutinesVersion=1.9.0          # falls Flow später benötigt; für 0.3.0 NICHT zwingend
hikariVersion=6.2.1
postgresqlJdbcVersion=42.7.4
mysqlJdbcVersion=9.1.0
sqliteJdbcVersion=3.47.1.0
testcontainersVersion=1.20.4
```

Modul-spezifische Dependencies:

| Modul | Neue Dependencies |
|---|---|
| `d-migrate-driver-api` | `com.zaxxer:HikariCP` |
| `d-migrate-driver-postgresql` | `org.postgresql:postgresql` |
| `d-migrate-driver-mysql` | `com.mysql:mysql-connector-j` |
| `d-migrate-driver-sqlite` | `org.xerial:sqlite-jdbc` |
| `d-migrate-formats` | `com.fasterxml.jackson.dataformat:jackson-dataformat-csv` |
| `d-migrate-streaming` | (neu, hängt nur von driver-api + formats ab) |
| `d-migrate-cli` | `implementation(project(":d-migrate-streaming"))` |
| Tests (PG/MySQL) | `org.testcontainers:postgresql`, `org.testcontainers:mysql`, `org.testcontainers:junit-jupiter` |

## 4. Implementierungsreihenfolge

### Phase A: Connection-Layer im `driver-api`

1. `gradle.properties` um neue Versionen ergänzen
2. `ConnectionConfig`, `PoolSettings`, `DatabaseConnection`, `ConnectionPool`
3. `ConnectionUrlParser` mit vollständiger Parser-Logik nach
   `connection-config-spec.md` §1 (inkl. Aliase, URL-Encoding, SQLite-Sonderformen)
4. `HikariConnectionPoolFactory` mit dialekt-abhängigen Defaults
5. Unit-Tests für Parser (jede Dialekt-Form, Edge-Cases, Sonderzeichen)

### Phase B: DataReader-Port und JDBC-Treiber

6. `DataReader`, `ChunkSequence`, `TableLister`, `DataChunk`, `ColumnDescriptor`,
   `DataFilter`, `DataReaderRegistry`
7. `PostgresJdbcUrlBuilder` + `PostgresDataReader` + `PostgresTableLister`
8. `MysqlJdbcUrlBuilder` (setzt `useCursorFetch=true` als Default) +
   `MysqlDataReader` + `MysqlTableLister`
9. `SqliteJdbcUrlBuilder` + `SqliteDataReader` + `SqliteTableLister`
10. Unit-Tests für JdbcUrlBuilder pro Dialekt; TableLister-Stubs gegen In-Memory-DBs
11. Integration-Tests pro Dialekt (für SQLite inline, für PG/MySQL via Testcontainers
    in `@Tag("integration")`-Suite, siehe §6.16)

### Phase C: Streaming-Pipeline

12. `d-migrate-streaming` Modul anlegen, in `settings.gradle.kts` registrieren
13. `PipelineConfig`, `ExportResult`, `TableExportSummary`
14. `StreamingExporter` mit Reader → Writer Glue-Code
15. Unit-Tests mit Fake-Reader (Liste vorbereitete Chunks) und Fake-Writer

### Phase D: Format-Codecs für Datenexport

16. `DataChunkWriter`-Interface, `DataChunkWriterFactory`, `DataExportFormat`,
    `ExportOptions`
17. `JsonChunkWriter` (Jackson Streaming-API)
18. `YamlChunkWriter` (jackson-dataformat-yaml)
19. `CsvChunkWriter` (jackson-dataformat-csv) inkl. BOM-Handling
20. Unit-Tests pro Writer mit Golden-Master-Output

### Phase E: CLI-Integration

21. `DataCommand` + `DataExportCommand` in `DataCommands.kt`
22. Registrierung in `Main.kt` (analog zu `SchemaCommand` aus 0.2.0)
23. URL-only-Validierung: kein `://` im `--source` → Exit 7 mit
    "Named connections not yet supported in 0.3.0, pass URL directly"
    (Named-Connection-Support folgt zusammen mit `data import` in 0.4.0; siehe §6.14)
24. Exit-Code-Mapping gemäß §6.10 (0 / 2 / 4 / 5 / 7)
25. CLI-Integration-Tests (gegen SQLite, weil kein Container nötig)

### Phase F: End-to-End-Tests mit Testcontainers

26. `DataExportTestcontainersTest` für PostgreSQL: Container starten, Schema
    anlegen, Daten einfügen, Export ausführen, JSON/YAML/CSV-Output verifizieren
    — markiert mit `@Tag("integration")`
27. Dasselbe für MySQL
28. SQLite-E2E direkt ohne Container (kann im Default-Test-Run laufen)
29. Neuer GitHub-Workflow `.github/workflows/integration.yml` mit
    `./gradlew test -PintegrationTests` (siehe §6.16)

## 5. Abhängigkeiten zwischen Modulen

```
d-migrate-driver-api
├── api(d-migrate-core)                # bestand
└── api("com.zaxxer:HikariCP")         # neu, transitiv für Pool

d-migrate-driver-postgresql            # implementation(driver-api) + JDBC
d-migrate-driver-mysql                 # implementation(driver-api) + JDBC
d-migrate-driver-sqlite                # implementation(driver-api) + JDBC

d-migrate-formats
├── implementation(d-migrate-driver-api)   # neu (DataChunk, ColumnDescriptor)
└── implementation(jackson-dataformat-csv) # neu

d-migrate-streaming                    # neu
├── implementation(d-migrate-driver-api)
└── implementation(d-migrate-formats)

d-migrate-cli
├── implementation(d-migrate-streaming)        # neu
├── implementation(d-migrate-driver-postgresql) # bestand (DDL)
├── implementation(d-migrate-driver-mysql)      # bestand
└── implementation(d-migrate-driver-sqlite)     # bestand
```

## 6. Zentrale Design-Entscheidungen

### 6.1 ChunkSequence: synchron, single-shot, AutoCloseable

Architektur §3.1 zeigt `Flow<DataChunk>`. Für 0.3.0 reicht jedoch ein
**synchroner Iterator** — er bildet das JDBC-Cursor-Streaming direkt ab und
braucht keine `kotlinx-coroutines`-Dependency. Migration zu Flow ist später
trivial (`asFlow()`), wenn Parallel-Export (1.0.0) tatsächlich Backpressure
benötigt.

Statt einer nackten `Sequence<DataChunk>` definiert der Plan einen eigenen
Typ `ChunkSequence : Sequence<DataChunk>, AutoCloseable` mit zwei
Vertragsergänzungen:

1. **Single-use** — eine `ChunkSequence` darf genau einmal iteriert werden.
   Eine zweite Iteration wirft `IllegalStateException("ChunkSequence already
   consumed; JDBC ResultSet cannot be re-iterated")`. Dies wird über ein
   internes State-Flag erzwungen, weil JDBC-Cursors nicht restartable sind und
   `Sequence` per Default das Gegenteil suggeriert.
2. **AutoCloseable** — der Caller kann die Sequence in einem `use {}`-Block
   verwenden; `close()` ist idempotent und gibt sowohl `ResultSet`,
   `Statement` als auch die JDBC-`Connection` (samt Transaktionsabschluss,
   siehe §6.12) frei. Bei vollständiger Iteration wird `close()` automatisch
   am Ende ausgeführt.

> Bewusste, dokumentierte Abweichung von Architektur §3.1. Wird in 0.5.0/1.0.0
> revidiert, sobald Pipeline-Parallelisierung umgesetzt wird.

### 6.2 Kein `DatabaseDriver`-Aggregat-Interface in 0.3.0

Architektur §3.1 beschreibt ein Aggregat `DatabaseDriver` mit `connect()`,
`schemaReader()`, `schemaWriter()`, `dataReader()`, `dataWriter()`. Für 0.3.0
wären 4 von 5 Methoden leer / `UnsupportedOperationException` — Anti-Pattern.
Stattdessen nur `DataReader`-Port; das Aggregat folgt in 0.4.0 wenn `DataWriter`
dazukommt, in 0.6.0 wenn `SchemaReader` dazukommt.

### 6.3 ConnectionUrlParser im `driver-api`

Der Parser ist dialekt-übergreifend (kennt alle Aliase) — er gehört nicht in
einen einzelnen Treiber. Im `driver-api` liegt er, weil
- `core` bleibt JDBC-frei
- ein neues `d-migrate-connection`-Modul wäre Overhead für 5 Klassen

### 6.4 DataChunk-Modell und Type-Serialisierung

```kotlin
DataChunk(
    columns: List<ColumnDescriptor>,    // wiederholt pro Chunk — bewusst
    rows: List<Array<Any?>>             // raw JDBC-Werte
)
```

- **Spalten pro Chunk** (statt einmalig pro Tabelle), damit ein Streaming-Writer
  jeden Chunk unabhängig serialisieren kann (zukünftig auch parallel).
- **Raw `Array<Any?>`** statt `Map<String, Any?>` — spart Hashmap-Allokationen
  bei Millionen von Rows. Format-Writer indexieren über `columns[i].name`.
- **Java-native Werte**, kein Mapping zu NeutralType in 0.3.0. Die Format-Writer
  kennen die Java-Klasse aus `ColumnDescriptor.javaTypeName` und serialisieren
  über die folgende verbindliche Mapping-Tabelle.

#### 6.4.1 Java-Klasse → Format-Mapping (verbindlich für 0.3.0)

| Java-Klasse | JSON | YAML | CSV |
|---|---|---|---|
| `null` | `null` | `~` | `--null-string` (Default: leerer String) |
| `String` | String | String (quoted bei Sonderzeichen) | gequotet wenn Delimiter/CR/LF/Quote enthalten |
| `Boolean` | `true`/`false` | `true`/`false` | `true`/`false` |
| `Byte`/`Short`/`Integer`/`Long` | JSON-Number | YAML-Number | dezimal |
| `Float`/`Double` | JSON-Number; `NaN`/`Infinity` → JSON-String mit Warnung | dito | dito |
| `BigInteger` | **JSON-String** (Präzisionsschutz) | YAML-Number | dezimal |
| `BigDecimal` | **JSON-String** (Präzisionsschutz, kein Double-Roundtrip) | **YAML-String** | unformatiert (`toPlainString()`) |
| `java.sql.Date` | ISO 8601 `yyyy-MM-dd` | dito | dito |
| `java.sql.Time` | ISO 8601 `HH:mm:ss[.fff]` | dito | dito |
| `java.sql.Timestamp` | ISO 8601 `yyyy-MM-ddTHH:mm:ss[.fffffffff]` (lokal, ohne Offset) | dito | dito |
| `java.time.LocalDate` | ISO 8601 | dito | dito |
| `java.time.LocalDateTime` | ISO 8601 | dito | dito |
| `java.time.OffsetDateTime` | ISO 8601 mit Offset (`...+02:00`) | dito | dito |
| `java.time.Instant` | ISO 8601 UTC (`...Z`) | dito | dito |
| `java.util.UUID` | String | String | String |
| `byte[]` | **Base64** (RFC 4648 ohne Linebreaks) | **Base64** | **Base64** |
| `java.sql.Blob` | gelesen als byte[], dann Base64 | dito | dito |
| `java.sql.Clob` | gelesen als String | dito | dito |
| `java.sql.Array` | rekursive JSON-Array-Serialisierung | YAML-Sequence | nicht unterstützt → W201 + `null` |
| `java.sql.Struct` | nicht unterstützt → W201 + `toString()` | dito | dito |
| `org.postgresql.util.PGobject` | `getValue()` (String) | dito | dito |
| `org.postgresql.geometric.*` | `toString()` (PostgreSQL-Textformat) + W201 | dito | dito |
| MySQL `BIT(1)` (`Boolean`) | `true`/`false` | dito | dito |
| MySQL `BIT(>1)` (`byte[]`) | Base64 | Base64 | Base64 |
| MySQL/PG `JSON`/`JSONB` (String) | **eingebettet als JSON-Wert** (parsed) | dito | als String |
| Sonst (Fallback) | `value.toString()` + Warnung **W202** in den Export-Report | dito | dito |

**Fallback-Policy**: Unbekannte Java-Klassen werden mit `toString()`
serialisiert und erzeugen genau einmal pro `(table, column, javaClass)`-Tripel
eine Warnung **W202** im Export-Report (siehe `ExportResult.warnings`). Eine
Exception wirft der Writer nur dann, wenn der `toString()`-Aufruf selbst
fehlschlägt.

**Forward-Compat-Hinweise**:
- Sobald 0.5.5 (Erweitertes Typsystem) Spatial- und JSON-Spalten als
  first-class-NeutralType einführt, wird hier die Mapping-Tabelle um
  WKT/WKB-Repräsentationen für `geometry`/`geography` und einen typsicheren
  `JsonNode`-Pfad ergänzt. Aktuell sind diese Werte unter `PGobject` /
  `byte[]` / `String` (W201/W202) erfasst.
- Sobald der `SchemaReader` (0.6.0) den `neutralType` in `ColumnDescriptor`
  füllt, kann die Tabelle auf neutrale Typen statt Java-Klassen umgestellt
  werden.

### 6.5 NULL-Handling

| Format | NULL-Repräsentation |
|---|---|
| JSON | `null` |
| YAML | `~` (kanonisch) |
| CSV | leerer String (Default), via `--null-string` überschreibbar |

### 6.6 Encoding und BOM bei CSV

- Default-Encoding: `UTF-8`
- BOM optional via `--csv-bom` Flag (Default: ohne BOM)
- Andere Encodings (`utf-16`, `iso-8859-1`) via `--encoding` — JsonGenerator und
  YAMLGenerator bekommen einen `OutputStreamWriter` mit dem Charset, CSV
  ebenfalls

### 6.7 SQL-Injection-Schutz beim `--filter`

Der `--filter`-Parameter ist eine Roh-WHERE-Klausel. Sie wird **nicht
parametrisiert** — der CLI-Aufruf ist ein Trust-Boundary (lokale Shell). Aber:

- `--tables` wird strikt validiert: nur `[A-Za-z_][A-Za-z0-9_]*` (sowie
  schema-qualifizierte Form `schema.table`), Identifier werden über den
  bestehenden TypeMapper-Quoting-Mechanismus aus 0.2.0 escaped
- `--filter` Inhalt wird unverändert in `SELECT * FROM table WHERE <filter>`
  eingebaut, mit klarer Doku-Warnung
- Bei `--source` aus der Config darf der Connection-User minimale Rechte haben
  (read-only)

> **Forward-Compat-Hinweis**: Wenn 2.0.0 (LF-022) eine REST-API mit `data
> export`-Endpoint einführt, ist die CLI-Trust-Boundary nicht mehr gültig. Der
> `--filter`-Pfad MUSS dann an der API-Grenze re-validiert werden — entweder
> über Whitelist-Parser oder vollständige Parametrisierung. Diese Annahme
> wird in der API-Spezifikation explizit als blockierende Voraussetzung
> markiert.

### 6.8 Tabellen-Auflistung über `TableLister`

Die Auflistung der zu exportierenden Tabellen — wenn `--tables` nicht gesetzt
ist — erfolgt über den eigenständigen `TableLister`-Port (siehe §3.1). Dieser
bleibt strikt vom row-streaming-fokussierten `DataReader` getrennt (SRP).

In 0.6.0 wird `TableLister` durch den vollständigen `SchemaReader` (LF-004)
abgelöst. Da `DataReader` und `TableLister` unabhängig sind, ist diese
Migration ein additive Change ohne Auswirkung auf die row-streaming-Pfade.

### 6.9 ExportOutput: Datei vs. stdout

```kotlin
sealed class ExportOutput {
    object Stdout : ExportOutput()
    data class SingleFile(val path: Path) : ExportOutput()
    data class FilePerTable(val directory: Path) : ExportOutput()
}
```

**Auflösungsregel — explizit, keine Heuristik:**

| `--output` | `--split-files` | `--tables` | Resultat |
|---|---|---|---|
| nicht gesetzt | nicht gesetzt | leer / 1 Tabelle | `Stdout` |
| nicht gesetzt | nicht gesetzt | ≥2 Tabellen | **Exit 2** mit Hinweis auf `--split-files` |
| nicht gesetzt | gesetzt | beliebig | **Exit 2** (`--split-files` braucht `--output`-Verzeichnis) |
| Pfad zu Datei | nicht gesetzt | leer / 1 Tabelle | `SingleFile(path)` |
| Pfad zu Datei | nicht gesetzt | ≥2 Tabellen | **Exit 2** |
| Pfad zu Verzeichnis | gesetzt | beliebig | `FilePerTable(directory)` |
| Pfad zu Verzeichnis | nicht gesetzt | beliebig | **Exit 2** |

**Dateinamenschema bei `FilePerTable`** (löst Schema-Kollisionen aus M6):

| Eingabe in `--tables` | Resultierender Dateiname |
|---|---|
| `users` | `users.<format>` |
| `public.orders` | `public.orders.<format>` |
| `reporting.orders` | `reporting.orders.<format>` |
| `weird name` | per Validierung in §6.7 abgelehnt |

Der Punkt zwischen Schema und Tabelle bleibt erhalten — er ist auf allen
relevanten Dateisystemen (ext4, NTFS, APFS, tmpfs) erlaubt und macht die
Herkunft beim manuellen Inspizieren offensichtlich. Kollisionen zwischen
`public.orders` und `reporting.orders` sind damit ausgeschlossen.

### 6.10 Exit-Code-Matrix

| Code | Trigger | Beispiel-Meldung |
|---|---|---|
| `0` | Erfolgreicher Export, alle Tabellen geschrieben | `Exported 3 tables (12 345 rows, 4.2 MB) in 1.8 s` |
| `2` | Ungültige CLI-Optionen oder unverträgliche Flag-Kombination (siehe §6.9) | `--tables a,b requires --output <dir> --split-files` |
| `4` | Connection-Fehler (HikariCP wirft, kein `SELECT 1` möglich) | `Connection refused: localhost:5432` |
| `5` | Export-Fehler (SQLException, IOException während Streaming, Writer-Failure) | `Failed to export table 'orders' at chunk 47: ResultSet closed` |
| `7` | Konfigurationsfehler (URL-Parser, fehlende ENV-Vars, Named-Connection in 0.3.0) | `Named connections not yet supported in 0.3.0, pass URL directly` |

Die Codes folgen `cli-spec.md` §6.2 und sind konsistent mit `schema validate`
(0.1.0) und `schema generate` (0.2.0).

### 6.11 Password-Scrubbing in Logs und Reports

Gemäß `connection-config-spec.md` §4.3 dürfen Passwörter und
Connection-URLs nie unmaskiert geloggt werden. Implementierung:

- `ConnectionConfig.toString()` wird überschrieben — `password` wird durch
  `***` ersetzt, wenn nicht-null
- Ein zentraler `LogScrubber.maskUrl(String): String` ersetzt das Passwort in
  beliebigen URL-Strings (regex-basiert nach `://user:` Pattern), wird vor
  jedem Log-Aufruf benutzt der eine URL enthält (`StreamingExporter`-Logging,
  Fehlermeldungen in Exit-Code 4/7)
- Alle Test-Cases von `ConnectionUrlParserTest` enthalten einen
  `toString()`-Assertion, dass `***` statt des Klartexts erscheint
- API-Keys (relevant in 1.1.0+) werden in 0.3.0 noch nicht behandelt — Hinweis
  im KDoc von `LogScrubber`

### 6.12 Transaktions-Lifecycle für Read

Jede `streamTable`-Aufruf öffnet **eine eigene Transaktion** im autoCommit=
false-Modus (notwendig für PostgreSQL-Cursor-Streaming). Der Lifecycle ist
streng an `ChunkSequence.close()` gekoppelt:

```
open():
    conn = pool.borrow()
    savedAutoCommit = conn.autoCommit
    conn.autoCommit = false
    stmt = conn.prepareStatement(sql, FORWARD_ONLY, READ_ONLY)
    stmt.fetchSize = driverDefault   // siehe §6.13
    rs = stmt.executeQuery()

close():       // idempotent
    try { rs?.close() } catch (_) {}
    try { stmt?.close() } catch (_) {}
    try { conn.rollback() } catch (_) {}        // verwerfen, da read-only
    try { conn.autoCommit = savedAutoCommit } catch (_) {}
    pool.return(conn)
```

**Garantien**:
- HikariCP gibt nie eine Connection mit offener Transaktion oder
  abweichendem `autoCommit`-Flag zurück in den Pool
- `close()` ist sicher mehrfach aufrufbar
- Auch bei Exception während der Iteration wird `close()` über
  `ChunkSequence.use { }` (oder die `forEach`-Auto-Cleanup-Logik im
  `StreamingExporter`) garantiert ausgeführt

Tests: ein `ConnectionLeakTest` borgt eine Connection, iteriert mit
absichtlicher Exception in der Mitte, prüft anschließend
`HikariPoolMXBean.activeConnections == 0`.

### 6.13 Treiber-interne Streaming-Tuning-Konstanten

`fetchSize` und verwandte Tuning-Werte gehören **nicht** in `PipelineConfig`,
weil ihre korrekte Form pro Treiber unterschiedlich ist und der Nutzer das
nicht intuitiv steuern kann:

| Treiber | Tuning | Begründung |
|---|---|---|
| PostgreSQL | `setFetchSize(1000)` + `setAutoCommit(false)` | Standard-Cursor-Pfad |
| MySQL | URL-Parameter `useCursorFetch=true` + `setFetchSize(1000)` | siehe §3.3 — gegen `Integer.MIN_VALUE` |
| SQLite | keiner — JDBC liest ohnehin lazy aus der Datei | kein Cursor-Konzept |

Die Werte sind als `private const val` in den jeweiligen `*DataReader`-Klassen
dokumentiert. **Wenn** sich später ein User-Tuning-Bedarf zeigt, kann ein
optionales `Map<DatabaseDialect, Int>` in `PipelineConfig` ergänzt werden —
für 0.3.0 YAGNI.

### 6.14 Named Connections sind 0.4.0-Scope

`connection-config-spec.md` §3 + §5 beschreiben `.d-migrate.yaml` mit
benannten Verbindungen. **In 0.3.0 unterstützt der CLI nur volle URLs.** Wenn
`--source` keinen `://`-Marker enthält, beendet der Command mit Exit 7 und
der Meldung:

```
Named connections (.d-migrate.yaml) are not yet supported in 0.3.0.
Pass a full URL like postgresql://user@host/db.
Tracking: planned for 0.4.0 alongside `data import`.
```

Begründung: Named Connections sind ein eigenständiges Feature mit voller
Config-Loader-Story (Multi-Quelle, Profile, ENV-Substitution, Validierung).
Die zweite CLI-Familie `data import` (0.4.0) braucht denselben Loader — beide
zusammen zu bauen vermeidet zwei halbe Implementierungen.

### 6.15 `--format` ist Pflicht (kein Default)

Stille Defaults wie `--format=json` führen erfahrungsgemäß zu Verwirrung beim
Debuggen großer Exporte. Der CLI-Command erzwingt explizite Angabe — Clikt's
`.required()` liefert Exit-Code 2 mit der Standard-Usage-Meldung, wenn das
Flag fehlt.

### 6.16 Testcontainers im separaten Workflow

PostgreSQL/MySQL-Tests via Testcontainers brauchen einen Docker-Daemon und
sind ~30-60 s langsamer pro Container-Start. Sie würden den 5-Minuten-CI-
Budget aus dem Lastenheft (§5.1.5) sprengen.

**Lösung**: Tag-basierte Trennung.

- Testcontainers-Tests werden mit JUnit-`@Tag("integration")` markiert
- Default-Test-Task `./gradlew test` schließt `integration`-Tag aus
- Neuer Workflow `.github/workflows/integration.yml` läuft auf Push/PR und
  ruft `./gradlew test -PintegrationTests` (aktiviert den Tag)
- Default-Workflow `build.yml` bleibt unter 5 Minuten

## 7. Dateien (geschätzt ~30 neue Dateien)

```
# Neues Modul d-migrate-streaming
d-migrate-streaming/build.gradle.kts
d-migrate-streaming/src/main/kotlin/dev/dmigrate/streaming/StreamingExporter.kt
d-migrate-streaming/src/main/kotlin/dev/dmigrate/streaming/PipelineConfig.kt
d-migrate-streaming/src/main/kotlin/dev/dmigrate/streaming/ExportResult.kt
d-migrate-streaming/src/test/kotlin/dev/dmigrate/streaming/StreamingExporterTest.kt

# Erweiterung d-migrate-driver-api
d-migrate-driver-api/src/main/kotlin/dev/dmigrate/driver/connection/ConnectionConfig.kt
d-migrate-driver-api/src/main/kotlin/dev/dmigrate/driver/connection/PoolSettings.kt
d-migrate-driver-api/src/main/kotlin/dev/dmigrate/driver/connection/ConnectionUrlParser.kt
d-migrate-driver-api/src/main/kotlin/dev/dmigrate/driver/connection/DatabaseConnection.kt
d-migrate-driver-api/src/main/kotlin/dev/dmigrate/driver/connection/ConnectionPool.kt
d-migrate-driver-api/src/main/kotlin/dev/dmigrate/driver/connection/HikariConnectionPoolFactory.kt
d-migrate-driver-api/src/main/kotlin/dev/dmigrate/driver/connection/LogScrubber.kt
d-migrate-driver-api/src/main/kotlin/dev/dmigrate/driver/data/DataReader.kt
d-migrate-driver-api/src/main/kotlin/dev/dmigrate/driver/data/ChunkSequence.kt
d-migrate-driver-api/src/main/kotlin/dev/dmigrate/driver/data/TableLister.kt
d-migrate-driver-api/src/main/kotlin/dev/dmigrate/driver/data/DataChunk.kt
d-migrate-driver-api/src/main/kotlin/dev/dmigrate/driver/data/ColumnDescriptor.kt
d-migrate-driver-api/src/main/kotlin/dev/dmigrate/driver/data/DataFilter.kt
d-migrate-driver-api/src/main/kotlin/dev/dmigrate/driver/data/DataReaderRegistry.kt
d-migrate-driver-api/src/test/kotlin/dev/dmigrate/driver/connection/ConnectionUrlParserTest.kt
d-migrate-driver-api/src/test/kotlin/dev/dmigrate/driver/connection/LogScrubberTest.kt

# Treiber-Erweiterungen
d-migrate-driver-postgresql/src/main/kotlin/.../PostgresJdbcUrlBuilder.kt
d-migrate-driver-postgresql/src/main/kotlin/.../PostgresDataReader.kt
d-migrate-driver-postgresql/src/main/kotlin/.../PostgresTableLister.kt
d-migrate-driver-postgresql/src/test/kotlin/.../PostgresJdbcUrlBuilderTest.kt
d-migrate-driver-postgresql/src/test/kotlin/.../PostgresDataReaderTest.kt   # @Tag("integration")

d-migrate-driver-mysql/src/main/kotlin/.../MysqlJdbcUrlBuilder.kt
d-migrate-driver-mysql/src/main/kotlin/.../MysqlDataReader.kt
d-migrate-driver-mysql/src/main/kotlin/.../MysqlTableLister.kt
d-migrate-driver-mysql/src/test/kotlin/.../MysqlJdbcUrlBuilderTest.kt
d-migrate-driver-mysql/src/test/kotlin/.../MysqlDataReaderTest.kt           # @Tag("integration")

d-migrate-driver-sqlite/src/main/kotlin/.../SqliteJdbcUrlBuilder.kt
d-migrate-driver-sqlite/src/main/kotlin/.../SqliteDataReader.kt
d-migrate-driver-sqlite/src/main/kotlin/.../SqliteTableLister.kt
d-migrate-driver-sqlite/src/test/kotlin/.../SqliteJdbcUrlBuilderTest.kt
d-migrate-driver-sqlite/src/test/kotlin/.../SqliteDataReaderTest.kt          # inline, file-DB

# Format-Erweiterungen
d-migrate-formats/src/main/kotlin/dev/dmigrate/format/data/DataChunkWriter.kt
d-migrate-formats/src/main/kotlin/dev/dmigrate/format/data/DataChunkWriterFactory.kt
d-migrate-formats/src/main/kotlin/dev/dmigrate/format/data/DataExportFormat.kt
d-migrate-formats/src/main/kotlin/dev/dmigrate/format/data/ExportOptions.kt
d-migrate-formats/src/main/kotlin/dev/dmigrate/format/data/json/JsonChunkWriter.kt
d-migrate-formats/src/main/kotlin/dev/dmigrate/format/data/yaml/YamlChunkWriter.kt
d-migrate-formats/src/main/kotlin/dev/dmigrate/format/data/csv/CsvChunkWriter.kt
d-migrate-formats/src/test/kotlin/.../JsonChunkWriterTest.kt
d-migrate-formats/src/test/kotlin/.../YamlChunkWriterTest.kt
d-migrate-formats/src/test/kotlin/.../CsvChunkWriterTest.kt

# CLI
d-migrate-cli/src/main/kotlin/dev/dmigrate/cli/commands/DataCommands.kt
d-migrate-cli/src/test/kotlin/dev/dmigrate/cli/CliDataExportTest.kt           # SQLite

# Geänderte Dateien
settings.gradle.kts                                                          # +d-migrate-streaming
gradle.properties                                                            # +5 versions
build.gradle.kts                                                             # kover-Aggregation für streaming + driver-* Module
d-migrate-driver-api/build.gradle.kts                                        # +HikariCP api
d-migrate-driver-postgresql/build.gradle.kts                                 # +postgresql jdbc
d-migrate-driver-mysql/build.gradle.kts                                      # +mysql connector
d-migrate-driver-sqlite/build.gradle.kts                                     # +sqlite jdbc
d-migrate-formats/build.gradle.kts                                           # +jackson-csv, +driver-api dep
d-migrate-cli/build.gradle.kts                                               # +streaming
.github/workflows/build.yml                                                  # +streaming + driver-* koverVerify (rückwirkend für 0.2.0-Module)
.github/workflows/integration.yml                                            # NEU — @Tag("integration")-Job
docs/cli-spec.md                                                             # `--incremental` aus 0.3.0-Block entfernen → 0.4.0
docs/architecture.md                                                         # §2.2 Dependency-Kante streaming↔formats, §3.1 DataReader-Signatur ohne fetchSize
docs/connection-config-spec.md                                               # Status: Entwurf → Stand 0.3.0 (URL-Parser umgesetzt)
```

## 8. Coverage-Ziele

| Modul | Ziel | Schwerpunkt |
|---|---|---|
| `d-migrate-driver-api` (connection + data) | 90% | ConnectionUrlParser 100%, JdbcUrlBuilder pro Dialekt, LogScrubber 100% |
| `d-migrate-driver-postgresql` | 90% gesamt; DataReader-Pfad via Testcontainers | URL-Builder 100%, TableLister 100% |
| `d-migrate-driver-mysql` | 90% gesamt | wie PG; `useCursorFetch`-Setzung im Builder verifiziert |
| `d-migrate-driver-sqlite` | 90% gesamt | DataReader inline mit file-DB |
| `d-migrate-streaming` (neu) | 90% | StreamingExporter mit Fake Reader/Writer; ConnectionLeakTest |
| `d-migrate-formats` (data/) | 90% | je Format Golden-Master-Tests; Type-Mapping-Tabelle (§6.4.1) zeilenweise abgedeckt |
| `d-migrate-cli` | bisher 50% → 60% | DataExportCommand-Pfade |

**Begründung CLI-Ziel 60% statt 90%**: CLI-Kommandos bestehen zum großen Teil
aus I/O-Glue (Argument-Parsing, Pool-Aufbau, Output-Streaming), das sich nur
durch Integration-Tests sinnvoll abdecken lässt. Die fachliche Logik (URL-
Parsing, Streaming, Format-Codec) ist in den jeweiligen Modulen abgedeckt
(90%). Ein Push der CLI auf 90% würde redundante Mock-Tests erzwingen, ohne
echte Bug-Klassen zu finden.

**ConnectionUrlParser-Tests: 100% Coverage** — jeder Dialekt-Alias, jede SQLite-
Sonderform, URL-encoded Sonderzeichen, fehlende Felder, ungültige URLs.

**LogScrubber-Tests: 100% Coverage** — jede Maskierungsregel aus §6.11 mit
expliziten Assertions, dass Klartext-Passwort nicht im Output erscheint.

**Hinweis Workflow**: `.github/workflows/build.yml` ruft `koverVerify` aktuell
nur für `core/formats/cli` auf. **Mit 0.3.0 muss der Workflow um
`d-migrate-streaming` und (rückwirkend für 0.2.0 nachgeholt) die drei
Driver-Module ergänzt werden** — `docs/releasing.md` §3.4 weist darauf hin.

## 9. Verifikation

```bash
# Vollständiger Build inkl. Tests
docker build -t d-migrate:dev .

# Nur die neuen Module
./gradlew :d-migrate-streaming:test :d-migrate-driver-api:test

# Testcontainers-Tests (benötigen laufenden Docker-Daemon)
./gradlew :d-migrate-driver-postgresql:test --tests "*DataReaderTest*"
./gradlew :d-migrate-driver-mysql:test --tests "*DataReaderTest*"

# CLI Smoke-Test mit SQLite (kein Container)
./gradlew :d-migrate-cli:installDist
./d-migrate-cli/build/install/d-migrate-cli/bin/d-migrate-cli \
    data export --source sqlite:///tmp/test.db --format json --tables users

# CLI Smoke-Test mit Docker-Image gegen ad-hoc PostgreSQL
docker run --rm -d --name pg-test -e POSTGRES_PASSWORD=test -p 5432:5432 postgres:16
docker run --rm --network host ghcr.io/pt9912/d-migrate:0.3.0 \
    data export --source postgresql://postgres:test@localhost/postgres \
                --format yaml --tables pg_database
docker stop pg-test
```

## 10. Risiken

| Risiko | Mitigation |
|---|---|
| JDBC ResultSet-Streaming subtil pro Dialekt | Pro Dialekt fixierte Tuning-Konstante in §6.13; Cursor-Pfad in PG via `setFetchSize`+`autoCommit=false`+`rollback`; MySQL via `useCursorFetch=true` (siehe §3.3); E2E-Test mit ≥100k Rows pro Dialekt; Memory-Profiling im Test |
| HikariCP + SQLite (Pool-Verhalten bei file-DB) | poolSize=1 hardcoded; Test mit `:memory:` und file-DB |
| Testcontainers in CI langsam / flaky | `@Tag("integration")` + separater Workflow `integration.yml` (siehe §6.16); `build.yml` bleibt unter 5 Minuten |
| Coroutines/Flow-Adoption verfrüht | `ChunkSequence` statt Flow für 0.3.0 (siehe §6.1); Migrationspfad dokumentiert |
| Sequence-Doppelnutzung (Reuse-Bug, JDBC-Cursor nicht restartable) | `ChunkSequence` Single-Use-Contract mit `IllegalStateException` (§6.1) |
| Connection-Leak durch offene Transaktion / abweichendes autoCommit | §6.12 Transaktions-Lifecycle mit idempotentem `close()`; `ConnectionLeakTest` über `HikariPoolMXBean.activeConnections` |
| Jackson `jackson-dataformat-csv` Encoding-Bugs mit BOM | Eigener BOM-Writer vor dem CsvSchema-Output, nicht Jackson überlassen |
| Type-Serialisierung uneindeutig (BigDecimal, byte[], PG-Spezifika) | Verbindliche Mapping-Tabelle in §6.4.1 mit Fallback-Policy (W202) und Forward-Compat-Notiz für 0.5.5 |
| Schema-qualifizierte Tabellen kollidieren bei FilePerTable | Dateinamen-Schema `<schema>.<table>.<format>` (§6.9) |
| `--filter` öffnet SQL-Injection (CLI ist Trust-Boundary, REST-API später nicht) | Doku-Warnung; minimale read-only Credentials empfohlen; striktes `--tables`-Identifier-Pattern; §6.7 Forward-Compat-Hinweis für REST-API (2.0.0) |
| Passwörter in Logs / Reports | §6.11 Password-Scrubbing zentral im LogScrubber + überschriebenem `ConnectionConfig.toString()` |
| Dateigröße bei Single-File-Output > Plattenplatz | Stream direkt in Datei (keine Pufferung in Memory); progress logging mit `bytesWritten` |
| Default `chunkSize=10_000` zu groß für sehr breite Tabellen (BLOB-Spalten) | `--chunk-size` Flag dokumentiert; Risiko-Hinweis in Guide |
| Named Connections fehlen, Nutzer erwartet sie | Klare Fehlermeldung (§6.14) mit Verweis auf 0.4.0; URL-only-Pfad ist vollständig dokumentiert |

## 11. Aufgelöste Review-Fragen

Die ursprünglichen offenen Fragen aus dem Review-Durchgang sind verbindlich
entschieden und in den Plan eingearbeitet:

| # | Frage | Entscheidung | Verankert in |
|---|---|---|---|
| 1 | Sequence vs. Flow | `ChunkSequence` (Sequence-basiert, single-use, AutoCloseable) | §6.1 |
| 2 | `d-migrate-streaming` als eigenes Modul | Ja — eigenes Modul (Checkpoint/Parallel-Executor folgen) | §2.1 |
| 3 | Named Connections aus `.d-migrate.yaml` | Minimal in 0.3.0: nur URLs, sonst Exit 7. Vollständiger Loader in 0.4.0 | §6.14 |
| 4 | `listTables()` Verortung | Eigenständiger `TableLister`-Port, nicht im DataReader | §3.1, §6.8 |
| 5 | `--format` Pflichtfeld vs. Default | Pflicht (Clikt `.required()`, Exit 2 wenn fehlt) | §6.15 |
| 6 | Single-File vs. FilePerTable | Explizites `--split-files` Flag, keine Heuristik; Auflösungstabelle in §6.9 | §6.9 |
| 7 | Testcontainers im Default-CI | Tag-basierte Trennung, separater `integration.yml`-Workflow | §6.16 |

---

**Referenzen**:
- [Architektur §3.1](./architecture.md) — Driver-/Streaming-Konzept
- [Lastenheft](./lastenheft-d-migrate.md) — LF-008/LF-009, LN-005/LN-007/LN-048
- [CLI-Spezifikation §6.2](./cli-spec.md) — `data export` Command
- [Connection- und Konfigurationsspezifikation](./connection-config-spec.md) — URL-Format §1, HikariCP §2, Profile §5
- [Roadmap Milestone 0.3.0](./roadmap.md)
- [Releasing Guide](./releasing.md) — Voraussetzungen für den 0.3.0-Release
