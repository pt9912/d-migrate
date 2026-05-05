# Spec: Kontinuierliche Shadow-Migration

Status: Draft  
Zielversion: 1.1 Design, 1.2 Spike, 1.3 Experimental, 2.0 Stable  
Datei: `spec/shadow-migration.md`  
Kategorie: Optional Execution Backend / Data Migration Workflow

---

## 1. Kontext

d-migrate unterstützt Schema-Migration, DDL-Generierung, Reverse Engineering, Datenexport, Datenimport, Transfer, Profiling und Job-/Artefakt-basierte Workflows.

Für große produktive Datenbanken reicht ein klassischer Ablauf oft nicht aus:

```text
export -> import -> validate -> cutover
```

Das Downtime-Fenster kann zu groß werden. Außerdem ändern sich Daten während der Migration weiter.

Kontinuierliche Shadow-Migration löst dieses Problem durch einen zweiphasigen Ansatz:

1. Initialer Snapshot der Quelldaten in die Zielumgebung.
2. Kontinuierliche Replikation laufender Änderungen per CDC oder gleichwertigem Änderungsstrom.

Die Ziel-Datenbank läuft parallel als Shadow-System. Erst wenn Datenbestand, Schema, Latenz, Validierung und Anwendungstests passen, erfolgt der Cutover.

---

## 2. Ziel

Diese Spec definiert einen Shadow-Migration-Workflow für d-migrate.

d-migrate soll dabei nicht selbst zu einem Streaming-Framework werden. d-migrate bleibt Orchestrator, Planer, Validator und Artefaktverwalter.

Ein optionales Execution Backend, zum Beispiel Apache Flink oder eine Flink-CDC-Pipeline, kann Snapshot und CDC-Verarbeitung ausführen.

Ziele:

1. Initialen Snapshot von Quelltabellen in Zieltabellen übertragen.
2. Laufende Änderungen kontinuierlich ins Ziel replizieren.
3. Fortschritt, Lag, Fehler und Validierungsstatus als Jobs und Artefakte sichtbar machen.
4. Cutover-Readiness deterministisch bewerten.
5. Checkpoint-/Resume-Fähigkeit über ein Execution Backend unterstützen.
6. Mapping- und Schema-Versionen eindeutig fixieren.
7. Fehlerhafte Events nachvollziehbar in Dead-Letter-Artefakten ablegen.
8. Privacy-, Secrets- und Audit-Anforderungen berücksichtigen.
9. Lokale d-migrate-Funktionen nicht mit Flink oder CDC-Abhängigkeiten belasten.

---

## 3. Nicht-Ziele

Shadow-Migration ist kein Ersatz für:

- DDL-Generierung.
- Schema-Design.
- Schema-Compare.
- Exakte Migrationsvalidierung.
- Fachliche Cutover-Entscheidung ohne menschliche Freigabe.
- Backup-/Restore-Strategien.
- Vollständige Datenreplikationsplattform.
- Generische Event-Streaming-Plattform.
- Automatische Konfliktlösung bei aktiven Writes auf Source und Target.
- Automatische App-Cutover-Orchestrierung ohne externe Freigabe.

Shadow-Migration darf nicht behaupten:

```text
Flink = automatisch exactly-once End-to-End
```

Korrekt ist:

```text
Ein Execution Backend kann checkpointed processing unterstützen.
End-to-End-Garantien hängen von Source, Sink, Commit-Protokoll,
Idempotenz, Primary Keys und Transaktionsmodell ab.
```

---

## 4. Begriffe

### 4.1 Shadow-Migration

Ein Migrationsverfahren, bei dem das Zielsystem parallel zum produktiven Quellsystem aufgebaut und kontinuierlich synchron gehalten wird, bis ein Cutover möglich ist.

### 4.2 Source

Produktive oder migrationsrelevante Quelldatenbank.

### 4.3 Target

Zieldatenbank oder Shadow-Datenbank.

### 4.4 Snapshot

Initiale Kopie des Quellbestands zu einem konsistenten oder hinreichend definierten Startpunkt.

### 4.5 CDC

Change Data Capture. Änderungsstrom aus Inserts, Updates, Deletes und optional DDL-Events.

### 4.6 Cutover

Umschalten der Anwendung oder des Datenzugriffs von Source auf Target.

### 4.7 Drain

Kontrolliertes Auslaufen des Änderungsstroms vor oder während des Cutovers.

### 4.8 Lag

Zeitliche oder offsetbasierte Verzögerung zwischen Source-Änderung und Anwendung im Target.

### 4.9 Checkpoint

Persistierter Fortschritt eines Shadow-Migration-Jobs, der Resume nach Fehlern ermöglicht.

### 4.10 Savepoint

Explizit ausgelöster, stabiler Zustand eines Execution Backends, der für kontrollierte Stop-/Upgrade-Szenarien verwendet werden kann.

### 4.11 Dead Letter

Nicht verarbeitbares Event oder nicht anwendbarer Datensatz, der mit Kontextinformationen als Artefakt gespeichert wird.

---

## 5. Architektur

### 5.1 Rollenverteilung

```text
d-migrate:
  Schema verstehen
  DDL erzeugen
  Mapping prüfen
  Shadow-Migration planen
  Jobs starten/stoppen
  Status normalisieren
  Artefakte verwalten
  Validierung ausführen
  Cutover-Readiness bewerten

Execution Backend:
  Snapshot lesen
  CDC lesen
  Transformationen ausführen
  Backpressure handhaben
  Checkpoints schreiben
  Ziel-DB aktualisieren
  technische Jobmetriken liefern

Target DB:
  Shadow-Datenbestand halten
  Upserts/Deletes anwenden
  Constraints und Indizes bereitstellen

Artifact Store:
  Pläne, Reports, Dead Letters, Checkpoints, Logs, Validierungen halten
```

### 5.2 Ziel-Modulplatzierung

```text
hexagon:shadow
  ShadowMigrationJob
  ShadowMigrationPlan
  ShadowStatus
  ShadowReadiness
  ShadowValidation
  ShadowPorts

hexagon:jobs
  JobContract
  JobStatus
  ArtifactContract

hexagon:core
  Schema-Modell
  Mapping-Modell
  Type Mapping

hexagon:profiling
  optionale Validierungsprofile

adapters:driven:execution-local
  optionaler lokaler Runner für kleine Spike-/Testfälle

adapters:driven:execution-flink
  submit/cancel/status/savepoint für Flink Jobs

adapters:driven:execution-flink-cdc-pipeline
  generiert und submitted Flink-CDC-Pipeline-YAML

adapters:driven:flink-shadow-job
  eigentlicher Flink Job für Snapshot + CDC + Sink

adapters:driven:driver-*-profiling
  Validierungszugriff auf Source/Target

adapters:driving:cli
  shadow start/status/validate/readiness/stop

adapters:driving:rest
  Shadow-Migration Job API

adapters:driving:grpc
  Shadow-Migration Service

adapters:driving:mcp
  Shadow-Migration Tools und Ressourcen
```

### 5.3 Übergang zur aktuellen Repo-Struktur

Die Modulnamen in 5.2 beschreiben das Zielbild.

Im aktuellen Repository existieren einige Zielmodule noch nicht. Bis sie angelegt werden, gilt folgende Zuordnung:

| Zielbereich | Aktuelle Platzierung |
| --- | --- |
| `hexagon:jobs` | bestehende Job-Contracts in `hexagon:core`, Job-Orchestrierung in `hexagon:application`, Ports in `hexagon:ports-*` |
| `hexagon:shadow` | neues Modul, sobald Shadow-Contracts implementiert werden |
| `adapters:driving:rest` | neues Modul, sobald REST als eigener Driving Adapter eingeführt wird |
| `adapters:driving:grpc` | neues Modul, sobald gRPC als eigener Driving Adapter eingeführt wird |
| `adapters:driving:mcp` | bestehendes Modul für MCP-Tools und Ressourcen |

Die erste Implementierung darf bestehende Job- und Artefakt-Contracts wiederverwenden, muss aber Shadow-spezifische Domain-Status, Readiness und Artefakte klar vom generischen Job-Lifecycle trennen.

### 5.4 Dependency-Regeln

`hexagon:core` darf nicht abhängig sein von:

```text
Apache Flink
Flink CDC
konkreten CDC-Connectoren
execution-flink
```

`hexagon:shadow` darf abhängig sein von:

```text
hexagon:core
hexagon:jobs
hexagon:profiling contracts, falls benötigt
```

`hexagon:shadow` darf nicht abhängig sein von:

```text
Apache Flink
konkreten Datenbanktreibern
konkreten CDC-Connectoren
```

`adapters:driven:execution-flink` darf abhängig sein von:

```text
hexagon:shadow
hexagon:jobs
Flink Client APIs
```

`adapters:driven:execution-flink-cdc-pipeline` darf abhängig sein von:

```text
hexagon:shadow
hexagon:jobs
Flink CDC Pipeline CLI/API
YAML Serialisierung
```

`adapters:driven:flink-shadow-job` darf abhängig sein von:

```text
Flink Runtime APIs
Flink CDC Connectoren
d-migrate Mapping Runtime
d-migrate Shadow Job Contract DTOs
```

---

## 6. Execution Backend

### 6.1 ExecutionBackendPort

```kotlin
interface ShadowExecutionBackendPort {
    fun start(request: StartShadowExecutionRequest): ShadowExecutionAccepted
    fun status(executionId: String): ShadowExecutionStatus
    fun cancel(request: CancelShadowExecutionRequest): ShadowExecutionStatus
    fun drain(request: DrainShadowExecutionRequest): ShadowExecutionStatus
    fun triggerCheckpoint(request: TriggerCheckpointRequest): CheckpointRef
    fun triggerSavepoint(request: TriggerSavepointRequest): SavepointRef
}
```

### 6.2 Start Request

```kotlin
data class StartShadowExecutionRequest(
    val jobId: String,
    val plan: ShadowMigrationPlan,
    val artifactBaseUri: String,
    val checkpointBaseUri: String?,
    val executionOptions: ShadowExecutionOptions
)
```

### 6.3 Execution Options

```kotlin
data class ShadowExecutionOptions(
    val backend: ExecutionBackend,
    val parallelism: Int? = null,
    val checkpointIntervalMs: Long? = null,
    val checkpointTimeoutMs: Long? = null,
    val maxRestartAttempts: Int? = null,
    val restartDelayMs: Long? = null
)
```

```kotlin
enum class ExecutionBackend {
    LOCAL,
    FLINK,
    FLINK_CDC_PIPELINE
}
```

### 6.4 Flink als optionales Backend

Flink ist kein Pflichtbestandteil von d-migrate.

Flink darf nur über ein optionales Backend eingebunden werden:

```text
adapters:driven:execution-flink
```

Das Flink Backend ist zuständig für:

```text
Job-Submission
Job-Status
Cancel
Drain
Checkpoint
Savepoint
Mapping von Flink-Status auf d-migrate-Status
Artefaktreferenzen
```

d-migrate darf Flink-spezifische Details nicht in Core-Modelle leaken, außer als optionale Backend-Metadaten.

### 6.5 Flink CDC 3.6 Pipeline Backend

Für Shadow-Migration kann d-migrate ein optionales `FLINK_CDC_PIPELINE` Backend unterstützen.

Dieses Backend erzeugt aus einem `ShadowMigrationPlan` eine Flink-CDC-3.6-kompatible Pipeline-YAML und submitted sie an eine Flink-CDC/Flink-Umgebung.

d-migrate bleibt dabei Orchestrator und Vertragsinstanz.

Flink CDC übernimmt, abhängig von Source, Sink und Connector-Fähigkeiten:

```text
Snapshot-Scan
Wechsel zu CDC
Streaming-Ausführung
Routing
einfache Transformationen
optionale Schema-Evolution
Pipeline-Ausführung auf Flink
```

Die Pipeline-YAML muss mindestens diese Top-Level-Blöcke enthalten:

```text
source
sink
pipeline
```

Optional kann d-migrate diese Blöcke generieren:

```text
route
transform
```

### 6.6 Flink CDC Pipeline Artefakt

Ein Shadow-Migration-Job mit `FLINK_CDC_PIPELINE` muss ein zusätzliches immutable Artefakt erzeugen:

```text
flink-cdc-pipeline.yaml
```

Das Artefakt muss mit SHA-256 referenziert werden.

```yaml
artifactId: artifact_flink_cdc_pipeline_001
kind: flink-cdc-pipeline
jobId: shadow-prod-001
path: flink-cdc-pipeline.yaml
contentType: application/yaml
sha256: "..."
```

### 6.7 Mapping von d-migrate zu Flink CDC

| d-migrate Shadow Plan | Flink CDC Pipeline |
| --- | --- |
| `sourceConnectionId` | `source` |
| `targetConnectionId` | `sink` |
| `tables` | `source.tables` |
| `ShadowTablePlan.sourceTable` | `route.source-table` |
| `ShadowTablePlan.targetTable` | `route.sink-table` |
| `ShadowColumnMapping` | `transform.projection` |
| `ShadowFilter` | `transform.filter` |
| `ShadowExecutionOptions.parallelism` | `pipeline.parallelism` |
| `SchemaDriftPolicy` | `pipeline.schema.change.behavior` |
| Job name | `pipeline.name` |

### 6.8 Beispiel: generierte Flink CDC Pipeline

```yaml
source:
  type: postgres
  name: legacy-postgres
  hostname: "${DMIGRATE_SOURCE_HOST}"
  port: 5432
  username: "${DMIGRATE_SOURCE_USER}"
  password: "${DMIGRATE_SOURCE_PASSWORD}"
  tables: app.public.orders
  decoding.plugin.name: pgoutput
  slot.name: dmigrate_shadow_orders
  table-id.include-database: true

sink:
  type: postgres
  name: modern-postgres
  hostname: "${DMIGRATE_TARGET_HOST}"
  port: 5432
  username: "${DMIGRATE_TARGET_USER}"
  password: "${DMIGRATE_TARGET_PASSWORD}"
  database-name: app_modern

route:
  - source-table: app.public.orders
    sink-table: app_modern.public.orders
    description: "d-migrate shadow route for orders"

transform:
  - source-table: app.public.orders
    projection: id, customer_id, amount, status, updated_at
    filter: id IS NOT NULL
    description: "d-migrate generated projection and filter"

pipeline:
  name: d-migrate-shadow-shadow-prod-001
  parallelism: 4
  execution.runtime-mode: STREAMING
  schema.change.behavior: exception
  operator.uid.prefix: dmigrate-shadow-prod-001
```

### 6.9 Secrets-Regel für Flink CDC

Die generierte Pipeline-Datei darf keine Klartext-Secrets enthalten.

Nicht erlaubt:

```yaml
password: "super-secret"
```

Erlaubt:

```yaml
password: "${DMIGRATE_SOURCE_PASSWORD}"
```

Alternativ darf d-migrate backend-spezifische Secret-Referenzen verwenden, wenn diese nicht in Artefakten, Logs oder MCP-Antworten als Klartext erscheinen.

### 6.10 Schema Drift Mapping für Flink CDC

| d-migrate | Flink CDC `schema.change.behavior` | Bemerkung |
| --- | --- | --- |
| `FAIL_FAST` | `exception` | Default für produktive Shadow-Migration |
| `AUTO_EVOLVE_ADDITIVE_ONLY` | kein direktes 1:1 Mapping | d-migrate muss vorher prüfen |
| `RECORD_AND_WARN` | `ignore` oder separate Drift-Erfassung | nur wenn bewusst erlaubt |
| experimentell | `try_evolve` | kann Datenverlust- oder Casting-Risiken haben |
| experimentell | `lenient` | nicht Default, da veränderte Zielstruktur entstehen kann |

Default für d-migrate Shadow-Migration:

```yaml
pipeline:
  schema.change.behavior: exception
```

Begründung:

Shadow-Migration darf Schemaänderungen nicht stillschweigend übernehmen, solange Cutover-Readiness und Mapping-Fingerprints stabil bleiben müssen.

### 6.11 Postgres-Einschränkung für Flink CDC

Für Postgres als Flink-CDC-Pipeline-Source gilt:

```text
Postgres CDC kann Snapshot- und inkrementelle Daten lesen.
Postgres CDC Pipeline Source unterstützt aktuell keine Synchronisierung von Tabellenstrukturänderungen.
```

Wenn d-migrate Route- oder Transform-Regeln mit dreiteiligen Table IDs (`database.schema.table`) generiert, muss für Postgres `table-id.include-database: true` gesetzt werden.

Ohne `table-id.include-database: true` muss d-migrate die von Flink CDC verwendete Table-ID-Form ohne Database-Anteil (`schema.table`) in `route.source-table` und `transform.source-table` verwenden.

Konsequenz für d-migrate:

```text
SchemaDriftPolicy AUTO_EVOLVE_ADDITIVE_ONLY ist für Postgres CDC nicht automatisch erlaubt.
Default bleibt FAIL_FAST.
Schemaänderungen müssen durch d-migrate erkannt und als Drift-Artefakt behandelt werden.
```

### 6.12 Route-Regeln

Flink CDC unterstützt Routing von Source Tables auf Sink Tables.

d-migrate darf daraus einfache 1:1-Routen generieren:

```yaml
route:
  - source-table: app.public.orders
    sink-table: app_modern.public.orders
```

Mehrere Source Tables auf eine Sink Table sind nur erlaubt, wenn der Shadow Plan dies explizit erlaubt.

```yaml
route:
  - source-table: app.public.orders_2024
    sink-table: app_modern.public.orders
  - source-table: app.public.orders_2025
    sink-table: app_modern.public.orders
```

Default:

```text
1 source table -> 1 target table
```

### 6.13 Transform-Regeln

Flink CDC Transform unterstützt Projektion, Filter und computed columns.

d-migrate darf nur deterministische Transformationen in Flink CDC Pipeline YAML generieren.

Erlaubt:

```yaml
transform:
  - source-table: app.public.orders
    projection: id, amount, status
    filter: id IS NOT NULL
```

Nicht erlaubt ohne explizite Freigabe:

```yaml
transform:
  - source-table: app.public.orders
    projection: random() AS migration_id
```

### 6.14 Type Mapping für Flink CDC

Flink CDC besitzt eigene interne und externe Typen.

d-migrate muss deshalb eine explizite Mapping-Schicht besitzen:

```text
d-migrate logical type
  -> source db type
  -> Flink CDC DataType
  -> target db type
```

Für jedes unterstützte Source/Target-Paar muss dokumentiert werden:

```text
lossless
widening
narrowing
unsupported
requires explicit cast
```

### 6.15 Operator UID

Für Flink CDC Pipelines soll d-migrate `operator.uid.prefix` setzen.

```yaml
pipeline:
  operator.uid.prefix: dmigrate-shadow-prod-001
```

Begründung:

Stabile Operator-UIDs helfen bei zustandsbehafteten Upgrades, Troubleshooting und Diagnose in der Flink UI.

---

## 7. ShadowMigrationJob

### 7.1 Job-Modell

```kotlin
data class ShadowMigrationJob(
    val jobId: String,
    val sourceConnectionId: String,
    val targetConnectionId: String,
    val planRef: ArtifactRef,
    val mappingRef: ArtifactRef,
    val executionBackend: ExecutionBackend,
    val status: ShadowMigrationStatus,
    val createdBy: String?,
    val artifacts: List<ArtifactRef> = emptyList(),
    val metrics: ShadowMigrationMetrics? = null,
    val readiness: ShadowReadiness? = null
)
```

### 7.2 Status

```kotlin
enum class ShadowMigrationStatus {
    PLANNED,
    INITIALIZING,
    SNAPSHOT_RUNNING,
    SNAPSHOT_COMPLETED,
    CDC_STARTING,
    CDC_RUNNING,
    CATCHING_UP,
    IN_SYNC,
    DEGRADED,
    VALIDATING,
    CUTOVER_READY,
    DRAINING,
    DRAINED,
    STOPPING,
    STOPPED,
    FAILED,
    CANCELLED
}
```

### 7.3 Status-Semantik

| Status               | Bedeutung                                              |
| -------------------- | ------------------------------------------------------ |
| `PLANNED`            | Plan wurde erzeugt, aber noch nicht gestartet          |
| `INITIALIZING`       | Execution Backend bereitet Job vor                     |
| `SNAPSHOT_RUNNING`   | Initialer Bestand wird übertragen                      |
| `SNAPSHOT_COMPLETED` | Initialer Snapshot ist abgeschlossen                   |
| `CDC_STARTING`       | CDC-Stream wird initialisiert                          |
| `CDC_RUNNING`        | Laufende Änderungen werden verarbeitet                 |
| `CATCHING_UP`        | CDC läuft, Lag ist noch oberhalb des Zielwerts         |
| `IN_SYNC`            | Lag liegt innerhalb des Zielwerts                      |
| `DEGRADED`           | Job läuft, aber mit Warnungen oder erhöhter Fehlerrate |
| `VALIDATING`         | d-migrate führt Validierung aus                        |
| `CUTOVER_READY`      | Readiness-Kriterien sind erfüllt                       |
| `DRAINING`           | Job verarbeitet restliche Events vor Stop/Cutover      |
| `DRAINED`            | Drain abgeschlossen                                    |
| `STOPPING`           | Stop wurde angefordert                                 |
| `STOPPED`            | Job wurde kontrolliert gestoppt                        |
| `FAILED`             | Job ist fehlgeschlagen                                 |
| `CANCELLED`          | Job wurde abgebrochen                                  |

### 7.4 Generischer JobStatus vs. ShadowMigrationStatus

`ShadowMigrationStatus` ist ein Domain-Status und ersetzt nicht den bestehenden generischen `JobStatus`.

Der bestehende `JobStatus` bleibt der grobe technische Job-Lifecycle:

```text
QUEUED
RUNNING
SUCCEEDED
FAILED
CANCELLED
```

Shadow-spezifische Phasen werden in `ShadowMigrationJob.status` und im `shadow-status.json` Artefakt gespeichert.

Falls ein bestehender `ManagedJob` verwendet wird, darf `JobProgress.phase` den aktuellen `ShadowMigrationStatus` als String spiegeln. Die Quelle der Wahrheit bleibt aber der Shadow-Domain-Status.

Mapping:

| `ShadowMigrationStatus` | generischer `JobStatus` |
| --- | --- |
| `PLANNED` | `QUEUED` |
| `INITIALIZING` bis `STOPPING` | `RUNNING` |
| `STOPPED` | `SUCCEEDED` |
| `FAILED` | `FAILED` |
| `CANCELLED` | `CANCELLED` |

`DRAINED` ist nur dann `SUCCEEDED`, wenn der Backend-Job danach kontrolliert beendet wurde. Läuft der Shadow-Job nach dem Drain weiter oder wartet auf Cutover-Aktion, bleibt der generische `JobStatus` `RUNNING`.

---

## 8. ShadowMigrationPlan

### 8.1 Plan-Modell

```kotlin
data class ShadowMigrationPlan(
    val planVersion: String,
    val sourceConnectionId: String,
    val targetConnectionId: String,
    val tables: List<ShadowTablePlan>,
    val mappingRef: ArtifactRef,
    val schemaFingerprint: SchemaFingerprint,
    val options: ShadowMigrationOptions
)
```

### 8.2 Table Plan

```kotlin
data class ShadowTablePlan(
    val sourceTable: TableRef,
    val targetTable: TableRef,
    val primaryKey: List<String>,
    val mode: ShadowTableMode,
    val columnMappings: List<ShadowColumnMapping>,
    val filters: List<ShadowFilter> = emptyList(),
    val ordering: ShadowOrdering? = null,
    val partitioning: ShadowPartitioning? = null,
    val ddlPolicy: ShadowSpecialOperationPolicy = ShadowSpecialOperationPolicy.FAIL_FAST,
    val truncatePolicy: ShadowSpecialOperationPolicy = ShadowSpecialOperationPolicy.REJECT,
    val allowExplicitTruncateApply: Boolean = false
)
```

```kotlin
enum class ShadowTableMode {
    SNAPSHOT_AND_CDC,
    SNAPSHOT_ONLY,
    CDC_ONLY,
    APPEND_ONLY
}
```

```kotlin
enum class ShadowSpecialOperationPolicy {
    FAIL_FAST,
    REJECT,
    RECORD_AND_WARN,
    DEAD_LETTER,
    IGNORE_EXPLICIT,
    APPLY_EXPLICIT
}
```

### 8.3 Column Mapping

```kotlin
data class ShadowColumnMapping(
    val sourceColumn: String?,
    val targetColumn: String,
    val expression: String? = null,
    val defaultValue: String? = null,
    val transform: String? = null,
    val nullable: Boolean
)
```

### 8.4 Schema Fingerprint

```kotlin
data class SchemaFingerprint(
    val sourceSchemaSha256: String,
    val targetSchemaSha256: String,
    val mappingSha256: String
)
```

### 8.5 Shadow Migration Options

```kotlin
data class ShadowMigrationOptions(
    val schemaDriftPolicy: SchemaDriftPolicy = SchemaDriftPolicy.FAIL_FAST,
    val deadLetterPolicy: DeadLetterPolicy = DeadLetterPolicy.FAIL_FAST,
    val maxLagMs: Long? = null,
    val requiredStableDuration: Duration? = null,
    val allowManySourceTablesToOneTarget: Boolean = false,
    val allowExperimentalFlinkCdcSchemaEvolution: Boolean = false
)
```

`ShadowExecutionOptions` beschreibt laufzeitnahe Backend-Optionen.

`ShadowMigrationOptions` beschreibt fachliche Plan- und Readiness-Policy.

REST, gRPC, CLI und MCP müssen diese Trennung beibehalten und dürfen Policy-Felder nicht backend-spezifisch anders interpretieren.

### 8.6 Plan-Artefakt

Jeder Shadow-Migration-Job referenziert einen immutable Plan.

```yaml
planVersion: "1"
sourceConnectionId: "conn_legacy"
targetConnectionId: "conn_modern"
schemaFingerprint:
  sourceSchemaSha256: "..."
  targetSchemaSha256: "..."
  mappingSha256: "..."
tables:
  - sourceTable:
      schema: public
      name: orders
    targetTable:
      schema: public
      name: orders
    primaryKey:
      - id
    mode: SNAPSHOT_AND_CDC
    columnMappings:
      - sourceColumn: id
        targetColumn: id
        nullable: false
      - sourceColumn: amount
        targetColumn: amount
        nullable: false
```

---

## 9. Source- und Target-Anforderungen

### 9.1 Primary Keys

Für `SNAPSHOT_AND_CDC` und `CDC_ONLY` müssen stabile Primary Keys oder eindeutige Schlüssel vorhanden sein.

Ohne Schlüssel wird der Plan abgelehnt.

```yaml
error:
  code: SHADOW_PRIMARY_KEY_REQUIRED
  message: "Shadow migration requires a stable primary key for table orders."
```

Ausnahme:

```text
APPEND_ONLY
```

`APPEND_ONLY` darf ohne Primary Key erlaubt werden, wenn explizit konfiguriert.

### 9.2 Delete-Unterstützung

Tabellen im Modus `SNAPSHOT_AND_CDC` müssen Delete-Events korrekt abbilden können.

Erlaubte Strategien:

```text
HARD_DELETE
SOFT_DELETE
IGNORE_DELETE
REJECT_DELETE
```

Default:

```text
HARD_DELETE
```

### 9.3 Upsert-Strategie

Target-Writes müssen idempotent sein.

Default-Strategie:

```text
UPSERT_BY_PRIMARY_KEY
```

Beispiel:

```yaml
sink:
  writeMode: UPSERT_BY_PRIMARY_KEY
  deleteMode: HARD_DELETE
```

### 9.4 Target Writes

Target Writes müssen mindestens eine dieser Garantien bieten:

```text
idempotent upsert
transactional batch commit
deduplication by event id
deduplication by primary key + source offset
```

Wenn keine Strategie verfügbar ist, darf Shadow-Migration nicht gestartet werden.

---

## 10. Event-Modell

### 10.1 ShadowChangeEvent

```kotlin
data class ShadowChangeEvent(
    val eventId: String,
    val source: TableRef,
    val operation: ShadowOperation,
    val primaryKey: Map<String, ProfileValue>,
    val before: Map<String, ProfileValue?>?,
    val after: Map<String, ProfileValue?>?,
    val sourceOffset: SourceOffset,
    val eventTime: Instant?,
    val ingestionTime: Instant?
)
```

```kotlin
enum class ShadowOperation {
    INSERT,
    UPDATE,
    DELETE,
    SNAPSHOT_READ,
    TRUNCATE,
    DDL,
    HEARTBEAT
}
```

### 10.2 Sonderoperationen

`DDL` und `TRUNCATE` dürfen nicht stillschweigend auf das Target angewendet werden.

Default:

```text
DDL -> FAIL_FAST und Schema-Drift-Artefakt
TRUNCATE -> REJECT und Dead-Letter- oder Failure-Artefakt
```

Erlaubte Policies:

```text
FAIL_FAST
REJECT
RECORD_AND_WARN
DEAD_LETTER
IGNORE_EXPLICIT
APPLY_EXPLICIT
```

Diese Policies werden pro Tabelle in `ShadowTablePlan.ddlPolicy` und `ShadowTablePlan.truncatePolicy` gespeichert.

`APPLY_EXPLICIT` ist für `TRUNCATE` nur erlaubt, wenn der Shadow Plan die betroffene Tabelle explizit dafür freigibt.

`APPLY_EXPLICIT` für `DDL` ist im produktiven Default nicht erlaubt und darf nur über connector-spezifische, dokumentierte Schema-Evolution-Regeln aktiviert werden.

Für Postgres Flink CDC Pipeline Source bleibt DDL-Synchronisierung nicht unterstützt; DDL muss durch d-migrate als Drift erkannt und gemäß `SchemaDriftPolicy` behandelt werden.

### 10.3 Source Offset

```kotlin
data class SourceOffset(
    val connector: String,
    val value: Map<String, String>
)
```

### 10.4 Event-ID

Die Event-ID muss stabil sein.

Die Eingabe für den Hash muss kanonisch serialisiert werden.

Kanonische Form:

```json
{
  "sourceConnectionId": "conn_legacy",
  "table": {
    "schema": "public",
    "name": "orders"
  },
  "operation": "UPDATE",
  "primaryKey": {
    "id": {
      "type": "INT64",
      "value": "123"
    }
  },
  "sourceOffset": {
    "connector": "postgres-cdc",
    "value": {
      "lsn": "0/16B6C50"
    }
  }
}
```

Serialisierungsregeln:

```text
UTF-8 JSON
Objekt-Keys lexikografisch sortiert
keine optionalen Felder mit implizitem Default
Nullwerte explizit als JSON null
ProfileValue immer mit type und value serialisieren
Binärwerte base64url ohne Padding
Timestamps als ISO-8601 UTC mit Nanosekunden, falls vorhanden
keine Whitespace-Formatierung
```

Hash:

```text
eventId = sha256(canonical-json-bytes)
```

### 10.5 Event Ordering

Ordering muss mindestens pro Tabelle und Primary Key stabil sein.

```text
required:
  per primary key ordering

not required:
  global total ordering across all tables
```

Wenn ein Backend diese Garantie nicht liefern kann, muss der Plan abgelehnt werden oder eine explizite schwächere Semantik dokumentieren.

### 10.6 Snapshot/CDC-Handoff

Der Übergang vom initialen Snapshot zur CDC-Verarbeitung muss nachvollziehbar sein.

Für jedes Backend muss d-migrate mindestens als Artefakt festhalten:

```text
snapshotStartOffset, falls verfügbar
snapshotEndOffset oder äquivalente Watermark
cdcStartOffset
handoffStrategy
duplicateHandling
```

Für `FLINK_CDC_PIPELINE` darf d-migrate die Handoff-Semantik des Flink-CDC-Connectors verwenden, muss diese aber im Shadow-Status und in Artefakten referenzieren.

Produktive Readiness darf nicht `READY` werden, wenn das Backend keine definierte Semantik gegen Datenlücken zwischen Snapshot und CDC dokumentiert.

---

## 11. Mapping und Transformation

### 11.1 Mapping-Version

Jede Shadow-Migration referenziert eine immutable Mapping-Version.

```yaml
mapping:
  artifactId: artifact_mapping_v12
  sha256: "..."
```

Während eines laufenden Shadow-Jobs darf das Mapping nicht stillschweigend geändert werden.

### 11.2 Mapping-Änderungen

Bei Mapping-Änderungen gibt es nur explizite Optionen:

```text
restart-from-snapshot
resume-with-compatible-mapping
start-new-shadow-job
```

Default:

```text
start-new-shadow-job
```

### 11.3 Transformationen

Transformationen müssen deterministisch sein.

Nicht erlaubt im stabilen Mapping:

```text
now()
random()
non-deterministic UUID generation
external HTTP calls
timezone-dependent parsing without explicit timezone
```

Erlaubt:

```text
pure expressions
type casts
constant defaults
stable lookup tables with pinned version
```

### 11.4 Fehlerhafte Transformation

Fehlerhafte Transformationen erzeugen abhängig von Policy:

```text
fail-fast
dead-letter
skip-with-warning
```

Default:

```text
fail-fast
```

Für produktive Shadow-Migration ist `skip-with-warning` nicht empfohlen.

---

## 12. Schema Drift

### 12.1 Drift-Arten

```text
SOURCE_ADDED_COLUMN
SOURCE_DROPPED_COLUMN
SOURCE_CHANGED_TYPE
SOURCE_CHANGED_NULLABILITY
SOURCE_ADDED_TABLE
TARGET_CHANGED_COLUMN
TARGET_DROPPED_COLUMN
TARGET_CHANGED_TYPE
MAPPING_MISMATCH
```

### 12.2 Drift Policy

```kotlin
enum class SchemaDriftPolicy {
    FAIL_FAST,
    RECORD_AND_WARN,
    AUTO_EVOLVE_ADDITIVE_ONLY
}
```

Default:

```text
FAIL_FAST
```

### 12.3 Auto-Evolve

`AUTO_EVOLVE_ADDITIVE_ONLY` darf nur additive Änderungen anwenden.

Erlaubt:

```text
neue nullable source column ignorieren
neue target column mit default befüllen
neue nullable target column akzeptieren
```

Nicht erlaubt:

```text
type narrowing
drop column
rename inference
nullable -> not null ohne default
primary key change
```

### 12.4 Drift-Artefakt

```yaml
kind: schema-drift
jobId: job_shadow_001
table: orders
drift:
  type: SOURCE_ADDED_COLUMN
  column: coupon_code
policy: RECORD_AND_WARN
action: ignored
```

---

## 13. Checkpoint und Resume

### 13.1 Checkpoint Contract

Ein Shadow-Migration-Job muss seinen Fortschritt rekonstruierbar machen.

```kotlin
data class CheckpointRef(
    val checkpointId: String,
    val backend: ExecutionBackend,
    val uri: String,
    val sourceOffsets: Map<String, SourceOffset>,
    val createdAt: Instant?,
    val sha256: String?
)
```

### 13.2 Resume

Resume ist nur erlaubt, wenn folgende Fingerprints übereinstimmen:

```text
sourceConnectionId
targetConnectionId
mappingSha256
sourceSchemaSha256, falls strict
targetSchemaSha256, falls strict
executionBackend
checkpoint compatibility version
```

### 13.3 Savepoint

Savepoints sind für kontrollierte Stop-/Upgrade-Szenarien.

```bash
d-migrate shadow savepoint \
  --job shadow-prod-001
```

### 13.4 Checkpoint Storage

Checkpoint Storage muss explizit konfiguriert werden.

```yaml
shadow:
  checkpoint:
    uri: "s3://dmigrate-checkpoints/prod-shadow"
    intervalMs: 30000
    timeoutMs: 600000
```

Lokale Pfade sind nur für Entwicklung und Tests empfohlen.

---

## 14. Validierung

### 14.1 Validierungsarten

Shadow-Migration benötigt mehrstufige Validierung.

```text
lag validation
row count validation
primary key coverage
checksum validation
approximate profile validation
schema drift validation
dead-letter validation
application read validation, optional extern
```

### 14.2 Exakte Validierung

Exakte Validierung umfasst:

```text
row count
primary key count
partition checksums
failed events count
dead-letter events count
schema fingerprint
```

### 14.3 Approximative Validierung

Approximative Validierung kann nutzen:

```text
distinct estimates
quantiles
frequent items
distribution drift
```

Approximative Validierung ersetzt keine exakte Validierung.

### 14.4 Validation Result

```kotlin
data class ShadowValidationResult(
    val jobId: String,
    val status: ValidationStatus,
    val checks: List<ShadowValidationCheck>,
    val artifacts: List<ArtifactRef> = emptyList()
)
```

```kotlin
enum class ValidationStatus {
    PASS,
    WARNING,
    FAIL,
    NOT_RUN
}
```

```kotlin
data class ShadowValidationCheck(
    val name: String,
    val status: ValidationStatus,
    val severity: ValidationSeverity,
    val message: String,
    val table: TableRef? = null,
    val column: String? = null,
    val details: Map<String, String> = emptyMap()
)
```

### 14.5 Validation CLI

```bash
d-migrate shadow validate \
  --job shadow-prod-001 \
  --checks row-count,checksum,approx-profile,lag,dead-letter
```

---

## 15. Cutover Readiness

### 15.1 Readiness-Modell

```kotlin
data class ShadowReadiness(
    val jobId: String,
    val status: ReadinessStatus,
    val gates: List<ReadinessGate>,
    val recommendation: CutoverRecommendation
)
```

```kotlin
enum class ReadinessStatus {
    NOT_READY,
    READY_WITH_WARNINGS,
    READY,
    UNKNOWN
}
```

```kotlin
enum class CutoverRecommendation {
    DO_NOT_CUTOVER,
    REVIEW_WARNINGS,
    CUTOVER_ALLOWED
}
```

### 15.2 Readiness Gates

Pflicht-Gates:

```text
job status is IN_SYNC, CUTOVER_READY or DRAINED
source lag <= configured max lag
stable duration >= configured duration
failed events == 0
dead-letter events == 0
schema drift == none or accepted
row count validation == pass
checksum validation == pass, if configured
mapping fingerprint matches
target schema fingerprint matches
```

`CUTOVER_READY` darf nur gesetzt werden, wenn dieselben Gates erfüllt sind, die auch `ReadinessStatus.READY` erzeugen.

Alternativ darf eine Implementierung `CUTOVER_READY` weglassen und Cutover-Freigabe ausschließlich über `ShadowReadiness.status = READY` ausdrücken. In diesem Fall bleibt der Job-Status `IN_SYNC` bis Drain oder Stop.

### 15.3 Beispielkonfiguration

```yaml
shadow:
  readiness:
    maxLagMs: 5000
    requiredStableDuration: PT30M
    requireNoDeadLetters: true
    requireNoFailedEvents: true
    requireSchemaDriftFree: true
    validation:
      rowCount: required
      checksum: required
      approximateProfile: advisory
```

### 15.4 Readiness CLI

```bash
d-migrate shadow readiness \
  --job shadow-prod-001
```

### 15.5 Beispielausgabe

```yaml
jobId: shadow-prod-001
status: READY
recommendation: CUTOVER_ALLOWED
gates:
  - name: lag
    status: PASS
    message: "Lag is below 5000ms for PT30M."
  - name: deadLetters
    status: PASS
    message: "No dead-letter events."
  - name: rowCount
    status: PASS
    message: "Source and target row counts match."
  - name: checksum
    status: PASS
    message: "Partition checksums match."
```

---

## 16. Cutover und Drain

### 16.1 Cutover wird nicht automatisch durchgeführt

d-migrate darf Cutover-Readiness bewerten, aber Cutover nicht ohne explizite Nutzeraktion durchführen.

### 16.2 Drain

Drain verarbeitet ausstehende Events kontrolliert bis zu einem definierten Punkt.

Der Zielpunkt muss vor dem Drain eindeutig feststehen.

Empfohlene Semantik:

```text
1. Anwendung in Write-Freeze oder Maintenance Mode versetzen.
2. Letzten Source-Offset oder äquivalente Backend-Watermark erfassen.
3. Shadow-Job bis genau zu diesem Offset drainen.
4. Finalen Target-Apply-Offset als Artefakt speichern.
```

`DRAINED` darf nur gesetzt werden, wenn der Target-Apply-Offset den definierten Drain-Zielpunkt erreicht hat.

```bash
d-migrate shadow stop \
  --job shadow-prod-001 \
  --mode drain
```

### 16.3 Stop-Modi

```kotlin
enum class ShadowStopMode {
    CANCEL,
    DRAIN,
    SAVEPOINT_AND_STOP
}
```

Semantik:

```text
CANCEL:
  Job sofort abbrechen

DRAIN:
  offene Events bis zum definierten Offset verarbeiten

SAVEPOINT_AND_STOP:
  Savepoint erzeugen und kontrolliert stoppen
```

### 16.4 Cutover Ablauf

Empfohlener Ablauf:

```text
1. shadow readiness prüfen
2. Anwendung in Write-Freeze oder Maintenance Mode versetzen
3. shadow drain starten
4. letzte Validierung ausführen
5. Anwendung auf Target umschalten
6. Shadow-Job stoppen oder in Beobachtungsmodus versetzen
7. Post-Cutover-Validierung ausführen
```

---

## 17. Fehler- und Dead-Letter-Modell

### 17.1 Fehlerklassen

```kotlin
enum class ShadowErrorCode {
    SHADOW_PRIMARY_KEY_REQUIRED,
    SHADOW_SOURCE_CONNECT_FAILED,
    SHADOW_TARGET_CONNECT_FAILED,
    SHADOW_SCHEMA_DRIFT_DETECTED,
    SHADOW_MAPPING_INCOMPATIBLE,
    SHADOW_TRANSFORM_FAILED,
    SHADOW_SINK_WRITE_FAILED,
    SHADOW_CHECKPOINT_FAILED,
    SHADOW_RESUME_INCOMPATIBLE,
    SHADOW_LAG_THRESHOLD_EXCEEDED,
    SHADOW_DEAD_LETTER_LIMIT_EXCEEDED,
    SHADOW_EXECUTION_BACKEND_UNAVAILABLE,
    SHADOW_EXECUTION_BACKEND_FAILED
}
```

### 17.2 Dead Letter Event

```kotlin
data class DeadLetterEvent(
    val jobId: String,
    val table: TableRef,
    val operation: ShadowOperation,
    val primaryKey: Map<String, ProfileValue>?,
    val sourceOffset: SourceOffset?,
    val errorCode: ShadowErrorCode,
    val errorMessage: String,
    val payloadRef: ArtifactRef?,
    val redacted: Boolean
)
```

### 17.3 Dead Letter Artefakt

```yaml
kind: dead-letter
jobId: shadow-prod-001
table:
  schema: public
  name: orders
operation: UPDATE
primaryKey:
  id: "123"
sourceOffset:
  connector: postgres-cdc
  value:
    lsn: "0/16B6C50"
errorCode: SHADOW_TRANSFORM_FAILED
errorMessage: "Cannot cast value 'abc' to DECIMAL."
payloadRef:
  artifactId: artifact_deadletter_payload_001
redacted: true
```

### 17.4 Dead Letter Policy

```kotlin
enum class DeadLetterPolicy {
    FAIL_FAST,
    WRITE_AND_CONTINUE,
    WRITE_AND_DEGRADE
}
```

Default:

```text
FAIL_FAST
```

Für experimentelle Shadow-Migration kann `WRITE_AND_DEGRADE` erlaubt werden.

---

## 18. Metriken

### 18.1 ShadowMigrationMetrics

```kotlin
data class ShadowMigrationMetrics(
    val sourceLagMs: Long?,
    val lastSourceOffset: SourceOffset?,
    val lastCheckpointId: String?,
    val lastCheckpointAgeMs: Long?,
    val snapshotRowsRead: Long,
    val snapshotRowsWritten: Long,
    val cdcEventsRead: Long,
    val cdcEventsWritten: Long,
    val insertsApplied: Long,
    val updatesApplied: Long,
    val deletesApplied: Long,
    val failedEvents: Long,
    val deadLetterEvents: Long,
    val throughputRowsPerSecond: Double?
)
```

### 18.2 Beispiel

```yaml
shadow:
  status: CDC_RUNNING
  sourceLagMs: 842
  lastSourceOffset:
    connector: postgres-cdc
    value:
      lsn: "0/16B6C50"
  lastCheckpointId: chk-4382
  lastCheckpointAgeMs: 12000
  snapshotRowsRead: 12000000
  snapshotRowsWritten: 12000000
  cdcEventsRead: 93210
  cdcEventsWritten: 93210
  insertsApplied: 1200
  updatesApplied: 88422
  deletesApplied: 3588
  failedEvents: 0
  deadLetterEvents: 0
  throughputRowsPerSecond: 18200.5
```

---

## 19. CLI

### 19.1 Start

```bash
d-migrate shadow start \
  --plan-artifact artifact_shadow_plan_001 \
  --execution-backend flink-cdc-pipeline \
  --checkpoint-dir s3://dmigrate-checkpoints/prod-shadow \
  --artifact-dir s3://dmigrate-artifacts/prod-shadow
```

### 19.2 Status

```bash
d-migrate shadow status \
  --job shadow-prod-001
```

### 19.3 Validate

```bash
d-migrate shadow validate \
  --job shadow-prod-001 \
  --checks row-count,checksum,approx-profile,lag,dead-letter
```

### 19.4 Readiness

```bash
d-migrate shadow readiness \
  --job shadow-prod-001
```

### 19.5 Savepoint

```bash
d-migrate shadow savepoint \
  --job shadow-prod-001
```

### 19.6 Stop

```bash
d-migrate shadow stop \
  --job shadow-prod-001 \
  --mode drain
```

### 19.7 CLI-Optionen

```text
--source <connection-alias>
--target <connection-alias>
--source-connection-id <id>
--target-connection-id <id>
--mapping <file>
--tables <list>
--plan <file>
--plan-artifact <artifact-id>
--execution-backend local|flink|flink-cdc-pipeline
--checkpoint-dir <uri>
--artifact-dir <uri>
--parallelism <int>
--checkpoint-interval-ms <long>
--schema-drift-policy fail-fast|record-and-warn|auto-evolve-additive-only
--dead-letter-policy fail-fast|write-and-continue|write-and-degrade
--max-lag-ms <long>
--required-stable-duration <duration>
```

CLI darf `--source` und `--target` als nutzerfreundliche Aliase verwenden.

`shadow plan` darf `--source`, `--target`, `--mapping` und `--tables` verwenden.

`shadow start` verwendet `--plan` oder `--plan-artifact`; Source, Target, Mapping und Tabellen kommen aus dem immutable Plan.

API-Verträge referenzieren beim Start ein Plan-Artefakt. `sourceConnectionId` und `targetConnectionId` bleiben Felder des Plans.

---

## 20. REST API

### 20.1 Start

Asynchrone Shadow-Job-Starts benötigen einen Idempotency-Key.

```http
POST /api/v1/shadow-migrations
Idempotency-Key: 8f7f6b0e-9c5d-4f8d-98a2-5a0e2f7d9e11
Content-Type: application/json
```

```json
{
  "planArtifactId": "artifact_shadow_plan_001",
  "executionBackend": "FLINK_CDC_PIPELINE",
  "checkpointUri": "s3://dmigrate-checkpoints/prod-shadow",
  "artifactUri": "s3://dmigrate-artifacts/prod-shadow",
  "executionOptions": {
    "parallelism": 8,
    "checkpointIntervalMs": 30000
  },
  "migrationOptions": {
    "schemaDriftPolicy": "FAIL_FAST",
    "deadLetterPolicy": "FAIL_FAST"
  }
}
```

Der Start-Request referenziert immer ein immutable `shadow-plan` Artefakt.

Der Plan enthält `sourceConnectionId`, `targetConnectionId`, `mappingRef`, Tabellen, Routen, Filter, Table-Modes und per-table Policies.

`sourceConnectionId` und `targetConnectionId` dürfen im Start-Request nicht erneut überschrieben werden.

Response:

```json
{
  "jobId": "shadow-prod-001",
  "status": "accepted",
  "resourceUri": "/api/v1/shadow-migrations/shadow-prod-001"
}
```

### 20.2 Status

```http
GET /api/v1/shadow-migrations/{jobId}
```

Response:

```json
{
  "jobId": "shadow-prod-001",
  "status": "CDC_RUNNING",
  "metrics": {
    "sourceLagMs": 842,
    "cdcEventsRead": 93210,
    "cdcEventsWritten": 93210,
    "failedEvents": 0,
    "deadLetterEvents": 0
  }
}
```

### 20.3 Validate

```http
POST /api/v1/shadow-migrations/{jobId}/validate
Idempotency-Key: 2f963d8e-8c58-4d50-8585-dac75d1b1142
Content-Type: application/json
```

```json
{
  "checks": ["ROW_COUNT", "CHECKSUM", "APPROX_PROFILE", "LAG", "DEAD_LETTER"]
}
```

### 20.4 Readiness

```http
GET /api/v1/shadow-migrations/{jobId}/readiness
```

### 20.5 Stop

```http
POST /api/v1/shadow-migrations/{jobId}/stop
Idempotency-Key: 2d049e3e-cf39-41d3-b8cb-7428d4171925
Content-Type: application/json
```

```json
{
  "mode": "DRAIN"
}
```

---

## 21. gRPC API

### 21.1 Service

```proto
service ShadowMigrationService {
  rpc StartShadowMigration(StartShadowMigrationRequest) returns (JobAccepted);
  rpc GetShadowMigration(GetShadowMigrationRequest) returns (ShadowMigrationStatusResponse);
  rpc ValidateShadowMigration(ValidateShadowMigrationRequest) returns (JobAccepted);
  rpc GetShadowReadiness(GetShadowReadinessRequest) returns (ShadowReadinessResponse);
  rpc StopShadowMigration(StopShadowMigrationRequest) returns (JobAccepted);
  rpc TriggerShadowSavepoint(TriggerShadowSavepointRequest) returns (SavepointResponse);
}
```

### 21.2 Start Request

```proto
message StartShadowMigrationRequest {
  string plan_artifact_id = 1;
  ExecutionBackend execution_backend = 2;
  string checkpoint_uri = 3;
  string artifact_uri = 4;
  ShadowExecutionOptions execution_options = 5;
  ShadowMigrationOptions migration_options = 6;
}
```

```proto
enum ExecutionBackend {
  EXECUTION_BACKEND_UNSPECIFIED = 0;
  EXECUTION_BACKEND_LOCAL = 1;
  EXECUTION_BACKEND_FLINK = 2;
  EXECUTION_BACKEND_FLINK_CDC_PIPELINE = 3;
}
```

```proto
message ShadowExecutionOptions {
  optional int32 parallelism = 1;
  optional int64 checkpoint_interval_ms = 2;
  optional int64 checkpoint_timeout_ms = 3;
  optional int32 max_restart_attempts = 4;
  optional int64 restart_delay_ms = 5;
}
```

```proto
message ShadowMigrationOptions {
  SchemaDriftPolicy schema_drift_policy = 1;
  DeadLetterPolicy dead_letter_policy = 2;
  optional int64 max_lag_ms = 3;
  optional string required_stable_duration = 4;
  bool allow_many_source_tables_to_one_target = 5;
  bool allow_experimental_flink_cdc_schema_evolution = 6;
}
```

```proto
enum SchemaDriftPolicy {
  SCHEMA_DRIFT_POLICY_UNSPECIFIED = 0;
  SCHEMA_DRIFT_POLICY_FAIL_FAST = 1;
  SCHEMA_DRIFT_POLICY_RECORD_AND_WARN = 2;
  SCHEMA_DRIFT_POLICY_AUTO_EVOLVE_ADDITIVE_ONLY = 3;
}
```

```proto
enum DeadLetterPolicy {
  DEAD_LETTER_POLICY_UNSPECIFIED = 0;
  DEAD_LETTER_POLICY_FAIL_FAST = 1;
  DEAD_LETTER_POLICY_WRITE_AND_CONTINUE = 2;
  DEAD_LETTER_POLICY_WRITE_AND_DEGRADE = 3;
}
```

```proto
enum ShadowSpecialOperationPolicy {
  SHADOW_SPECIAL_OPERATION_POLICY_UNSPECIFIED = 0;
  SHADOW_SPECIAL_OPERATION_POLICY_FAIL_FAST = 1;
  SHADOW_SPECIAL_OPERATION_POLICY_REJECT = 2;
  SHADOW_SPECIAL_OPERATION_POLICY_RECORD_AND_WARN = 3;
  SHADOW_SPECIAL_OPERATION_POLICY_DEAD_LETTER = 4;
  SHADOW_SPECIAL_OPERATION_POLICY_IGNORE_EXPLICIT = 5;
  SHADOW_SPECIAL_OPERATION_POLICY_APPLY_EXPLICIT = 6;
}
```

---

## 22. MCP Integration

### 22.1 Tools

MCP stellt Shadow-Migration als Job-orientierte Tools bereit.

```text
shadow_migration_start
shadow_migration_status
shadow_migration_validate
shadow_migration_readiness
shadow_migration_stop
```

### 22.2 `shadow_migration_start`

Tool-Argumente:

```json
{
  "planArtifactId": "artifact_shadow_plan_001",
  "executionBackend": "FLINK_CDC_PIPELINE",
  "checkpointUri": "s3://dmigrate-checkpoints/prod-shadow",
  "artifactUri": "s3://dmigrate-artifacts/prod-shadow",
  "executionOptions": {
    "parallelism": 8,
    "checkpointIntervalMs": 30000
  },
  "migrationOptions": {
    "schemaDriftPolicy": "FAIL_FAST",
    "deadLetterPolicy": "FAIL_FAST"
  }
}
```

Response:

```json
{
  "jobId": "shadow-prod-001",
  "status": "accepted",
  "resourceUri": "d-migrate://shadow-migrations/shadow-prod-001"
}
```

### 22.3 MCP Resource

```text
d-migrate://shadow-migrations/{jobId}
```

Resource-Auszug:

```json
{
  "jobId": "shadow-prod-001",
  "status": "CDC_RUNNING",
  "metrics": {
    "sourceLagMs": 842,
    "failedEvents": 0,
    "deadLetterEvents": 0
  },
  "readiness": {
    "status": "NOT_READY",
    "recommendation": "DO_NOT_CUTOVER"
  }
}
```

### 22.4 LLM-Sicherheitsregel

MCP-Antworten dürfen standardmäßig keine Rohdatenwerte aus Dead Letters, Samples oder CDC-Payloads enthalten.

Erlaubt:

```json
{
  "table": "orders",
  "failedEvents": 3,
  "errorCodes": ["SHADOW_TRANSFORM_FAILED"]
}
```

Nicht erlaubt ohne explizite Freigabe:

```json
{
  "payload": {
    "customer_email": "alice@example.org"
  }
}
```

---

## 23. Artefakte

### 23.1 Artefaktarten

```text
shadow-plan.yaml
shadow-status.json
shadow-validation.yaml
shadow-readiness.yaml
flink-cdc-pipeline.yaml
schema-drift/*.yaml
dead-letter/*.json
checkpoint-metadata/*.yaml
savepoint-metadata/*.yaml
execution-backend/*.json
logs/*.txt
```

### 23.2 Artefakt-Metadaten

```yaml
artifactId: artifact_shadow_plan_001
kind: shadow-plan
jobId: shadow-prod-001
contentType: application/yaml
sha256: "..."
sizeBytes: 8192
createdAt: "2026-05-04T10:15:00Z"
```

### 23.3 Immutable Artefakte

Plan-, Mapping- und Validation-Artefakte sind immutable.

Ein laufender Job darf nicht auf ein veränderliches Mapping-Artefakt zeigen.

---

## 24. Datenschutz und Secrets

### 24.1 Secrets

Connection Secrets dürfen nicht in Shadow-Plänen, Logs oder Artefakten gespeichert werden.

Erlaubt:

```yaml
sourceConnectionId: conn_legacy
targetConnectionId: conn_modern
```

Nicht erlaubt:

```yaml
password: secret
jdbcUrlWithPassword: jdbc:postgresql://...
```

### 24.2 Dead Letter Redaction

Dead Letter Payloads müssen standardmäßig redacted sein.

```yaml
deadLetter:
  includePayload: false
  redactValues: true
```

Unredacted Dead Letters benötigen explizite Freigabe.

```yaml
deadLetter:
  includePayload: true
  redactValues: false
  allowSensitivePayloads: true
```

### 24.3 Sensitive Columns

```yaml
privacy:
  sensitiveColumns:
    - email
    - phone
    - iban
    - ssn
    - birth_date
```

Sensitive Werte werden in Logs, MCP-Antworten und Standard-Artefakten maskiert.

---

## 25. Determinismus und Reproduzierbarkeit

### 25.1 Stabile Reports

Vergleichbare Reports dürfen keine laufzeitvariablen Felder enthalten.

Nicht im stabilen Report:

```yaml
generatedAt: "2026-05-04T10:15:00Z"
```

Zeitstempel sind nur in Job- oder Artefakt-Metadaten erlaubt.

### 25.2 Stabile Sortierung

Reports müssen stabil sortieren:

```text
tables:
  schema, tableName

columns:
  ordinal position
  fallback columnName

checks:
  severity desc
  name asc
  path asc

deadLetter summaries:
  table asc
  errorCode asc

artifacts:
  kind asc
  artifactId asc
```

### 25.3 Transformationen

Transformationen müssen deterministisch sein.

Nichtdeterministische Transformationen sind verboten, sofern sie nicht explizit als nicht vergleichbar markiert werden.

---

## 26. Betrieb und Observability

### 26.1 Logs

Logs müssen enthalten:

```text
jobId
executionBackend
table
phase
status
errorCode, falls vorhanden
artifactId, falls vorhanden
```

Logs dürfen keine Secrets oder unredacted Payloads enthalten.

### 26.2 Metriken

Empfohlene Metriken:

```text
dmigrate_shadow_lag_ms
dmigrate_shadow_snapshot_rows_read_total
dmigrate_shadow_snapshot_rows_written_total
dmigrate_shadow_cdc_events_read_total
dmigrate_shadow_cdc_events_written_total
dmigrate_shadow_failed_events_total
dmigrate_shadow_dead_letter_events_total
dmigrate_shadow_checkpoint_age_ms
dmigrate_shadow_throughput_rows_per_second
```

### 26.3 Health

Ein Shadow-Job ist degraded, wenn:

```text
lag threshold überschritten
checkpoint zu alt
dead letters > 0
failed events > 0
schema drift detected
execution backend reports unstable status
```

---

## 27. Implementierungsstrategie

### 27.1 Phase 1: Design

Zielversion: 1.1

Umfang:

- `spec/shadow-migration.md`
- `ShadowMigrationJob` Contract
- `ShadowMigrationPlan` Contract
- `ShadowExecutionBackendPort`
- REST/gRPC/MCP Vertragsentwurf
- Readiness-Gates
- Datenschutzregeln

### 27.2 Phase 2: Spike

Zielversion: 1.2

Umfang:

- Experimenteller `FLINK_CDC_PIPELINE`-Adapter.
- Generierung von `flink-cdc-pipeline.yaml` aus `ShadowMigrationPlan`.
- Ein Source/Sink-Paar, bevorzugt PostgreSQL -> PostgreSQL oder MySQL -> PostgreSQL.
- Initial Snapshot.
- CDC-Verarbeitung.
- Upsert-by-primary-key.
- Lag-Metrik.
- Dead Letter Artefakt.
- Manuelle Readiness-Prüfung.

Nicht im Spike:

```text
vollständige Multi-DB-Matrix
automatischer Cutover
auto-evolve schema drift
produktive HA-Garantien
```

### 27.3 Phase 3: Experimental

Zielversion: 1.3

Umfang:

- CLI `shadow start/status/validate/readiness/stop`.
- REST Job API.
- Checkpoint/Resume.
- Savepoint/Stop.
- Validierungsreports.
- Datenschutzkonfiguration.
- Artifact Store Integration.

### 27.4 Phase 4: Stable

Zielversion: 2.0

Umfang:

- Stabiler Distributed Shadow-Migration-Modus.
- Mehrere DB-Kombinationen.
- Produktive Cutover-Readiness.
- Stabile Flink-/Flink-CDC-Deployment-Dokumentation.
- Betriebsleitfaden.
- Failure-Recovery-Dokumentation.
- Performance- und Langzeittests.

---

## 28. Tests

### 28.1 Unit Tests

- Planvalidierung.
- Primary-Key-Pflicht.
- Mapping-Fingerprint.
- Schema-Fingerprint.
- Statusübergänge.
- Readiness-Gates.
- Dead Letter Redaction.
- Drift Policy.
- Stop Mode.
- Resume Compatibility.

### 28.2 Integration Tests

- Shadow start.
- Snapshot transfer.
- CDC insert.
- CDC update.
- CDC delete.
- Target upsert idempotency.
- Dead letter on transform failure.
- Schema drift detection.
- Checkpoint trigger.
- Savepoint trigger.
- Drain stop.

### 28.3 End-to-End Tests

Mindestens ein E2E-Szenario:

```text
PostgreSQL source
PostgreSQL target
orders table
snapshot
cdc updates
cdc deletes
validation
readiness
drain
stop
```

Optional:

```text
MySQL source
PostgreSQL target
```

### 28.4 Failure Tests

- Source temporarily unavailable.
- Target temporarily unavailable.
- Invalid mapping.
- Checkpoint storage unavailable.
- Duplicate event replay.
- Out-of-order events for same key.
- Constraint violation on target.
- Dead letter threshold exceeded.
- Execution backend failure.

### 28.5 Determinismus-Tests

- Identische Pläne erzeugen identische stabile Plan-Artefakte.
- Identische Validierungen erzeugen identische stabile Reports.
- Keine laufzeitvariablen Felder in stabilen Reports.
- Tabellen, Checks und Artefakte sind stabil sortiert.

---

## 29. Akzeptanzkriterien

Shadow-Migration gilt als akzeptiert, wenn:

1. `hexagon:core` keine Flink-Dependency besitzt.
2. `hexagon:shadow` keine Flink-Dependency besitzt.
3. Flink nur über ein optionales Execution Backend eingebunden ist.
4. Shadow-Migration nutzt immutable Plan- und Mapping-Artefakte.
5. Source und Target werden im immutable Plan über Connection IDs referenziert.
6. Primary Keys sind für CDC-Modi verpflichtend.
7. Upsert-by-primary-key ist die Default-Sink-Strategie.
8. Delete-Verhalten ist explizit modelliert.
9. Schema Drift wird erkannt und gemäß Policy behandelt.
10. Mapping-Änderungen während laufender Jobs sind nicht stillschweigend erlaubt.
11. Checkpoint/Resume besitzt Kompatibilitätsprüfungen.
12. Dead Letters werden als Artefakte abgelegt.
13. Dead Letter Payloads sind standardmäßig redacted.
14. Secrets erscheinen nicht in Reports, Logs oder Artefakten.
15. Readiness-Gates sind explizit und maschinenlesbar.
16. Cutover wird nicht automatisch ohne explizite Nutzeraktion durchgeführt.
17. REST-Starts verlangen einen Idempotency-Key.
18. CLI darf Aliase verwenden, APIs nutzen Connection IDs.
19. MCP gibt standardmäßig keine Rohdaten-Payloads aus.
20. Stabile Reports enthalten kein `generatedAt`.
21. Reports sind deterministisch sortiert.
22. Approximate Validation ersetzt keine exakte Validierung.
23. End-to-End exactly-once wird nicht pauschal behauptet.
24. Betriebsmetriken für Lag, Events, Fehler und Checkpoints existieren.
25. Ein E2E-Test für mindestens ein Source/Target-Paar existiert.
26. `FLINK_CDC_PIPELINE` ist ein optionales Execution Backend.
27. d-migrate kann aus einem `ShadowMigrationPlan` eine Flink-CDC-Pipeline-YAML erzeugen.
28. Die generierte Pipeline enthält `source`, `sink` und `pipeline`.
29. `route` wird für Source-Target-Tabellenmapping verwendet.
30. `transform` wird nur für deterministische Projektionen und Filter verwendet.
31. Secrets werden nicht als Klartext in die Pipeline geschrieben.
32. `schema.change.behavior` wird aus `SchemaDriftPolicy` abgeleitet.
33. Default für produktive Shadow-Migration ist `exception`.
34. Postgres-CDC-Schema-Evolution-Einschränkungen werden dokumentiert und technisch validiert.
35. `operator.uid.prefix` wird gesetzt.
36. Das Pipeline-YAML wird als immutable Artefakt mit SHA-256 gespeichert.
37. Flink-CDC-spezifische Begriffe leaken nicht in `hexagon:core`.
38. Start-APIs referenzieren ein immutable `shadow-plan` Artefakt statt flacher Tabellenlisten.

---

## 30. Offene Entscheidungen

### 30.1 Erstes Source/Target-Paar

Optionen:

```text
PostgreSQL -> PostgreSQL
MySQL -> PostgreSQL
PostgreSQL -> MySQL
```

Vorschlag:

```text
PostgreSQL -> PostgreSQL
```

Begründung:

- weniger Type-Mapping-Risiko
- einfacher zu validieren
- guter erster Spike

### 30.2 Flink oder lokaler CDC-Runner zuerst?

Vorschlag:

```text
Flink CDC Pipeline Backend für Spike, aber hinter ExecutionBackendPort.
```

Begründung:

Shadow-Migration ist primär für kontinuierliche, größere Datenpfade relevant. Flink CDC 3.6 kann viele einfache Fälle über Pipeline-YAML abdecken, ohne dass d-migrate zuerst eigene Flink-Job-JARs bauen muss. Ein lokaler Runner kann für Tests nützlich sein, sollte aber nicht das Zielmodell verzerren.

### 30.3 Auto-Evolve erlauben?

Vorschlag:

```text
Kurzfristig nein.
Langfristig nur additive Änderungen.
```

Default bleibt:

```text
FAIL_FAST
```

### 30.4 Automatischer Cutover?

Vorschlag:

```text
Nein.
```

d-migrate darf Readiness bewerten und Schritte dokumentieren. Der tatsächliche Cutover bleibt explizite Nutzer- oder Plattformaktion.

### 30.5 Append-only Tabellen ohne Primary Key?

Vorschlag:

```text
Ja, aber nur mit explizitem APPEND_ONLY-Modus.
```

Ohne explizite Konfiguration wird fehlender Primary Key abgelehnt.

---

## 31. Beispiel: End-to-End

### 31.1 Plan erstellen

```bash
d-migrate shadow plan \
  --source legacy \
  --target modern \
  --mapping migration.yaml \
  --tables orders,order_items \
  --output shadow-plan.yaml
```

### 31.2 Shadow starten

```bash
d-migrate shadow start \
  --plan shadow-plan.yaml \
  --execution-backend flink-cdc-pipeline \
  --checkpoint-dir s3://dmigrate-checkpoints/prod-shadow \
  --artifact-dir s3://dmigrate-artifacts/prod-shadow \
  --parallelism 8 \
  --checkpoint-interval-ms 30000
```

### 31.3 Status prüfen

```bash
d-migrate shadow status \
  --job shadow-prod-001
```

Beispiel:

```yaml
jobId: shadow-prod-001
status: CDC_RUNNING
metrics:
  sourceLagMs: 842
  snapshotRowsRead: 12000000
  snapshotRowsWritten: 12000000
  cdcEventsRead: 93210
  cdcEventsWritten: 93210
  failedEvents: 0
  deadLetterEvents: 0
```

### 31.4 Readiness prüfen

```bash
d-migrate shadow readiness \
  --job shadow-prod-001
```

Beispiel:

```yaml
jobId: shadow-prod-001
status: READY
recommendation: CUTOVER_ALLOWED
gates:
  - name: lag
    status: PASS
  - name: deadLetters
    status: PASS
  - name: rowCount
    status: PASS
  - name: checksum
    status: PASS
```

### 31.5 Drain und Stop

```bash
d-migrate shadow stop \
  --job shadow-prod-001 \
  --mode drain
```

---

## 32. Zusammenfassung

Kontinuierliche Shadow-Migration ist der stärkste Anwendungsfall für ein optionales Flink- oder Flink-CDC-Pipeline-Backend in d-migrate.

Die saubere Architektur ist:

```text
d-migrate:
  plant, validiert, orchestriert, verwaltet Artefakte

Flink CDC Pipeline Backend:
  generiert und submitted Pipeline-YAML für Snapshot + CDC + Sink-Verarbeitung

Flink Job Backend:
  führt spezialisierte Snapshot + CDC + Sink-Verarbeitung aus, falls Pipeline-YAML nicht reicht

Core:
  bleibt frei von Flink-Abhängigkeiten
```

Shadow-Migration ist kein Ersatz für exakte Validierung und kein automatischer Cutover-Mechanismus.

Das Feature muss vorsichtig eingeführt werden:

```text
1. Design
2. Spike
3. Experimental
4. Stable
```

Erst wenn Keys, Mapping, Schema Drift, Checkpoints, Dead Letters, Privacy, Readiness und Betriebsmetriken sauber modelliert sind, ist Shadow-Migration produktionsreif.
