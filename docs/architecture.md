# Architektur-Dokument: d-migrate

**CLI-Tool für datenbankunabhängige Migrationen und Datenverwaltung**

> Dokumenttyp: Architektur-Spezifikation
>
> Die Module unter `hexagon/` und `adapters/` sind seit Milestone 0.4.0 in der hexagonalen Verzeichnisstruktur implementiert (siehe §1.2 / §2.1). Weitere Module (integrations, ai, testdata, docs) beschreiben den geplanten Soll-Zustand fuer spaetere Milestones; die 0.8.0-I18n-Basis wird bewusst ohne separates Top-Level-`i18n`-Modul eingefuehrt.

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

| Schicht         | Modul                               | Rolle                                                                                                                                |
| --------------- | ----------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------ |
| Domain Core     | `hexagon:core`                      | Neutrales Modell, Validierung, Typsystem — keine externen Deps                                                                       |
| Ports           | `hexagon:ports`                     | Port-Interfaces (`DatabaseDriver`, `DdlGenerator`, `DataReader`, `SchemaCodec`, `DataChunkWriter/Reader`, …) + zugehörige Datentypen |
| Application     | `hexagon:application`               | Use-Case-Runner (`SchemaGenerateRunner`, `DataExportRunner`)                                                                         |
| Driving Adapter | `adapters:driving:cli`              | CLI-Einstiegspunkt (Clikt), Wiring aller Module                                                                                      |
| Driven Adapter  | `adapters:driven:driver-common`     | Gemeinsame DB-Infrastruktur (`AbstractDdlGenerator`, `HikariConnectionPoolFactory`, …)                                               |
| Driven Adapter  | `adapters:driven:driver-postgresql` | PostgreSQL-Implementierung der `DatabaseDriver`-Fassade                                                                              |
| Driven Adapter  | `adapters:driven:driver-mysql`      | MySQL-Implementierung der `DatabaseDriver`-Fassade                                                                                   |
| Driven Adapter  | `adapters:driven:driver-sqlite`     | SQLite-Implementierung der `DatabaseDriver`-Fassade                                                                                  |
| Driven Adapter  | `adapters:driven:formats`           | Serialisierung/Deserialisierung (JSON, YAML, CSV)                                                                                    |
| Driven Adapter  | `adapters:driven:streaming`         | Streaming-Pipeline (`StreamingExporter`)                                                                                             |

> Hinweis: Der geplante Refactor fuer eine schmalere Library- und
> Integrationsschnittstelle ist in
> [`implementation-plan-0.9.1.md`](./implementation-plan-0.9.1.md)
> beschrieben.

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
```
**Erzwungene Regeln** (durch Gradle-Abhängigkeiten garantiert):
- `hexagon:core` hat keine Abhängigkeiten auf andere Module
- `hexagon:ports` hängt nur von `hexagon:core` ab
- `hexagon:application` hängt nur vom Hexagon-Inneren ab, nicht von Adaptern
- Driven Adapters dürfen in main nicht voneinander abhängen (Ausnahme: Driver-Module → `driver-common`)

---

## 2. Modul-Struktur

### 2.1 Projekt-Layout (Gradle Multi-Module)

Die Module `hexagon:core`, `hexagon:ports`, `hexagon:application` sowie die
Adapter-Module unter `adapters/` sind seit Milestone 0.4.0 implementiert.
Weitere Module (integrations, ai, testdata, docs) beschreiben den geplanten
Soll-Zustand fuer spaetere Milestones. Fuer 0.8.0 wird die
Internationalisierungsbasis bewusst ohne eigenes Top-Level-`i18n`-Modul
eingefuehrt.

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
│   ├── ports/                             # Port-Interfaces + Datentypen
│   │   └── dev/dmigrate/
│   │       ├── driver/
│   │       │   ├── DatabaseDriver.kt      # Zentrale Port-Fassade
│   │       │   ├── DatabaseDriverRegistry.kt
│   │       │   ├── DatabaseDialect.kt
│   │       │   ├── DdlGenerator.kt        # + DdlResult, TransformationNote, …
│   │       │   ├── TypeMapper.kt
│   │       │   ├── connection/            # ConnectionPool, ConnectionConfig, JdbcUrlBuilder, PoolSettings
│   │       │   └── data/                  # DataReader, TableLister, ChunkSequence
│   │       ├── format/
│   │       │   ├── SchemaCodec.kt
│   │       │   └── data/                  # DataChunkWriter/Reader, Factories, ExportOptions, ImportOptions, DataExportFormat
│   │       └── streaming/                 # ExportOutput, ExportResult, PipelineConfig
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

> Geplante, noch nicht implementierte Module: `integrations/` (Flyway, Liquibase,
> Django, Knex), `ai/` (Ollama, LM Studio, OpenAI, Anthropic, …), `testdata/`
> (Faker, KI-gestuetzt), `docs/` — siehe Roadmap. Die 0.8.0-I18n-Bausteine
> werden zunaechst in bestehenden Modulen verortet (`hexagon:application`,
> `adapters:driving:cli`, `adapters:driven:formats`).

### 2.2 Modul-Abhängigkeiten

```
adapters:driving:cli
├── hexagon:application ──▶ hexagon:core, hexagon:ports
├── hexagon:ports ──▶ hexagon:core
├── adapters:driven:driver-common ──▶ hexagon:ports, HikariCP, SLF4J
├── adapters:driven:driver-postgresql ──▶ hexagon:ports, driver-common
├── adapters:driven:driver-mysql ──▶ hexagon:ports, driver-common
├── adapters:driven:driver-sqlite ──▶ hexagon:ports, driver-common
├── adapters:driven:formats ──▶ hexagon:ports, Jackson, DSL-JSON, SnakeYAML, Univocity
└── adapters:driven:streaming ──▶ hexagon:ports
```

**Regel**: `hexagon:core` hat KEINE Abhängigkeit auf andere Module. `hexagon:ports` hängt nur von `core` ab. `hexagon:application` hängt nur vom Hexagon-Inneren ab, nie von Adaptern. Driven Adapters dürfen in main nicht voneinander abhängen (Ausnahme: Driver-Module → `driver-common`).

---

## 3. Kernkomponenten

### 3.1 Database Driver (Port & Adapter)

#### Ist-Stand (bis 0.5.x)

Das produktive `DatabaseDriver`-Interface exponiert heute folgende Ports:

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

#### `schemaReader()` (0.6.0)

`DatabaseDriver` wurde in 0.6.0 um `schemaReader()` erweitert — implementiert
für PostgreSQL, MySQL und SQLite:

```kotlin
interface DatabaseDriver {
    // … bestehende Ports …
    fun schemaReader(): SchemaReader        // 0.6.0
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
  `SchemaWriter`-Interface ist für spätere Milestones vorgesehen.

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
    // sie typischerweise aus ResolvedI18nSettings.timezone ab, nicht aus
    // einem blanket-UTC-Default (siehe docs/ImpPlan-0.8.0-E.md).
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

### 3.5 Generator-Options-Pfad und Spatial-Profil (0.5.5)

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

| Aspekt | Verortung | Begruendung |
|---|---|---|
| `type: geometry` | `hexagon:core` — neutrales Modell | Gehoert zur portablen Schema-Definition |
| `geometry_type`, `srid` | `hexagon:core` — `ColumnDefinition` | Portable Schema-Metadaten |
| E120, E121 | `hexagon:core` — `SchemaValidator` | Schema-/Modellregeln, unabhaengig vom Zieldialekt |
| `spatialProfile` | `hexagon:ports` — `GeneratorOptions` | Generator-Konfiguration, dialektabhaengig |
| E052, W120 | Driven Adapter — DDL-Generator | Generator-/Report-Regeln, entstehen erst bei `schema generate` |

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

Die Typsystem-Erweiterung in 0.5.5 (`geometry`, `geometry_type`, `srid`,
`GeneratorOptions`) ist Voraussetzung fuer die Reverse-Engineering- und
Daten-Transfer-Funktionalitaet in 0.6.0: `schema reverse` muss Spatial-Spalten
in das neutrale Modell zurueckfuehren koennen, und `schema compare` muss
Geometry-Spalten korrekt vergleichen. Ohne die in 0.5.5 geschaffene Modell- und
Validierungsbasis waere das nicht typsicher moeglich.

### 3.6 Tool-Export-Pfad (0.7.0)

0.7.0 fuehrt einen dedizierten Exportpfad fuer externe Migrationstools ein.
Der Pfad folgt der hexagonalen Architektur und fuehrt weder neue Pflicht-
Laufzeitabhaengigkeiten ein noch mutiert er bestehende Tool-Projektdateien.

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

Die fokussierte 0.7.0-Matrix (Flyway→PostgreSQL, Liquibase→PostgreSQL,
Django→SQLite, Knex→SQLite) wird als Integrations-Tests in
`adapters:driven:integrations` ausgefuehrt, markiert mit
`NamedTag("integration")` und steuerbar ueber `-PintegrationTests`.

### 3.7 Daten-Profiling (0.7.5)

0.7.5 fuehrt ein dediziertes Profiling-Modul ein, das Spaltenstatistiken,
Qualitaetswarnungen und Zieltyp-Kompatibilitaet fuer bestehende Datenbanken
liefert.

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

Architekturvertrag fuer 0.8.0:

- `defaultLocale` beschreibt den Produktdefault; Root-/Fallback-Bundle ist Englisch (`messages.properties`).
- Die effektive I18n-Konfiguration wird ueber denselben Pfadvertrag wie die bestehende CLI-Konfiguration bestimmt: `--config` > `D_MIGRATE_CONFIG` > `./.d-migrate.yaml`.
- `defaultTimezone` ist optional und wird durch `I18nSettingsResolver` in der Reihenfolge `i18n.default_timezone` -> `ZoneId.systemDefault()` -> `UTC` (Error-/Leer-Fallback) zu einer `ZoneId` aufgeloest; die aufgeloeste Zone greift per Phase-E-Vertrag nur in expliziten Konvertierungen, nicht als Serialisierungs-Offset fuer lokale Werte (siehe `docs/ImpPlan-0.8.0-E.md`).
- Der finale Nutzervertrag fuer `--lang` als CLI-Override wird erst in 0.9.0 abgeschlossen.

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
- Temporale Werte folgen dem Phase-E-Vertrag (`docs/ImpPlan-0.8.0-E.md`): ISO-8601-Profile fuer Serialisierung, `OffsetDateTime` bleibt offsethaltig, `LocalDateTime` bleibt lokal ohne stille Umdeutung zu UTC oder JVM-Zone. Die Default-Zeitzone wird in der Reihenfolge `i18n.default_timezone` -> `ZoneId.systemDefault()` -> `UTC` (Error-/Leer-Fallback) aufgeloest und greift nur in **expliziten** Konvertierungen ueber `TemporalFormatPolicy.toZoned(...)`.
- Locale-sensible Werte wie Zahlen- und Waehrungsdarstellungen werden an Ein-/Ausgabegrenzen normalisiert, damit interne Verarbeitung formatunabhaengig bleibt.
- Unicode-Normalisierung dient fuer 0.8.0 als Utility fuer Vergleiche, Metadaten und Darstellungsstabilitaet; Nutzdatenpayloads werden dadurch nicht still umgeschrieben.
- BOM-Erkennung und CSV-BOM-Verhalten bauen fuer 0.8.0 auf dem seit 0.4.0 vorhandenen Unterbau auf und werden als Vertragskonsolidierung dokumentiert, nicht als neu erfundenes Feature.
- Strukturierte JSON-/YAML-Ausgaben bleiben sprachstabil: Feldnamen, Codes und freie Fehlermeldungstexte bleiben englisch, lokalisiert werden nur menschenlesbare Plain-Text-Ausgaben.
- Optionale Validierungsbausteine fuer E.164-Telefonnummern bleiben ein spaeterer Erweiterungspfad und gehoeren nicht zum 0.8.0-Mindestvertrag.

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
| ICU4J               | 76.x    | application/cli | Unicode-Verarbeitung     |
| Ktor Client         | 3.x     | ai              | HTTP für KI-APIs         |
| SLF4J + Logback     | 2.x/1.5 | Alle            | Logging                  |
| Kotest              | 5.9.x   | Test            | Test-Framework           |
| Jqwik               | 1.9.x   | Test            | Property-Based Testing   |
| Testcontainers      | 1.20.x  | Test            | Docker-basierte DB-Tests |

### 5.3 Distribution

```
Distribution-Formate:

1. GitHub Release Assets (0.5.0-MVP)
   → ZIP/TAR mit launcherbasiertem `bin/d-migrate`
   → Fat JAR für `java -jar d-migrate-<version>-all.jar`
   → Kanonischer Build: `:adapters:driving:cli:assembleReleaseAssets`
   → Distribution: GitHub Releases

2. OCI Image (ghcr.io/pt9912/d-migrate) ✅
   → docker run --rm -v $(pwd):/work ghcr.io/pt9912/d-migrate:latest schema validate --source /work/schema.yaml
   → Basis: eclipse-temurin:21-jre-noble (Ubuntu 24.04, glibc, ZGC)
   → Build: ./gradlew :adapters:driving:cli:jibDockerBuild (Jib, kein Dockerfile nötig)
   → Für CI/CD-Pipelines und Nutzer ohne JDK

3. Homebrew-Basis (0.5.0-MVP)
   → Formula im Repository unter `packaging/homebrew/d-migrate.rb`
   → Konsumiert das publizierte GitHub-Release-ZIP
   → Verifikation nach Publish via `brew install --formula`

4. Zukunftspfade (nicht aktueller 0.5.0-Auslieferungsstand)
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

### 8.4 Neuen Tool-Exporter hinzufügen (0.7.0)

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

- [Lastenheft](./lastenheft-d-migrate.md) — Vollständige Anforderungsspezifikation
- [Design](./design.md) — Design-Philosophie, Datenflüsse, CLI, Fehlerbehandlung
- [Neutrales-Modell-Spezifikation](./neutral-model-spec.md) — YAML-Format, Typsystem, DDL-Parser, Validierung
- [CLI-Spezifikation](./cli-spec.md) — Exit-Codes, Ausgabeformate, Kommando-Referenz
- [DDL-Generierungsregeln](./ddl-generation-rules.md) — Quoting, Statement-Ordering, Dialekt-Besonderheiten
- [Connection- und Konfigurationsspezifikation](./connection-config-spec.md) — URL-Format, `.d-migrate.yaml`-Schema
- [Roadmap](./roadmap.md) — Phasen, Milestones und Release-Planung
- [Beispiel: Stored Procedure Migration](./beispiel-stored-procedure-migration.md) — KI-gestützte Transformation PostgreSQL → MySQL

---

**Version**: 1.7
**Stand**: 2026-04-14
**Status**: Milestone 0.1.0–0.6.0 implementiert (core, ports, application, formats, cli, driver-postgresql/-mysql/-sqlite, streaming); 0.6.0: `SchemaReader` für PostgreSQL/MySQL/SQLite, `schema reverse` CLI, `schema compare` mit DB-Operanden (file/db, db/db), `data transfer` (DB-zu-DB-Streaming)
