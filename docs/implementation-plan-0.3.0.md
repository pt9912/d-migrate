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
    ├── PipelineConfig.kt           # nur chunkSize (siehe §6.13)
    └── ExportResult.kt             # Statistiken: rows, chunks, durationMs, bytesWritten
```

```kotlin
class StreamingExporter(
    private val reader: DataReader,
    private val tableLister: TableLister,
    private val writerFactory: DataChunkWriterFactory
) {
    /**
     * Orchestriert den Export. Bekommt einen ConnectionPool — Reader und
     * TableLister borgen sich daraus selbst Connections (siehe §6.12).
     * Der Exporter hält keine eigene JDBC-Connection.
     */
    fun export(
        pool: ConnectionPool,
        tables: List<String>,                  // leer = via tableLister.listTables(pool) ermitteln
        output: ExportOutput,                  // file/stdout + format options
        config: PipelineConfig
    ): ExportResult
}

data class PipelineConfig(
    val chunkSize: Int = 10_000               // einziger user-tunable Parameter; fetchSize ist treiberintern (§6.13)
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
- Hängt nur von `core` (Datenmodell), `driver-api` (Reader-Ports + Pool) und
  `formats` (Writer) ab, kennt keinen CLI-Code.

## 3. Bestehende Module — Änderungen

### 3.1 `d-migrate-driver-api`

Erweitert um den Connection- und Daten-Lese-Layer. **Kein** neues Aggregat-
Interface `DatabaseDriver` (siehe Architektur §3.1) — für 0.3.0 reicht ein
schlanker `DataReader`-Port; das Aggregat folgt in 0.4.0/0.6.0, wenn auch
`DataWriter` und `SchemaReader` dazukommen.

Die Daten-Modell-Klassen (`DataChunk`, `ColumnDescriptor`, `DataFilter`)
liegen **nicht** hier, sondern in `d-migrate-core/data/` (siehe §3.7 und
F3-Begründung), damit `d-migrate-formats` JDBC-frei bleibt.

```
d-migrate-driver-api/
└── src/main/kotlin/dev/dmigrate/driver/
    ├── connection/
    │   ├── ConnectionConfig.kt          # URL, credentials, pool settings
    │   ├── PoolSettings.kt
    │   ├── ConnectionUrlParser.kt       # parse → ConnectionConfig
    │   ├── ConnectionPool.kt            # AutoCloseable; borrow()/close() Semantik
    │   ├── HikariConnectionPoolFactory.kt
    │   └── LogScrubber.kt               # Password-Maskierung für Logs (§6.11)
    └── data/
        ├── DataReader.kt                # Port-Interface (row streaming)
        ├── TableLister.kt               # Port-Interface (Tabellen-Auflistung)
        ├── ChunkSequence.kt             # Single-use Sequence + AutoCloseable (§6.1)
        └── DataReaderRegistry.kt        # dialect → DataReader / TableLister (0.6.0: ServiceLoader)
```

```kotlin
/**
 * Pool-Wrapper. Owner aller HikariDataSource-Operationen.
 *
 * - borrow() liefert eine ausgeliehene java.sql.Connection. Hikari wrappt
 *   die Connection so, dass `connection.close()` sie NICHT physisch schließt,
 *   sondern in den Pool zurückgibt. Der Caller MUSS sie mit `close()`
 *   freigeben (idiomatisch via `pool.borrow().use { conn -> ... }`).
 *   Es gibt KEINE separate `return()`-Methode — das ist Hikari-Standard.
 * - close() schließt den gesamten Pool (am Ende des CLI-Aufrufs).
 *
 * Reader und TableLister bekommen den Pool, NICHT eine fertige Connection
 * (siehe F1-Klärung in §6.18). Damit vermeidet der Plan doppelten
 * Connection-Besitz.
 */
interface ConnectionPool : AutoCloseable {
    val dialect: DatabaseDialect
    fun borrow(): java.sql.Connection
    fun activeConnections(): Int       // für ConnectionLeakTest, siehe §6.12
}

interface DataReader {
    val dialect: DatabaseDialect

    /**
     * Single-use, pull-basierter Stream über die Daten einer Tabelle.
     *
     * Connection-Ownership:
     *   - Der Reader borgt sich die JDBC-Connection PRO Aufruf selbst aus
     *     dem übergebenen Pool. Der Caller besitzt KEINE Connection.
     *   - ChunkSequence.close() führt Rollback + autoCommit-Reset aus und
     *     ruft conn.close() auf, was die Hikari-wrapped Connection in den
     *     Pool zurückführt (siehe §6.12).
     *
     * Vertrag (siehe §6.1, §6.12, §6.17):
     *   - Die zurückgegebene ChunkSequence darf GENAU EINMAL iteriert werden.
     *     Eine zweite Iteration wirft IllegalStateException.
     *   - Der Caller MUSS die Sequence vollständig konsumieren ODER via
     *     use {} schließen, sonst leakt die Connection.
     *   - Auch bei einer LEEREN Tabelle (0 Rows) MUSS mindestens ein Chunk
     *     mit `columns` und `rows = emptyList()` emittiert werden, damit
     *     Format-Writer ihren Header schreiben können (siehe §6.17).
     *   - Die fetchSize-Tuningkonstante ist Treiber-intern und nicht
     *     parametrisiert (siehe §6.13).
     */
    fun streamTable(
        pool: ConnectionPool,
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
 *
 * Connection-Ownership: borgt sich die Connection selbst aus dem Pool;
 * gibt sie nach dem Listing sofort zurück.
 */
interface TableLister {
    val dialect: DatabaseDialect
    fun listTables(pool: ConnectionPool): List<String>
}

data class ConnectionConfig(
    val dialect: DatabaseDialect,
    val host: String?,
    val port: Int?,
    val database: String,
    val user: String?,
    val password: String?,                   // sensitiv — niemals loggen, siehe §6.11
    val params: Map<String, String> = emptyMap(),
    val pool: PoolSettings = PoolSettings()
) {
    /** Maskiert das Passwort als '***'. Siehe §6.11. */
    override fun toString(): String { /* implementation */ }
}

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

**Wichtig**: Die Format-Writer importieren `DataChunk`/`ColumnDescriptor`
aus **`d-migrate-core/data/`** (siehe §3.7) — `d-migrate-formats` bekommt
**keine** Abhängigkeit auf `d-migrate-driver-api`. Damit bleibt das
Formats-Modul JDBC-frei und die Architekturkante `formats → core` aus
`docs/architecture.md` §2.2 wird nicht verletzt.

```
d-migrate-formats/
└── src/main/kotlin/dev/dmigrate/format/
    └── data/
        ├── DataChunkWriter.kt           # Port-Interface (Streaming)
        ├── DataChunkWriterFactory.kt    # format → writer
        ├── DataExportFormat.kt          # enum: JSON, YAML, CSV
        ├── ExportOptions.kt             # encoding, BOM, csvHeader, csvDelimiter, csvQuote, csvNullString
        ├── ValueSerializer.kt           # Java-Klasse → Format-Repräsentation (§6.4.1)
        ├── json/JsonChunkWriter.kt
        ├── yaml/YamlChunkWriter.kt
        └── csv/CsvChunkWriter.kt
```

```kotlin
data class ExportOptions(
    val encoding: Charset = StandardCharsets.UTF_8,
    val csvHeader: Boolean = true,            // Default: Header schreiben — siehe §6.17
    val csvDelimiter: Char = ',',
    val csvQuote: Char = '"',
    val csvBom: Boolean = false,
    val csvNullString: String = "",            // CSV NULL-Repräsentation
)
```

```kotlin
interface DataChunkWriter : AutoCloseable {
    /** Wird einmal vor dem ersten Chunk aufgerufen. */
    fun begin(table: String, columns: List<ColumnDescriptor>)

    /**
     * Wird pro Chunk aufgerufen. Schreibt die Rows direkt in den Output-Stream.
     * Bei einem leeren Chunk (rows.isEmpty()) ist der Aufruf erlaubt — der
     * Writer schreibt nichts (siehe §6.17).
     */
    fun write(chunk: DataChunk)

    /** Wird nach dem letzten Chunk aufgerufen. Schließt offene Container (z.B. JSON-Array). */
    fun end()
}
```

**Format-spezifische Streaming-Strategien:**

| Format | Strategie | Container | Leere Tabelle | NULL |
|---|---|---|---|---|
| JSON | JsonGenerator (Jackson Streaming-API), Array-of-Objects | `[\n  {...},\n  {...}\n]` | `[]` | `null` |
| YAML | YAMLGenerator, Sequence-of-Maps | `- col1: ...\n  col2: ...\n- col1: ...` | `[]` | `~` |
| CSV | CsvSchema dynamisch aus columns; Header steuerbar via `--csv-no-header` (Default: an) | Header + Datenzeilen | nur Header-Zeile (oder leere Datei mit `--csv-no-header`) | `--null-string` (Default: leerer String) |

Detaillierte Java-Klasse → Format-Mapping in §6.4.1.

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
    val source by option("--source").required()                // URL ODER Name aus .d-migrate.yaml (§6.14)
    val format by option("--format").choice("json", "yaml", "csv").required()  // PFLICHT (§6.15)
    val output by option("--output").path()                    // default: stdout
    val tables by option("--tables").split(",")                // optional, default: alle
    val filter by option("--filter")                           // SQL WHERE clause (§6.7)
    val encoding by option("--encoding").default("utf-8")
    val chunkSize by option("--chunk-size").int().default(10_000)
    val splitFiles by option("--split-files").flag()           // §6.9
    val csvDelimiter by option("--csv-delimiter").default(",")
    val csvBom by option("--csv-bom").flag()
    val csvNoHeader by option("--csv-no-header").flag()        // §6.17 — Default: Header an
    val nullString by option("--null-string").default("")      // CSV-NULL-Repräsentation

    override fun run() {
        // 0. Root-Command holen — analog zu SchemaValidateCommand in 0.2.0
        //    (SchemaCommands.kt:44). Hierarchie ist d-migrate → data → export,
        //    der Root-Command sitzt also ZWEI Parent-Hops weiter oben:
        val root = currentContext.parent?.parent?.command as? DMigrate
        val configPath: Path? = root?.config
            ?: System.getenv("D_MIGRATE_CONFIG")?.let(::Path)

        // 1. NamedConnectionResolver(configPath).resolve(source) → vollständige URL
        //    Behandelt URL-Direktnutzung, Connection-Name-Lookup, ${ENV_VAR}-Substitution.
        //    default_source aus YAML wird gelesen aber für data export ignoriert (§6.14)
        // 2. ConnectionUrlParser.parse(url) → ConnectionConfig
        // 3. HikariConnectionPoolFactory.create(config).use { pool -> ... }
        // 4. DataReader + TableLister für Dialect aus DataReaderRegistry holen
        // 5. tables ermitteln (--tables oder tableLister.listTables(pool))
        //    Mehr-Tabellen-Output ohne --split-files → Exit 2 (§6.9)
        // 6. DataChunkWriter aus format + ExportOptions (mit csvHeader = !csvNoHeader) erzeugen
        // 7. StreamingExporter(reader, tableLister, factory).export(pool, ...)
        // 8. ExportResult als ProgressSummary ausgeben (stderr)
    }
}
```

> **Hinweis zum Root-Command-Zugriff**: Das `--config`/`-c` Flag liegt am
> Root-Command `DMigrate` (`Main.kt:26`), nicht am direkten Parent
> `DataCommand`. Bei der Hierarchie `d-migrate → data → export` sind das
> ZWEI Parent-Hops, also `currentContext.parent?.parent?.command as? DMigrate`.
> Das identische Pattern wird in `SchemaCommands.kt:44` für `cliContext()`
> verwendet — bitte nicht durch `parent.config` o.ä. ersetzen, das wäre der
> falsche Hop.

Registrierung in `Main.kt` (analog zu `SchemaCommand` in 0.2.0).

**Tabellen-Auflistung wenn `--tables` fehlt**: erfolgt über den eigenständigen
`TableLister`-Port (siehe §3.1). Der CLI-Code kennt nur das Interface, die
dialektspezifischen SQL-Queries (`information_schema.tables` für PG/MySQL,
`sqlite_master` für SQLite) liegen in den jeweiligen `*TableLister`-Adaptern
in den Treibermodulen. In 0.6.0 wird dieser Port nahtlos durch den
vollständigen `SchemaReader` (LF-004) abgelöst.

### 3.7 `d-migrate-core`

Erweitert um ein **neutrales Daten-Modell** als Pendant zum bestehenden
neutralen Schema-Modell. Diese Klassen sind explizit JDBC-frei (nur Strings,
Booleans, `Any?`) und werden sowohl von `d-migrate-driver-api` (zum Befüllen)
als auch von `d-migrate-formats` (zum Serialisieren) und `d-migrate-streaming`
(zum Durchreichen) benutzt — siehe F3-Begründung.

```
d-migrate-core/
└── src/main/kotlin/dev/dmigrate/core/
    └── data/
        ├── DataChunk.kt
        ├── ColumnDescriptor.kt
        └── DataFilter.kt
```

```kotlin
data class DataChunk(
    val table: String,
    val columns: List<ColumnDescriptor>,     // wiederholt sich pro Chunk; günstig für Streaming-Writer
    val rows: List<Array<Any?>>,             // raw Java-Werte (kein JDBC-Typ)
    val chunkIndex: Long
)

data class ColumnDescriptor(
    val name: String,
    val nullable: Boolean,
    /**
     * Opaker DB-Type-Name aus ResultSetMetaData#getColumnTypeName().
     * In 0.3.0 nur informativ; in 0.6.0 wird der vollständige Reverse-Mapper
     * darauf einen `neutralType: NeutralType` ableiten.
     */
    val sqlTypeName: String? = null
)

sealed class DataFilter {
    /** Roh-WHERE-Klausel — siehe SQL-Injection-Schutz §6.7. */
    data class WhereClause(val sql: String) : DataFilter()
    data class ColumnSubset(val columns: List<String>) : DataFilter()
    data class Compound(val parts: List<DataFilter>) : DataFilter()
}
```

**Begründung der Verortung in `core`** (statt in `driver-api`):
- `core` enthält bereits das neutrale Schema-Modell (`SchemaDefinition`,
  `NeutralType`). Das Daten-Modell ist sein direktes Pendant.
- Würde `DataChunk` in `driver-api` liegen, müsste `formats` an `driver-api`
  hängen. Das verletzt die Architektur-Regel `formats → core` aus
  `architecture.md` §2.2 und koppelt das Format-Modul an JDBC-/Treiberkonzepte.
- Die Felder sind reine Strings/Booleans/`Any?` — kein JDBC-Import nötig,
  semantisch neutral.
- Format-Writer dispatchen über `value::class` zur Laufzeit, nicht über
  einen Java-Type-Hint im `ColumnDescriptor`. Damit braucht das Modell
  keinen JDBC-spezifischen `javaTypeName`.

`ConnectionConfig`, `ConnectionPool` etc. **bleiben** in `driver-api` —
das sind echte JDBC-/Pool-Konzepte und in `formats` nicht benötigt.

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
| `d-migrate-streaming` | (neu, hängt von core + driver-api + formats ab — siehe §5) |
| `d-migrate-cli` | `implementation(project(":d-migrate-streaming"))` |
| Tests (PG/MySQL) | `org.testcontainers:postgresql`, `org.testcontainers:mysql`, `org.testcontainers:junit-jupiter` |

## 4. Implementierungsreihenfolge

### Phase A: Daten-Modell in `core` + Connection-Layer in `driver-api`

1. `gradle.properties` um neue Versionen ergänzen
2. **In `d-migrate-core/data/`**: `DataChunk`, `ColumnDescriptor`, `DataFilter`
   (siehe §3.7 / F3) — neutrales JDBC-freies Modell
3. **In `d-migrate-driver-api/connection/`**: `ConnectionConfig`, `PoolSettings`,
   `ConnectionPool`-Interface, `LogScrubber`
4. `ConnectionUrlParser` mit vollständiger Parser-Logik nach
   `connection-config-spec.md` §1 (inkl. Aliase, URL-Encoding, SQLite-Sonderformen)
5. `HikariConnectionPoolFactory` mit dialekt-abhängigen Defaults (SQLite poolSize=1)
6. Unit-Tests für Parser, LogScrubber, ConnectionPool-Lifecycle (alle 100% Coverage)

### Phase B: DataReader-Port und JDBC-Treiber

7. `DataReader`, `ChunkSequence`, `TableLister`, `DataReaderRegistry` im
   `driver-api/data/`-Package (importieren `DataChunk` etc. aus `core/data/`)
8. `PostgresJdbcUrlBuilder` + `PostgresDataReader` + `PostgresTableLister`
9. `MysqlJdbcUrlBuilder` (setzt `useCursorFetch=true` als Default) +
   `MysqlDataReader` + `MysqlTableLister`
10. `SqliteJdbcUrlBuilder` + `SqliteDataReader` + `SqliteTableLister`
11. Unit-Tests für JdbcUrlBuilder pro Dialekt; TableLister-Stubs gegen In-Memory-DBs
12. Integration-Tests pro Dialekt (für SQLite inline, für PG/MySQL via Testcontainers
    in `@Tag("integration")`-Suite, siehe §6.16)
13. Empty-table-Test pro Dialekt — verifiziert §6.17 (ein Chunk mit `rows=emptyList()`)

### Phase C: Streaming-Pipeline

14. `d-migrate-streaming` Modul anlegen, in `settings.gradle.kts` registrieren
15. `PipelineConfig` (nur `chunkSize`), `ExportResult`, `TableExportSummary`
16. `StreamingExporter` mit Reader → Writer Glue-Code; nimmt `ConnectionPool`
    statt einer Connection (siehe §6.18)
17. Unit-Tests mit Fake-Reader (Liste vorbereitete Chunks) und Fake-Writer

### Phase D: Format-Codecs für Datenexport

18. `DataChunkWriter`-Interface, `DataChunkWriterFactory`, `DataExportFormat`,
    `ExportOptions`, `ValueSerializer` (implementiert §6.4.1 Mapping-Tabelle)
19. `JsonChunkWriter` (Jackson Streaming-API)
20. `YamlChunkWriter` (jackson-dataformat-yaml)
21. `CsvChunkWriter` (jackson-dataformat-csv) inkl. BOM-Handling
22. Unit-Tests pro Writer mit Golden-Master-Output (inkl. leerer Tabelle, §6.17)

### Phase E: CLI-Integration

23. `DataCommand` + `DataExportCommand` in `DataCommands.kt`
24. Registrierung in `Main.kt` (analog zu `SchemaCommand` aus 0.2.0)
25. **Minimaler `NamedConnectionResolver`** (§6.14): respektiert
    `--config`/`-c` und `D_MIGRATE_CONFIG` aus dem globalen CLI-Gerüst,
    sucht nach `./.d-migrate.yaml` als Default, löst
    `database.connections.<name>` auf, substituiert `${ENV_VAR}`,
    ignoriert `default_source` für `data export`
26. Exit-Code-Mapping gemäß §6.10 (0 / 2 / 4 / 5 / 7) mit den Meldungen aus §6.14.3
27. CLI-Integration-Tests (gegen SQLite, weil kein Container nötig) — inkl.
    Round-Trip mit `--source <name>` aus einer temp `.d-migrate.yaml`,
    inkl. `${ENV_VAR}`-Substitution gegen `setEnvironmentVariable`-Helper

### Phase F: End-to-End-Tests mit Testcontainers

28. `DataExportTestcontainersTest` für PostgreSQL: Container starten, Schema
    anlegen, Daten einfügen, Export ausführen, JSON/YAML/CSV-Output verifizieren
    — markiert mit `@Tag("integration")`
29. Dasselbe für MySQL
30. SQLite-E2E direkt ohne Container (kann im Default-Test-Run laufen)
31. Neuer GitHub-Workflow `.github/workflows/integration.yml` mit
    `./gradlew test -PintegrationTests` (siehe §6.16)

## 5. Abhängigkeiten zwischen Modulen

```
d-migrate-core
└── (keine externen Deps)              # neu in 0.3.0: data/-Sub-Package mit DataChunk etc.

d-migrate-driver-api
├── api(d-migrate-core)                # bestand — DataChunk/ColumnDescriptor sichtbar für Treiber
└── api("com.zaxxer:HikariCP")         # neu, transitiv für Pool

d-migrate-driver-postgresql            # implementation(driver-api) + JDBC
d-migrate-driver-mysql                 # implementation(driver-api) + JDBC
d-migrate-driver-sqlite                # implementation(driver-api) + JDBC

d-migrate-formats
├── implementation(d-migrate-core)         # bestand — KEINE neue Kante zu driver-api (siehe F3 / §3.7)
└── implementation(jackson-dataformat-csv) # neu

d-migrate-streaming                    # neu
├── implementation(d-migrate-core)         # für DataChunk
├── implementation(d-migrate-driver-api)   # für DataReader, TableLister, ConnectionPool
└── implementation(d-migrate-formats)      # für DataChunkWriter

d-migrate-cli
├── implementation(d-migrate-streaming)        # neu
├── implementation(d-migrate-driver-postgresql) # bestand (DDL)
├── implementation(d-migrate-driver-mysql)      # bestand
└── implementation(d-migrate-driver-sqlite)     # bestand
```

**Wichtig (F3-Klärung)**: Die Kante `formats → driver-api` wurde in einem
früheren Plan-Entwurf vorgeschlagen, weil `DataChunk` damals in `driver-api`
lag. Mit der Verschiebung nach `core/data/` (siehe §3.7) entfällt diese
Kante komplett — `formats` bleibt JDBC-frei und die Architektur aus
`docs/architecture.md` §2.2 bleibt unverletzt.

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
// d-migrate-core/data/
DataChunk(
    columns: List<ColumnDescriptor>,    // wiederholt pro Chunk — bewusst
    rows: List<Array<Any?>>             // raw Java-Werte
)
```

- **Verortung in `core/data/`** statt `driver-api/data/` — siehe §3.7 und
  F3-Begründung. `formats` darf nicht an JDBC koppeln.
- **Spalten pro Chunk** (statt einmalig pro Tabelle), damit ein Streaming-Writer
  jeden Chunk unabhängig serialisieren kann (zukünftig auch parallel).
- **Raw `Array<Any?>`** statt `Map<String, Any?>` — spart Hashmap-Allokationen
  bei Millionen von Rows. Format-Writer indexieren über `columns[i].name`.
- **Java-native Werte**, kein Mapping zu NeutralType in 0.3.0. Format-Writer
  dispatchen über `value::class` zur Laufzeit (NICHT über einen
  `javaTypeName`-Hint im `ColumnDescriptor` — der Hint wäre für `null`-Werte
  nutzlos und für die Type-Dispatch redundant). `ColumnDescriptor.sqlTypeName`
  ist nur informativ und wird in 0.3.0 von keinem Writer ausgewertet.

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
| MySQL/PG `JSON`/`JSONB` | als gewöhnlicher String (escaped JSON-Literal) | als String | als String |
| Sonst (Fallback) | `value.toString()` + Warnung **W202** in den Export-Report | dito | dito |

**Fallback-Policy**: Unbekannte Java-Klassen werden mit `toString()`
serialisiert und erzeugen genau einmal pro `(table, column, javaClass)`-Tripel
eine Warnung **W202** im Export-Report (siehe `ExportResult.warnings`). Eine
Exception wirft der Writer nur dann, wenn der `toString()`-Aufruf selbst
fehlschlägt.

**JSON/JSONB-Hinweis (F7-Klärung)**: Die JDBC-Treiber liefern JSON-/JSONB-
Spalten in 0.3.0 als gewöhnlichen `String` (PostgreSQL über `PGobject.getValue()`,
MySQL als `String`). Der Writer dispatcht ausschließlich über `value::class`
(siehe §6.4) und kann String-Inhalte nicht von "echten" `VARCHAR`-Strings
unterscheiden, ohne den `sqlTypeName` auszuwerten. Für 0.3.0 gilt deshalb
**bewusst der String-Pfad**: JSON-Werte werden als String-Literal ausgegeben
(im JSON-Output also als escaped String, nicht als embedded Object). Ein
Round-Trip JSON-Spalte → Export → Re-Import bleibt korrekt, aber konsumierende
Tools sehen einen String, nicht ein verschachteltes Objekt.

**Forward-Compat-Hinweise**:
- 0.5.5 (Erweitertes Typsystem, erst dann existiert `NeutralType.Json` als
  first-class) wird `ColumnDescriptor` um den geparsten neutralen Typ
  erweitern. Erst dann kann der `JsonChunkWriter` JSON/JSONB-Spalten als
  embedded JSON-Wert ausgeben.
- 0.5.5 bringt ebenfalls Spatial-Typen — die Mapping-Tabelle wird um
  WKT/WKB-Repräsentationen für `geometry`/`geography` ergänzt. Aktuell
  fallen diese Werte unter `PGobject.toString()` (W201).
- Sobald der `SchemaReader` (0.6.0) den `neutralType` in `ColumnDescriptor`
  füllt, kann die Tabelle vollständig auf neutrale Typen statt Java-Klassen
  umgestellt werden.

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

`TableLister.listTables(pool)` borgt sich seine Connection genauso aus dem
Pool wie `DataReader.streamTable(pool, ...)` — keine Connection wird vom
Caller durchgereicht (siehe F1-Klärung in §6.18).

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
| `7` | Konfigurationsfehler (URL-Parser, `.d-migrate.yaml` parse error, unbekannter Connection-Name, fehlende ENV-Var) | `Connection name 'staging' is not defined in .d-migrate.yaml under database.connections` |

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
    conn = pool.borrow()                          // Hikari-wrapped Connection
    savedAutoCommit = conn.autoCommit
    conn.autoCommit = false
    stmt = conn.prepareStatement(sql, FORWARD_ONLY, READ_ONLY)
    stmt.fetchSize = driverDefault                // siehe §6.13
    rs = stmt.executeQuery()

close():                                          // idempotent
    try { rs?.close() } catch (_) {}
    try { stmt?.close() } catch (_) {}
    try { conn.rollback() } catch (_) {}          // verwerfen, da read-only
    try { conn.autoCommit = savedAutoCommit } catch (_) {}
    try { conn.close() } catch (_) {}             // Hikari: gibt in den Pool zurück
```

Hikari-Hinweis: `conn.close()` schließt die echte JDBC-Connection NICHT,
sondern gibt die Hikari-Wrapper-Connection an den Pool zurück. Es gibt
deshalb keine separate `pool.return(conn)`-Methode auf dem
`ConnectionPool`-Interface — siehe KDoc in §3.1.

**Garantien**:
- Vor dem Rückgeben in den Pool werden `rollback()` und `autoCommit`-Reset
  ausgeführt — Hikari erhält nie eine Connection mit offener Transaktion
  oder abweichendem `autoCommit`-Flag
- `close()` ist sicher mehrfach aufrufbar (jede `try { ... } catch (_) {}`-
  Stufe ist no-op nach erstem Erfolg)
- Auch bei Exception während der Iteration wird `close()` über
  `ChunkSequence.use { }` (oder die `forEach`-Auto-Cleanup-Logik im
  `StreamingExporter`) garantiert ausgeführt

Tests: ein `ConnectionLeakTest` borgt eine Connection, iteriert mit
absichtlicher Exception in der Mitte, prüft anschließend
`pool.activeConnections() == 0` (intern delegated an `HikariPoolMXBean`).

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

### 6.14 Named Connections — minimaler Loader für 0.3.0 (F4 + F8)

`connection-config-spec.md` §1.4, §3 und §5 dokumentieren bereits den
vollständigen `.d-migrate.yaml`-Loader inkl. benannter Verbindungen, Profile,
ENV-Substitution und Validierungs-Codes E40x. Das globale CLI-Gerüst aus
`Main.kt` enthält ebenfalls schon das `--config`/`-c`-Flag, und cli-spec.md
§9 dokumentiert `D_MIGRATE_CONFIG` als Umgebungsvariable.

Ein früherer Plan-Entwurf wollte für 0.3.0 ein striktes URL-only-Verhalten
erzwingen und `--config`/`D_MIGRATE_CONFIG`/`default_source`/`${ENV_VAR}`
**aktiv ablehnen** — das hätte den globalen CLI-Vertrag gebrochen, statt
ihn nur zu reduzieren. F8-Klärung: der 0.3.0-Loader **respektiert** alle
existierenden Mechanismen, implementiert sie aber nur mit dem notwendigen
Minimum.

#### 6.14.1 Was wird in 0.3.0 implementiert

```kotlin
class NamedConnectionResolver(
    /** Pfad zu config-Datei (CLI: --config / -c → ENV: D_MIGRATE_CONFIG → ./.d-migrate.yaml). */
    private val configPath: Path?,
) {
    /**
     * Liest die Config (falls vorhanden), löst `database.connections.<name>` auf,
     * substituiert ${ENV_VAR} in den Werten und liefert eine vollständige URL.
     *
     * Wenn `source` ein "://" enthält, wird er unverändert zurückgegeben — die
     * Config-Datei muss in diesem Fall nicht existieren.
     */
    fun resolve(source: String): String
}
```

**Auflösungsreihenfolge des `configPath`** (übernimmt cli-spec.md §9
Priorität CLI > ENV > Default):

1. Wenn `--config <path>` gesetzt → diesen Pfad verwenden (Pflichtdatei,
   muss existieren)
2. Sonst wenn `D_MIGRATE_CONFIG` ENV gesetzt → diesen Pfad verwenden
   (Pflichtdatei)
3. Sonst `./.d-migrate.yaml` im CWD → optional, kein Fehler wenn fehlt

**Auflösung von `--source <value>`**:

| `--source`-Form | configPath gefunden | Verhalten |
|---|---|---|
| Enthält `://` | irrelevant | URL unverändert; configPath muss nicht existieren |
| Kein `://` | nein (CLI/ENV explizit gesetzt, Datei fehlt) | **Exit 7**: configPath konnte nicht geladen werden |
| Kein `://` | nein (kein CLI, kein ENV, default `.d-migrate.yaml` fehlt) | **Exit 7**: Wert sieht aus wie ein Connection-Name, aber keine Config-Datei vorhanden |
| Kein `://` | ja, Name in `database.connections` gefunden | Auflösung zur dort hinterlegten URL, anschließend ENV-Substitution |
| Kein `://` | ja, Name fehlt | **Exit 7**: Name nicht in `database.connections` definiert |

**`${ENV_VAR}`-Substitution**: Werte in `database.connections.<name>` werden
nach dem Lookup einer einfachen `${VAR_NAME}`-Substitution unterzogen
(`System.getenv(name)`). Fehlende ENV-Variable → Exit 7 mit Verweis auf den
betroffenen Connection-Namen. `$${VAR}` bleibt als literales `${VAR}`
stehen (Escape gemäß `connection-config-spec.md` §3.3).

Die Substitution ist trivial (1 Regex, ~15 Zeilen Kotlin) und vermeidet
einen späteren Migration-Bruch — Nutzer können in 0.3.0 schon
`postgresql://app:${DB_PROD_PW}@host/db` schreiben, ohne dass sich das
Verhalten in 0.4.0 ändert.

**ENV-Werte werden literal substituiert — kein Auto-URL-Encoding** (F13).
Der Resolver weiß nicht, in welchem URL-Segment eine ENV-Variable landet
(Passwort? Hostname? Pfad?), und Auto-Encoding würde:

1. Bereits-encodete Werte doppelt encoden (`%40` → `%2540`)
2. Hostnames und Pfade fälschlich verändern
3. Mit dem Standard-Verhalten von shell-style `${VAR}`-Substitution brechen

**Verantwortlichkeit des Nutzers**: Sonderzeichen in Passwörtern (`@`, `:`,
`/`, `?`, `#`, `%` — siehe `connection-config-spec.md` §1.7) müssen **vor**
dem Schreiben in die ENV-Variable URL-encoded werden:

```bash
# Korrekt: Passwort 'p@ss:word' → URL-encoded
export DB_PROD_PW='p%40ss%3Aword'
d-migrate data export --source prod --format json

# Falsch: Klartext mit Sonderzeichen, der ConnectionUrlParser bricht
export DB_PROD_PW='p@ss:word'
```

Der `ConnectionUrlParser` würde im falschen Beispiel über das `@` in der
Passwort-Position stolpern und Exit 7 werfen — das ist die natürliche
URL-Validierung, kein Sonder-Pfad. Eine entsprechende Doku-Notiz gehört in
`docs/guide.md` und in die Fehlermeldung des `ConnectionUrlParser`.

**`database.default_source`**: Wird **gelesen aber für `data export`
ignoriert**, weil `--source` Pflicht ist. Kein Fehler. In 0.4.0 wird
`default_source` für die zweite CLI-Familie `data import` benutzt, dort
ist es relevant.

Beispiel-Konfiguration in 0.3.0:

```yaml
# .d-migrate.yaml
database:
  default_source: local_pg          # in 0.3.0 ignoriert für data export, kein Fehler
  connections:
    local_pg: "postgresql://dev:dev@localhost:5432/myapp"
    local_sqlite: "sqlite:///tmp/test.db"
    prod: "postgresql://app:${DB_PROD_PW}@prod.example.com/myapp?ssl=require"
```

```bash
$ d-migrate data export --source local_pg --format json --tables users
$ d-migrate data export --source postgresql://other@host/db --format json
$ d-migrate -c /etc/d-migrate.yaml data export --source local_pg --format yaml
$ DB_PROD_PW=secret d-migrate data export --source prod --format json
```

#### 6.14.2 Feature-Status pro Loader-Funktion

| Feature | 0.3.0 | 0.4.0 / später | Notiz |
|---|---|---|---|
| `database.connections.<name>: <url>` Map | ✅ | ✅ | |
| Auflösung von `--source <name>` über die Map | ✅ | ✅ | |
| `--config <path>` / `-c` (existiert in `Main.kt:26`) | ✅ respektiert | ✅ | |
| `D_MIGRATE_CONFIG` ENV (cli-spec.md §9) | ✅ respektiert | ✅ | |
| Default-Pfad `./.d-migrate.yaml` | ✅ | ✅ | |
| `${ENV_VAR}`-Substitution in Werten | ✅ einfache Variante (`System.getenv`, `$${...}` Escape) | ✅ identisch | trivial, vermeidet Migration-Bruch |
| `database.default_source` | ✅ gelesen, für `data export` ignoriert (kein Fehler) | ✅ aktiv für `data import` | |
| `database.default_target` | ✅ gelesen, für `data export` ignoriert | ✅ aktiv für `data import` | |
| `database.pool.*` | ❌ ignoriert, Hikari-Defaults aus §3.1 | ✅ | für 0.3.0 unnötig |
| `~/.d-migrate/config.yaml` (Global, Multi-Source-Merge) | ❌ | ✅ | |
| Profile-Auflösung (mehrere Connections pro Umgebung) | ❌ | ✅ | |
| Validierungs-Codes E400–E405 (granular) | ❌ → generischer Exit 7 mit klarer Meldung | ✅ | |
| Encrypted credentials (`credentials.enc`) | ❌ | ❌ → 1.0.0 | |

#### 6.14.3 Fehlermeldungen

| Bedingung | Meldung |
|---|---|
| `--config <path>` gesetzt, Datei fehlt | `Exit 7: Config file not found: <path>` |
| `D_MIGRATE_CONFIG` gesetzt, Datei fehlt | `Exit 7: D_MIGRATE_CONFIG points to non-existent file: <path>` |
| `--source local_pg`, kein `--config`, kein ENV, keine `./.d-migrate.yaml` | `Exit 7: '--source local_pg' looks like a connection name, but no .d-migrate.yaml was found. Use --config <path>, set D_MIGRATE_CONFIG, or pass a full URL.` |
| `--source local_pg`, Config geladen, Name fehlt | `Exit 7: Connection name 'local_pg' is not defined in <config-path> under database.connections.` |
| `${ENV_VAR}` referenziert, ENV nicht gesetzt | `Exit 7: Environment variable 'DB_PROD_PW' (referenced by connection 'prod') is not set.` |
| YAML-Parser-Fehler | `Exit 7: Failed to parse <config-path>: <message>` |

#### 6.14.4 Konsequenz für die Spec

`connection-config-spec.md` bekommt eine neue **"Implementierungsstatus pro
Feature"-Tabelle** (analog zu §6.14.2 hier), die markiert, was bereits in
0.3.0 funktioniert und was erst in 0.4.0+ kommt. Das Dokument bleibt als
Ziel-Spezifikation gültig, der Statusabschnitt zeigt aber den partiellen
Implementierungsstand.

#### 6.14.5 Vorteile gegenüber dem strikten URL-only-Ansatz

- **Kein Bruch** des globalen CLI-Vertrags (`--config` / `D_MIGRATE_CONFIG`
  bleiben funktionsfähig wie in `cli-spec.md` §1.3 / §9 dokumentiert)
- **Kein toter Fehler** für `default_source` — wird einfach für `data export`
  ignoriert, ist in 0.4.0 sofort nutzbar ohne Verhaltensänderung
- **Keine Migration-Falle** für `${ENV_VAR}` — Nutzer schreiben es in 0.3.0
  schon richtig, in 0.4.0 ändert sich nichts
- Plan und Spec stimmen funktional überein — keine Doku-Drift

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

### 6.17 Reader-Vertrag für leere Tabellen

Das Writer-Interface hat zwei separate Aufrufe für Metadaten und Rows:

```kotlin
writer.begin(table, columns)   // Metadaten — schreibt z.B. CSV-Header
writer.write(chunk)            // pro Chunk
writer.end()                   // Container schließen
```

Damit das Modell auch für leere Tabellen sauber funktioniert (kein
Header-Verlust bei 0 Rows), MUSS jede `DataReader.streamTable`-Implementation
**immer mindestens einen Chunk** liefern — auch bei einer leeren Tabelle.
Dieser erste Chunk hat dann:

- `columns` korrekt gefüllt aus `ResultSetMetaData`
- `rows = emptyList()`
- `chunkIndex = 0`

Der Caller (`StreamingExporter`) liest den ersten Chunk, ruft `writer.begin()`
mit den `columns` darin auf, dann `writer.write(chunk)` (der Writer schreibt
nichts bei `rows.isEmpty()`), iteriert über etwaige folgende Chunks und ruft
zuletzt `writer.end()`.

**Format-Outputs für eine leere Tabelle**:

| Format | CLI-Flag | Output |
|---|---|---|
| JSON | (kein Flag) | `[]` |
| YAML | (kein Flag) | `[]` |
| CSV (Default) | — | nur die Header-Zeile, terminiert mit Linebreak |
| CSV ohne Header | `--csv-no-header` | leere Datei (nur ggf. BOM, falls `--csv-bom`) |

**Tests**: Pro Format ein Golden-Master-Test mit einer leeren Tabelle, plus
ein Reader-Test, der prüft, dass `streamTable("empty_table")` exakt einen
Chunk mit `rows.isEmpty() == true` zurückgibt.

### 6.18 Connection-Ownership: Pool wird durchgereicht (F1-Klärung)

In einem früheren Entwurf des Plans hatten `StreamingExporter.export(...)`
und `DataReader.streamTable(...)` beide eine `DatabaseConnection`-Parameter
— gleichzeitig beschrieb §6.12 aber, dass jeder `streamTable`-Aufruf selbst
eine Connection aus dem Pool borgt und am Ende zurückgibt. Diese beiden
Modelle widersprachen sich (doppelter Connection-Besitz, Mehr-Tabellen-
Export führte zu Doppel-Schließen).

**Verbindliche Klärung für 0.3.0**:

- Der **Pool** wird durch alle Schichten durchgereicht (CLI →
  StreamingExporter → DataReader/TableLister).
- **Der Caller besitzt KEINE JDBC-Connection**. Das CLI-Kommando hält nur
  den `ConnectionPool`-Wrapper.
- `DataReader.streamTable(pool, ...)` und `TableLister.listTables(pool)`
  borgen sich pro Aufruf eine eigene `java.sql.Connection` aus dem Pool,
  halten sie für die Lifetime der Operation und geben sie via `conn.close()`
  zurück (Hikari wrappt das so, dass die Connection in den Pool zurückgeht
  statt physisch geschlossen zu werden — siehe §6.12).
- `StreamingExporter` koordiniert nur Reader und Writer, hat selbst nie
  eine offene JDBC-Connection.
- `ConnectionPool.close()` wird ausschließlich am Ende des CLI-Aufrufs in
  einem `pool.use { ... }`-Block aufgerufen.

**Konsequenzen**:

- Mehr-Tabellen-Export ist trivial: Jede Tabelle bekommt eine frische
  Connection aus dem Pool, in 1.0.0 lässt sich das ohne Refactoring auf
  parallele Verarbeitung erweitern.
- §6.12 (Transaktions-Lifecycle) bleibt wie beschrieben — die `open()`/
  `close()`-Logik dort gehört eindeutig in den Reader, nicht in den
  StreamingExporter oder den CLI-Code.
- Tests müssen `ConnectionPool` (oder ein Fake) mocken, nicht eine
  einzelne `Connection`.

## 7. Dateien (geschätzt ~30 neue Dateien)

```
# Neues Modul d-migrate-streaming
d-migrate-streaming/build.gradle.kts
d-migrate-streaming/src/main/kotlin/dev/dmigrate/streaming/StreamingExporter.kt
d-migrate-streaming/src/main/kotlin/dev/dmigrate/streaming/PipelineConfig.kt
d-migrate-streaming/src/main/kotlin/dev/dmigrate/streaming/ExportResult.kt
d-migrate-streaming/src/test/kotlin/dev/dmigrate/streaming/StreamingExporterTest.kt

# Erweiterung d-migrate-core (NEU für 0.3.0 — Daten-Modell, JDBC-frei)
d-migrate-core/src/main/kotlin/dev/dmigrate/core/data/DataChunk.kt
d-migrate-core/src/main/kotlin/dev/dmigrate/core/data/ColumnDescriptor.kt
d-migrate-core/src/main/kotlin/dev/dmigrate/core/data/DataFilter.kt
d-migrate-core/src/test/kotlin/dev/dmigrate/core/data/DataChunkTest.kt

# Erweiterung d-migrate-driver-api
d-migrate-driver-api/src/main/kotlin/dev/dmigrate/driver/connection/ConnectionConfig.kt
d-migrate-driver-api/src/main/kotlin/dev/dmigrate/driver/connection/PoolSettings.kt
d-migrate-driver-api/src/main/kotlin/dev/dmigrate/driver/connection/ConnectionUrlParser.kt
d-migrate-driver-api/src/main/kotlin/dev/dmigrate/driver/connection/ConnectionPool.kt
d-migrate-driver-api/src/main/kotlin/dev/dmigrate/driver/connection/HikariConnectionPoolFactory.kt
d-migrate-driver-api/src/main/kotlin/dev/dmigrate/driver/connection/LogScrubber.kt
d-migrate-driver-api/src/main/kotlin/dev/dmigrate/driver/data/DataReader.kt
d-migrate-driver-api/src/main/kotlin/dev/dmigrate/driver/data/ChunkSequence.kt
d-migrate-driver-api/src/main/kotlin/dev/dmigrate/driver/data/TableLister.kt
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
d-migrate-cli/src/main/kotlin/dev/dmigrate/cli/config/NamedConnectionResolver.kt
d-migrate-cli/src/test/kotlin/dev/dmigrate/cli/CliDataExportTest.kt           # SQLite
d-migrate-cli/src/test/kotlin/dev/dmigrate/cli/config/NamedConnectionResolverTest.kt

# Geänderte Dateien
settings.gradle.kts                                                          # +d-migrate-streaming
gradle.properties                                                            # +5 versions
build.gradle.kts                                                             # kover-Aggregation für streaming + driver-* Module
d-migrate-core/build.gradle.kts                                              # (keine deps, nur neues data/-Sub-Package)
d-migrate-driver-api/build.gradle.kts                                        # +HikariCP api (core kommt schon mit)
d-migrate-driver-postgresql/build.gradle.kts                                 # +postgresql jdbc
d-migrate-driver-mysql/build.gradle.kts                                      # +mysql connector
d-migrate-driver-sqlite/build.gradle.kts                                     # +sqlite jdbc
d-migrate-formats/build.gradle.kts                                           # +jackson-csv (KEINE neue driver-api dep — siehe F3)
d-migrate-cli/build.gradle.kts                                               # +streaming
.github/workflows/build.yml                                                  # +streaming + driver-* koverVerify (rückwirkend für 0.2.0-Module)
.github/workflows/integration.yml                                            # NEU — @Tag("integration")-Job
docs/cli-spec.md                                                             # §6.2 `data export` vollständig aktualisieren: alle 0.3.0-Flags (--split-files, --csv-delimiter, --csv-bom, --csv-no-header, --null-string, --tables, --filter, --encoding, --chunk-size) ergänzen, --incremental → 0.4.0 verschieben, Exit-Codes 0/2/4/5/7 (siehe Plan §6.10)
docs/architecture.md                                                         # §2.2 streaming-Dependency, §3.1 DataReader-Signatur (Pool statt Connection), neutrales Daten-Modell in core
docs/connection-config-spec.md                                               # Status: Entwurf → Stand 0.3.0 (URL-Parser + minimaler Named-Connection-Loader umgesetzt; Tabelle "Status pro Feature" nach §6.14)
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
| **Connection-Ownership ambivalent (F1)** | §6.18 fixiert: Pool wird durchgereicht, Reader/TableLister borgen sich selbst, Caller besitzt nie eine Connection |
| Connection-Leak durch offene Transaktion / abweichendes autoCommit | §6.12 Transaktions-Lifecycle mit idempotentem `close()`; `ConnectionLeakTest` über `ConnectionPool.activeConnections()` |
| **Leere Tabellen verlieren Header (F2)** | §6.17: Reader MUSS bei 0 Rows einen Chunk mit `rows=emptyList()` und gefüllten `columns` liefern; Format-Outputs sind explizit definiert |
| **Architekturverletzung formats↔driver-api (F3)** | §3.7: `DataChunk`/`ColumnDescriptor`/`DataFilter` wandern nach `core/data/`; `formats` hängt nur an `core` |
| Jackson `jackson-dataformat-csv` Encoding-Bugs mit BOM | Eigener BOM-Writer vor dem CsvSchema-Output, nicht Jackson überlassen |
| Type-Serialisierung uneindeutig (BigDecimal, byte[], PG-Spezifika) | Verbindliche Mapping-Tabelle in §6.4.1 mit Fallback-Policy (W202) und Forward-Compat-Notiz für 0.5.5 |
| Schema-qualifizierte Tabellen kollidieren bei FilePerTable | Dateinamen-Schema `<schema>.<table>.<format>` (§6.9) |
| `--filter` öffnet SQL-Injection (CLI ist Trust-Boundary, REST-API später nicht) | Doku-Warnung; minimale read-only Credentials empfohlen; striktes `--tables`-Identifier-Pattern; §6.7 Forward-Compat-Hinweis für REST-API (2.0.0) |
| Passwörter in Logs / Reports | §6.11 Password-Scrubbing zentral im LogScrubber + überschriebenem `ConnectionConfig.toString()` |
| Dateigröße bei Single-File-Output > Plattenplatz | Stream direkt in Datei (keine Pufferung in Memory); progress logging mit `bytesWritten` |
| Default `chunkSize=10_000` zu groß für sehr breite Tabellen (BLOB-Spalten) | `--chunk-size` Flag dokumentiert; Risiko-Hinweis in Guide |
| **Plan widerspricht connection-config-spec (F4)** | §6.14 fixiert: minimaler `NamedConnectionResolver` in 0.3.0 (`database.connections.<name>`, `${ENV_VAR}`-Substitution, `--config`/`D_MIGRATE_CONFIG` respektiert, `default_source` für `data export` ignoriert), Spec wird mit "Status pro Feature"-Tabelle aktualisiert |
| **PipelineConfig.fetchSize inkonsistent (F5)** | §2.1 PipelineConfig hat nur `chunkSize`; fetchSize ist treiberintern (§6.13) |
| **ConnectionPool API ohne return() (F6)** | §3.1 Interface bleibt minimal; §6.12 Pseudo-Code nutzt `conn.close()` (Hikari wrappt das so, dass die Connection in den Pool zurückgegeben wird) |
| **JSON/JSONB-Sonderbehandlung nicht ableitbar (F7)** | §6.4.1 fixiert: 0.3.0 serialisiert JSON/JSONB als String, kein Embedding; first-class JSON kommt mit 0.5.5 |
| **NamedConnectionResolver bricht globalen CLI-Vertrag (F8)** | §6.14 entstrikten: `--config`/`D_MIGRATE_CONFIG`/`${ENV}` werden respektiert, `default_source` ignoriert (kein Fehler) |
| **CSV-Header-Semantik mehrdeutig (F9)** | §6.17 + §3.5/§3.6: explizites `--csv-no-header` Flag, Default Header an, `ExportOptions.csvHeader: Boolean = true` |
| **Exit-Code-Matrix veraltet (F10)** | §6.10 Exit 7 Beispielmeldung aktualisiert auf "Connection name '...' is not defined ..." |
| **cli-spec.md inkomplett (F11)** | §7 Liste der zu pflegenden cli-spec.md-Änderungen vollständig: alle 9 neuen Flags + Exit-Codes 2/7, nicht nur `--incremental` |
| **--config Zugriff falsche Parent-Hops (F12)** | §3.6 Snippet auf `currentContext.parent?.parent?.command as? DMigrate` (Pattern aus SchemaCommands.kt:44) korrigiert + Hinweis-Block |
| **ENV-Substitution + URL-Encoding undefiniert (F13)** | §6.14: ENV-Werte werden literal substituiert; Nutzer ist verantwortlich für URL-Encoding (`%40` statt `@`); Begründung dokumentiert |
| **Dependency-Tabelle Drift (F14)** | §3.9 streaming-Eintrag auf "core + driver-api + formats" korrigiert |

## 11. Aufgelöste Review-Fragen und Findings

### 11.1 Erste Review-Runde — Open Questions

| # | Frage | Entscheidung | Verankert in |
|---|---|---|---|
| 1 | Sequence vs. Flow | `ChunkSequence` (Sequence-basiert, single-use, AutoCloseable) | §6.1 |
| 2 | `d-migrate-streaming` als eigenes Modul | Ja — eigenes Modul (Checkpoint/Parallel-Executor folgen) | §2.1 |
| 3 | Named Connections aus `.d-migrate.yaml` | Minimaler Loader in 0.3.0 (nur `database.connections.<name>`); voller Loader in 0.4.0 | §6.14 |
| 4 | `listTables()` Verortung | Eigenständiger `TableLister`-Port, nicht im DataReader | §3.1, §6.8 |
| 5 | `--format` Pflichtfeld vs. Default | Pflicht (Clikt `.required()`, Exit 2 wenn fehlt) | §6.15 |
| 6 | Single-File vs. FilePerTable | Explizites `--split-files` Flag, keine Heuristik; Auflösungstabelle in §6.9 | §6.9 |
| 7 | Testcontainers im Default-CI | Tag-basierte Trennung, separater `integration.yml`-Workflow | §6.16 |

### 11.2 Zweite Review-Runde — Findings F1–F5

| # | Severity | Finding | Auflösung | Verankert in |
|---|---|---|---|---|
| F1 | HOCH | Connection-Ownership widersprüchlich (Caller vs. Reader) | Pool wird durchgereicht; Reader/TableLister borgen sich selbst; Caller hält nie eine Connection | §2.1, §3.1, §6.18 |
| F2 | Mittel | Leere Tabellen führen zu fehlenden Headern | Reader-Vertrag: bei 0 Rows MUSS ein Chunk mit `rows=emptyList()` und gefüllten `columns` emittiert werden; Format-Outputs explizit definiert | §3.1, §6.17 |
| F3 | Mittel | Architekturverletzung `formats → driver-api` | `DataChunk`/`ColumnDescriptor`/`DataFilter` wandern nach `core/data/`; `formats` hängt nur an `core` (architektur-konform) | §3.5, §3.7, §6.4 |
| F4 | Mittel | Plan widerspricht `connection-config-spec.md` (URL-only zu strikt) | Minimaler `NamedConnectionResolver` in 0.3.0 löst `database.connections.<name>` auf; Spec wird mit "Status pro Feature"-Tabelle ergänzt; volle Multi-Source/ENV/Profile in 0.4.0 | §6.14 |
| F5 | Niedrig | `PipelineConfig.fetchSize` doppelt definiert/treiberintern | Aus `PipelineConfig` entfernt; nur `chunkSize` bleibt user-tunable; `fetchSize` ist treiberinterne Konstante | §2.1, §6.13 |

### 11.3 Dritte Review-Runde — Findings F6–F10

| # | Severity | Finding | Auflösung | Verankert in |
|---|---|---|---|---|
| F6 | HOCH | `ConnectionPool` Interface ohne `return()`-API, Pseudo-Code rief aber `pool.return(conn)` | Hikari-Standard nutzen: `conn.close()` gibt die Wrapper-Connection in den Pool zurück. Interface bleibt minimal (`borrow`, `activeConnections`, `close`). §6.12 Pseudo-Code korrigiert | §3.1, §6.12, §6.18 |
| F7 | Mittel | JSON/JSONB-Sonderbehandlung in §6.4.1 nicht ableitbar (Writer dispatcht über `value::class`, sieht nur `String`) | JSON/JSONB werden in 0.3.0 als gewöhnlicher String serialisiert (kein Embedding). Forward-Compat: 0.5.5 bringt first-class `NeutralType.Json`, dann kann der Writer parsen und embedden | §6.4.1 |
| F8 | Mittel | `NamedConnectionResolver` lehnt `--config`/`D_MIGRATE_CONFIG`/`default_source`/`${ENV_VAR}` aktiv ab — bricht globalen CLI-Vertrag | Resolver respektiert alle vier: `--config`/`-c` und `D_MIGRATE_CONFIG` aus `Main.kt` werden gelesen; `default_source` wird für `data export` ignoriert (kein Fehler); `${ENV_VAR}` wird trivial substituiert. Reduktion statt Bruch | §6.14, §3.6, §4 Phase E |
| F9 | Niedrig | CSV-Header-Semantik mehrdeutig (kein Flag für Default an/aus) | Neues `--csv-no-header`-Flag (Default: Header an); `ExportOptions.csvHeader: Boolean = true`; §6.17-Tabelle referenziert das Flag explizit | §3.5, §3.6, §6.17 |
| F10 | Niedrig | Exit-Code-Matrix §6.10 zeigte alte URL-only-Meldung für Exit 7 | Beispielmeldung aktualisiert auf `Connection name 'staging' is not defined in .d-migrate.yaml under database.connections` — passt zum neuen §6.14 | §6.10 |

### 11.4 Vierte Review-Runde — Findings F11–F14

| # | Severity | Finding | Auflösung | Verankert in |
|---|---|---|---|---|
| F11 | Mittel | `cli-spec.md` §6.2 fehlen die neuen 0.3.0-Flags und Exit-Codes 2/7; Plan §7 listete nur `--incremental` als zu entfernen | §7-Eintrag erweitert: Vollständige Liste der nachzuziehenden Flags (`--split-files`, `--csv-delimiter`, `--csv-bom`, `--csv-no-header`, `--null-string`, `--tables`, `--filter`, `--encoding`, `--chunk-size`) plus Exit-Codes 0/2/4/5/7 — wird mit der Implementation in cli-spec.md eingearbeitet | §7 |
| F12 | Mittel | CLI-Snippet zeigte `parent.config` — bei Hierarchie `d-migrate → data → export` falsch (config liegt am Root, zwei Parent-Hops weiter) | Snippet auf `currentContext.parent?.parent?.command as? DMigrate` korrigiert (identisches Pattern wie `SchemaCommands.kt:44`); zusätzlicher Hinweis-Block erklärt warum | §3.6 |
| F13 | Niedrig | `${ENV_VAR}`-Substitution + URL-Encoding nicht abgegrenzt | Explizite Regel: ENV-Werte werden **literal** substituiert, kein Auto-URL-Encoding. Sonderzeichen müssen vom Nutzer vorab encoded werden (`%40` statt `@`). Begründung: Auto-Encoding bricht bereits-encodete Werte und ist segment-blind | §6.14 (ENV-Substitution) |
| F14 | Niedrig | Dependency-Tabelle §3.9 zeigte für `streaming` nur "driver-api + formats", `core` fehlte (Drift gegen §5 und §2.1) | §3.9 Tabelleneintrag aktualisiert auf "core + driver-api + formats" mit Verweis auf §5 | §3.9 |

---

**Referenzen**:
- [Architektur §3.1](./architecture.md) — Driver-/Streaming-Konzept
- [Lastenheft](./lastenheft-d-migrate.md) — LF-008/LF-009, LN-005/LN-007/LN-048
- [CLI-Spezifikation §6.2](./cli-spec.md) — `data export` Command
- [Connection- und Konfigurationsspezifikation](./connection-config-spec.md) — URL-Format §1, HikariCP §2, Profile §5
- [Roadmap Milestone 0.3.0](./roadmap.md)
- [Releasing Guide](./releasing.md) — Voraussetzungen für den 0.3.0-Release
