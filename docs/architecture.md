# Architektur-Dokument: d-migrate

**CLI-Tool für datenbankunabhängige Migrationen und Datenverwaltung**

> Dokumenttyp: Architektur-Spezifikation
>
> Die Module `d-migrate-core`, `d-migrate-formats` und `d-migrate-cli` sind seit Milestone 0.1.0 implementiert. Weitere Module (drivers, integrations, ai, streaming, testdata, docs) beschreiben den geplanten Soll-Zustand für spätere Milestones.

---

## 1. Architektur-Übersicht

### 1.1 Systemkontext

```
                    ┌─────────────────────────────────────────────┐
                    │                  d-migrate                   │
  Benutzer ───CLI──▶│                                             │
                    │  ┌─────────┐ ┌──────────┐ ┌─────────────┐  │
  CI/CD ──Script───▶│  │ Schema  │ │  Daten   │ │ Integration │  │
                    │  │ Mgmt    │ │  Mgmt    │ │ Adapter     │  │
                    │  └────┬────┘ └────┬─────┘ └──────┬──────┘  │
                    │       │           │              │          │
                    │  ┌────▼───────────▼──────────────▼──────┐  │
                    │  │              Core Engine              │  │
                    │  │  (Neutrales Modell, Validierung,      │  │
                    │  │   Type-Mapping, Streaming-Pipeline)   │  │
                    │  └────┬───────────┬──────────────┬───────┘  │
                    │       │           │              │          │
                    │  ┌────▼────┐ ┌────▼────┐  ┌─────▼──────┐  │
                    │  │ DB      │ │ Format  │  │ KI         │  │
                    │  │ Driver  │ │ Codec   │  │ Provider   │  │
                    │  │ (JDBC)  │ │         │  │            │  │
                    │  └────┬────┘ └────┬────┘  └─────┬──────┘  │
                    └───────┼───────────┼─────────────┼──────────┘
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

| Schicht | Modul | Rolle |
|---------|-------|-------|
| Domain Core | `hexagon:core` | Neutrales Modell, Validierung, Typsystem — keine externen Deps |
| Ports | `hexagon:ports` | Port-Interfaces (`DatabaseDriver`, `DdlGenerator`, `DataReader`, `SchemaCodec`, `DataChunkWriter/Reader`, …) + zugehörige Datentypen |
| Application | `hexagon:application` | Use-Case-Runner (`SchemaGenerateRunner`, `DataExportRunner`) |
| Driving Adapter | `adapters:driving:cli` | CLI-Einstiegspunkt (Clikt), Wiring aller Module |
| Driven Adapter | `adapters:driven:driver-common` | Gemeinsame DB-Infrastruktur (`AbstractDdlGenerator`, `HikariConnectionPoolFactory`, …) |
| Driven Adapter | `adapters:driven:driver-postgresql` | PostgreSQL-Implementierung der `DatabaseDriver`-Fassade |
| Driven Adapter | `adapters:driven:driver-mysql` | MySQL-Implementierung der `DatabaseDriver`-Fassade |
| Driven Adapter | `adapters:driven:driver-sqlite` | SQLite-Implementierung der `DatabaseDriver`-Fassade |
| Driven Adapter | `adapters:driven:formats` | Serialisierung/Deserialisierung (JSON, YAML, CSV) |
| Driven Adapter | `adapters:driven:streaming` | Streaming-Pipeline (`StreamingExporter`) |

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
         │  │  hexagon:ports           │  │  ← Port-Interfaces
         │  └────────────┬─────────────┘  │
         │               │                │
         │  ┌────────────▼─────────────┐  │
         │  │  hexagon:core            │  │  ← Domain-Modell
         │  └──────────────────────────┘  │
         └────────────────┬───────────────┘
                          │
            ┌─────────────┼──────────────┐
            ▼             ▼              ▼
      driver-common   formats      streaming
      driver-pg/my/sl

**Erzwungene Regeln** (durch Gradle-Abhängigkeiten garantiert):
- `hexagon:core` hat keine Abhängigkeiten auf andere Module
- `hexagon:ports` hängt nur von `hexagon:core` ab
- `hexagon:application` hängt nur vom Hexagon-Inneren ab, nicht von Adaptern
- Driven Adapters dürfen in main nicht voneinander abhängen (Ausnahme: Driver-Module → `driver-common`)

---

## 2. Modul-Struktur

### 2.1 Zielbild: Projekt-Layout (Gradle Multi-Module)

> **Hinweis**: Die Modulstruktur wurde in 0.4.0 auf eine hexagonale
> Verzeichnishierarchie umgestellt (`hexagon/`, `adapters/`). Die aktuelle
> Struktur ist in §1.2 oben dokumentiert. Das folgende Layout zeigt den
> detaillierten Package-Aufbau pro Modul und wird schrittweise an die
> neue Hierarchie angepasst.

Die folgende Struktur beschreibt den geplanten Soll-Zustand der Codebasis:

```
d-migrate/
├── build.gradle.kts                    # Root Build-Konfiguration
├── settings.gradle.kts
│
├── d-migrate-core/                     # Domain Core (keine externen Deps)
│   └── src/main/kotlin/
│       └── dev/dmigrate/core/
│           ├── model/                  # Neutrales Schema-Modell
│           │   ├── SchemaDefinition.kt
│           │   ├── TableDefinition.kt
│           │   ├── ColumnDefinition.kt
│           │   ├── NeutralType.kt
│           │   └── ...
│           ├── validation/             # Schema-Validierung
│           │   ├── SchemaValidator.kt
│           │   └── ValidationResult.kt
│           ├── types/                  # Neutrales Typsystem
│           │   └── TypeCompatibility.kt
│           ├── diff/                   # Schema-Vergleich
│           │   ├── SchemaDiff.kt
│           │   └── DiffResult.kt
│           └── migration/              # Migrationsplan-Logik
│               ├── MigrationPlan.kt
│               └── DependencyGraph.kt
│
├── d-migrate-drivers/                  # Datenbank-Adapter
│   ├── d-migrate-driver-api/           # Driver SPI
│   │   └── src/main/kotlin/
│   │       └── dev/dmigrate/driver/
│   │           ├── DatabaseDriver.kt           # Port-Interface
│   │           ├── SchemaReader.kt
│   │           ├── SchemaWriter.kt
│   │           ├── DataReader.kt
│   │           ├── DataWriter.kt
│   │           └── TypeMapper.kt              # Dialekt-Port
│   │
│   ├── d-migrate-driver-postgresql/
│   │   └── src/main/kotlin/
│   │       └── dev/dmigrate/driver/postgresql/
│   │           ├── PostgresDriver.kt
│   │           ├── PostgresTypeMapper.kt
│   │           ├── PostgresDdlGenerator.kt
│   │           └── PostgresSchemaReader.kt
│   │
│   ├── d-migrate-driver-mysql/
│   │   └── src/main/kotlin/
│   │       └── dev/dmigrate/driver/mysql/
│   │           ├── MysqlDriver.kt
│   │           ├── MysqlTypeMapper.kt
│   │           ├── MysqlDdlGenerator.kt
│   │           └── MysqlSchemaReader.kt
│   │
│   └── d-migrate-driver-sqlite/
│       └── src/main/kotlin/
│           └── dev/dmigrate/driver/sqlite/
│               ├── SqliteDriver.kt
│               ├── SqliteTypeMapper.kt
│               ├── SqliteDdlGenerator.kt
│               └── SqliteSchemaReader.kt
│
├── d-migrate-formats/                  # Serialisierung / Deserialisierung
│   └── src/main/kotlin/
│       └── dev/dmigrate/format/
│           ├── FormatCodec.kt          # Port-Interface
│           ├── TextEncoding.kt         # UTF-8/UTF-16/ISO-8859-1, BOM
│           ├── ImportParser.kt         # Datei → neutrales Datenmodell
│           ├── json/
│           │   └── JsonCodec.kt
│           ├── yaml/
│           │   └── YamlCodec.kt
│           ├── csv/
│           │   ├── CsvCodec.kt
│           │   └── CsvBomHandler.kt
│           └── sql/
│               ├── SqlCodec.kt
│               └── DdlParser.kt        # SQL-DDL-Dateien → neutrales Modell (LF-004)
│                                        # Details: neutral-model-spec.md §12
│
├── d-migrate-integrations/             # Tool-Integrationen
│   └── src/main/kotlin/
│       └── dev/dmigrate/integration/
│           ├── IntegrationAdapter.kt   # Port-Interface
│           ├── flyway/
│           │   └── FlywayAdapter.kt
│           ├── liquibase/
│           │   └── LiquibaseAdapter.kt
│           ├── django/
│           │   └── DjangoAdapter.kt
│           └── knex/
│               └── KnexAdapter.kt
│
├── d-migrate-ai/                       # KI-Integration
│   └── src/main/kotlin/
│       └── dev/dmigrate/ai/
│           ├── AiProvider.kt           # Port-Interface
│           ├── TransformService.kt     # Orchestrierung
│           ├── ollama/
│           │   └── OllamaProvider.kt
│           ├── lmstudio/
│           │   └── LmStudioProvider.kt
│           ├── openai/
│           │   └── OpenAiProvider.kt
│           ├── anthropic/
│           │   └── AnthropicProvider.kt
│           ├── xai/
│           │   └── XaiProvider.kt
│           ├── google/
│           │   └── GeminiProvider.kt
│           ├── vllm/
│           │   └── VllmProvider.kt
│           ├── tgi/
│           │   └── TgiProvider.kt
│           └── noop/
│               └── RuleBasedProvider.kt
│
├── d-migrate-testdata/                 # Testdaten-Generierung (LF-024)
│   └── src/main/kotlin/
│       └── dev/dmigrate/testdata/
│           ├── TestdataGenerator.kt    # Port-Interface
│           ├── GeneratorConfig.kt      # Seed, Menge, Locale, Regeln
│           ├── faker/
│           │   └── FakerGenerator.kt   # Regelbasiert (Faker-Bibliothek)
│           └── ai/
│               └── AiGenerator.kt      # KI-gestützt (optional, nutzt AiProvider)
│
├── d-migrate-streaming/                # Streaming-Pipeline
│   └── src/main/kotlin/
│       └── dev/dmigrate/streaming/
│           ├── Pipeline.kt
│           ├── ChunkProcessor.kt
│           ├── Checkpoint.kt
│           ├── ParallelExecutor.kt
│           ├── PartitionHandler.kt     # Partition-aware Export/Import (LN-008)
│           └── DeltaDetector.kt        # Inkrementelle Delta-Erkennung (LN-006)
│
├── d-migrate-i18n/                     # Internationalisierung
│   └── src/main/
│       ├── kotlin/
│       │   └── dev/dmigrate/i18n/
│       │       ├── Messages.kt
│       │       ├── UnicodeUtils.kt
│       │       ├── LocaleFormats.kt    # Datum/Zeit/Zahl/Waehrung
│       │       ├── TimezonePolicy.kt   # UTC-Default, Konvertierung
│       │       └── PhoneNumberValidator.kt # Optionale E.164-Validierung
│       └── resources/
│           └── messages/
│               ├── messages_de.properties
│               └── messages_en.properties
│
├── d-migrate-docs/                     # Dokumentationsgenerierung
│   └── src/main/kotlin/
│       └── dev/dmigrate/docs/
│           ├── DocumentationGenerator.kt
│           ├── MarkdownRenderer.kt
│           ├── HtmlRenderer.kt
│           ├── PdfRenderer.kt
│           └── ErDiagramGenerator.kt
│
└── d-migrate-cli/                      # CLI-Anwendung (Einstiegspunkt)
    └── src/main/kotlin/
        └── dev/dmigrate/cli/
            ├── Main.kt
            ├── commands/
            │   ├── SchemaCommands.kt
            │   ├── DataCommands.kt
            │   ├── TransformCommands.kt
            │   ├── ExportCommands.kt
            │   └── ValidateCommands.kt
            └── output/
                ├── ProgressRenderer.kt
                └── TableRenderer.kt
```

### 2.2 Modul-Abhängigkeiten

```
d-migrate-cli
├── d-migrate-core
├── d-migrate-drivers
│   ├── d-migrate-driver-api ──▶ d-migrate-core
│   ├── d-migrate-driver-postgresql ──▶ d-migrate-driver-api, d-migrate-core
│   ├── d-migrate-driver-mysql ──▶ d-migrate-driver-api, d-migrate-core
│   └── d-migrate-driver-sqlite ──▶ d-migrate-driver-api, d-migrate-core
├── d-migrate-formats ──▶ d-migrate-core
├── d-migrate-integrations ──▶ d-migrate-core
├── d-migrate-ai ──▶ d-migrate-core
├── d-migrate-testdata ──▶ d-migrate-core, d-migrate-ai (optional)
├── d-migrate-streaming ──▶ d-migrate-core, d-migrate-driver-api
├── d-migrate-i18n
└── d-migrate-docs ──▶ d-migrate-core, d-migrate-i18n
```

**Regel**: `d-migrate-core` hat KEINE Abhängigkeit auf andere Module. Port-Module wie `d-migrate-driver-api` dürfen `core` referenzieren, wenn ihre Signaturen das neutrale Modell verwenden. Konkrete Adapter hängen an Ports und bei Bedarf direkt an `core`, aber nie umgekehrt.

---

## 3. Kernkomponenten

### 3.1 Database Driver (Port & Adapter)

```kotlin
/**
 * Port: Abstraktion für Datenbankzugriff.
 * Jeder Treiber implementiert dieses Interface.
 */
interface DatabaseDriver {
    val dialect: DatabaseDialect

    fun connect(config: ConnectionConfig): DatabaseConnection
    fun schemaReader(): SchemaReader
    fun schemaWriter(): SchemaWriter
    fun dataReader(): DataReader
    fun dataWriter(): DataWriter
}

interface SchemaReader {
    /** Reverse-Engineering: DB → Neutrales Modell */
    fun readSchema(connection: DatabaseConnection): SchemaDefinition
    fun readProcedures(connection: DatabaseConnection): List<ProcedureDefinition>
    fun readFunctions(connection: DatabaseConnection): List<FunctionDefinition>
    fun readViews(connection: DatabaseConnection): List<ViewDefinition>
    fun readTriggers(connection: DatabaseConnection): List<TriggerDefinition>
}

interface SchemaWriter {
    /** DDL-Generierung: Neutrales Modell → DB-spezifisches SQL */
    fun generateDdl(schema: SchemaDefinition): List<DdlStatement>
    fun generateMigration(diff: DiffResult): List<DdlStatement>
    /** Rollback-Generierung: Inverse Operationen für eine Migration (LF-014) */
    fun generateRollback(diff: DiffResult): List<DdlStatement>
}

interface DataReader {
    /** Streaming-basierter Datenexport */
    fun streamTable(
        connection: DatabaseConnection,
        table: String,
        filter: DataFilter? = null,
        chunkSize: Int = 10_000
    ): Flow<DataChunk>
}

interface DataWriter {
    /** Transaktionaler Datenimport */
    suspend fun importChunk(
        connection: DatabaseConnection,
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
    val timezone: ZoneId = ZoneOffset.UTC
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
 * Bidirektionales Type-Mapping zwischen neutralem Typ und DB-spezifischem Typ.
 * Pure Function — kein State, vollständig testbar.
 */
interface TypeMapper {
    fun toNeutral(dbType: String, metadata: ColumnMetadata): NeutralType
    fun fromNeutral(neutralType: NeutralType): String
}

// Beispiel: PostgreSQL Type Mapper
class PostgresTypeMapper : TypeMapper {
    override fun toNeutral(dbType: String, metadata: ColumnMetadata): NeutralType =
        when (dbType.uppercase()) {
            "SERIAL"         -> NeutralType.Identifier(autoIncrement = true)
            "BIGSERIAL"      -> NeutralType.BigIdentifier(autoIncrement = true)
            "INTEGER", "INT" -> NeutralType.Integer
            "BIGINT"         -> NeutralType.BigInteger
            "TEXT"           -> NeutralType.Text()
            "VARCHAR"        -> NeutralType.Text(maxLength = metadata.length)
            "CHAR"           -> NeutralType.Char(length = metadata.length ?: 1)
            "SMALLINT"       -> NeutralType.SmallInt
            "REAL"           -> NeutralType.Float(precision = FloatPrecision.SINGLE)
            "DOUBLE PRECISION" -> NeutralType.Float(precision = FloatPrecision.DOUBLE)
            "XML"            -> NeutralType.Xml
            "BOOLEAN"        -> NeutralType.Boolean
            "JSONB", "JSON"  -> NeutralType.Json
            "BYTEA"          -> NeutralType.Binary
            "UUID"           -> NeutralType.Uuid
            "TIMESTAMP"      -> NeutralType.DateTime(withTimezone = false)
            "TIMESTAMPTZ"    -> NeutralType.DateTime(withTimezone = true)
            else             -> NeutralType.Text() // Fallback mit Warnung
        }

    override fun fromNeutral(neutralType: NeutralType): String =
        when (neutralType) {
            is NeutralType.Identifier    -> "SERIAL"
            is NeutralType.BigIdentifier -> "BIGSERIAL"
            is NeutralType.Integer       -> "INTEGER"
            is NeutralType.BigInteger    -> "BIGINT"
            is NeutralType.Text         -> if (neutralType.maxLength != null)
                                              "VARCHAR(${neutralType.maxLength})"
                                           else "TEXT"
            is NeutralType.Char          -> "CHAR(${neutralType.length})"
            is NeutralType.SmallInt      -> "SMALLINT"
            is NeutralType.Float         -> when (neutralType.precision) {
                                              FloatPrecision.SINGLE -> "REAL"
                                              FloatPrecision.DOUBLE -> "DOUBLE PRECISION"
                                           }
            is NeutralType.Xml           -> "XML"
            is NeutralType.Boolean       -> "BOOLEAN"
            is NeutralType.Json          -> "JSONB"
            is NeutralType.Binary        -> "BYTEA"
            is NeutralType.Uuid          -> "UUID"
            is NeutralType.DateTime      -> if (neutralType.withTimezone)
                                              "TIMESTAMP WITH TIME ZONE"
                                           else "TIMESTAMP"
            is NeutralType.Decimal       -> "DECIMAL(${neutralType.precision},${neutralType.scale})"
            is NeutralType.Enum          -> "TEXT" // PostgreSQL ENUM via CREATE TYPE
            // ...
        }
}
```

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
    val defaultTimezone: String = "UTC",
    val normalizeUnicode: UnicodeNormalization = UnicodeNormalization.NFC
)

data class DocumentationConfig(
    val enabledFormats: Set<String> = setOf("markdown"),
    val includeErDiagrams: Boolean = true,
    val includeLocalizedLabels: Boolean = true
)
```

### 4.2 Logging und Observability

```
┌─────────────────────────────────────────────┐
│                  Logging                     │
├─────────────────────────────────────────────┤
│ Level    │ Inhalt                            │
│──────────┼───────────────────────────────────│
│ ERROR    │ Fehlgeschlagene Operationen       │
│ WARN     │ Fallback-Typen, Deprecations      │
│ INFO     │ Start/Ende von Operationen        │
│ DEBUG    │ SQL-Statements, Chunk-Verarbeitung│
│ TRACE    │ Einzelne Datensätze (nur Dev)     │
├─────────────────────────────────────────────┤
│ Trennung: Technische Logs (EN) vs.          │
│           User-Meldungen (lokalisiert)       │
└─────────────────────────────────────────────┘
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
- Temporale Werte werden intern zeitzonenbewusst verarbeitet; Standard fuer Serialisierung und Export ist UTC.
- Locale-sensible Werte wie Zahlen- und Waehrungsdarstellungen werden an Ein-/Ausgabegrenzen normalisiert, damit interne Verarbeitung formatunabhaengig bleibt.
- Optionale Validierungsbausteine fuer E.164-Telefonnummern werden als Querschnittskomponente im `d-migrate-i18n`-Modul bereitgestellt.

---

## 5. Build und Distribution

### 5.1 Build-System

**Gradle (Kotlin DSL)** mit Multi-Module-Setup:

Das folgende Build-Skript ist als Zielbild fuer die geplante Multi-Module-Codebasis zu verstehen:

```kotlin
// build.gradle.kts (Root)
plugins {
    kotlin("jvm") version "2.1.0"
    id("org.graalvm.buildtools.native") version "0.10.4"
}

allprojects {
    group = "dev.dmigrate"
    version = "0.1.0"
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

| Bibliothek          | Version | Modul           | Zweck                    |
| ------------------- | ------- | --------------- | ------------------------ |
| Kotlin Stdlib       | 2.1.x   | Alle            | Sprach-Grundlagen        |
| Kotlin Coroutines   | 1.9.x   | streaming, cli  | Async/Parallel           |
| Clikt               | 5.x     | cli             | CLI-Framework            |
| Jackson (YAML/JSON) | 2.18.x  | formats         | Serialisierung           |
| PostgreSQL JDBC     | 42.x    | driver-postgres | DB-Zugriff               |
| MySQL Connector/J   | 9.x     | driver-mysql    | DB-Zugriff               |
| SQLite JDBC         | 3.47.x  | driver-sqlite   | DB-Zugriff               |
| HikariCP            | 6.x     | drivers         | Connection Pooling       |
| ICU4J               | 76.x    | i18n            | Unicode-Verarbeitung     |
| Ktor Client         | 3.x     | ai              | HTTP für KI-APIs         |
| SLF4J + Logback     | 2.x/1.5 | Alle            | Logging                  |
| Kotest              | 5.9.x   | Test            | Test-Framework           |
| Jqwik               | 1.9.x   | Test            | Property-Based Testing   |
| Testcontainers      | 1.20.x  | Test            | Docker-basierte DB-Tests |

### 5.3 Distribution

```
Distribution-Formate:

1. JVM/Fat JAR (Standard für Entwicklung und dynamische Erweiterbarkeit)
   → java -jar d-migrate.jar
   → Unterstützt ServiceLoader-basierte Erweiterungen zur Laufzeit
   → Distribution: Maven Central, SDKMAN

2. GraalVM Native Image (optimiertes Deployment für vordefinierte Bundles)
   → Single Binary für Linux/macOS/Windows
   → ~50 MB, Start in <100ms
   → Enthält nur die beim Build eingebundenen Treiber/Provider
   → Distribution: GitHub Releases, Homebrew

3. OCI Image (ghcr.io/pt9912/d-migrate) ✅
   → docker run --rm -v $(pwd):/work ghcr.io/pt9912/d-migrate:latest schema validate --source /work/schema.yaml
   → Basis: eclipse-temurin:21-jre-noble (Ubuntu 24.04, glibc, ZGC)
   → Build: ./gradlew :d-migrate-cli:jibDockerBuild (Jib, kein Dockerfile nötig)
   → Für CI/CD-Pipelines und Nutzer ohne JDK

4. Package Manager
   → brew install d-migrate        (macOS/Linux)
   → sdk install dmigrate          (SDKMAN)
   → scoop install d-migrate       (Windows)
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
src/test/resources/fixtures/
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
└── data/                             # Testdaten für Import/Export (ab 0.3.0)
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

## 7. Architekturentscheidungen (ADR-Übersicht)

| #       | Entscheidung                       | Status        | Begründung                                             |
| ------- | ---------------------------------- | ------------- | ------------------------------------------------------ |
| ADR-001 | Kotlin als Implementierungssprache | Akzeptiert    | JDBC-Ökosystem, Null-Safety, Coroutines                |
| ADR-002 | Hexagonale Architektur             | Akzeptiert    | Testbarkeit, Austauschbarkeit der Adapter              |
| ADR-003 | Gradle Multi-Module                | Akzeptiert    | Modulare Builds, unabhängige Versionierung             |
| ADR-004 | JDBC statt R2DBC                   | Akzeptiert    | Breitere Treiberunterstützung, Streaming via ResultSet |
| ADR-005 | Kotlin Coroutines statt Threads    | Akzeptiert    | Strukturierte Concurrency, leichtgewichtig             |
| ADR-006 | Jackson für Serialisierung         | Akzeptiert    | YAML + JSON Support, bewährt, performant               |
| ADR-007 | SPI für Driver-Plugin-System       | Akzeptiert    | Standard-Java-Mechanismus, kein Framework nötig        |
| ADR-008 | GraalVM Native Image               | Vorgeschlagen | Single-Binary, schneller Start, höherer Build-Aufwand  |
| ADR-009 | Testcontainers für DB-Tests        | Akzeptiert    | Echte Datenbanken im Test, reproduzierbar              |
| ADR-010 | YAML als Schema-Definitionsformat  | Akzeptiert    | Menschenlesbar, Git-freundlich, kommentierbar          |

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
1. Interface AiProvider implementieren
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

---

## 9. Deployment-Diagramm

```
Entwickler-Maschine                    CI/CD-Pipeline
┌────────────────────┐                ┌─────────────────────┐
│                    │                │  GitHub Actions      │
│  d-migrate (CLI)   │                │                     │
│  ┌──────────────┐  │                │  ┌───────────────┐  │
│  │ Native Binary│  │                │  │ Testcontainers│  │
│  │ oder JAR     │  │                │  │ ┌───────────┐ │  │
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
│  │ Ollama       │  │                │ Maven Central       │
│  │ (optional)   │  │                │ Docker Hub          │
│  └──────────────┘  │                │ Homebrew / SDKMAN   │
└────────────────────┘                └─────────────────────┘
```

---

---

## Verwandte Dokumentation

- [Lastenheft](./lastenheft-d-migrate.md) — Vollständige Anforderungsspezifikation
- [Design](./design.md) — Design-Philosophie, Datenflüsse, CLI, Fehlerbehandlung
- [Neutrales-Modell-Spezifikation](./neutral-model-spec.md) — YAML-Format, Typsystem, DDL-Parser, Validierung
- [CLI-Spezifikation](./cli-spec.md) — Exit-Codes, Ausgabeformate, Kommando-Referenz
- [DDL-Generierungsregeln](./ddl-generation-rules.md) — Quoting, Statement-Ordering, Dialekt-Besonderheiten
- [Connection- und Konfigurationsspezifikation](./connection-config-spec.md) — URL-Format, `.d-migrate.yaml`-Schema
- [Roadmap](./roadmap.md) — Phasen, Milestones und Release-Planung
- [Beispiel: Stored Procedure Migration](./beispiel-stored-procedure-migration.md) — KI-gestützte Transformation PostgreSQL → MySQL

---

**Version**: 1.5
**Stand**: 2026-04-05
**Status**: Milestone 0.1.0 + 0.2.0 implementiert (core, formats, cli, driver-api, driver-postgresql, driver-mysql, driver-sqlite), weitere Module im Entwurf
