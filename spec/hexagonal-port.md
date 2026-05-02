# Plan: Hexagonale Modulstruktur

> **Ziel**: Die flache Modulstruktur in eine hexagonale Verzeichnishierarchie
> überführen, sodass Architektur und Dateisystem übereinstimmen. Gradle erzwingt
> die Abhängigkeitsrichtung zur Compile-Zeit.

---

## 0. Ist-Zustand vs. Soll-Zustand

### 0.1 Diskrepanzen (Doku vs. Code)

| # | Doku (Soll) | Code (Ist) | Abweichung |
|---|---|---|---|
| D1 | `DatabaseDriver` als zentrales Port-Interface | Existiert nicht. Einzelne Interfaces + Registry-Singletons | Kein gemeinsamer Port |
| D2 | `SchemaReader` Port | Nicht implementiert (Reverse-Engineering fehlt) | Port fehlt komplett |
| D3 | `SchemaWriter` Port (`generateDdl(): List<DdlStatement>`) | `DdlGenerator` mit anderer Signatur (`generate(): DdlResult`) | Signatur divergiert |
| D4 | `DataReader` mit `Flow<DataChunk>`, `DatabaseConnection` | `DataReader` mit `ChunkSequence`, `ConnectionPool` | Signatur divergiert (bewusst) |
| D5 | `DataWriter` Port | Nicht implementiert (Import fehlt) | Port fehlt komplett |
| D6 | `FormatCodec<T>` generisches Port-Interface | Spezialisierte Interfaces: `SchemaCodec`, `DataChunkReader/Writer` | Andere Abstraktion gewählt |
| D7 | Input Ports (Use Cases) als eigene Schicht | Runner-Klassen in `d-migrate-cli` | Kein Application Layer |
| D8 | ServiceLoader-SPI für Driver-Registrierung | Statische `register()`-Aufrufe + Registries | Manuell statt SPI |
| D9 | `StreamingPipeline` nutzt `DatabaseDriver` | `StreamingExporter` bekommt Einzelteile injiziert | Keine Fassade |

### 0.2 Strukturproblem

Alle Module liegen flach auf Root-Ebene. Die Architektur (was ist Hexagon, was ist
Adapter) ist nur im Kopf der Entwickler, nicht im Dateisystem und nicht in der
Gradle-Konfiguration erzwungen.

---

## 1. Zielstruktur

```
d-migrate/
├── hexagon/
│   ├── core/                              ← Domain (Modell, Validierung, Typsystem)
│   ├── ports/                             ← Port-Interfaces (Output) + Datentypen
│   └── application/                       ← Use Cases (Runner-Klassen)
│
├── adapters/
│   ├── driving/
│   │   └── cli/                           ← Clikt-Shells, Wiring, OutputFormatter
│   └── driven/
│       ├── driver-postgresql/
│       ├── driver-mysql/
│       ├── driver-sqlite/
│       ├── driver-common/                 ← Abstract-Base-Klassen, HikariCP, Registries
│       ├── formats/                       ← JSON/YAML/CSV Codec-Implementierungen
│       └── streaming/                     ← Pipeline-Infrastruktur
│
├── build.gradle.kts
└── settings.gradle.kts
```

### 1.1 Gradle-Module (settings.gradle.kts)

```kotlin
rootProject.name = "d-migrate"

// Hexagon (innen)
include("hexagon:core")
include("hexagon:ports")
include("hexagon:application")

// Adapters (außen)
include("adapters:driving:cli")
include("adapters:driven:driver-common")
include("adapters:driven:driver-postgresql")
include("adapters:driven:driver-mysql")
include("adapters:driven:driver-sqlite")
include("adapters:driven:formats")
include("adapters:driven:streaming")
```

### 1.2 Abhängigkeitsgraph

```
hexagon:core                 → (keine)
hexagon:ports                → hexagon:core
hexagon:application          → hexagon:core, hexagon:ports

adapters:driven:driver-common      → hexagon:ports, HikariCP, SLF4J
adapters:driven:driver-postgresql  → hexagon:ports, adapters:driven:driver-common
adapters:driven:driver-mysql       → hexagon:ports, adapters:driven:driver-common
adapters:driven:driver-sqlite      → hexagon:ports, adapters:driven:driver-common
adapters:driven:formats            → hexagon:ports, Jackson, DSL-JSON, SnakeYAML, Univocity
adapters:driven:streaming          → hexagon:ports

adapters:driving:cli               → hexagon:application, hexagon:ports,
                                     adapters:driven:driver-*,
                                     adapters:driven:formats,
                                     adapters:driven:streaming,
                                     Clikt, Logback
```

**Erzwungene Regeln:**
- `hexagon:core` hat **keine** Abhängigkeiten auf andere Module
- `hexagon:ports` hängt nur von `hexagon:core` ab — keine externen Libs (nur JDK `java.sql`)
- `hexagon:application` hängt nur vom Hexagon-Inneren ab, **nicht** von Adaptern
- Kein Adapter darf von `hexagon:application` abhängen außer `adapters:driving:cli`
- Driven Adapters dürfen in **main** nicht voneinander abhängen (Ausnahme: Driver-Module → `driver-common` als shared infrastructure; **test**-Dependencies auf andere Adapters sind erlaubt)

### 1.3 Architekturdiagramm

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
         │  │  hexagon:ports           │  │  ← Output-Port-Interfaces
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

---

## 2. Grundsatzentscheidungen

### 2.1 Implementierungs-Signaturen beibehalten

Die Doku beschreibt Entwurfs-Signaturen (`Flow<DataChunk>`, `DatabaseConnection`,
`List<DdlStatement>`). Die Implementierung nutzt praxiserprobte Typen (`ChunkSequence`,
`ConnectionPool`, `DdlResult`). **Die Implementierungs-Signaturen bleiben**, die Doku
wird angepasst.

### 2.2 Scope der Port-Einführung

Nur Ports einführen, die heute bedienbar sind. Noch nicht implementierte Ports
(`SchemaReader`, `DataWriter`) als Soll-Zustand in der Doku kennzeichnen.

### 2.3 `FormatCodec<T>` — bewusst nicht implementieren

Statt eines generischen `FormatCodec<T>` bleiben die spezialisierten Interfaces:
- `SchemaCodec` — liest/schreibt `SchemaDefinition` (read-all-at-once)
- `DataChunkWriter/Reader` + Factories — Streaming chunk-by-chunk

**Begründung**: Schema- und Daten-Codecs haben fundamental unterschiedliche Lifecycles.
Ein generisches Interface würde sie in eine gemeinsame Abstraktion zwingen, die keinem
der beiden Fälle gerecht wird.

### 2.4 ServiceLoader vs. manuelle Registrierung

Manuelle Registrierung beibehalten. ServiceLoader hat Nachteile bei GraalVM Native Image,
und explizite Registrierung ist transparenter. Doku-Abschnitt §3.2 anpassen.

### 2.5 Keine Input-Port-Interfaces

Die Runner-Klassen sind die Use Cases. Ein 1:1-Interface-Layer darüber wäre eine leere
Abstraktion — es gibt nur eine Implementierung pro Use Case. Falls ein zweiter Driving
Adapter (REST-API) dazukommt, können Interfaces nachträglich extrahiert werden.

### 2.6 Package-Namen vorerst beibehalten

Die Kotlin-Packages (`dev.dmigrate.core.*`, `dev.dmigrate.driver.*`, `dev.dmigrate.format.*`,
`dev.dmigrate.cli.*`, `dev.dmigrate.streaming.*`) bleiben unverändert. Packages und
Gradle-Module sind unabhängig voneinander. Ein Package-Rename wäre reiner Churn ohne
funktionalen Nutzen und kann bei Bedarf separat erfolgen.

---

## 3. Was wohin wandert

### 3.1 `hexagon:core` (vorher `d-migrate-core`)

**Verschoben von** `d-migrate-core/` **nach** `hexagon/core/`.

Inhalt unverändert — alle Klassen in `dev.dmigrate.core.*`:
- `model/` — SchemaDefinition, TableDefinition, ColumnDefinition, NeutralType, …
- `validation/` — SchemaValidator, ValidationResult
- `data/` — DataChunk, ColumnDescriptor, DataFilter, ImportSchemaMismatchException

**Abhängigkeiten**: keine.

### 3.2 `hexagon:ports` (vorher Teile aus `d-migrate-driver-api` + `d-migrate-formats`)

Neues Modul — enthält **nur Interfaces und zugehörige Datentypen**, keine Implementierungen.

> Hinweis: Die spaetere Nachschaerfung von Read-/Write-Schnitt,
> Optionsmodellen und externer Library-Konsumierbarkeit ist als eigener
> Refactor-Milestone in
> [`implementation-plan-0.9.1.md`](../docs/planning/implementation-plan-0.9.1.md)
> dokumentiert.

**Aus `d-migrate-driver-api` hierher verschoben:**

| Datei | Package | Typ |
|---|---|---|
| `DatabaseDialect.kt` | `dev.dmigrate.driver` | Enum |
| `DdlGenerator.kt` | `dev.dmigrate.driver` | Interface + DdlResult, DdlStatement, TransformationNote, NoteType, SkippedObject |
| `TypeMapper.kt` | `dev.dmigrate.driver` | Interface |
| `DataReader.kt` | `dev.dmigrate.driver.data` | Interface |
| `TableLister.kt` | `dev.dmigrate.driver.data` | Interface |
| `ChunkSequence.kt` | `dev.dmigrate.driver.data` | Interface |
| `ConnectionPool.kt` | `dev.dmigrate.driver.connection` | Interface |
| `ConnectionConfig.kt` | `dev.dmigrate.driver.connection` | Data class |
| `PoolSettings.kt` | `dev.dmigrate.driver.connection` | Data class |
| `JdbcUrlBuilder.kt` | `dev.dmigrate.driver.connection` | Interface |

**Aus `d-migrate-formats` hierher verschoben:**

| Datei | Package | Typ |
|---|---|---|
| `SchemaCodec.kt` | `dev.dmigrate.format` | Interface |
| `DataChunkWriter.kt` | `dev.dmigrate.format.data` | Interface |
| `DataChunkWriterFactory.kt` | `dev.dmigrate.format.data` | Interface |
| `DataChunkReader.kt` | `dev.dmigrate.format.data` | Interface |
| `DataChunkReaderFactory.kt` | `dev.dmigrate.format.data` | Interface |
| `DataExportFormat.kt` | `dev.dmigrate.format.data` | Enum |
| `ExportOptions.kt` | `dev.dmigrate.format.data` | Data class |
| `ImportOptions.kt` | `dev.dmigrate.driver.data` | Data class (write/import-oriented) |
| `FormatReadOptions.kt` | `dev.dmigrate.format.data` | Data class (read-oriented) |

**Neues Interface:**

```kotlin
// dev.dmigrate.driver.DatabaseDriver
interface DatabaseDriver {
    val dialect: DatabaseDialect

    fun ddlGenerator(): DdlGenerator
    fun dataReader(): DataReader
    fun tableLister(): TableLister
    fun urlBuilder(): JdbcUrlBuilder

    // Noch nicht implementiert — ab 0.5.0+:
    // fun schemaReader(): SchemaReader
    // fun dataWriter(): DataWriter
}
```

**`TypeMapper` bewusst nicht in der Fassade**: Wird ausschließlich intern von
`AbstractDdlGenerator` und den konkreten DdlGeneratoren verwendet. Bleibt
Implementierungsdetail — wer `driver.ddlGenerator()` aufruft, bekommt
Type-Mapping implizit mit.

**Abhängigkeiten**: nur `hexagon:core` (+ JDK `java.sql.Connection` für `ConnectionPool`).

### 3.3 `hexagon:application` (vorher Teile aus `d-migrate-cli`)

Neues Modul — enthält die Use-Case-Runner.

**Aus `d-migrate-cli` hierher verschoben:**

| Datei | Package | Anmerkung |
|---|---|---|
| `SchemaGenerateRunner.kt` | `dev.dmigrate.cli.commands` | Refactoring nötig (siehe unten) |
| `DataExportRunner.kt` | `dev.dmigrate.cli.commands` | Refactoring nötig (siehe unten) |
| `ExportExecutor` (fun interface) | `dev.dmigrate.cli.commands` | Wandert mit DataExportRunner |

> **Bekannter Smell**: Package `dev.dmigrate.cli.commands` passt nicht zum
> Application-Layer. Rename auf `dev.dmigrate.application` ist optional und
> kann separat erfolgen (§2.6).

#### 3.3a CLI-Typen aus Runner-Signaturen entfernen

Die Runner referenzieren aktuell CLI-spezifische Typen, die nicht nach
`hexagon:application` wandern können:

| Typ | Wo referenziert | Problem |
|---|---|---|
| `CliContext` | `SchemaGenerateRequest.ctx`, `formatterFactory: (CliContext) -> OutputFormatter` | CLI-DTO |
| `OutputFormatter` | `formatterFactory`-Rückgabetyp | CLI-Klasse |
| `defaultExportExecutor` | Default-Wert in `DataExportRunner` | Referenziert `StreamingExporter` (Adapter) |

**Lösung für `SchemaGenerateRunner`:**

`formatterFactory` und `CliContext` werden durch generische Funktionstypen ersetzt.
Der Runner braucht den Formatter nur, um Validierungsergebnisse und Fehler auszugeben.
Das lässt sich auf zwei Lambdas reduzieren:

```kotlin
// Vorher (referenziert CLI-Typen):
private val formatterFactory: (CliContext) -> OutputFormatter = ::OutputFormatter,

// Nachher (nur Funktionstypen):
private val printValidationResult: (ValidationResult, SchemaDefinition, String) -> Unit,
private val printError: (message: String, source: String) -> Unit,
```

`SchemaGenerateRequest` verliert das `ctx`-Feld — es gehört zur CLI, nicht zum Use Case.
Die CLI baut die Lambdas beim Wiring:

```kotlin
val ctx = root?.cliContext() ?: CliContext()
val formatter = OutputFormatter(ctx)
val runner = SchemaGenerateRunner(
    printValidationResult = { result, schema, source ->
        formatter.printValidationResult(result, schema, source)
    },
    printError = { msg, src -> formatter.printError(msg, src) },
    // ...
)
```

**Lösung für `DataExportRunner`:**

`ExportExecutor` (fun interface) wandert mit dem Runner — es referenziert nur
Port-Typen (`ConnectionPool`, `DataReader`, `TableLister`, etc.). ✓

`defaultExportExecutor` (die Production-Implementierung) bleibt in der CLI —
sie referenziert `StreamingExporter` (Adapter). Der Runner bekommt keinen Default
für `exportExecutor`, die CLI übergibt ihn explizit.

**Alle Adapter-referenzierenden Defaults werden entfernt:**

```kotlin
// Aktuell — referenziert Adapter-Klassen:
private val readerLookup: (DatabaseDialect) -> DataReader = DataReaderRegistry::dataReader,
private val writerFactoryBuilder: (...) -> DataChunkWriterFactory =
    { sink -> DefaultDataChunkWriterFactory(warningSink = sink) },
private val exportExecutor: ExportExecutor = defaultExportExecutor,

// Nachher — reine Funktionstypen ohne Defaults:
private val driverLookup: (DatabaseDialect) -> DatabaseDriver,
private val writerFactoryBuilder: ((Warning) -> Unit) -> DataChunkWriterFactory,
private val exportExecutor: ExportExecutor,
```

**Defaults, die nur Core/Ports-Typen referenzieren, dürfen bleiben:**
```kotlin
private val validator: (SchemaDefinition) -> ValidationResult =
    { SchemaValidator().validate(it) },   // OK — SchemaValidator ist in hexagon:core
```

Das Wiring (welche konkreten Adapter-Implementierungen injiziert werden) wandert
komplett in die CLI.

**Abhängigkeiten**: `hexagon:core`, `hexagon:ports`.

### 3.4 `adapters:driven:driver-common` (vorher Teile aus `d-migrate-driver-api`)

Neues Modul — enthält die **gemeinsame Adapter-Infrastruktur** für DB-Driver.

**Aus `d-migrate-driver-api` hierher verschoben:**

| Datei | Package | Typ |
|---|---|---|
| `AbstractDdlGenerator.kt` | `dev.dmigrate.driver` | Abstract class |
| `ViewQueryTransformer.kt` | `dev.dmigrate.driver` | Class |
| `AbstractJdbcDataReader.kt` | `dev.dmigrate.driver.data` | Abstract class |
| `DataReaderRegistry.kt` | `dev.dmigrate.driver.data` | Object (wird obsolet, siehe §4.4) |
| `HikariConnectionPoolFactory.kt` | `dev.dmigrate.driver.connection` | Object |
| `ConnectionUrlParser.kt` | `dev.dmigrate.driver.connection` | Object |
| `LogScrubber.kt` | `dev.dmigrate.driver.connection` | Object |
| `JdbcUrlBuilderRegistry.kt` | `dev.dmigrate.driver.connection` | Object (wird obsolet) |

**Abhängigkeiten**: `hexagon:ports`, HikariCP, SLF4J.

### 3.5 `adapters:driven:driver-postgresql` (vorher `d-migrate-driver-postgresql`)

Verschoben nach `adapters/driven/driver-postgresql/`.

**Änderung**: `PostgresDriver` wird von `object` zu `class : DatabaseDriver`:

```kotlin
class PostgresDriver : DatabaseDriver {
    override val dialect = DatabaseDialect.POSTGRESQL
    override fun ddlGenerator() = PostgresDdlGenerator()
    override fun dataReader() = PostgresDataReader()
    override fun tableLister() = PostgresTableLister()
    override fun urlBuilder() = PostgresJdbcUrlBuilder()
}
```

Die bisherige `register()`-Methode entfällt.

**Abhängigkeiten**: `hexagon:ports`, `adapters:driven:driver-common`, PostgreSQL-JDBC.

### 3.6 `adapters:driven:driver-mysql` / `driver-sqlite`

Analog zu 3.5.

### 3.7 `adapters:driven:formats` (vorher `d-migrate-formats`)

Verschoben nach `adapters/driven/formats/`.

Die Port-Interfaces (`SchemaCodec`, `DataChunkReader/Writer`, Factories) sind nach
`hexagon:ports` gewandert (§3.2). Dieses Modul enthält nur noch die
**Implementierungen**:

| Verbleibt | Package |
|---|---|
| `YamlSchemaCodec.kt` | `dev.dmigrate.format.yaml` |
| `TransformationReportWriter.kt` | `dev.dmigrate.format.report` |
| `JsonChunkWriter.kt`, `JsonChunkReader.kt` | `dev.dmigrate.format.data.json` |
| `YamlChunkWriter.kt`, `YamlChunkReader.kt` | `dev.dmigrate.format.data.yaml` |
| `CsvChunkWriter.kt`, `CsvChunkReader.kt` | `dev.dmigrate.format.data.csv` |
| `DefaultDataChunkWriterFactory.kt` | `dev.dmigrate.format.data` |
| `DefaultDataChunkReaderFactory.kt` | `dev.dmigrate.format.data` |
| `ValueSerializer.kt`, `ValueDeserializer.kt` | `dev.dmigrate.format.data` |
| `EncodingDetector.kt` | `dev.dmigrate.format.data` |
| `CharsetReencodingOutputStream.kt` | `dev.dmigrate.format.data.json` |
| `CharsetTranscodingInputStream.kt` | `dev.dmigrate.format.data.json` |

**Abhängigkeiten (main)**: `hexagon:ports`, `hexagon:core`, Jackson, DSL-JSON, SnakeYAML Engine, Univocity.
Kein `driver-common` in main — `DdlResult`/`NoteType` (für `TransformationReportWriter`)
kommen über `hexagon:ports`.

**Abhängigkeiten (test)**: `adapters:driven:driver-common` (benötigt weil DdlGoldenMasterTests
konkrete DdlGeneratoren instanziieren, die von `AbstractDdlGenerator` erben — die
Driver-Module exportieren driver-common nicht transitiv via `implementation`).

### 3.8 `adapters:driven:streaming` (vorher `d-migrate-streaming`)

Verschoben nach `adapters/driven/streaming/`.

Inhalt unverändert (`StreamingExporter`, `ExportOutput`, `ExportResult`, etc.).
Imports referenzieren ausschließlich Port-Interfaces und Core-Typen — nach der
Verschiebung der Interfaces nach `hexagon:ports` ändert sich an den Imports nichts.

**Abhängigkeiten**: `hexagon:ports` (transitiv `hexagon:core`). Keine externen Libs.

### 3.9 `adapters:driving:cli` (vorher `d-migrate-cli`)

Verschoben nach `adapters/driving/cli/`.

**Behält:**
- Clikt-Commands (`SchemaCommands.kt`, `DataCommands.kt`, `DMigrate`)
- `OutputFormatter.kt`
- `NamedConnectionResolver.kt`
- `SchemaGenerateHelpers.kt` (wird reduziert — `getGenerator()` entfällt)
- `DataExportHelpers.kt`
- `Main.kt` (Wiring)

**Verliert:**
- `SchemaGenerateRunner.kt` → `hexagon:application`
- `DataExportRunner.kt` → `hexagon:application`

**Neues Wiring in Main.kt:**

```kotlin
fun registerDrivers() {
    DatabaseDriverRegistry.register(PostgresDriver())
    DatabaseDriverRegistry.register(MysqlDriver())
    DatabaseDriverRegistry.register(SqliteDriver())
}
```

**Neues Wiring in Commands** (Default-Werte, die vorher in den Runnern waren):

```kotlin
// In SchemaGenerateCommand oder einer Factory-Funktion:
val runner = SchemaGenerateRunner(
    schemaReader = { path -> YamlSchemaCodec().read(path) },
    validator = { SchemaValidator().validate(it) },
    driverLookup = { DatabaseDriverRegistry.get(it) },
    reportWriter = { path, result, schema, dialect, source ->
        TransformationReportWriter().write(path, result, schema, dialect, source)
    },
    // ...
)
```

**Abhängigkeiten**: `hexagon:application`, `hexagon:ports`, alle Driven Adapters, Clikt.

---

## 4. Umsetzungsphasen

### Phase A: Verzeichnisstruktur anlegen

Verzeichnisse und leere `build.gradle.kts` für die neue Struktur anlegen.
`settings.gradle.kts` auf die neuen Module umstellen. Noch keine Dateien verschieben.

### Phase B: `hexagon:ports` extrahieren

Port-Interfaces und Datentypen aus `d-migrate-driver-api` und `d-migrate-formats`
nach `hexagon/ports/` verschieben. Neues `DatabaseDriver`-Interface anlegen.
`DatabaseDriverRegistry` anlegen.

**Transition-Strategie**: Die Quellmodule (`d-migrate-driver-api`, `d-migrate-formats`)
werden vorübergehend zu Fassaden, die `hexagon:ports` per `api()`-Dependency re-exportieren:

```kotlin
// d-migrate-driver-api/build.gradle.kts (temporär in Phase B–D)
dependencies {
    api(project(":hexagon:ports"))   // re-export — alle Consumer sehen die Port-Typen
    // ... bisherige Implementierungs-Dependencies bleiben
}
```

Damit kompilieren alle Module, die bisher von `d-migrate-driver-api` abhängen,
sofort weiter — die Klassen liegen jetzt in `hexagon:ports`, aber der Classpath
stimmt durch das transitive `api()`. Gleiches Prinzip für `d-migrate-formats`.

Die Fassaden werden schrittweise abgebaut:
- Phase D löst `d-migrate-driver-api` komplett auf
- Phase E stellt `d-migrate-formats` auf direkte `hexagon:ports`-Abhängigkeit um

### Phase C: `hexagon:core` verschieben

`d-migrate-core/` nach `hexagon/core/` verschieben. Rein mechanisch — keine
inhaltlichen Änderungen.

### Phase D: `adapters:driven:driver-common` extrahieren

Abstract-Base-Klassen und Infrastruktur aus `d-migrate-driver-api` nach
`adapters/driven/driver-common/` verschieben. Das alte `d-migrate-driver-api`
Modul wird aufgelöst (Interfaces → ports, Implementierungen → driver-common).

### Phase E: Driven Adapters verschieben

- `d-migrate-driver-postgresql/` → `adapters/driven/driver-postgresql/`
- `d-migrate-driver-mysql/` → `adapters/driven/driver-mysql/`
- `d-migrate-driver-sqlite/` → `adapters/driven/driver-sqlite/`
- `d-migrate-formats/` → `adapters/driven/formats/`
- `d-migrate-streaming/` → `adapters/driven/streaming/`

Driver von `object` zu `class : DatabaseDriver` umbauen.

### Phase F: `hexagon:application` extrahieren

Runner aus `d-migrate-cli` nach `hexagon/application/` verschieben.
Adapter-referenzierende Defaults entfernen. Wiring in die CLI verlagern.

### Phase G: `adapters:driving:cli` verschieben

`d-migrate-cli/` → `adapters/driving/cli/`. Wiring in `Main.kt` auf
`DatabaseDriverRegistry` + `DatabaseDriver`-Fassade umstellen.

### Phase H: Aufräumen + Doku

- Alte Modulverzeichnisse löschen
- `DataReaderRegistry`, `JdbcUrlBuilderRegistry` entfernen (obsolet durch `DatabaseDriverRegistry`)
- `SchemaGenerateHelpers.getGenerator()` entfernen (obsolet durch `DatabaseDriver`)
- `architecture.md` aktualisieren (§1.2 Modulzuordnung, §3.1 Signaturen, §3.2 SPI, §3.3 Pipeline)
- `FormatCodec<T>` aus der Doku entfernen
- Hexagon-Diagramm in §1.2 an die neue Struktur anpassen

### Reihenfolge

```
Phase A ──► Phase B ──► Phase C ──► Phase D ──► Phase E ──► Phase F ──► Phase G ──► Phase H
```

Strikt sequentiell. Jede Phase muss kompilieren und alle Tests bestehen, bevor
die nächste beginnt.

---

## 5. Betroffene Dateien

### 5.1 Neue Dateien

| Datei | Phase |
|---|---|
| `hexagon/ports/build.gradle.kts` | A |
| `hexagon/application/build.gradle.kts` | A |
| `adapters/driven/driver-common/build.gradle.kts` | A |
| `hexagon/ports/.../DatabaseDriver.kt` | B |
| `hexagon/ports/.../DatabaseDriverRegistry.kt` | B |

### 5.2 Verschobene Dateien (Auswahl — nur strukturrelevante)

| Von | Nach | Phase |
|---|---|---|
| `d-migrate-core/**` | `hexagon/core/**` | C |
| `d-migrate-driver-api/**/DdlGenerator.kt` (Interface) | `hexagon/ports/**/` | B |
| `d-migrate-driver-api/**/DataReader.kt` (Interface) | `hexagon/ports/**/` | B |
| `d-migrate-driver-api/**/TypeMapper.kt` (Interface) | `hexagon/ports/**/` | B |
| `d-migrate-driver-api/**/ConnectionPool.kt` (Interface) | `hexagon/ports/**/` | B |
| `d-migrate-driver-api/**/AbstractDdlGenerator.kt` | `adapters/driven/driver-common/**/` | D |
| `d-migrate-driver-api/**/AbstractJdbcDataReader.kt` | `adapters/driven/driver-common/**/` | D |
| `d-migrate-driver-api/**/HikariConnectionPoolFactory.kt` | `adapters/driven/driver-common/**/` | D |
| `d-migrate-formats/**/SchemaCodec.kt` (Interface) | `hexagon/ports/**/` | B |
| `d-migrate-formats/**/DataChunkWriter.kt` (Interface) | `hexagon/ports/**/` | B |
| `d-migrate-formats/**/DataChunkReader.kt` (Interface) | `hexagon/ports/**/` | B |
| `d-migrate-formats/**/DataChunkWriterFactory.kt` (Interface) | `hexagon/ports/**/` | B |
| `d-migrate-formats/**/DataChunkReaderFactory.kt` (Interface) | `hexagon/ports/**/` | B |
| `d-migrate-formats/**/DataExportFormat.kt` (Enum) | `hexagon/ports/**/` | B |
| `d-migrate-formats/**/ExportOptions.kt` | `hexagon/ports/**/` | B |
| `d-migrate-formats/**/ImportOptions.kt` | `hexagon/ports/driver/data/` | B |
| `d-migrate-cli/**/SchemaGenerateRunner.kt` | `hexagon/application/**/` | F |
| `d-migrate-cli/**/DataExportRunner.kt` | `hexagon/application/**/` | F |
| `d-migrate-driver-postgresql/**` | `adapters/driven/driver-postgresql/**` | E |
| `d-migrate-driver-mysql/**` | `adapters/driven/driver-mysql/**` | E |
| `d-migrate-driver-sqlite/**` | `adapters/driven/driver-sqlite/**` | E |
| `d-migrate-formats/**` (Implementierungen) | `adapters/driven/formats/**` | E |
| `d-migrate-streaming/**` | `adapters/driven/streaming/**` | E |
| `d-migrate-cli/**` (Rest) | `adapters/driving/cli/**` | G |

### 5.3 Geänderte Dateien

| Datei | Phase | Änderung |
|---|---|---|
| `PostgresDriver.kt` | E | `object` → `class : DatabaseDriver` |
| `MysqlDriver.kt` | E | `object` → `class : DatabaseDriver` |
| `SqliteDriver.kt` | E | `object` → `class : DatabaseDriver` |
| `SchemaGenerateRunner.kt` | F | CLI-Typen entfernen (`CliContext`, `OutputFormatter` → Lambdas), `driverLookup` statt `generatorLookup` |
| `SchemaGenerateRequest.kt` (oder inline) | F | `ctx: CliContext`-Feld entfernen |
| `DataExportRunner.kt` | F | Adapter-Defaults entfernen, `driverLookup` statt `readerLookup`+`listerLookup`, `defaultExportExecutor` bleibt in CLI |
| `Main.kt` | G | `DatabaseDriverRegistry.register(PostgresDriver())` etc. |
| `SchemaCommands.kt` | G | Runner-Wiring mit konkreten Adapter-Defaults |
| `DataCommands.kt` | G | Runner-Wiring mit konkreten Adapter-Defaults |
| `spec/architecture.md` | H | §1.2, §3.1, §3.2, §3.3 |

### 5.4 Gelöschte Dateien (Phase H)

| Datei | Grund |
|---|---|
| `d-migrate-driver-api/` (gesamtes Modul) | Aufgelöst in `hexagon:ports` + `adapters:driven:driver-common` |
| `DataReaderRegistry.kt` | Obsolet durch `DatabaseDriverRegistry` |
| `JdbcUrlBuilderRegistry.kt` | Obsolet durch `DatabaseDriverRegistry` |
| `SchemaGenerateHelpers.getGenerator()` | Obsolet durch `DatabaseDriver`-Fassade |

---

## 6. Test-Strategie

### 6.1 Test-Fixture: `FakeDatabaseDriver`

Neues Test-Fixture in `hexagon:ports` (testFixtures):

```
hexagon/ports/src/testFixtures/kotlin/dev/dmigrate/driver/test/FakeDatabaseDriver.kt
```

```kotlin
class FakeDatabaseDriver(
    override val dialect: DatabaseDialect = DatabaseDialect.POSTGRESQL,
    private val ddlGenerator: DdlGenerator = StubDdlGenerator(),
    private val dataReader: DataReader = FakeDataReader(),
    private val tableLister: TableLister = FakeTableLister(),
    private val urlBuilder: JdbcUrlBuilder = StubJdbcUrlBuilder(),
) : DatabaseDriver {
    override fun ddlGenerator() = ddlGenerator
    override fun dataReader() = dataReader
    override fun tableLister() = tableLister
    override fun urlBuilder() = urlBuilder
}
```

Bestehende Fakes (`FakeDataReader`, `FakeTableLister`) werden wiederverwendet.

### 6.2 Runner-Tests migrieren

Die Runner-Tests (`DataExportRunnerTest`, `SchemaGenerateRunnerTest`) leben nach
Phase F in `hexagon/application/src/test/`. Ihre `newRunner()`-Hilfsfunktionen
werden auf `driverLookup = { FakeDatabaseDriver(...) }` umgestellt.

### 6.3 Compile-Garantie pro Phase

Jede Phase endet mit `./gradlew build` — kompiliert und alle Tests grün.
Keine Phase darf den Build brechen.

---

## 7. Risiken

| Risiko | Mitigation |
|---|---|
| Großer Umbau mit vielen verschobenen Dateien | Pro Phase ein Commit; jede Phase kompiliert eigenständig |
| Git-History geht bei Verschiebungen verloren | `git mv` verwenden — Git erkennt Renames bei >50% Ähnlichkeit |
| Runner verlieren Adapter-Defaults | Wiring explizit in CLI — Tests nutzen `FakeDatabaseDriver` |
| `d-migrate-driver-api` wird aufgelöst | Phase B + D zusammen reviewen, damit nichts verloren geht |
| Kover-Coverage-Config muss angepasst werden | Modul-Referenzen in Root `build.gradle.kts` aktualisieren |
| `d-migrate-formats` importiert `DdlResult`/`NoteType` (für `TransformationReportWriter`) | Diese Typen wandern nach `hexagon:ports` → Import bleibt gültig |
| `StreamingExporter` importiert Format-Interfaces | Interfaces wandern nach `hexagon:ports` → `streaming` hängt nur von `hexagon:ports` ab, nicht von `formats` |
| Jib/Docker-Config in CLI-Modul | Pfad in `build.gradle.kts` anpassen (`adapters:driving:cli`) |
| CI-Pipeline referenziert alte Modulnamen | `settings.gradle.kts` ist Single Source of Truth — CI-Skripte prüfen |
| Gradle-Task-Pfade ändern sich (`:d-migrate-cli:run` → `:adapters:driving:cli:run`) | Doku, CI-Skripte und `scripts/` aktualisieren |
| `SchemaGenerateRunner` verliert `CliContext`/`OutputFormatter`-Zugriff | Runner-Signatur auf generische Lambdas umstellen (§3.3a) |
| `ExportExecutor`-Default referenziert `StreamingExporter` | Default bleibt in CLI; Runner bekommt keinen Default (§3.3a) |
