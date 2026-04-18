# Implementierungsplan: Phase G-2 - Port-Modulschnitt (ports-common/ports-read/ports-write)

> **Milestone**: 0.9.1 - Library-Refactor und Integrationsschnitt
> **Phase**: G-2 (Port-Modulschnitt)
> **Status**: Draft (2026-04-18)
> **Vorbedingung**: Phase C Kern (FormatReadOptions), Phase D
> (Profiling-Extraktion), Phase F (Consumer-Read-Probe)
> **Referenz**: `docs/ImpPlan-0.9.1-C.md` Stretch Goals 5.4-5.5;
> `docs/ImpPlan-0.9.1-F.md` §8.6; `docs/ImpPlan-0.9.1-G.md` §2

---

## 1. Ziel

Phase G-2 spaltet `hexagon:ports` (52 Dateien) in drei Teilmodule auf,
damit read-only Consumer nur die read-orientierten Porttypen transitiv
sehen. `hexagon:ports` bleibt als Aggregator erhalten.

---

## 2. Ausgangslage

- `hexagon:ports` enthaelt heute 52 Kotlin-Dateien in 6 Paketen:
  - `dev.dmigrate.driver` (15 Dateien: SchemaReader, DdlGenerator,
    DatabaseDialect, SqlIdentifiers, TypeMapper, DialectCapabilities,
    ManualActionRequired, ...)
  - `dev.dmigrate.driver.connection` (4 Dateien: ConnectionPool,
    ConnectionConfig, JdbcUrlBuilder, PoolSettings)
  - `dev.dmigrate.driver.data` (11 Dateien: DataReader, DataWriter,
    ImportOptions, TableImportSession, TargetColumn, ...)
  - `dev.dmigrate.format.data` (7 Dateien: DataChunkReader/Writer,
    Factories, FormatReadOptions, ExportOptions, DataExportFormat)
  - `dev.dmigrate.streaming` (6 Dateien: ExportOutput, ImportInput,
    ExportResult, ImportResult, PipelineConfig, ProgressEvent)
  - `dev.dmigrate.migration` (5 Dateien), `dev.dmigrate.format` (1),
    `dev.dmigrate.streaming.checkpoint` (2)
- 9 Module haengen heute an `project(":hexagon:ports")`
- `adapters:driven:formats` exponiert oeffentliche Writer-Klassen
  (Finding aus Phase-C-Review)

---

## 3. Zielzuschnitt

### 3.1 `hexagon:ports-common`

Gemeinsame Basistypen, die sowohl Read- als auch Write-Consumer brauchen:

| Paket | Dateien |
|-------|---------|
| `dev.dmigrate.driver` | `DatabaseDialect`, `SqlIdentifiers`, `TypeMapper`, `DialectCapabilities` |
| `dev.dmigrate.driver.connection` | `ConnectionPool`, `ConnectionConfig`, `JdbcUrlBuilder`, `PoolSettings` |
| `dev.dmigrate.driver.data` | `ResumeMarker` (benoetigt von DataReader in ports-read UND ExportResult in ports-write) |
| `dev.dmigrate.format.data` | `DataExportFormat` |
| `dev.dmigrate.format` | `SchemaCodec` |

### 3.2 `hexagon:ports-read`

Read-orientierte Vertraege — das, was ein `source-d-migrate`-Adapter
braucht:

| Paket | Dateien |
|-------|---------|
| `dev.dmigrate.driver` | `SchemaReader`, `SchemaReadOptions`, `SchemaReadResult`, `SchemaReadNote`, `SchemaReadReportInput`, `DdlGenerator`, `DdlGenerationOptions` + DdlResult/DdlStatement/TransformationNote/NoteType/SkippedObject (aus DdlGenerator.kt), `ManualActionRequired` (referenziert TransformationNote/SkippedObject) |
| `dev.dmigrate.driver.data` | `DataReader`, `ChunkSequence`, `TableLister` |
| `dev.dmigrate.format.data` | `DataChunkReader`, `DataChunkReaderFactory`, `FormatReadOptions` |

Abhaengigkeit: `api(project(":hexagon:ports-common"))`

### 3.3 `hexagon:ports-write`

Write-/Import-orientierte Vertraege:

| Paket | Dateien |
|-------|---------|
| `dev.dmigrate.driver.data` | `DataWriter`, `TableImportSession`, `ImportOptions`, `TargetColumn`, `WriteResult`, `FinishTableResult`, `SchemaSync`, `SequenceAdjustment`, `UnsupportedTriggerModeException` |
| `dev.dmigrate.format.data` | `DataChunkWriter`, `DataChunkWriterFactory`, `ExportOptions` |
| `dev.dmigrate.streaming` | `ExportOutput`, `ExportResult`, `ImportInput`, `ImportResult`, `PipelineConfig`, `ProgressEvent` + alle streaming-Typen |
| `dev.dmigrate.streaming.checkpoint` | `CheckpointManifest`, `CheckpointStore` + Checkpoint-Typen |
| `dev.dmigrate.migration` | alle 5 Dateien |

Abhaengigkeit: `api(project(":hexagon:ports-common"))`

### 3.4 `hexagon:ports` (Aggregator)

Bleibt bestehen, re-exportiert alle drei:

```kotlin
dependencies {
    api(project(":hexagon:ports-common"))
    api(project(":hexagon:ports-read"))
    api(project(":hexagon:ports-write"))
}
```

Enthaelt: `DatabaseDriver`, `DatabaseDriverRegistry` (gemischte
Treiberflaeche, kann keinem Teilmodul zugeordnet werden).

---

## 4. Consumer-Anpassungen

| Consumer | Heute | Nachher | Churn |
|----------|-------|---------|-------|
| `hexagon:application` | `implementation(:hexagon:ports)` | unveraendert (Aggregator) | keiner |
| `hexagon:profiling` | `implementation(:hexagon:ports)` | unveraendert | keiner |
| `adapters:driven:driver-common` | `api(:hexagon:ports)` | unveraendert | keiner |
| `adapters:driven:formats` | `api(:hexagon:ports)` | `api(:hexagon:ports-read)` + `api(:hexagon:ports-write)` | minimal |
| `adapters:driven:integrations` | `api(:hexagon:ports)` | unveraendert | keiner |
| `adapters:driven:streaming` | transitiv | unveraendert | keiner |
| `test:integration-*` | `testImpl(:hexagon:ports)` | unveraendert | keiner |
| `test:consumer-read-probe` | `testImpl(:hexagon:ports)` | `testImpl(:hexagon:ports-read)` | 1 Zeile |

Fazit: nur 2 Module werden direkt angepasst (`formats`, `consumer-read-probe`).
Alle anderen bleiben auf dem Aggregator.

---

## 5. Arbeitspakete

### 5.1 Neue Gradle-Module anlegen

- `hexagon/ports-common/build.gradle.kts` mit `hexagon:core`-Abhaengigkeit
- `hexagon/ports-read/build.gradle.kts` mit `api(:hexagon:ports-common)`
- `hexagon/ports-write/build.gradle.kts` mit `api(:hexagon:ports-common)`
- `settings.gradle.kts` erweitern
- Root-Kover-Aggregation erweitern

### 5.2 Dateien verschieben (git mv)

Pro Teilmodul die Dateien gemaess §3.1-3.3 verschieben.
Paketnamen bleiben identisch → kein Import-Churn.

### 5.3 Aggregator umstellen

`hexagon:ports/build.gradle.kts`:
- `api` auf alle drei Teilmodule
- `DatabaseDriver.kt` und `DatabaseDriverRegistry.kt` bleiben hier

### 5.4 Consumer anpassen

- `adapters:driven:formats/build.gradle.kts`:
  `api(:hexagon:ports)` → `api(:hexagon:ports-read)` + `api(:hexagon:ports-write)`
- `test:consumer-read-probe/build.gradle.kts`:
  `testImpl(:hexagon:ports)` → `testImpl(:hexagon:ports-read)` +
  `testImpl(:hexagon:ports-common)`

### 5.5 Consumer-Read-Probe verschaerfen

Die Probe baut jetzt gegen `hexagon:ports-read` statt gegen den
Aggregator. Build-Erzwingung: wenn `hexagon:ports-write` nicht im
transitiven Compile-Graph ist, kompiliert die Probe nur mit
read-orientierten Typen.

---

## 6. Verifikation

```bash
docker build -t d-migrate:dev .
```

- Alle Tests gruen
- Kover 90%+ in allen Modulen
- `consumer-read-probe` baut gegen `ports-read`, nicht gegen Aggregator
- `adapters:driven:formats` baut mit `api(ports-read)` + `api(ports-write)`
- Alle anderen Consumer bleiben auf Aggregator ohne Aenderung

---

## 7. Risiken

### 7.1 Dateien mit gemischten Read-/Write-Typen

Manche Dateien (z.B. `DdlGenerator.kt`) definieren sowohl
`DdlStatement` (read-nah) als auch `TransformationNote`/`SkippedObject`
(diagnostisch). Diese bleiben zusammen in ports-read, weil sie
fachlich zum DDL-Generierungspfad gehoeren.

### 7.2 Streaming-Typen sind schwer zuzuordnen

`ExportOutput`, `ImportInput`, `ExportResult`, `ImportResult` sind
gemischt. Sie wandern nach ports-write, weil sie primaer vom
Streaming-Adapter (write-path) konsumiert werden.

### 7.3 formats-Split ist separates Thema

`adapters:driven:formats` exponiert weiterhin Writer-Klassen oeffentlich.
G-2 loest den Port-Modulschnitt (hexagon:ports → 3 Teilmodule). Der
Format-Adapter-Split (formats → formats-read + formats-write) ist ein
eigenstaendiges Thema, das in der 1.0.0-Publish-Vorbereitung adressiert
werden muss — G-2 ist dafuer keine Voraussetzung, aber auch kein Ersatz.
Die Probe verifiziert den Port-Schnitt, nicht den Format-Adapter-Schnitt.
