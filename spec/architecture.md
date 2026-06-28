# Architektur-Dokument: d-migrate

**CLI-Tool für datenbankunabhängige Migrationen und Datenverwaltung**

> Dokumenttyp: Architektur-Spezifikation
>
> Die Module unter `hexagon/` und `adapters/` bilden die hexagonale
> Verzeichnisstruktur ab (siehe §1.2 / §2.1). Weitere Module wie `ai`,
> `testdata` und `docs` beschreiben geplante Erweiterungen. I18n bleibt ohne
> separates Top-Level-`i18n`-Modul in die bestehenden Modulgrenzen integriert.

---

## 1. Architektur-Übersicht

### 1.1 Systemkontext

```
                    ┌─────────────────────────────────────────────┐
                    │                  d-migrate                  │
  Benutzer ───CLI──▶│                                             │
                    │  ┌─────────┐ ┌──────────┐ ┌─────────────┐   │
  CI/CD ──Script───▶│  │ Schema  │ │  Daten   │ │ Integration │   │
                    │  │ Mgmt    │ │  Mgmt    │ │ Adapter     │   │
                    │  └────┬────┘ └────┬─────┘ └──────┬──────┘   │
                    │       │           │              │          │
                    │  ┌────▼───────────▼──────────────▼───────┐  │
                    │  │              Core Engine              │  │
                    │  │  (Neutrales Modell, Validierung,      │  │
                    │  │   Type-Mapping, Streaming-Pipeline)   │  │
                    │  └────┬───────────┬──────────────┬───────┘  │
                    │       │           │              │          │
                    │  ┌────▼────┐ ┌────▼────┐  ┌─────▼──────┐    │
                    │  │ DB      │ │ Format  │  │ KI         │    │
                    │  │ Driver  │ │ Codec   │  │ Provider   │    │
                    │  │ (JDBC)  │ │         │  │            │    │
                    │  └────┬────┘ └────┬────┘  └─────┬──────┘    │
                    └───────┼───────────┼─────────────┼───────────┘
                            │           │             │
                    ┌───────▼──┐  ┌─────▼────┐  ┌─────▼──────┐
                    │PostgreSQL│  │JSON/YAML │  │Ollama      │
                    │MySQL     │  │CSV/SQL   │  │LM Studio   │
                    │SQLite    │  │          │  │OpenAI      │
                    │(Oracle)  │  │          │  │Anthropic   │
                    │(MSSQL)   │  │          │  │xAI/Gemini  │
                    │          │  │          │  │vLLM/TGI    │
                    └──────────┘  └──────────┘  └────────────┘
```

### 1.2 Architekturstil

**Hexagonale Architektur (Ports & Adapters)** mit klarer Trennung zwischen:

- **Domain Core**: Neutrales Schema-Modell (spezifiziert in [neutral-model-spec.md](./neutral-model-spec.md)), Validierungslogik, Diff- und Planungslogik - keine externen Abhängigkeiten
- **Ports**: Interfaces für Datenbank-Zugriff, Datei-I/O, KI-Provider und datenbankspezifisches Type-Mapping
- **Adapters**: Konkrete Implementierungen (JDBC-Driver, Jackson-Serializer, HTTP-Clients, Dialekt-spezifische TypeMapper)

**Zuordnung der Gradle-Module zu den Architekturschichten:**

```
d-migrate/
├── hexagon/                               ← Innerhalb des Hexagons
│   ├── core/                              ← Domain (Modell, Validierung, Typsystem)
│   ├── ports/                             ← Port-Interfaces (Output) + Datentypen
│   └── application/                       ← Use Cases (Runner-Klassen)
│
├── adapters/                              ← Außerhalb des Hexagons
│   ├── driving/
│   │   └── cli/                           ← Clikt-Shells, Wiring, OutputFormatter
│   └── driven/
│       ├── driver-common/                 ← Abstract-Base-Klassen, HikariCP
│       ├── driver-postgresql/
│       ├── driver-mysql/
│       ├── driver-sqlite/
│       ├── formats/                       ← JSON/YAML/CSV Codec-Implementierungen
│       └── streaming/                     ← Pipeline-Infrastruktur
```

| Schicht         | Modul                                | Rolle                                                                                                                                |
| --------------- | ------------------------------------ | ------------------------------------------------------------------------------------------------------------------------------------ |
| Domain Core     | `hexagon:core`                       | Neutrales Modell, Validierung, Typsystem, FK-Topo-Sort — keine externen Deps                                                         |
| Ports           | `hexagon:ports-common`               | Gemeinsame Typen (`DatabaseDialect`, `SqlIdentifiers`, `DialectCapabilities`, `ConnectionPool`, `SchemaCodec`, `DataExportFormat`)   |
| Ports           | `hexagon:ports-read`                 | Lese-Ports (`SchemaReader`, `DdlGenerator`, `DataReader`, `DataChunkReader`, `FormatReadOptions`, `ManualActionRequired`)            |
| Ports           | `hexagon:ports-write`                | Schreib-Ports (`DataWriter`, `TableImportSession`, `ImportOptions`, `ExportOptions`, `DataChunkWriter`, Checkpoint, Streaming-Typen) |
| Ports           | `hexagon:ports`                      | Aggregator-Modul — re-exportiert `ports-common`, `ports-read`, `ports-write` für bestehende Consumer                                 |
| Application     | `hexagon:application`                | Use-Case-Runner (`SchemaGenerateRunner`, `DataExportRunner`, `ExportResumeCoordinator`, `ImportResumeCoordinator`)                   |
| Profiling       | `hexagon:profiling`                  | Profiling-Domänenmodell, Rule-Engine, Services — keine Treiber-Abhängigkeit                                                          |
| Driving Adapter | `adapters:driving:cli`               | CLI-Einstiegspunkt (Clikt), Wiring aller Module                                                                                      |
| Driven Adapter  | `adapters:driven:driver-common`      | Gemeinsame DB-Infrastruktur (`AbstractDdlGenerator`, `HikariConnectionPoolFactory`, `SqlIdentifiers`, …)                             |
| Driven Adapter  | `adapters:driven:driver-postgresql`  | PostgreSQL-Implementierung der `DatabaseDriver`-Fassade                                                                              |
| Driven Adapter  | `adapters:driven:driver-mysql`       | MySQL-Implementierung der `DatabaseDriver`-Fassade                                                                                   |
| Driven Adapter  | `adapters:driven:driver-sqlite`      | SQLite-Implementierung der `DatabaseDriver`-Fassade                                                                                  |
| Driven Adapter  | `adapters:driven:driver-*-profiling` | Optionale Profiling-Adapter pro Dialekt (Introspection, LogicalTypeResolver, ProfilingData)                                          |
| Driven Adapter  | `adapters:driven:formats`            | Serialisierung/Deserialisierung (JSON, YAML, CSV)                                                                                    |
| Driven Adapter  | `adapters:driven:integrations`       | Tool-Exporter (Flyway, Liquibase, Django, Knex)                                                                                      |
| Driven Adapter  | `adapters:driven:streaming`          | Streaming-Pipeline (`StreamingExporter`, `StreamingImporter`, `TableExporter`, `TableImporter`)                                      |
| Driven Adapter  | `adapters:driven:text-icu`           | ICU4J-basierte `UnicodeTextService`-Implementierung (`IcuUnicodeTextService`); haelt ICU4J aus dem Application-Layer fern            |

```
              adapters:driving:cli  (Clikt)
                      │
                      ▼
         ┌────────────────────────────────┐
         │            Hexagon             │
         │                                │
         │  ┌──────────────────────────┐  │
         │  │  hexagon:application     │  │  ← Use Cases (Runner)
         │  └────────────┬─────────────┘  │
         │               │                │
         │  ┌────────────▼─────────────┐  │
         │  │  ports-read / ports-write│  │  ← Port-Interfaces
         │  └────────────┬─────────────┘  │
         │          ports-common          │  ← Gemeinsame Typen
         │               │                │
         │  ┌────────────▼─────────────┐  │
         │  │  hexagon:core            │  │  ← Domain-Modell
         │  └──────────────────────────┘  │
         │                                │
         │  hexagon:profiling (optional)  │  ← Profiling-Domäne
         └────────────────┬───────────────┘
                          │
            ┌─────────────┼──────────────┐
            ▼             ▼              ▼
      driver-common   formats      streaming
      driver-pg/my/sl
      driver-*-profiling (optional)
```
**Erzwungene Regeln** (durch Gradle-Abhängigkeiten garantiert):
- `hexagon:core` hat keine Abhängigkeiten auf andere Module
- `hexagon:ports-common` hängt nur von `hexagon:core` ab
- `hexagon:ports-read` hängt nur von `ports-common` ab
- `hexagon:ports-write` hängt von `ports-common` und `ports-read` ab
- `hexagon:ports` ist ein Aggregator und re-exportiert alle drei Port-Module
- `hexagon:application` hängt nur vom Hexagon-Inneren ab, nicht von Adaptern
- Driven Adapters dürfen in main nicht voneinander abhängen (Ausnahme: Driver-Module → `driver-common`)
- Treiber-Kernmodule hängen **nicht** von `hexagon:profiling` ab; Profiling-Adapter sind optionale Zusatzmodule

Zusätzlich, durch eine **Architektur-Fitness-Function** (Gate) statt durch Gradle garantiert — denn
`java.sql` ist JDK-intern und ließe sich nicht über Modul-Abhängigkeiten ausschließen:
- Die **Ports-Schicht** (`hexagon:ports-*`) exponiert in ihren Signaturen **kein `java.sql`**; JDBC
  (Treiber-Technologie) lebt ausschließlich in den Adaptern. Ein neutrales
  `DatabaseConnection`-Handle in `hexagon:ports-common` trägt die von den Ports benötigten
  Fähigkeiten (Pool-Rückgabe via `AutoCloseable`, Transaktions-Lebenszyklus, Ausführung bereits
  gerenderter Statements, Session-Reset); die JDBC-gebundene Implementierung (`JdbcDatabaseConnection`,
  Wrapper um die Hikari-Connection) liegt in `adapters:driven:driver-common`, wo die Adapter sie zur
  realen Connection auspacken.

### 1.3 Leitprinzipien

- **Database-Agnostic First**: Alle internen Datenstrukturen sind datenbankunabhängig; datenbankspezifisches Verhalten lebt ausschließlich in austauschbaren Adaptern.
- **Convention over Configuration**: Sinnvolle Defaults überall, explizite Konfiguration nur wo nötig.
- **Streaming by Default**: Datenverarbeitung ist grundsätzlich streaming-basiert (beliebig große Datenmengen).
- **Fail-Safe**: Standardmäßig transaktionale Verarbeitung ohne stillschweigende Teilmigrationen; Best-Effort nur explizit konfigurierbar.
- **Privacy by Design**: Lokale Verarbeitung als Standard, externe APIs nur opt-in.

---

## 2. Modul-Struktur

### 2.1 Projekt-Layout (Gradle Multi-Module)

Die Module `hexagon:core`, `hexagon:ports-common`, `hexagon:ports-read`,
`hexagon:ports-write`, `hexagon:ports` (Aggregator), `hexagon:application`,
`hexagon:profiling` sowie die Adapter-Module unter `adapters/` sind
implementiert. `hexagon:ports` ist in drei Teilmodule zerlegt; der Aggregator
re-exportiert alle drei für bestehende Consumer. Die Profiling-Adapter liegen
in optionalen `driver-*-profiling`-Modulen.
Weitere Module (ai, testdata) beschreiben den geplanten Soll-Zustand
für geplante Erweiterungen.

```
d-migrate/
├── build.gradle.kts
├── settings.gradle.kts
│
├── hexagon/
│   ├── core/                              # Domain Core (keine externen Deps)
│   │   └── dev/dmigrate/core/
│   │       ├── model/                     # SchemaDefinition, NeutralType, …
│   │       ├── validation/                # SchemaValidator, ValidationResult
│   │       └── data/                      # DataChunk, DataFilter, ColumnDescriptor
│   │
│   ├── ports-common/                      # Gemeinsame Port-Typen
│   │   └── dev/dmigrate/
│   │       ├── driver/
│   │       │   ├── DatabaseDialect.kt
│   │       │   ├── DialectCapabilities.kt # Capability-Modell pro Dialekt
│   │       │   ├── SqlIdentifiers.kt      # Zentrales Identifier-Quoting
│   │       │   ├── TypeMapper.kt
│   │       │   ├── connection/            # ConnectionPool, ConnectionConfig, JdbcUrlBuilder, PoolSettings
│   │       │   └── data/                  # ResumeMarker
│   │       └── format/
│   │           ├── SchemaCodec.kt
│   │           └── data/                  # DataExportFormat
│   │
│   ├── ports-read/                        # Lese-Ports (read-only Consumer)
│   │   └── dev/dmigrate/
│   │       ├── driver/
│   │       │   ├── DdlGenerator.kt        # + DdlResult, DdlPhase, TransformationNote, …
│   │       │   ├── ManualActionRequired.kt
│   │       │   ├── SchemaReader.kt
│   │       │   └── data/                  # DataReader, TableLister, ChunkSequence
│   │       └── format/data/               # DataChunkReader, FormatReadOptions
│   │
│   ├── ports-write/                       # Schreib-Ports
│   │   └── dev/dmigrate/
│   │       ├── driver/data/               # DataWriter, TableImportSession, ImportOptions
│   │       ├── format/data/               # DataChunkWriter, ExportOptions
│   │       ├── migration/                 # MigrationBundle, ToolMigrationExporter
│   │       └── streaming/                 # ExportOutput, ExportResult, PipelineConfig, Checkpoint
│   │
│   ├── ports/                             # Aggregator — re-exportiert ports-common/-read/-write
│   │
│   └── application/                       # Use Cases (Runner-Klassen)
│       └── dev/dmigrate/cli/commands/
│           ├── SchemaGenerateRunner.kt
│           ├── DataExportRunner.kt
│           └── DataExportHelpers.kt
│
├── adapters/
│   ├── driving/
│   │   └── cli/                           # CLI-Einstiegspunkt (Clikt)
│   │       └── dev/dmigrate/cli/
│   │           ├── Main.kt                # Bootstrap + Wiring
│   │           ├── commands/              # Clikt-Commands (SchemaCommands, DataCommands, SchemaGenerateHelpers)
│   │           ├── config/                # NamedConnectionResolver
│   │           └── output/                # OutputFormatter
│   │
│   └── driven/
│       ├── driver-common/                 # Gemeinsame DB-Infrastruktur
│       │   └── dev/dmigrate/driver/
│       │       ├── AbstractDdlGenerator.kt
│       │       ├── ViewQueryTransformer.kt
│       │       ├── connection/            # HikariConnectionPoolFactory, ConnectionUrlParser, LogScrubber
│       │       └── data/                  # AbstractJdbcDataReader
│       │
│       ├── driver-postgresql/             # PostgreSQL DatabaseDriver
│       │   └── dev/dmigrate/driver/postgresql/
│       │       ├── PostgresDriver.kt      # class : DatabaseDriver
│       │       ├── PostgresDdlGenerator.kt
│       │       ├── PostgresTypeMapper.kt
│       │       ├── PostgresDataReader.kt
│       │       ├── PostgresTableLister.kt
│       │       └── PostgresJdbcUrlBuilder.kt
│       │
│       ├── driver-mysql/                  # MySQL DatabaseDriver (analog)
│       ├── driver-sqlite/                 # SQLite DatabaseDriver (analog)
│       │
│       ├── formats/                       # Serialisierung / Deserialisierung
│       │   └── dev/dmigrate/format/
│       │       ├── yaml/YamlSchemaCodec.kt
│       │       ├── report/TransformationReportWriter.kt
│       │       └── data/                  # Json/Yaml/CsvChunkWriter+Reader, DefaultFactories, ValueSerializer/Deserializer, EncodingDetector
│       │
│       └── streaming/                     # Streaming-Pipeline
│           └── dev/dmigrate/streaming/
│               └── StreamingExporter.kt
```

> Zielbild-Erweiterung: `integrations/` (Flyway, Liquibase, Django, Knex).
> Geplante, noch nicht implementierte Module: `ai/` (Ollama, LM Studio, OpenAI,
> Anthropic, …), `testdata/` (Faker, KI-gestuetzt) — siehe Roadmap.

### 2.2 Modul-Abhängigkeiten

```
adapters:driving:cli
├── hexagon:application ──▶ hexagon:core, hexagon:ports
├── hexagon:ports (Aggregator) ──▶ ports-common, ports-read, ports-write
│   ├── hexagon:ports-common ──▶ hexagon:core
│   ├── hexagon:ports-read ──▶ ports-common
│   └── hexagon:ports-write ──▶ ports-common, ports-read
├── hexagon:profiling ──▶ hexagon:core (optional, kein Treiber-Dep)
├── adapters:driven:driver-common ──▶ hexagon:ports, HikariCP, SLF4J
├── adapters:driven:driver-postgresql ──▶ hexagon:ports, driver-common
├── adapters:driven:driver-mysql ──▶ hexagon:ports, driver-common
├── adapters:driven:driver-sqlite ──▶ hexagon:ports, driver-common
├── adapters:driven:driver-*-profiling ──▶ driver-*, hexagon:profiling (optional)
├── adapters:driven:formats ──▶ ports-read, ports-write, Jackson, DSL-JSON, SnakeYAML, Univocity
├── adapters:driven:integrations ──▶ hexagon:ports, driver-common
├── adapters:driven:streaming ──▶ ports-read, ports-write
└── adapters:driven:text-icu ──▶ hexagon:ports-common, ICU4J
```

**Regel**: `hexagon:core` hat KEINE Abhängigkeit auf andere Module. `ports-common` hängt nur von `core` ab. `ports-read` nur von `ports-common`. `ports-write` von `ports-common` und `ports-read`. `hexagon:application` hängt nur vom Hexagon-Inneren ab, nie von Adaptern. Driven Adapters dürfen in main nicht voneinander abhängen (Ausnahme: Driver-Module → `driver-common`). Treiber-Kernmodule hängen nicht von `hexagon:profiling` ab.

---

## 3. Kernkomponenten

### 3.1 Database Driver (Port & Adapter)

#### Driver-Fassade

Das `DatabaseDriver`-Interface exponiert folgende Ports:

```kotlin
/**
 * Port: Abstraktion für Datenbankzugriff.
 * Jeder Treiber implementiert dieses Interface.
 */
interface DatabaseDriver {
    val dialect: DatabaseDialect

    fun ddlGenerator(): DdlGenerator
    fun dataReader(): DataReader
    fun tableLister(): TableLister
    fun dataWriter(): DataWriter
    fun urlBuilder(): JdbcUrlBuilder
}
```

`TableLister` existiert produktiv und wird bereits für Auto-Discovery im
Datenexport genutzt. `TypeMapper` ist kein exponierter Port — er ist internes
Implementierungsdetail von `DdlGenerator` (via `AbstractDdlGenerator`).

#### `schemaReader()`

`schemaReader()` ist Teil der Driver-Fassade und wird von PostgreSQL, MySQL
und SQLite bereitgestellt:

```kotlin
interface DatabaseDriver {
    // … bestehende Ports …
    fun schemaReader(): SchemaReader
}
```

`SchemaReader` liefert ein Ergebnisobjekt, das neben dem Schema auch
Reverse-Notes und übersprungene Objekte transportiert:

```kotlin
interface SchemaReader {
    fun read(
        pool: ConnectionPool,
        options: SchemaReadOptions = SchemaReadOptions(),
    ): SchemaReadResult
}

data class SchemaReadResult(
    val schema: SchemaDefinition,
    val notes: List<SchemaReadNote> = emptyList(),
    val skippedObjects: List<SkippedObject> = emptyList(),
)
```

Wichtig:
- `SchemaReader` liefert **nicht** ein nacktes `SchemaDefinition`, sondern
  ein `SchemaReadResult` mit `schema`, `notes` und optional `skippedObjects`.
- `TableLister` bleibt als bestehender Zwischenport für Export-Pfade
  sichtbar und wird nicht still aus der Architektur gestrichen.
- `SchemaWriter` (DDL-Generierung via neutrales Modell) wird in der
  bestehenden Codebasis durch `DdlGenerator` abgedeckt. Ein separates
  `SchemaWriter`-Interface bleibt ein optionaler Erweiterungspfad.

#### Weitere Port-Interfaces

```kotlin
interface DataReader {
    /** Streaming-basierter Datenexport */
    fun streamTable(
        connection: ConnectionPool,
        table: String,
        filter: DataFilter? = null,
        chunkSize: Int = 10_000
    ): Flow<DataChunk>
}

interface DataWriter {
    /** Transaktionaler Datenimport */
    suspend fun importChunk(
        connection: ConnectionPool,
        table: String,
        chunk: DataChunk
    ): ImportResult
}

interface FormatCodec<T> {
    val format: DataFormat

    fun read(input: InputStream, options: FormatOptions): T
    fun write(output: OutputStream, value: T, options: FormatOptions)
}

data class FormatOptions(
    val encoding: TextEncoding = TextEncoding.UTF8,
    val bomMode: BomMode = BomMode.AUTO,
    // Optional; wenn gesetzt, wird die Zone in expliziten Konvertierungen
    // via TemporalFormatPolicy.toZoned(...) eingesetzt. Der Caller leitet
    // sie typischerweise aus ResolvedI18nSettings.timezone ab.
    val timezone: ZoneId? = null
)
```

### 3.2 Driver-Registrierung (SPI)

Treiber werden über Java ServiceLoader automatisch erkannt:

```
# META-INF/services/dev.dmigrate.driver.DatabaseDriver
dev.dmigrate.driver.postgresql.PostgresDriver
dev.dmigrate.driver.mysql.MysqlDriver
dev.dmigrate.driver.sqlite.SqliteDriver
```

Dadurch können neue Treiber (Oracle, MSSQL) als separate JARs hinzugefügt werden, ohne den Core zu ändern.

Wichtig für Distribution und Deployment:

- **JVM/Fat-JAR und Docker**: Dynamisches Nachladen separater Treiber-JARs wird direkt unterstützt.
- **GraalVM Native Image**: Unterstützte Treiber werden zur Build-Zeit eingebunden. Zusätzliche Treiber erfordern ein neues Native-Build-Profil oder den Wechsel auf die JVM-Distribution.

### 3.3 Streaming-Pipeline

```kotlin
/**
 * Streaming-Pipeline für Datenverarbeitung.
 * Verarbeitet Daten chunkweise ohne vollständiges Laden in den Speicher.
 */
class StreamingPipeline(
    private val sourceDriver: DatabaseDriver,
    private val targetDriver: DatabaseDriver,
    private val transformer: DataTransformer,
    private val checkpointStore: CheckpointStore
) {
    suspend fun execute(
        sourceConnection: DatabaseConnection,
        targetConnection: DatabaseConnection,
        tables: List<String>,
        foreignKeys: List<ForeignKeyDefinition>,
        config: PipelineConfig
    ): PipelineResult {
        val graph = DependencyGraph.build(tables, foreignKeys)
        val executionOrder = graph.topologicalSort()
        val sourceReader = sourceDriver.dataReader()
        val targetWriter = targetDriver.dataWriter()

        // Unabhängige Tabellen parallel, abhängige sequentiell
        return coroutineScope {
            for (group in executionOrder.parallelGroups()) {
                group.map { table ->
                    async(Dispatchers.IO) {
                        processTable(
                            table = table,
                            sourceConnection = sourceConnection,
                            targetConnection = targetConnection,
                            sourceReader = sourceReader,
                            targetWriter = targetWriter,
                            config = config
                        )
                    }
                }.awaitAll()
            }

            PipelineResult.success(processedTables = tables)
        }
    }

    private suspend fun processTable(
        table: String,
        sourceConnection: DatabaseConnection,
        targetConnection: DatabaseConnection,
        sourceReader: DataReader,
        targetWriter: DataWriter,
        config: PipelineConfig
    ) {
        val checkpoint = checkpointStore.load(table)

        sourceReader
            .streamTable(
                connection = sourceConnection,
                table = table,
                filter = checkpoint?.let { DataFilter.resumeAfter(it.lastProcessedId) },
                chunkSize = config.chunkSize
            )
            .onEach { chunk ->
                val transformed = transformer.transform(chunk)
                targetWriter.importChunk(
                    connection = targetConnection,
                    table = table,
                    chunk = transformed
                )
                checkpointStore.save(table, chunk.lastId, chunk.count)
            }
            .collect()
    }
}
```

### 3.4 Type-Mapping-Engine

Die vollständige Typ-Mapping-Tabelle (18 neutrale Typen mit Attributen) und die Validierungsregeln sind in der [Neutrales-Modell-Spezifikation §3](./neutral-model-spec.md#3-neutrales-typsystem) definiert.

```kotlin
/**
 * Forward Type-Mapping vom neutralen Typ zum DB-spezifischen Typ.
 * Pure Function — kein State, vollständig testbar.
 */
interface TypeMapper {
    val dialect: DatabaseDialect
    fun toSql(type: NeutralType): String
    fun toDefaultSql(default: DefaultValue, type: NeutralType): String
}

// Beispiel: PostgreSQL Forward Type Mapper
class PostgresTypeMapper : TypeMapper {
    override val dialect = DatabaseDialect.POSTGRESQL

    override fun toSql(type: NeutralType): String = when (type) {
        is NeutralType.Identifier -> if (type.autoIncrement) "SERIAL" else "INTEGER"
        is NeutralType.Integer -> "INTEGER"
        is NeutralType.BigInteger -> "BIGINT"
        is NeutralType.Text -> if (type.maxLength != null) "VARCHAR(${type.maxLength})" else "TEXT"
        is NeutralType.Char -> "CHAR(${type.length})"
        is NeutralType.SmallInt -> "SMALLINT"
        is NeutralType.Float -> when (type.floatPrecision) {
            FloatPrecision.SINGLE -> "REAL"
            FloatPrecision.DOUBLE -> "DOUBLE PRECISION"
        }
        is NeutralType.Xml -> "XML"
        is NeutralType.BooleanType -> "BOOLEAN"
        is NeutralType.Json -> "JSONB"
        is NeutralType.Binary -> "BYTEA"
        is NeutralType.Uuid -> "UUID"
        is NeutralType.DateTime -> if (type.timezone) "TIMESTAMP WITH TIME ZONE" else "TIMESTAMP"
        is NeutralType.Decimal -> "DECIMAL(${type.precision},${type.scale})"
        is NeutralType.Enum -> "TEXT" // PostgreSQL ENUM via CREATE TYPE
        // ...
    }
}
```

Reverse-Mapping ist nicht Teil von `TypeMapper`; es liegt in den
driver-spezifischen Metadata-Mapping-Komponenten. Der aktuelle PostgreSQL-Pfad
mapped 32-bit `serial`/Identity auf `NeutralType.Identifier(autoIncrement =
true)`. 64-bit `bigserial`/`bigint identity` wird bewusst als
`NeutralType.BigInteger` plus Diagnose transportiert, damit die Breite nicht zu
`SERIAL` kollabiert. `NeutralType.BigIdentifier` existiert nicht. Der geplante
64-bit-Identity-Vertrag wird als separates Spaltenmetadatum
`ColumnGeneration.Identity` modelliert, nicht als weiterer `NeutralType`.

### 3.5 Generator-Options-Pfad und Spatial-Profil

#### Datenfluss

`schema generate` uebergibt Generator-Optionen als separaten Konfigurationspfad
an den DDL-Generator. Diese Optionen sind **nicht** Teil des neutralen
Schema-Modells (d.h. sie sind weder in `SchemaDefinition` noch in
`ColumnDefinition` gespeichert) — sie steuern ausschliesslich das
Generierungsverhalten.

```
adapters:driving:cli
  SchemaGenerateCommand
       │  parst --target, --spatial-profile, --generate-rollback
       │
       ▼
  GeneratorOptions(
      dialect         = DatabaseDialect.POSTGRESQL,
      spatialProfile  = SpatialProfile.POSTGIS,   ← aus CLI-Flag oder Dialect-Default
      generateRollback = false
  )
       │
       ▼
hexagon:application
  SchemaGenerateRunner.run(schema: SchemaDefinition, options: GeneratorOptions)
       │
       ▼
hexagon:ports
  DdlGenerator.generate(schema: SchemaDefinition, options: GeneratorOptions): DdlResult
       │
       ▼
adapters:driven:driver-postgresql / driver-mysql / driver-sqlite
  Konkrete DDL-Generierung mit spatialProfile aus options
```

`GeneratorOptions` wird im `hexagon:ports`-Modul definiert, damit alle
Driven-Adapter denselben Kontrakt implementieren. `hexagon:core` kennt
`GeneratorOptions` nicht — es beschreibt nur das Schema-Modell und die
Schema-Validierungsregeln.

#### Verortung von `spatialProfile`

| Aspekt                  | Verortung                            | Begruendung                                                    |
| ----------------------- | ------------------------------------ | -------------------------------------------------------------- |
| `type: geometry`        | `hexagon:core` — neutrales Modell    | Gehoert zur portablen Schema-Definition                        |
| `geometry_type`, `srid` | `hexagon:core` — `ColumnDefinition`  | Portable Schema-Metadaten                                      |
| E120, E121              | `hexagon:core` — `SchemaValidator`   | Schema-/Modellregeln, unabhaengig vom Zieldialekt              |
| `spatialProfile`        | `hexagon:ports` — `GeneratorOptions` | Generator-Konfiguration, dialektabhaengig                      |
| E052, W120              | Driven Adapter — DDL-Generator       | Generator-/Report-Regeln, entstehen erst bei `schema generate` |

#### Trennung von Validierung und Generierung

Die Architektur unterscheidet explizit zwischen zwei Ebenen:

- **`schema validate`** prueft das neutrale Schema-Modell in `hexagon:core`.
  Hier entstehen E120 (unbekannter `geometry_type`) und E121 (`srid` <= 0).
  Diese Pruefungen sind dialektunabhaengig.

- **`schema generate`** bewertet die Generierbarkeit im gewaehlten Zielprofil
  in den Driven Adaptern. Hier entstehen E052 (Tabelle blockiert wegen Profil
  `none`) und W120 (SRID-Uebertragung best-effort). Diese Codes existieren
  nicht auf Schema-Ebene — sie sind Ergebnisse der konkreten Transformation.

Ein Schema, das E120 oder E121 erzeugt, wird von `schema validate` zurueckgewiesen.
`schema generate` ruft `schema validate` implizit auf und bricht bei
Validierungsfehlern ab (Exit-Code 3), bevor irgendwelche DDL erzeugt wird.

Die Spatial-Typsystem-Basis (`geometry`, `geometry_type`, `srid`,
`GeneratorOptions`) ist Voraussetzung fuer Reverse Engineering und
Daten-Transfer: `schema reverse` muss Spatial-Spalten in das neutrale Modell
zurueckfuehren koennen, und `schema compare` muss Geometry-Spalten korrekt
vergleichen. Ohne diese Modell- und Validierungsbasis waere das nicht
typsicher moeglich.

### 3.6 Tool-Export-Pfad

Der Tool-Export-Pfad stellt externe Migrationstools als Driven Adapter bereit.
Im Hexagon bleibt er ein tool-neutraler Generate-Use-Case: Die Application
erzeugt aus neutralem Schema, validierter Generator-Konfiguration und
DDL-Ergebnis ein `MigrationBundle`; die Integrations-Adapter rendern daraus
tool-spezifische Artefakte. Der Pfad fuehrt keine Pflicht-
Laufzeitabhaengigkeiten auf Tool-Runtimes ein und mutiert keine bestehenden
Tool-Projektdateien.

#### Port-Vertrag (`hexagon:ports`)

Die tool-neutrale Exportfamilie liegt im Package `dev.dmigrate.migration`:

- `MigrationBundle` — kapselt Identity, Schema, DDL-Payloads und Rollback-State
- `MigrationIdentity` — Tool, Dialekt, Version, Versionsquelle, Slug
- `MigrationDdlPayload` — generatornahes `DdlResult` plus timestamp-bereinigte
  deterministische SQL-Darstellung
- `MigrationRollback` — sealed: `NotRequested` oder `Requested(down)`
- `ArtifactRelativePath` — validierter relativer Pfad (kein Escape, kein absolut)
- `ToolMigrationExporter` — Port fuer Tool-Adapter: `render(bundle) -> ToolExportResult`
- `ToolExportResult` — Artefaktliste plus export-spezifische `ToolExportNote`s

Generator-Diagnostik (`DdlResult.notes`, `skippedObjects`) bleibt im Bundle
erhalten und wird nicht in Export-Notes umgedeutet.

#### Application-Layer (`hexagon:application`)

- `MigrationIdentityResolver` — Version-/Slug-Aufloesung (I/O-frei)
- `MigrationVersionValidator` — tool-spezifische Versionsformate
- `MigrationSlugNormalizer` — `schema.name` → dateinamenfaehiger Slug
- `DdlNormalizer` — einmalige Timestamp-Bereinigung fuer deterministische Inhalte
- `ArtifactCollisionChecker` — in-run- und Dateisystem-Kollisionen (I/O-frei)
- `ToolExportRunner` — Orchestrator: Schema lesen → validieren → DDL erzeugen
  → Bundle bauen → Exporter aufrufen → Kollisionen pruefen → Artefakte schreiben
  → Report und Diagnostik ausgeben

#### Driven Adapter (`adapters:driven:integrations`)

Vier Implementierungen von `ToolMigrationExporter`, alle side-effect-frei:

- `FlywayMigrationExporter` — `V<version>__<slug>.sql` + opt. `U...sql`
- `LiquibaseMigrationExporter` — versionierter XML-Changelog mit `changeSet`
- `DjangoMigrationExporter` — `RunSQL`-Migration aus `result.statements`
- `KnexMigrationExporter` — CommonJS mit `knex.raw()` aus `result.statements`

Das Modul haengt nur von `hexagon:ports` ab und fuehrt keine Tool-Runtime-
Abhaengigkeiten ein.

#### Driving Adapter (`adapters:driving:cli`)

- `ExportCommand` — Clikt-Gruppe unter `d-migrate export`
- `ExportFlywayCommand`, `ExportLiquibaseCommand`, `ExportDjangoCommand`,
  `ExportKnexCommand` — duenne Schalen, die `ToolExportRequest` bauen und
  an `ToolExportRunner` delegieren

#### Runtime-Validierung (Test)

Die fokussierte Runtime-Matrix (Flyway→PostgreSQL, Liquibase→PostgreSQL,
Django→SQLite, Knex→SQLite) wird als Integrations-Tests in
`adapters:driven:integrations` ausgefuehrt, markiert mit
`NamedTag("integration")` und steuerbar ueber `-PintegrationTests`.

### 3.7 Daten-Profiling

Daten-Profiling liegt in einem dedizierten Profiling-Modul, das
Spaltenstatistiken, Qualitaetswarnungen und Zieltyp-Kompatibilitaet fuer
bestehende Datenbanken liefert.

#### Modul `hexagon:profiling`

Eigenes Hexagon-Modul mit:

- **Domaenenmodell**: `DatabaseProfile`, `TableProfile`, `ColumnProfile`,
  `ProfileWarning`, `TargetTypeCompatibility`
- **Typsystem**: `LogicalType` (datenorientiert, getrennt von `NeutralType`),
  `TargetLogicalType`, `Severity`, `WarningCode`
- **Rule-Engine**: `WarningEvaluator` mit 8 migrationsrelevanten Regeln
  (rein funktional, kein JDBC)
- **Outbound-Ports**: `SchemaIntrospectionPort`, `ProfilingDataPort`,
  `LogicalTypeResolverPort`
- **Services**: `ProfileTableService`, `ProfileDatabaseService`

`DatabaseDriver` bleibt unveraendert — Profiling-Ports werden ueber einen
separaten `ProfilingAdapterSet`-Lookup verdrahtet.

#### Adapter (in bestehenden Driver-Modulen)

PostgreSQL, MySQL und SQLite implementieren je:

- `SchemaIntrospectionAdapter` — eigene Profiling-Projektion mit rohem `dbType`
- `ProfilingDataAdapter` — Aggregat-Queries, Top-N, Kompatibilitaetspruefungen
- `LogicalTypeResolver` — DB-Typ → `LogicalType` Mapping

#### Application-Layer

- `DataProfileRunner` — Orchestrator analog zu `SchemaReverseRunner`
- `DataProfileRequest` — DTO mit `source`, `tables`, `schema`, `topN`,
  `format`, `output`

#### CLI

- `DataProfileCommand` unter `d-migrate data profile`
- JSON (Default) oder YAML-Report via `ProfileReportWriter`

#### Determinismus

Der Default-Report ist byte-reproduzierbar: stabile Tabellen-/Spaltenreihenfolge,
stabile `topValues`-Sortierung, kein laufzeitvariables `generatedAt`.

### 3.8 Phasenbezogenes DDL-Modell

Das phasenbezogene DDL-Modell klassifiziert DDL-Statements in `PRE_DATA` und
`POST_DATA`. Damit kann `schema generate --split pre-post` importfreundliche
Artefakte erzeugen, bei denen Trigger erst nach einem Datenimport aktiviert
werden.

#### Modell (`hexagon:ports-read`)

```kotlin
enum class DdlPhase {
    /** Structural DDL: tables, columns, sequences, indexes, constraints. */
    PRE_DATA,
    /** Deferred DDL: triggers, functions, procedures, views with routine deps. */
    POST_DATA,
}

data class DdlStatement(
    val sql: String,
    val notes: List<TransformationNote> = emptyList(),
    val phase: DdlPhase = DdlPhase.PRE_DATA,
)
```

`DdlResult` bietet Filtermethoden pro Phase:

- `statementsForPhase(phase)` — Statements einer Phase
- `renderPhase(phase)` — gerenderte DDL einer Phase
- `notesForPhase(phase)` / `skippedObjectsForPhase(phase)` — Diagnostik pro Phase

#### Objektzuordnung

| Phase       | Objekte                                                                                                                 |
| ----------- | ----------------------------------------------------------------------------------------------------------------------- |
| `PRE_DATA`  | Custom Types, Sequences, Tabellen (topologisch sortiert), Indizes, Constraints, Views **ohne** Routinen-Abhaengigkeiten |
| `POST_DATA` | Functions, Procedures, Triggers, Views **mit** Routinen-Abhaengigkeiten                                                 |

Die Zuordnung von Views erfolgt ueber den `ViewPhaseClassifier`
(`adapters:driven:driver-common`), der drei Regeln anwendet:

1. **Deklarierte Abhaengigkeiten**: `dependencies.functions` im Schema → `POST_DATA`
2. **Inferierte Funktionsaufrufe**: Query-Text wird auf Funktionsnamen geparst → `POST_DATA`
3. **Transitive Propagation**: Views, die von einer `POST_DATA`-View abhaengen → `POST_DATA`

Views ohne Query-Text und ohne deklarierte `dependencies.functions` erzeugen
bei vorhandenen Functions im Schema den Fehlercode E060.

#### Datenfluss

```
SchemaGenerateRunner
    │  SplitMode: SINGLE | PRE_POST
    ▼
AbstractDdlGenerator.generate()
    │  weist DdlPhase pro Statement zu
    │  (PRE_DATA default, POST_DATA explizit fuer Routinen/Trigger)
    ▼
DdlResult
    │
    ├─ SINGLE:    renderAll() → eine Ausgabedatei
    └─ PRE_POST:  renderPhase(PRE_DATA) → *.pre-data.sql
                  renderPhase(POST_DATA) → *.post-data.sql
```

Der Default-Modus `SINGLE` bleibt rueckwaertskompatibel — alle Statements
werden in einer Datei ausgegeben, die Phase-Information wird ignoriert.

### 3.9 Live-DB-Probes fuer Sequence-Migrationen

`schema migrate --execute` gegen MySQL/PostgreSQL-Targets traegt zwei
unabhaengige Live-DB-Probe-Stages im Render-Pfad — beide folgen dem
F.5 `CheckPreflightProbe`-Adapter-Muster: stateless Port in
`hexagon:ports-read`, JDBC-Adapter im Driver-Modul, Application-Layer-
Stage entscheidet ueber Skip/Probe/Block, CLI-Wiring im `:adapters:driving:cli`-
Modul.

#### 3.9.1 Drift-Check fuer die MySQL-Helper-Table-Emulation

Vor jedem Sequence-/`SequenceNextVal`-Render verifiziert die Pipeline,
dass die `dmg_sequences`-Helper-Table (Tabelle + `dmg_nextval`/`dmg_setval`-
Routinen + per-Sequenz-Zeile + spaltenbezogener `dmg_seq_…_bi`-Trigger)
der kanonischen Form entspricht. Drift wird mit
`E124_MYSQL_SEQUENCE_DRIFT_TABLE`/`_ROUTINE`/`_ROW`/`_TRIGGER` (alle
`MANUAL_ACTION_REQUIRED`) geblockt; PK-Verlust und Body-Signatur-
Vergleich (nicht nur Marker-Substring) sind Teil des Vertrags.

```
plan
  → MysqlSequenceCanonicityStage.run(probe, request, target, dialect, plan)
        → fuer jede Sequence-Op + jede AddColumn/AlterColumnDefault mit SequenceNextVal:
              SUPPORT_TABLE + NEXTVAL_ROUTINE + SETVAL_ROUTINE + SEQUENCE_ROW + SUPPORT_TRIGGER
              → Declarations (CANONICAL/DRIFT/MISSING/NOT_RUN_*/PROBE_RUNTIME_ERROR)
  → DdlGenerationOptions.dialectContext (MySql.sequenceCanonicity)
  → MysqlDiffSequenceOps.canonicityBlocks(op, intent, ctx) gated Create/Alter/Drop/Rename
  → MigrationDdlResult.mysqlSequenceCanonicity → SchemaMigrateReport
```

Schluesselkomponenten:

- **Port**: `MysqlSequenceCanonicityProbe` + sealed `MysqlSequenceCanonicityKind`
  (`SUPPORT_TABLE`/`NEXTVAL_ROUTINE`/`SETVAL_ROUTINE`/`SEQUENCE_ROW`/
  `SUPPORT_TRIGGER`) + `MysqlSequenceCanonicityStatus`.
- **Gate**: `MysqlSequenceCanonicityGate.decide(declaration, intent)` routet
  per `(status × OpIntent)`-Matrix; `MISSING + DROP` fuer `SEQUENCE_ROW`/
  `SUPPORT_TABLE` blockt mit `E124_MYSQL_SEQUENCE_MISSING_FOR_DROP`.
- **Adapter**: `MysqlSequenceCanonicityProbeAdapter` (`driver-mysql`) nutzt
  `INFORMATION_SCHEMA.COLUMNS` + `SHOW CREATE FUNCTION/TRIGGER`; 1305/1360
  MySQL-Errors → `MISSING`.
- **Stage**: `MysqlSequenceCanonicityStage` (`hexagon:application`) im Render-
  Pipeline-Flow.

#### 3.9.2 preserveCurrentValue — runtime-state-Migration

Per Default verliert eine Sequenz ihren runtime-Wert beim Migrieren —
`CREATE SEQUENCE … START WITH 1` setzt `nextval` auf `1`, auch wenn das
Live-Target bereits bei `5000` stand. `preserveCurrentValue: true` auf
`SequenceDefinition` aktiviert opt-in pro Sequenz einen Follow-up-Pfad,
der den geprobten `last_value`/`next_value` ueber die Migration rettet.

```
plan(initial)
  → SequencePreserveStage.run(probe, request, target, dialect, plan)
        → fuer jede Create/Alter/RenameSequence mit preserveCurrentValue=true:
              SequenceCurrentValueProbe(target, ref) → Read/NotFound/Failed/NotApplicable
              → AlterSequenceCurrentValue-FollowUp direkt hinter der parent-Op
                  (dependencies = setOf(parent.id))
              | INFO SEQUENCE_PRESERVE_NOT_FOUND (CreateSequence ohne Vorzustand)
              | BLOCKER SEQUENCE_PRESERVE_PROBE_FAILED/REQUIRES_DB_TARGET/NOT_SUPPORTED_BY_DIALECT/CONFIG_INVALID
  → augmentierter Plan ersetzt das Original (auch fuer migration-plan.v1-Artefakt!)
  → Renderer:
        PG  → SELECT setval('<seq>', <value>, <is_called>);
        MySQL → UPDATE dmg_sequences SET next_value = <v>
                 WHERE name = <key> AND managed_by IN (...) AND format_version IN (...);
        SQLite (helper_table opt-in via --sqlite-named-sequences)
              → UPDATE "dmg_sequences" SET "next_value" = <v>
                 WHERE "name" = <key>;
              (ohne Opt-in: BLOCKER SEQUENCE_PRESERVE_OPT_IN_REQUIRED → MANUAL_ACTION_REQUIRED)
```

Schluesselkomponenten:

- **Port**: `SequenceCurrentValueProbe` + sealed `SequenceCurrentValueProbeResult`
  (`Read{value, matchedRows, isCalled?, managedBy?, formatVersion?}`,
  `Failed{code, message}`, `NotFound`, `NotApplicable`).
- **Werttyp**: `SequenceObjectRef(name, schema, dialect: RenameProjectionDialect)`
  als Probe-Eingabe.
- **DiffOp-Subtyp**: `DiffOperation.AlterSequenceCurrentValue` mit
  `pairId`/`probeSequenceRef`/`applySequenceRef`/`currentValue`/`isCalled?`/
  `restoreValue?`/`restoreIsCalled?`/`rollbackImpossible`/
  `rollbackImpossibleReason?`/`revertAfterRename`.
- **Renderer**: `PostgresDiffSequenceOps.renderAlterSequenceCurrentValue`
  (PG), `MysqlDiffSequenceOps.renderAlterSequenceCurrentValue` (MySQL),
  `SqliteDiffSequenceOps.renderAlterSequenceCurrentValue` (SQLite —
  Up auf `applySequenceRef`, Down auf
  `probeSequenceRef` mit `restoreValue`; `restoreValue == null` ⇒
  Skip mit `SQLITE_SEQUENCE_CURRENT_VALUE_DOWN_ROLLBACK_IMPOSSIBLE`).
- **JDBC-Adapter**: `PostgresSequenceCurrentValueProbe` (SQLSTATE
  42P01/42501-Mapping), `MysqlSequenceCurrentValueProbe`
  (Error-Code 1146/1142 + `managed_by`/`format_version`-Set-Validation
  ueber `MysqlSequenceSupportNaming.SUPPORTED_MANAGED_BY` /
  `SUPPORTED_FORMAT_VERSIONS`), `SqliteSequenceCurrentValueProbe`
  (no-such-table-Message → `NotFound`, SQLITE_PERM/SQLITE_AUTH →
  `PROBE_PERMISSION_DENIED`; Validierung gegen
  `SqliteSequenceNaming.MANAGED_BY` / `FORMAT_VERSION`).
- **Stage**: `SequencePreserveStage` (`hexagon:application`) — Kandidaten-
  Filter, file-Target-Priority-Blocker, Dialekt-Allowlist
  (PG/MySQL/SQLite), SQLite-`helper_table`-Opt-in-Gate
  (`SEQUENCE_PRESERVE_OPT_IN_REQUIRED`), Probe-Routing,
  Plan-Augmentation.
- **CLI**: `SequenceCurrentValueProbeRunner` dispatcht per
  `SequenceObjectRef.dialect` an die PG/MySQL/SQLite-Adapter.
  `--sqlite-named-sequences helper_table` auf `schema migrate`
  schaltet den SQLite-Probe-Pfad frei.

`AlterSequenceCurrentValue`-Follow-ups landen **direkt hinter ihrer
parent-Op** im augmentierten Plan und sind in `dependencies` auf die
parent-Op-ID gepinnt. Das signierte `migration-plan.v1`-Artefakt
(`--plan-artefact`) erhaelt den augmentierten Plan — ein Operator,
der nur das Artefakt liest, sieht das `setval`/`UPDATE`-Statement.

#### 3.9.3 Gemeinsames Probe-Adapter-Pattern

Beide Stages folgen dem gleichen Schichtschnitt — neue Live-DB-Probes
(z.B. fuer CHECK-Constraints) folgen diesem Pattern, sodass
`:hexagon:application` keine driver-spezifischen Importe sieht und CLI-Wiring
konsistent bleibt:

| Schicht                     | Drift-Check                                              | preserveCurrentValue                                                                                                                                                                        |
| --------------------------- | -------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Port (`hexagon:ports-read`) | `MysqlSequenceCanonicityProbe`                           | `SequenceCurrentValueProbe`                                                                                                                                                                 |
| Result-Typ                  | `MysqlSequenceCanonicityDeclaration`                     | sealed `SequenceCurrentValueProbeResult`                                                                                                                                                    |
| Application-Layer Stage     | `MysqlSequenceCanonicityStage`                           | `SequencePreserveStage`                                                                                                                                                                     |
| JDBC-Adapter                | `MysqlSequenceCanonicityProbeAdapter`                    | `PostgresSequenceCurrentValueProbe`, `MysqlSequenceCurrentValueProbe`, `SqliteSequenceCurrentValueProbe`                                                                                    |
| CLI-Runner                  | `MysqlSequenceCanonicityProbeRunner`                     | `SequenceCurrentValueProbeRunner`                                                                                                                                                           |
| Renderer-Anbindung          | `MysqlDiffSequenceOps.canonicityBlocks`                  | Stage augmentiert Plan; Renderer emittiert Follow-up                                                                                                                                        |
| Classifier-Codes            | `E124_MYSQL_SEQUENCE_DRIFT_*` → `MANUAL_ACTION_REQUIRED` | `SEQUENCE_PRESERVE_PROBE_FAILED` / `_CONFIG_INVALID` / `_REQUIRES_DB_TARGET` / `_OPT_IN_REQUIRED` → `MANUAL_ACTION_REQUIRED`; `_NOT_SUPPORTED_BY_DIALECT` → `DIALECT_UNSUPPORTED_OPERATION` |

### 3.10 Reverse-Read-Treue fuer Programmability-Objekte

`schema reverse` und der DB-Operand in `schema compare` / `schema
migrate` muessen pro Dialekt nicht nur Strukturobjekte (Tabellen,
Indices, FKs) sondern auch Programmability-Objekte (Trigger,
Stored-Functions, Procedures, Views, Sequences) mit allen
Identity-Attributen korrekt aus dem Live-Katalog projizieren. Sonst
emittiert ein anschliessender Compare gegen ein File-Schema mit
identischen Werten spurious `Replace`-Diagnosen, die der Operator
manuell wegsortieren muss.

Fuer diese Treue gelten insbesondere zwei Reader-Vertraege:

#### 3.10.1 SQLite-Trigger-Reverse-Read

`sqlite_master` liefert Trigger als rohen `CREATE TRIGGER`-Text;
der Reader muss den DDL-String parsen, da SQLite kein
strukturiertes `information_schema` hat. Naive Substring-Suche ist
nicht ausreichend, weil sie WHEN-Klauseln verlieren und `INSTEAD OF`
mit `BEFORE` verwechseln kann.

- **Parser**: `SqliteTriggerSqlParser` (token-basiert, string- und
  comment-aware) extrahiert `timing` (BEFORE / AFTER / INSTEAD OF),
  `event` (INSERT / UPDATE / DELETE), `forEach` (ROW), `condition`
  (WHEN-Klausel inkl. Comment-Stripping) und `body` (Multi-Statement
  zwischen `BEGIN` und letztem `END`, mit einem Trim des optionalen
  trailing `;` fuer Renderer-Symmetrie).
- **Reader-Routing**: `SqliteSchemaReader.readTriggers` ruft den
  Parser; R212-rejected Trigger (schema-qualifizierte Namen wie
  `main.trg`) werden aus der Trigger-Map ausgeschlossen, damit
  downstream Object-Key-Kollisionen mit der `schema.table`-Form
  ausgeschlossen sind.
- **Diagnostics**: `R210` (timing missing) / `R211` (event missing)
  sind jetzt `ACTION_REQUIRED` (vorher `WARNING`); `R212`
  (schema-qualified, `ACTION_REQUIRED`) und `R213` (`UPDATE OF
  cols`, `WARNING`) sind neu.
- **Round-Trip-Vertrag**: Reverse → Renderer-DDL → DB → Reverse
  bleibt bit-identisch (`body` ohne trailing `;` weil der Renderer
  `;\nEND;` unconditional anhaengt); die YAML-Codec-Seite ist via
  `SchemaNodeProgrammabilityTriggerRoundtripTest` separat gepinnt.

#### 3.10.2 MySQL-Routine-Identity-Reverse-Read

`information_schema.routines` liefert pro Routine ein
`security_type`, einen `definer`-String (`'user'@'host'`) und einen
`sql_mode`-Snapshot zur Erzeugungszeit. Der Reader projiziert diese
Identity-Attribute, damit file-zu-DB-Diffs gegen ein File-Schema mit
explizitem `SQL SECURITY DEFINER` oder `sql_mode` keine spurious-
Replace-Diagnosen produzieren.

- **MetadataQueries**: `listFunctions` / `listProcedures` projizieren
  `security_type`, `definer`, `sql_mode` aus
  `information_schema.routines`.
- **Reader**: `readFunctions` / `readProcedures` populieren
  `FunctionDefinition.security` / `definer` / `sqlMode` (analog auf
  `ProcedureDefinition`). Leeres `sql_mode` wird zu `null`
  normalisiert; unbekannte `security_type`-Werte (z.B. aelteres
  MySQL oder eingeschraenkte `information_schema`-Sicht) fallen
  ebenfalls auf `null` zurueck.
- **Comparator-Symmetrie**: `RoutineIdentityNormalizer.normalizeMysqlSqlMode`
  sortiert / dedupliziert die `sql_mode`-Liste, sodass Reihenfolge-Drift im
  Live-Katalog keinen spurious-Replace ausloest.

---

## 4. Querschnittsthemen

### 4.1 Konfigurationsmanagement

```kotlin
/**
 * Hierarchische Konfiguration.
 * Lädt aus mehreren Quellen mit definierter Priorität.
 */
data class DmigrateConfig(
    val database: DatabaseConfig,
    val export: ExportConfig,
    val ai: AiConfig,
    val i18n: I18nConfig,
    val pipeline: PipelineConfig,
    val documentation: DocumentationConfig = DocumentationConfig()
)

data class AiConfig(
    val defaultBackend: String = "ollama",
    val backends: Map<String, AiBackendConfig>,
    val privacy: PrivacyConfig = PrivacyConfig()
)

data class PrivacyConfig(
    val preferLocal: Boolean = true,
    val allowExternal: Boolean = false
)

data class PipelineConfig(
    val chunkSize: Int = 10_000,
    val parallelism: Int = Runtime.getRuntime().availableProcessors(),
    val checkpointInterval: Int = 10_000,
    val retryAttempts: Int = 3,
    val retryDelayMs: Long = 1_000
)

data class I18nConfig(
    val defaultLocale: String = "en",
    // Optional im YAML. Wird vom I18nSettingsResolver aufgeloest:
    //   i18n.default_timezone -> ZoneId.systemDefault() -> UTC (Error-Fallback)
    // UTC ist der Safety-Net-Fallback, nicht der allgemeine Default.
    val defaultTimezone: String? = null,
    val normalizeUnicode: UnicodeNormalization = UnicodeNormalization.NFC
)

data class DocumentationConfig(
    val enabledFormats: Set<String> = setOf("markdown"),
    val includeErDiagrams: Boolean = true,
    val includeLocalizedLabels: Boolean = true
)
```

Architekturvertrag:

- `defaultLocale` beschreibt den Produktdefault; Root-/Fallback-Bundle ist Englisch (`messages.properties`).
- Die effektive I18n-Konfiguration wird ueber denselben Pfadvertrag wie die bestehende CLI-Konfiguration bestimmt: `--config` > `D_MIGRATE_CONFIG` > `./.d-migrate.yaml`. <!-- d-check:ignore (Nutzer-CWD-Pfad, kein Repo-Artefakt; ADR 0011) -->
- `defaultTimezone` ist optional und wird durch `I18nSettingsResolver` in der Reihenfolge `i18n.default_timezone` -> `ZoneId.systemDefault()` -> `UTC` (Error-/Leer-Fallback) zu einer `ZoneId` aufgeloest; die aufgeloeste Zone greift nur in expliziten Konvertierungen, nicht als Serialisierungs-Offset fuer lokale Werte.
- `--lang` ist ein CLI-Override: `--lang` hat Vorrang vor `D_MIGRATE_LANG` und ist strikt auf gebundelte Produktsprachen (`de`, `en`) beschraenkt.

### 4.2 Logging und Observability

```
┌──────────────────────────────────────────────┐
│                  Logging                     │
├──────────────────────────────────────────────┤
│ Level    │ Inhalt                            │
│──────────┼───────────────────────────────────│
│ ERROR    │ Fehlgeschlagene Operationen       │
│ WARN     │ Fallback-Typen, Deprecations      │
│ INFO     │ Start/Ende von Operationen        │
│ DEBUG    │ SQL-Statements, Chunk-Verarbeitung│
│ TRACE    │ Einzelne Datensätze (nur Dev)     │
├──────────────────────────────────────────────┤
│ Trennung: Technische Logs (EN) vs.           │
│           User-Meldungen (lokalisiert)       │
└──────────────────────────────────────────────┘
```

- **Framework**: SLF4J + Logback
- **Strukturiertes Logging**: JSON-Format für maschinelle Auswertung
- **Audit-Log**: Separate Datei für alle DB-verändernden Operationen
- **KI-Audit-Log**: Archivierung von Quell-/Zielcode und Metadaten aller KI-Transformationen (LN-030, LN-031)
- **Sensible Daten**: Passwörter und API-Keys werden NICHT geloggt

### 4.3 Sicherheit

```
Credentials-Handling:
┌─────────────────────────────────────────────┐
│ 1. Umgebungsvariablen (bevorzugt)           │
│ 2. Encrypted Config File (~/.d-migrate/     │
│    credentials.enc, AES-256)                │
│ 3. CLI-Prompt (interaktiv)                  │
│ 4. NIEMALS: Klartext in Config-Dateien      │
└─────────────────────────────────────────────┘

Verbindungen:
- TLS/SSL für alle DB-Verbindungen (konfigurierbar)
- Certificate Pinning optional
- Connection-String-Validierung gegen Injection

Rollenbasierte Zugriffskontrolle (LN-028):
- Rollen: reader (Export, Schema-Ansicht), writer (Import, Migration), admin (Konfiguration, Rollback)
- Konfiguration über .d-migrate.yaml oder Umgebungsvariablen
- Enforcement in CLI-Commands vor Ausführung kritischer Operationen
```

### 4.4 Fehlerbehandlung und Resilienz

```kotlin
/**
 * Retry mit exponentiellem Backoff für transiente Fehler.
 */
suspend fun <T> withRetry(
    maxAttempts: Int = 3,
    initialDelayMs: Long = 1_000,
    block: suspend () -> T
): T {
    var lastException: Throwable? = null
    repeat(maxAttempts) { attempt ->
        try {
            return block()
        } catch (e: TransientException) {
            lastException = e
            delay(initialDelayMs * (1L shl attempt))
        }
    }
    throw RetryExhaustedException(maxAttempts, lastException!!)
}
```

### 4.5 Encoding und internationale Datenformate

- Textbasierte Formate verwenden standardmaessig UTF-8.
- Dateiimporte erkennen UTF-8/UTF-16 sowie BOM-Markierungen automatisch; weitere Encodings sind explizit konfigurierbar.
- Exportformate erhalten Encoding-Metadaten, sofern das Zielformat diese transportieren kann; fuer CSV erfolgt dies optional ueber Sidecar-Dateien.
- Temporale Werte folgen stabilen ISO-8601-Profilen fuer Serialisierung; `OffsetDateTime` bleibt offsethaltig, `LocalDateTime` bleibt lokal ohne stille Umdeutung zu UTC oder JVM-Zone. Die Default-Zeitzone wird in der Reihenfolge `i18n.default_timezone` -> `ZoneId.systemDefault()` -> `UTC` (Error-/Leer-Fallback) aufgeloest und greift nur in **expliziten** Konvertierungen ueber `TemporalFormatPolicy.toZoned(...)`.
- Locale-sensible Werte wie Zahlen- und Waehrungsdarstellungen werden an Ein-/Ausgabegrenzen normalisiert, damit interne Verarbeitung formatunabhaengig bleibt.
- Unicode-Normalisierung dient als Utility fuer Vergleiche, Metadaten und Darstellungsstabilitaet; Nutzdatenpayloads werden dadurch nicht still umgeschrieben.
- BOM-Erkennung und CSV-BOM-Verhalten nutzen den bestehenden Encoding-Unterbau und sind Teil des Formatvertrags.
- Strukturierte JSON-/YAML-Ausgaben bleiben sprachstabil: Feldnamen, Codes und freie Fehlermeldungstexte bleiben englisch, lokalisiert werden nur menschenlesbare Plain-Text-Ausgaben.
- Optionale Validierungsbausteine fuer E.164-Telefonnummern bleiben ein Erweiterungspfad und gehoeren nicht zum Mindestvertrag.

### 4.6 Versionierung und Kompatibilität

- **Schema-Format**: rückwärtskompatibel für 2 Major-Versionen.
- **CLI-Argumente**: deprecated Flags bleiben 2 Minor-Versionen erhalten.
- **Export-Formate**: stabile Formate ab 1.0; JSON/YAML versionieren Metadaten im Dokument, CSV optional über Sidecar-Datei.

---

## 5. Build und Distribution

### 5.1 Build-System

**Gradle (Kotlin DSL)** mit Multi-Module-Setup:

Das folgende Build-Skript beschreibt die implementierte Multi-Module-Codebasis:

```kotlin
// build.gradle.kts (Root)
plugins {
    kotlin("jvm") version "2.1.0"
    id("org.graalvm.buildtools.native") version "0.10.4"
}

allprojects {
    group = "dev.dmigrate"
    version = "<project-version>"
}

subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")

    dependencies {
        testImplementation("io.kotest:kotest-runner-junit5:5.9.0")
        testImplementation("io.kotest:kotest-assertions-core:5.9.0")
    }

    tasks.test {
        useJUnitPlatform()
    }
}
```

### 5.2 Abhängigkeiten (extern)

| Bibliothek          | Version | Modul                    | Zweck                                                                                                                                                                                                              |
| ------------------- | ------- | ------------------------ | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| Kotlin Stdlib       | 2.1.x   | Alle                     | Sprach-Grundlagen                                                                                                                                                                                                  |
| Kotlin Coroutines   | 1.9.x   | streaming, cli           | Async/Parallel                                                                                                                                                                                                     |
| Clikt               | 5.x     | cli                      | CLI-Framework                                                                                                                                                                                                      |
| Jackson (YAML/JSON) | 2.18.x  | formats                  | Serialisierung                                                                                                                                                                                                     |
| PostgreSQL JDBC     | 42.x    | driver-postgres          | DB-Zugriff                                                                                                                                                                                                         |
| MySQL Connector/J   | 9.x     | driver-mysql             | DB-Zugriff                                                                                                                                                                                                         |
| SQLite JDBC         | 3.47.x  | driver-sqlite            | DB-Zugriff                                                                                                                                                                                                         |
| HikariCP            | 6.x     | drivers                  | Connection Pooling                                                                                                                                                                                                 |
| ICU4J               | 76.x    | adapters/driven/text-icu | Unicode-Verarbeitung (versteckt hinter `dev.dmigrate.text.UnicodeTextService` in `hexagon:ports-common`; Composition Root verdrahtet `IcuUnicodeTextService` in `adapters/driving/cli` und `adapters/driving/mcp`) |
| Ktor Client         | 3.x     | ai                       | HTTP für KI-APIs                                                                                                                                                                                                   |
| SLF4J + Logback     | 2.x/1.5 | Alle                     | Logging                                                                                                                                                                                                            |
| Kotest              | 5.9.x   | Test                     | Test-Framework                                                                                                                                                                                                     |
| Jqwik               | 1.9.x   | Test                     | Property-Based Testing                                                                                                                                                                                             |
| Testcontainers      | 1.20.x  | Test                     | Docker-basierte DB-Tests                                                                                                                                                                                           |

### 5.3 Distribution

```
Distribution-Formate:

1. GitHub Release Assets
   → ZIP/TAR mit launcherbasiertem `bin/d-migrate`
   → Fat JAR für `java -jar d-migrate-<version>-all.jar`
   → Kanonischer Build: `:adapters:driving:cli:assembleReleaseAssets`
   → Distribution: GitHub Releases

2. OCI Image (ghcr.io/pt9912/d-migrate) ✅
   → docker run --rm -v $(pwd):/work ghcr.io/pt9912/d-migrate:latest schema validate --source /work/schema.yaml
   → Basis: eclipse-temurin:21-jre-noble (Ubuntu 24.04, glibc, ZGC)
   → Build: ./gradlew :adapters:driving:cli:jibDockerBuild (Jib, kein Dockerfile nötig)
   → Für CI/CD-Pipelines und Nutzer ohne JDK

3. Homebrew-Basis
   → Formula im Repository unter `packaging/homebrew/d-migrate.rb`
   → Konsumiert das publizierte GitHub-Release-ZIP
   → Verifikation nach Publish via `brew install --formula`

4. Zukunftspfade
   → GraalVM Native Image
   → SDKMAN
   → Scoop
```

---

## 6. CI/CD-Pipeline

```
┌──────────┐    ┌──────────┐    ┌───────────┐    ┌──────────┐
│  Commit  │───▶│  Build   │───▶│   Test    │───▶│ Publish  │
└──────────┘    │          │    │           │    │          │
                │ Compile  │    │ Unit      │    │ Maven    │
                │ Lint     │    │ Integr.   │    │ GitHub   │
                │ Format   │    │ Property  │    │ Docker   │
                └──────────┘    │ Coverage  │    │ Native   │
                                └───────────┘    └──────────┘

Tools:
- Build: Gradle
- CI: GitHub Actions
- Lint: ktlint
- Coverage: Kover (Kotlin Coverage)
- Security: Dependabot, Snyk
- Release: Gradle + GitHub Actions
```

### 6.1 Quality Gates

| Gate                       | Schwellwert     |
| -------------------------- | --------------- |
| Unit-Test-Pass             | 100%            |
| Code-Coverage (Line)       | ≥ 80%           |
| Coverage Type-Mapping      | 100%            |
| ktlint                     | 0 Violations    |
| Dependency Vulnerabilities | 0 Critical/High |
| Build-Dauer                | < 5 Minuten     |

### 6.2 Test-Fixture-Layout

```
adapters/driven/formats/src/test/resources/fixtures/
├── schemas/                          # Neutrale Schema-Definitionen
│   ├── minimal.yaml                  # 1 Tabelle, 2 Spalten (Smoke-Test)
│   ├── e-commerce.yaml               # Referenz-Schema (Lastenheft Anhang B)
│   ├── all-types.yaml                # Alle 18 neutralen Typen
│   └── edge-cases.yaml               # Unicode, Sonderzeichen, zirkuläre FKs
│
├── ddl/                              # Erwartete DDL-Ausgaben (Golden Masters)
│   ├── minimal.postgresql.sql
│   ├── minimal.mysql.sql
│   ├── minimal.sqlite.sql
│   ├── e-commerce.postgresql.sql
│   ├── e-commerce.mysql.sql
│   └── e-commerce.sqlite.sql
│
├── invalid/                          # Ungültige Schemas (Validierungstests)
│   ├── missing-primary-key.yaml      # → E008
│   ├── broken-reference.yaml         # → E002
│   ├── duplicate-column.yaml         # → E004
│   ├── invalid-default.yaml          # → E009
│   └── missing-decimal-precision.yaml # → E010
│
└── data/                             # Testdaten für Import/Export
    ├── customers.json
    ├── customers.csv
    └── customers.yaml
```

**Namenskonvention**: `<schema-name>.<dialekt>.<format>`

**Golden-Master-Workflow**:
1. Schema-Fixture laden (`schemas/minimal.yaml`)
2. DDL generieren (z.B. für PostgreSQL)
3. Ergebnis gegen Golden Master vergleichen (`ddl/minimal.postgresql.sql`)
4. Bei gewollten Änderungen: Golden Master aktualisieren und committen

---

## 7. Architektur-Grundsatzentscheidungen

| #   | Entscheidung                       | Status        | Begründung                                             |
| --- | ---------------------------------- | ------------- | ------------------------------------------------------ |
| 1   | Kotlin als Implementierungssprache | Akzeptiert    | JDBC-Ökosystem, Null-Safety, Coroutines                |
| 2   | Hexagonale Architektur             | Akzeptiert    | Testbarkeit, Austauschbarkeit der Adapter              |
| 3   | Gradle Multi-Module                | Akzeptiert    | Modulare Builds, unabhängige Versionierung             |
| 4   | JDBC statt R2DBC                   | Akzeptiert    | Breitere Treiberunterstützung, Streaming via ResultSet |
| 5   | Kotlin Coroutines statt Threads    | Akzeptiert    | Strukturierte Concurrency, leichtgewichtig             |
| 6   | Jackson für Serialisierung         | Akzeptiert    | YAML + JSON Support, bewährt, performant               |
| 7   | SPI für Driver-Plugin-System       | Akzeptiert    | Standard-Java-Mechanismus, kein Framework nötig        |
| 8   | GraalVM Native Image               | Vorgeschlagen | Single-Binary, schneller Start, höherer Build-Aufwand  |
| 9   | Testcontainers für DB-Tests        | Akzeptiert    | Echte Datenbanken im Test, reproduzierbar              |
| 10  | YAML als Schema-Definitionsformat  | Akzeptiert    | Menschenlesbar, Git-freundlich, kommentierbar          |

---

## 8. Erweiterbarkeit

### 8.1 Neuen Datenbank-Treiber hinzufügen

```
1. Neues Modul erstellen: d-migrate-driver-oracle/
2. Interface DatabaseDriver implementieren
3. TypeMapper für Oracle-spezifische Typen implementieren
4. ServiceLoader-Registrierung in META-INF/services/
5. Tests mit Testcontainers + Oracle XE Image
→ Kein Core-Code muss geändert werden
```

### 8.2 Neuen KI-Provider hinzufügen

```
1. `AiProviderPort` implementieren (`hexagon/application/ai/`)
2. HTTP-Client oder lokales Adapter-Protokoll für Provider-API ergänzen
3. Konfiguration in AiBackendConfig ergänzen
4. ServiceLoader-Registrierung für JVM/Docker oder Native-Build-Profil erweitern
→ Kein Core-Code muss geändert werden
```

### 8.3 Neues Export-Format hinzufügen

```
1. Interface FormatCodec implementieren
2. Serializer/Deserializer für das Format
3. CLI-Command ergänzen (1 Zeile: neuer --format Wert)
→ Core bleibt unverändert
```

### 8.4 Neuen Tool-Exporter hinzufügen

```
1. ToolMigrationExporter in adapters:driven:integrations implementieren
2. render(bundle) gibt ToolExportResult mit relativen Artefakten zurück
3. Exporter in ExportCommands.kt unter exporterLookup registrieren
4. Neues ExportXyzCommand als Clikt-Subcommand unter ExportCommand
5. Tests: Renderer-Unit-Tests + Runtime-Integrationstest
→ Hexagon-Code bleibt unverändert
```

---

## 9. Deployment-Diagramm

```
Entwickler-Maschine                    CI/CD-Pipeline
┌────────────────────┐                ┌─────────────────────┐
│                    │                │  GitHub Actions      │
│  d-migrate (CLI)   │                │                     │
│  ┌──────────────┐  │                │  ┌───────────────┐  │
│  │ Launcher oder│  │                │  │ Testcontainers│  │
│  │ Fat JAR      │  │                │  │ ┌───────────┐ │  │
│  └──────┬───────┘  │                │  │ │ PostgreSQL│ │  │
│         │          │                │  │ │ MySQL     │ │  │
│         ▼          │                │  │ │ SQLite    │ │  │
│  ┌──────────────┐  │                │  │ └───────────┘ │  │
│  │ Lokale       │  │                │  └───────────────┘  │
│  │ Datenbank    │  │                └─────────────────────┘
│  └──────────────┘  │
│         │          │                Distribution
│         ▼          │                ┌─────────────────────┐
│  ┌──────────────┐  │                │ GitHub Releases     │
│  │ Ollama       │  │                │ GHCR (OCI)          │
│  │ (optional)   │  │                │ Homebrew-Basis      │
│  └──────────────┘  │                │ spätere Kanäle      │
└────────────────────┘                └─────────────────────┘
```

---

---

## Verwandte Dokumentation

- [Lastenheft](./lastenheft-d-migrate.md) — Vollständige Anforderungsspezifikation- [Neutrales-Modell-Spezifikation](./neutral-model-spec.md) — YAML-Format, Typsystem, DDL-Parser, Validierung
- [CLI-Spezifikation](./cli-spec.md) — Exit-Codes, Ausgabeformate, Kommando-Referenz
- [DDL-Generierungsregeln](./ddl-generation-rules.md) — Quoting, Statement-Ordering, Dialekt-Besonderheiten
- [Connection- und Konfigurationsspezifikation](./connection-config-spec.md) — URL-Format, `.d-migrate.yaml`-Schema

---

## External Read-Only Integration Surface

External consumers that only need schema reading and data reading
(e.g. a future `source-d-migrate` adapter for `d-browser`) should
depend on:

**Stable integration types:**
- `hexagon:core` — `SchemaDefinition`, neutral model types, `TableDependencySort`
- `hexagon:ports` — `SchemaReader`, `SchemaReadOptions`, `SchemaReadResult`,
  `DataReader`, `TableLister`, `DatabaseDialect`, `ConnectionPool`,
  `FormatReadOptions`, `DataChunkReaderFactory`
- `adapters:driven:driver-common` — `JdbcMetadataSession`, connection pooling
- `adapters:driven:formats` — format readers (JSON/YAML/CSV)

**Explicitly NOT part of the read surface:**
- `ImportOptions`, `DataWriter`, `TableImportSession` (write-oriented)
- `StreamingImporter`, `StreamingExporter` (orchestration)
- `DataImportRunner`, `DataExportRunner` (CLI)
- `hexagon:profiling` and `driver-*-profiling` modules (optional)
- `DatabaseDriver` / `DatabaseDriverRegistry` (mixed facade)

**Verification:** `test:consumer-read-probe` builds against the read
surface and compiles without write/CLI/profiling imports.

---

**Version**: 1.8
**Stand**: 2026-04-20
**Status**: Architektur-Zielbild mit implementierten Kernpfaden und markierten Erweiterungsbereichen
