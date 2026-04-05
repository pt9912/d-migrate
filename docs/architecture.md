# Architektur-Dokument: d-migrate

**Framework für datenbankunabhängige Migrationen und Datenverwaltung**

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
                    │MySQL     │  │CSV/SQL   │  │OpenAI      │
                    │SQLite    │  │          │  │Anthropic   │
                    │(Oracle)  │  │          │  │Grok        │
                    │(MSSQL)   │  │          │  │(lokal/ext.)│
                    └──────────┘  └──────────┘  └────────────┘
```

### 1.2 Architekturstil

**Hexagonale Architektur (Ports & Adapters)** mit klarer Trennung zwischen:

- **Domain Core**: Neutrales Schema-Modell, Validierungslogik, Type-Mapping — keine externen Abhängigkeiten
- **Ports**: Interfaces für Datenbank-Zugriff, Datei-I/O, KI-Provider
- **Adapters**: Konkrete Implementierungen (JDBC-Driver, Jackson-Serializer, HTTP-Clients)

```
                    Driving Adapters              Driven Adapters
                   (Input/Primary)              (Output/Secondary)
                         │                              │
              ┌──────────┤                  ┌───────────┤
              │          │                  │           │
         ┌────▼───┐ ┌────▼───┐        ┌────▼───┐ ┌────▼────┐
         │  CLI   │ │ Script │        │ JDBC   │ │ File    │
         │Adapter │ │  API   │        │Adapter │ │ Adapter │
         └────┬───┘ └────┬───┘        └────┬───┘ └────┬────┘
              │          │                  │          │
         ┌────▼──────────▼──┐        ┌─────▼──────────▼────┐
         │   Input Ports    │        │   Output Ports       │
         │  (Use Cases)     │        │  (Repositories,      │
         │                  │        │   Gateways)           │
         └────────┬─────────┘        └──────────┬───────────┘
                  │                              │
              ┌───▼──────────────────────────────▼───┐
              │           Domain Core                 │
              │                                       │
              │  SchemaDefinition  TypeMapper          │
              │  Validator         Transformer         │
              │  MigrationPlan     DiffEngine          │
              └───────────────────────────────────────┘
```

---

## 2. Modul-Struktur

### 2.1 Projekt-Layout (Gradle Multi-Module)

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
│           ├── typemap/                # Neutrales Typsystem
│           │   └── TypeMapper.kt
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
│   │           └── DataWriter.kt
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
│           ├── json/
│           │   └── JsonCodec.kt
│           ├── yaml/
│           │   └── YamlCodec.kt
│           ├── csv/
│           │   └── CsvCodec.kt
│           └── sql/
│               └── SqlCodec.kt
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
│           ├── openai/
│           │   └── OpenAiProvider.kt
│           ├── anthropic/
│           │   └── AnthropicProvider.kt
│           └── noop/
│               └── RuleBasedProvider.kt
│
├── d-migrate-streaming/                # Streaming-Pipeline
│   └── src/main/kotlin/
│       └── dev/dmigrate/streaming/
│           ├── Pipeline.kt
│           ├── ChunkProcessor.kt
│           ├── Checkpoint.kt
│           └── ParallelExecutor.kt
│
├── d-migrate-i18n/                     # Internationalisierung
│   └── src/main/
│       ├── kotlin/
│       │   └── dev/dmigrate/i18n/
│       │       ├── Messages.kt
│       │       └── UnicodeUtils.kt
│       └── resources/
│           └── messages/
│               ├── messages_de.properties
│               └── messages_en.properties
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
│   ├── d-migrate-driver-postgresql ──▶ d-migrate-driver-api
│   ├── d-migrate-driver-mysql ──▶ d-migrate-driver-api
│   └── d-migrate-driver-sqlite ──▶ d-migrate-driver-api
├── d-migrate-formats ──▶ d-migrate-core
├── d-migrate-integrations ──▶ d-migrate-core
├── d-migrate-ai ──▶ d-migrate-core
├── d-migrate-streaming ──▶ d-migrate-core, d-migrate-driver-api
└── d-migrate-i18n
```

**Regel**: `d-migrate-core` hat KEINE Abhängigkeit auf andere Module. Alle anderen Module hängen von `core` ab, aber nie umgekehrt.

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
    fun readViews(connection: DatabaseConnection): List<ViewDefinition>
    fun readTriggers(connection: DatabaseConnection): List<TriggerDefinition>
}

interface SchemaWriter {
    /** DDL-Generierung: Neutrales Modell → DB-spezifisches SQL */
    fun generateDdl(schema: SchemaDefinition): List<DdlStatement>
    fun generateMigration(diff: DiffResult): List<DdlStatement>
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

### 3.3 Streaming-Pipeline

```kotlin
/**
 * Streaming-Pipeline für Datenverarbeitung.
 * Verarbeitet Daten chunkweise ohne vollständiges Laden in den Speicher.
 */
class StreamingPipeline(
    private val source: DataReader,
    private val target: DataWriter,
    private val transformer: DataTransformer,
    private val checkpointStore: CheckpointStore
) {
    suspend fun execute(
        sourceConnection: DatabaseConnection,
        targetConnection: DatabaseConnection,
        tables: List<String>,
        config: PipelineConfig
    ): PipelineResult {
        val graph = DependencyGraph.build(tables, foreignKeys)
        val executionOrder = graph.topologicalSort()

        // Unabhängige Tabellen parallel, abhängige sequentiell
        return coroutineScope {
            executionOrder.parallelGroups().map { group ->
                group.map { table ->
                    async(Dispatchers.IO) {
                        processTable(table, sourceConnection, targetConnection, config)
                    }
                }.awaitAll()
            }
        }
    }

    private suspend fun processTable(
        table: String,
        source: DatabaseConnection,
        target: DatabaseConnection,
        config: PipelineConfig
    ) {
        val checkpoint = checkpointStore.load(table)

        source.dataReader()
            .streamTable(table, startAfter = checkpoint?.lastProcessedId)
            .onEach { chunk ->
                val transformed = transformer.transform(chunk)
                target.dataWriter().importChunk(table, transformed)
                checkpointStore.save(table, chunk.lastId, chunk.count)
            }
            .collect()
    }
}
```

### 3.4 Type-Mapping-Engine

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
    val pipeline: PipelineConfig
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

---

## 5. Build und Distribution

### 5.1 Build-System

**Gradle (Kotlin DSL)** mit Multi-Module-Setup:

```kotlin
// build.gradle.kts (Root)
plugins {
    kotlin("jvm") version "2.1.0"
    id("org.graalvm.buildtools.native") version "0.10.4"
}

allprojects {
    group = "dev.dmigrate"
    version = "0.1.0-SNAPSHOT"
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

1. GraalVM Native Image (bevorzugt)
   → Single Binary für Linux/macOS/Windows
   → ~50 MB, Start in <100ms
   → Distribution: GitHub Releases, Homebrew

2. Fat JAR (Fallback)
   → java -jar d-migrate.jar
   → Für Umgebungen ohne Native-Image-Support
   → Distribution: Maven Central, SDKMAN

3. Docker Image
   → docker run dmigrate/d-migrate schema validate ...
   → Für CI/CD-Pipelines

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
2. HTTP-Client für Provider-API
3. Konfiguration in AiBackendConfig ergänzen
4. ServiceLoader-Registrierung
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

**Version**: 1.0
**Stand**: 2026-04-05
**Status**: Entwurf
