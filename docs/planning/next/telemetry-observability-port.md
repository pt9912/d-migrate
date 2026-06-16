# Implementierungsplan: Milestone 0.9.x - Telemetry- und Observability-Port

> Dieses Dokument beschreibt den konkreten Implementierungsplan fuer einen
> neutralen Telemetry-/Observability-Port in d-migrate. Es dient als laufend
> gepflegte Spezifikation und Review-Grundlage waehrend der Umsetzung.
>
> Status: Draft
> Referenzen: `spec/architecture.md`, `docs/planning/in-progress/roadmap.md`,
> `spec/cli-spec.md`, `spec/mcp-server.md`

---

## 1. Ziel

d-migrate erhaelt einen neutralen, adapterfaehigen Telemetry-Port fuer
langlaufende und datenbewegende Operationen.

Der Fokus liegt nicht auf einer direkten Integration in ein konkretes
Observability-System wie DataKitchen, OpenTelemetry oder Prometheus, sondern auf
einem stabilen fachlichen Ereignismodell innerhalb der bestehenden hexagonalen
Architektur.

Ziele:

- Migrationen, Exporte, Importe und Transfers nachvollziehbar machen
- Run-, Step-, Table-, Chunk- und Checkpoint-Ereignisse fachlich modellieren
- CLI-, MCP- und spaetere Async-Job-Pfade ueber gemeinsame Correlation-IDs verbinden
- einen ersten JSONL-Adapter als einfache, testbare Referenzimplementierung liefern
- spaetere Adapter fuer OpenTelemetry, DataKitchen, Kafka oder Prometheus vorbereiten

Das Ergebnis ist kein Monitoring-Dashboard, sondern ein sauberer
Integrationspunkt fuer Observability.

---

## 2. Ausgangslage

d-migrate besitzt bereits:

- hexagonale Architektur mit Core, Ports, Application und Adapters
- Streaming-Datenpfade fuer `data export`, `data import` und `data transfer`
- Checkpoint-/Resume-Unterbau fuer langlaufende dateibasierte Operationen
- line-orientierte Fortschrittsausgaben fuer Datenoperationen
- MCP-Server mit Jobs, Idempotenz, Policy/Approval und Artifact-Konzepten
- CLI-Distribution, Docker-/OCI-Runtime und CI-Gates

Was fehlt:

- kein neutraler Telemetry-Port
- keine stabile Event-Semantik fuer Migration-/Transfer-Laeufe
- keine saubere Trennung zwischen Progress-Ausgabe und maschinenlesbarer Telemetry
- keine Adapterflaeche fuer externe Observability-Systeme
- keine durchgaengige Run-Korrelation zwischen CLI, MCP, Async Jobs und Checkpoints

---

## 3. Scope

### 3.1 In Scope

- neues Eventmodell fuer Migration Telemetry
- neuer Port `MigrationTelemetryPort`
- No-op-Implementierung als Default
- JSONL-Adapter als erste produktive Referenz
- CLI-Flags fuer Aktivierung und Ausgabeziel
- Integration in:
  - `data export`
  - `data import`
  - `data transfer`
- Modell vorbereiten fuer spaetere Integration in `schema reverse` und
  `data profile`, ohne diese Runner in diesem Milestone produktiv anzubinden
- Run-ID-/Operation-ID-/Job-ID-Korrelation
- Tests fuer Event-Emission, JSONL-Ausgabe und No-op-Verhalten
- Dokumentation in CLI-Spec, Architektur und User Guide

### 3.2 Bewusst nicht Teil dieses Milestones

- direkte DataKitchen-Integration
- direkte OpenTelemetry-Integration
- Prometheus-Exporter
- UI oder Dashboard
- Alerting
- Persistenz in eigener Datenbank
- automatische Data-Lineage-Visualisierung
- Rohdaten-Sampling oder Dateninhalt in Events
- Secrets, JDBC-URLs oder SQL-Rohdaten in Telemetry-Events
- produktive Telemetry-Emission fuer `schema reverse`, `schema compare` oder
  `data profile`

---

## 4. Leitentscheidungen

### 4.1 Der Port ist fachlich, nicht vendor-spezifisch

Der Port kennt keine Begriffe wie DataKitchen, Span, Meter, Metric, Prometheus
oder Kafka.

Verbindlich:

```kotlin
interface MigrationTelemetryPort : AutoCloseable {
    fun publish(event: MigrationTelemetryEvent)
    fun flush()

    override fun close() = flush()
}
```

Nicht erlaubt:

```kotlin
interface DataKitchenSink
interface OpenTelemetrySpanEmitter
interface MetricsLogger
```

Adapter duerfen vendor-spezifisch sein. Der Port nicht.

Der Port hat einen expliziten Schreib- und Lifecycle-Vertrag:

* `publish(...)` darf bei technischen Schreibfehlern eine
  `MigrationTelemetryWriteException` werfen.
* `flush()` wird am kontrollierten Laufende aufgerufen und darf ebenfalls eine
  `MigrationTelemetryWriteException` werfen.
* `close()` delegiert auf `flush()`, ist aber nicht der alleinige
  Fehlerbehandlungsmechanismus. Runner-/CLI-Wiring muss Telemetry-Close/Flush
  so aufrufen, dass ein bereits vorhandener Primaerfehler oder
  `OperationCancelledException` nicht maskiert wird.
* Der rohe Port kennt keinen CLI-Fail-Mode. `best-effort` und `strict` werden
  im Wiring ueber einen Guard/Decorator umgesetzt:
  * `best-effort` faengt Schreib-/Flush-Fehler ab, gibt hoechstens eine
    deduplizierte Warnung aus und laesst den Primaerlauf weiterlaufen.
  * `strict` laesst Schreib-/Flush-Fehler bis zum Runner/CLI-Wiring
    propagieren; wenn kein spezifischer Primaerfehler bereits den Exit-Code
    bestimmt, wird daraus Exit `7` (`LOCAL_ERROR`).
  * Falls der Primaerlauf bereits mit Fehler oder Cancellation beendet wurde,
    darf ein nachgelagerter Telemetry-Flush-/Close-Fehler den Primaer-Exit-Code
    nicht ueberschreiben. Er wird in diesem Fall nur als suppressed/sekundaere
    Diagnose behandelt.

### 4.2 Eventmodell statt Logger

Telemetry ist kein generischer Logger. Events beschreiben fachliche
Migrations- und Datenbewegungsereignisse.

Beispiele:

* `RunStarted`
* `RunFinished`
* `RunFailed`
* `StepStarted`
* `StepFinished`
* `TableStarted`
* `TableFinished`
* `ChunkProcessed`
* `CheckpointSaved`

Nicht Ziel:

```kotlin
telemetry.log("import started")
```

### 4.3 Keine Secrets und keine Nutzdaten

Telemetry-Events duerfen keine Passwoerter, Tokens, JDBC-URLs, Row-Inhalte oder
SQL-Rohdaten enthalten.

Erlaubt:

* Dialekt
* Connection-Name
* Tabellenname
* Spaltenanzahl
* Zeilenanzahl
* Dauer
* Status
* Fehlerklasse
* Exit-Code
* anonymisierte oder konfigurierte Umgebung
* Datenbank- oder Dateireferenz nur dann, wenn sie explizit freigegeben,
  gekuerzt oder gehasht wurde

Nicht erlaubt:

* komplette JDBC-URL mit Credentials
* SQLite-Dateipfade oder absolute lokale Pfade als Datenbankname
* produktive Datenbanknamen ohne explizite Freigabe oder Hashing
* Row Payloads
* konkrete Kundendaten
* rohe Filterausdruecke, falls sie sensible Werte enthalten koennen
* freie Attribute ohne dokumentierte Allowlist und Redaction-Regel

Fuer optionale Zusatzattribute gilt:

* Attribute sind keine freie Logging-Flaeche.
* Erlaubte Keys werden zentral als Allowlist dokumentiert und getestet.
* Werte muessen bereits beim Erzeugen secret-frei sein; der JSONL-Adapter
  fuehrt zusaetzlich defensives Redaction/Scrubbing aus.
* Idempotency-Keys, Tokens und aehnliche Korrelationswerte werden nicht roh
  serialisiert. Falls benoetigt, wird nur ein stabiler Hash oder eine
  explizit freigegebene Kurzreferenz aufgenommen.

### 4.4 Progress und Telemetry bleiben getrennt

Progress ist menschliche CLI-Ausgabe.

Telemetry ist maschinenlesbares Ereignisprotokoll.

Beide koennen aus denselben Runnern gespeist werden, duerfen aber nicht dieselbe
Schnittstelle sein.

### 4.5 JSONL ist der erste Adapter

JSONL ist absichtlich der erste Adapter, weil er:

* lokal testbar ist
* CI-freundlich ist
* ohne Server funktioniert
* spaeter leicht in DataKitchen, Kafka oder andere Systeme ueberfuehrt werden kann

---

## 5. Geplante Arbeitspakete

### Phase A - Spezifikationsbereinigung und Scope-Fixierung

1. `spec/architecture.md` um Telemetry-Port im Ports-/Adapters-Modell ergaenzen.
2. `spec/cli-spec.md` um globale Observability-Optionen erweitern.
3. Entscheidung dokumentieren:

   * Port liegt in `hexagon:ports-common`
   * No-op-Default liegt in `hexagon:ports-common`
   * JSONL-Adapter liegt unter `adapters:driven:observability-jsonl`
   * CLI-Wiring liegt in `adapters:driving:cli`
4. Roadmap-Eintrag fuer neuen Milestone ergaenzen.
5. Begriffe festlegen:

   * `runId`
   * `operationId`
   * `jobId`
   * `traceId`
   * `parentRunId`

   Verbindliche Invariante fuer diesen Milestone:

   * Im CLI-Pfad ist `runId` standardmaessig identisch zur bestehenden
     `operationId`, damit Progress, Checkpoint/Resume, Result und Telemetry
     dieselbe Laufreferenz verwenden.
   * `--run-id <id>` setzt fuer neue CLI-Laeufe der in diesem Milestone
     angebundenen Datenkommandos sowohl `runId` als auch `operationId` -
     unabhaengig davon, ob Telemetry aktiviert ist. Die Option ist damit kein
     reines Telemetry-Flag, sondern der explizite CLI-Einstieg fuer die
     bestehende Lauf-/Checkpoint-Korrelation.
   * Diese ID wird nicht nur im Telemetry-Kontext gesetzt, sondern bis in die
     vorhandenen Request-/Resume-/Checkpoint-Pfade durchgereicht:
     `DataExportRequest`, `DataImportRequest`, `DataTransferRequest`, die
     jeweiligen Checkpoint-Manager und die Result-/Progress-Objekte duerfen fuer
     denselben CLI-Lauf keine zweite UUID erzeugen.
   * Bei `--resume` gewinnt die `operationId` aus dem Checkpoint-Manifest.
     Weicht ein explizites `--run-id` davon ab, endet der Aufruf mit Exit `2`;
     stille Doppel-IDs sind nicht erlaubt.
   * Bei nicht angebundenen Commands wird `--run-id` syntaktisch akzeptiert,
     solange Telemetry nicht aktiviert ist, hat dort aber in diesem Milestone
     keine Wirkung auf fachliche Operation-IDs.
   * `jobId` bleibt eine zusaetzliche MCP-/Async-Korrelation und ersetzt
     `runId`/`operationId` nicht.
   * `traceId` ist fuer externe Trace-Korrelation reserviert und darf keinen
     Resume-/Checkpoint-Vertrag steuern.

   Sicherheitsvertrag fuer explizite `runId`/`operationId`-Werte:

   * `--run-id` ist ein sicherheitsrelevanter Checkpoint-Identifier, weil der
     Wert als `operationId` in Manifest- und Staging-Dateinamen eingeht.
   * Der CLI-Wert muss vor dem Bau von Requests validiert werden:
     `^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$`.
   * Die reservierten Werte `.` und `..` sind ungueltig.
   * Slash, Backslash, Pfadseparatoren, absolute Pfade, Whitespace-only-Werte
     und leere Werte sind ungueltig.
   * Die Checkpoint-Manager duerfen einen expliziten `runId` erst nach dieser
     Validierung verwenden. Tests muessen belegen, dass Pfad-Traversal- und
     absolute-Pfad-Versuche mit Exit `2` enden und keine Checkpoint-,
     Staging- oder Telemetry-Dateien an unerwarteten Orten erzeugen.

### Phase B - Port- und Eventmodell

Neuer Package-Vorschlag:

```text
hexagon/ports-common/src/main/kotlin/dev/dmigrate/observability/
  MigrationTelemetryPort.kt
  MigrationTelemetryEvent.kt
  MigrationTelemetryContext.kt
  MigrationTelemetryAttributes.kt
  MigrationTelemetryStatus.kt
```

Port:

```kotlin
interface MigrationTelemetryPort : AutoCloseable {
    fun publish(event: MigrationTelemetryEvent)
    fun flush()

    override fun close() = flush()
}
```

Basis-Event:

```kotlin
sealed interface MigrationTelemetryEvent {
    val context: MigrationTelemetryContext
    val timestamp: Instant
}
```

Kontext:

```kotlin
data class MigrationTelemetryContext(
    val runId: String,
    val operationId: String? = null,
    val jobId: String? = null,
    val parentRunId: String? = null,
    val traceId: String? = null,
    val command: String,
    val source: TelemetryEndpoint? = null,
    val target: TelemetryEndpoint? = null,
    val attributes: MigrationTelemetryAttributes = MigrationTelemetryAttributes.empty(),
)
```

Fuer CLI-Laeufe gilt: `operationId` ist nicht optional, sobald ein Runner
tatsaechlich Events emittiert. `runId` und `operationId` sind dann im
Normalfall gleich. `operationId = null` ist nur fuer spaetere nicht-resumable
oder Legacy-Callsites zulaessig, solange sie keine Checkpoint-/Resume-Semantik
besitzen.

Attribute:

```kotlin
@JvmInline
value class MigrationTelemetryAttributes private constructor(
    val values: Map<String, String>
) {
    companion object {
        fun empty(): MigrationTelemetryAttributes = MigrationTelemetryAttributes(emptyMap())
        fun of(values: Map<String, String>): MigrationTelemetryAttributes
    }
}
```

`of(...)` validiert ausschliesslich freigegebene Keys, begrenzte Werte-Laengen
und defensives Secret-Scrubbing. Die Factory kopiert die validierten Werte in
eine immutable, deterministisch geordnete Map, damit mutable Input-Maps nach der
Validierung keine freien oder sensitiven Keys mehr einschleusen koennen. Fuer den
ersten Milestone reichen technische Keys wie `environment`, `artifact_ref`,
`checkpoint_ref` und `idempotency_key_hash`. Keine Call-Site darf einen rohen
Idempotency-Key, eine URL, SQL oder Filterliterale als Attribut durchreichen.

Endpoint:

```kotlin
data class TelemetryEndpoint(
    val kind: TelemetryEndpointKind,
    val name: String? = null,
    val dialect: DatabaseDialect? = null,
    val database: String? = null,
    val schema: String? = null,
)

enum class TelemetryEndpointKind {
    DATABASE,
    FILE,
    ARTIFACT,
    MCP,
    OTHER
}
```

Endpoint-Sicherheitsregel:

* `kind` ist kein freier String. Er wird ueber `TelemetryEndpointKind`
  allowlistiert; neue Werte muessen bewusst im Eventmodell ergaenzt und
  getestet werden.
* `OTHER` ist nur fuer kontrollierte interne Uebergangspfade erlaubt und darf
  keine rohen Connection-URLs, Pfade oder freien Adapterbezeichnungen tragen.
* `name` ist der bevorzugte Wert und meint einen benannten Connection-Alias
  oder eine explizit konfigurierte Kurzreferenz.
* `database` darf nicht blind aus `ConnectionConfig.database` uebernommen
  werden, weil dieser Wert SQLite-Dateipfade oder produktive Datenbanknamen
  enthalten kann.
* Wenn eine Datenbankreferenz fuer Korrelation noetig ist, wird sie ueber einen
  zentralen Endpoint-Sanitizer erzeugt: entweder explizit allowlistiert,
  gekuerzt oder stabil gehasht.
* `schema` darf nur technische Schema-Namen enthalten, keine Pfade,
  SQL-Fragmente oder Filterwerte.

Status:

```kotlin
enum class MigrationTelemetryStatus {
    SUCCESS,
    WARNING,
    FAILED,
    CANCELLED,
    SKIPPED
}
```

Eventtypen:

```kotlin
data class RunStarted(...)
data class RunFinished(...)
data class RunFailed(...)
data class RunCancelled(...)
data class StepStarted(...)
data class StepFinished(...)
data class TableStarted(...)
data class TableFinished(...)
data class ChunkProcessed(...)
data class ChunkSummary(...)
data class CheckpointSaved(...)
```

`ChunkSummary` ist der verbindliche Summary-Event fuer
`--telemetry-chunk-events summary`. `SchemaDriftDetected` bleibt fuer spaetere
Schema-/Compare-Pfade vorgemerkt, ist aber kein verbindlicher Eventtyp dieses
Milestones, solange `schema reverse`, `schema compare` oder `data profile`
nicht produktiv an den Telemetry-Port angeschlossen werden.

Terminal-Event-Regeln:

* Jeder gestartete CLI-Aufruf beziehungsweise Attempt endet mit genau einem
  terminalen Run-Event: `RunFinished`, `RunFailed` oder `RunCancelled`.
* Die Eindeutigkeit gilt pro Prozessaufruf, nicht global pro `runId`. Ein
  Resume-Lauf verwendet dieselbe `runId`/`operationId` wie das
  Checkpoint-Manifest und darf deshalb mehrere Attempt-Sequenzen mit derselben
  `runId` in einer JSONL-Datei erzeugen.
* Fuer diesen Milestone wird kein separates `attemptId` eingefuehrt. Adapter und
  Tests duerfen terminale Events deshalb nicht global nach `runId` deduplizieren,
  sondern muessen die append-only Reihenfolge der JSONL-Datei als Attempt-Historie
  behandeln.
* Cancellation wird nicht als generischer Fehler modelliert. Sie erzeugt
  `RunCancelled` mit Exit-Code `130` und Status `CANCELLED`.
* Bei Abbruch duerfen keine nachgelagerten Fake-Completion-Events fuer Tabellen
  oder Chunks erzeugt werden.

### Phase C - Default- und JSONL-Adapter

No-op-Adapter:

```text
hexagon/ports-common/src/main/kotlin/dev/dmigrate/observability/NoOpMigrationTelemetryPort.kt
```

Der No-op liegt verbindlich in `ports-common`, damit `hexagon:application`
ohne Adapter-Abhaengigkeit einen Default verwenden kann. Ein separates
`observability-noop`-Adaptermodul ist in diesem Milestone nicht vorgesehen.

JSONL-Adapter:

```text
adapters/driven/observability-jsonl
```

Gradle-Wiring:

1. `settings.gradle.kts` ergaenzt `include("adapters:driven:observability-jsonl")`.
2. `adapters/driven/observability-jsonl/build.gradle.kts` haengt an <!-- d-check:ignore (Zielbild: entsteht mit observability-jsonl-Modul; ADR 0011) -->
   `:hexagon:ports-common`.
3. Der JSONL-Adapter bekommt eine eigene direkte Runtime-Dependency auf
   `com.dslplatform:dsl-json-java8` und verwendet DSL-JSONs Low-Level-
   `JsonWriter` fuer deterministische, streaming-freundliche Ausgabe. Er haengt
   nicht an `adapters:driven:formats`, damit kein formatbezogener Adapter als
   technische Transitiv-Abhaengigkeit in Observability gezogen wird.
4. Gemeinsame JSON-Hilfen werden nur dann extrahiert, wenn sie adapterneutral
   bleiben und keine Format-Adapter-Abhaengigkeit einfuehren.
5. `adapters/driving/cli/build.gradle.kts` bekommt eine
   `implementation(project(":adapters:driven:observability-jsonl"))`-Abhaengigkeit.

Aufgaben:

1. Event-Serialisierung mit direktem DSL-JSON `JsonWriter` gemaess Gradle-
   Wiring; keine Abhaengigkeit auf `adapters:driven:formats`.
2. Ein Event pro Zeile.
3. Deterministische Feldreihenfolge fuer Tests.
4. Flush-Verhalten fuer langlaufende Operationen.
5. Fehler beim Schreiben duerfen Migration nicht unkontrolliert zerstoeren.
6. Optionaler Fail-Mode:

   * `best-effort`
   * `strict`

Der JSONL-Adapter selbst ist strikt und wirft
`MigrationTelemetryWriteException` bei nicht behebbaren Datei-/I/O-Fehlern.
Das CLI-Wiring entscheidet ueber den Decorator, ob diese Fehler nur als Warnung
behandelt oder als Exit `7` sichtbar werden.

JSONL-Wire-Format:

* Jedes Event wird als JSON-Objekt mit deterministischer Top-Level-
  Feldreihenfolge geschrieben.
* Pflichtfelder stehen zuerst in dieser Reihenfolge:
  `type`, `timestamp`, `run_id`, `operation_id`, `command`.
* Der JSONL-Adapter ist in diesem Milestone nur fuer produktiv angebundene
  CLI-Events bestimmt. Diese Events muessen immer ein nicht-leeres
  `operation_id` tragen. Events ohne `operationId` sind fuer JSONL ungueltig und
  fuehren im strikten Adapter zu `MigrationTelemetryWriteException`; sie werden
  nicht stillschweigend mit fehlendem `operation_id` serialisiert.
* Optionale Korrelationsfelder folgen in dieser Reihenfolge und werden bei
  `null` ausgelassen: `job_id`, `parent_run_id`, `trace_id`.
* Endpoint-Felder werden bewusst nicht flachgezogen, sondern als `source` und
  `target` jeweils als Objekt serialisiert; auch dort werden `null`-Felder
  ausgelassen und die innere Feldreihenfolge bleibt deterministisch.
* Event-spezifische Felder folgen nach dem Kontext, z.B. `table`,
  `chunk_index`, `rows_read`, `rows_written`, `rows_failed`, `bytes_written`,
  `duration_ms`, `status`, `exit_code`, `error_class`.
* `attributes` wird nur geschrieben, wenn es nicht leer ist.
* `timestamp` ist ein UTC-ISO-8601-String aus `Instant.toString()`.
* `type` ist lower snake case, z.B. `run_started`.
* Enum-Werte wie `status` bleiben upper snake case, z.B. `SUCCESS`.
* JSON-Nullwerte werden nicht serialisiert; fehlende Felder bedeuten
  "nicht zutreffend" oder "nicht vorhanden".

Beispielausgabe:

```json
{"type":"run_started","timestamp":"2026-05-13T10:15:30Z","run_id":"...","operation_id":"...","command":"data transfer"}
{"type":"table_started","timestamp":"2026-05-13T10:15:31Z","run_id":"...","operation_id":"...","command":"data transfer","table":"customers"}
{"type":"chunk_summary","timestamp":"2026-05-13T10:15:32Z","run_id":"...","operation_id":"...","command":"data transfer","table":"customers","chunk_count":1,"rows_read":10000,"rows_written":10000,"duration_ms":812}
{"type":"table_finished","timestamp":"2026-05-13T10:15:33Z","run_id":"...","operation_id":"...","command":"data transfer","table":"customers","status":"SUCCESS","rows_read":10000,"rows_written":10000,"chunk_count":1,"duration_ms":920}
{"type":"run_finished","timestamp":"2026-05-13T10:15:49Z","run_id":"...","operation_id":"...","command":"data transfer","status":"SUCCESS","duration_ms":18441}
```

Output-Open-Mode:

* Fuer neue Laeufe erzeugt der JSONL-Adapter die Ausgabedatei neu und bricht mit
  `MigrationTelemetryWriteException` ab, wenn die Datei bereits existiert. Wer
  eine bestehende Datei behalten will, muss einen neuen Pfad waehlen.
* Fuer Resume-Laeufe wird dieselbe Ausgabedatei im Append-Modus geoeffnet, wenn
  sie existiert. Fehlt sie, wird sie neu erzeugt.
* Resume-Append darf keine vorhandenen Zeilen kuerzen oder ueberschreiben.
* Fuer checkpoint-faehige Resume-Laeufe (`data export`, `data import`) wird auf
  frischen Laeufen bei aktivierter JSONL-Telemetry zusaetzlich ein
  `telemetryOutputRefHash` im Checkpoint-Manifest persistiert. Der Wert ist ein
  stabiler Hash der normalisierten Telemetry-Ausgabereferenz und enthaelt keinen
  rohen Pfad.
* Bei `--resume` mit aktivierter JSONL-Telemetry muss der Hash des aktuellen
  `--telemetry-output` mit dem Manifest-Wert uebereinstimmen, sofern das
  Manifest diesen Wert enthaelt. Eine Abweichung ist Exit `2`, damit eine
  Attempt-Historie nicht versehentlich auf mehrere JSONL-Dateien verteilt wird.
  Legacy-Manifeste ohne `telemetryOutputRefHash` duerfen weiter resuming sein;
  in diesem Fall wird die explizit angegebene Datei append-only verwendet.
* `data transfer` besitzt in diesem Milestone keinen Checkpoint-Resume-Pfad.
  Fail-if-exists gilt dort deshalb immer fuer neue JSONL-Dateien.
* Terminale Events bleiben pro Prozessaufruf eindeutig. Ein wiederaufgenommener
  Lauf kann deshalb mehrere `run_started`-Events ueber dieselbe `run_id`
  enthalten, aber pro CLI-Aufruf genau ein terminales Run-Event.

Adapter-Initialisierung:

* JSONL-Adapter-Initialisierung und Datei-Open passieren im CLI-Wiring vor dem
  Start des fachlichen Runners und vor Datenbank-/Import-/Export-Seiteneffekten.
* Fuer checkpoint-faehige Resume-Laeufe muss vorher ein read-only
  Resume-Preflight laufen: Checkpoint-Manifest laden, ein explizites `--run-id`
  gegen die Manifest-`operationId` validieren und bei aktivierter JSONL-Telemetry
  den aktuellen `telemetryOutputRefHash` gegen das Manifest pruefen, sofern dort
  ein Wert vorhanden ist.
* Erst nach erfolgreichem Resume-Preflight darf die JSONL-Datei geoeffnet oder
  neu erzeugt werden. Ein Hash-Mismatch muss Exit `2` liefern, ohne eine neue
  Telemetry-Datei am falschen Pfad anzulegen.
* Im `strict`-Modus fuehrt ein Initialisierungs-/Open-Fehler ohne gestarteten
  Primaerlauf direkt zu Exit `7`; der Runner wird nicht ausgefuehrt.
* Im `best-effort`-Modus wird ein Initialisierungs-/Open-Fehler genau einmal als
  Warnung gemeldet. Danach wird der Telemetry-Port fuer diesen Aufruf auf No-op
  degradiert, damit nachfolgende `publish`, `flush` und `close` keine weiteren
  Fehler erzeugen.
* Diese Regel ist getrennt vom nachgelagerten Flush-/Close-Handling: Open-Fehler
  sind Vorlauf-Fehler, Flush-/Close-Fehler entstehen nach oder waehrend einem
  bereits gestarteten Primaerlauf.

Strict-/Close-Handling:

* CLI- und Async-Wiring muessen den Primaerausgang zuerst festhalten.
* Telemetry-Flush/Close laeuft danach kontrolliert.
* Nur wenn der Primaerausgang erfolgreich oder noch unbestimmt ist, darf ein
  `strict`-Telemetry-Fehler den Exit-Code auf `7` setzen.
* Wenn der Primaerausgang bereits Fehler oder Cancellation ist, bleibt dieser
  Exit-Code fuehrend; Telemetry-Fehler werden nur diagnostiziert.

### Phase D - Application-Wiring

Betroffene Runner:

* `DataExportRunner`
* `DataImportRunner`
* `DataTransferRunner`
* `TransferExecutor`

Nicht betroffen in diesem Milestone:

* `SchemaReverseRunner`
* `DataProfileRunner`

Regeln:

1. Runner bekommen `MigrationTelemetryPort` injiziert.
2. Default ist No-op.
3. Telemetry wird im Application-/Orchestration-Pfad verdrahtet, nicht im
   CLI-Rendering.
4. Die bestehende `ProgressReporter`-Flaeche bleibt unveraendert fuer
   menschliche stderr-Ausgabe. Export/Import duerfen ihre vorhandenen
   `ProgressEvent`s im Application-Layer in Telemetry-Events uebersetzen oder
   einen separaten typed Callback-Kanal erhalten; in beiden Varianten darf
   `--no-progress` nur Progress-Ausgabe unterdruecken, niemals aktivierte
   Telemetry.
5. Treiber duerfen nur dann Events erzeugen, wenn sie dafuer explizit einen Port
   erhalten.
6. Chunk-Events muessen begrenzbar sein, damit grosse Migrationen keine
   Event-Flut erzeugen.
7. `MigrationTelemetryWriteException` beziehungsweise der strict
   Guard-Fehler darf nicht von bestehenden Catch-all-Grenzen als fachlicher
   Export-/Import-/Transfer-Fehler gemappt werden. Alle betroffenen
   Catch-all-Bloecke muessen `OperationCancelledException` und
   `MigrationTelemetryWriteException` explizit durchreichen oder die
   Telemetry-Emission so kapseln, dass der Runner den Primaerausgang und den
   Telemetry-Ausgang getrennt bewerten kann.
8. Im `best-effort`-Modus darf der Decorator keine
   `MigrationTelemetryWriteException` aus `publish`, `flush` oder `close`
   nach aussen reichen. Im `strict`-Modus muss der Fehler bis zur zentralen
   Exit-Code-Entscheidung sichtbar bleiben.

Run-ID-/Operation-ID-Wiring:

* `DataExportRequest`, `DataImportRequest` und `DataTransferRequest` erhalten
  ein optionales `runId`/`operationId`-Eingabefeld aus dem CLI-Wiring.
* Bei neuen Export-/Import-Laeufen verwenden `ExportCheckpointManager` und
  `ImportCheckpointManager` diese ID statt intern eine neue UUID zu erzeugen.
* Bei Transfer-Laeufen ohne Checkpoint-Unterbau erzeugt das CLI-Wiring eine
  Operation-ID und reicht sie an Runner und Telemetry-Kontext weiter.
* Bei `--resume` validieren die Checkpoint-Manager ein explizites `--run-id`
  gegen die Manifest-`operationId`, bevor Events emittiert oder neue
  Checkpoints geschrieben werden.
* Bei aktivierter JSONL-Telemetry persistieren die Checkpoint-Manager fuer
  frische Export-/Import-Laeufe einen Top-Level-`telemetryOutputRefHash` im
  Manifest und validieren ihn auf Resume, falls der Manifest-Wert vorhanden ist.
* Progress- und Result-Objekte muessen dieselbe `operationId` tragen wie die
  Telemetry-Events desselben CLI-Aufrufs.

Checkpoint-Manifest-Versionierung:

* `telemetryOutputRefHash` wird als optionales Top-Level-Feld auf
  `CheckpointManifest` eingefuehrt, nicht unter `operationSpecific`, weil die
  Ausgabereferenz eine operationstypuebergreifende Resume-Invariante ist.
* Das additive optionale Feld erfordert fuer sich keine Manifest-Versionserhoehung.
  `CURRENT_SCHEMA_VERSION` bleibt `2`, damit frische Default-Laeufe ohne
  aktivierte Telemetry keine fuer aeltere 0.9.x-Builds unnoetig inkompatiblen
  Checkpoints erzeugen.
* Loader muessen Legacy-Manifeste mit `schemaVersion` 1 oder 2 ohne
  `telemetryOutputRefHash` weiterhin akzeptieren. Falls spaeter eine echte
  inkompatible Manifest-Aenderung noetig wird, wird sie separat versioniert.
* `FileCheckpointStore` in `adapters:driven:streaming` muss das Feld schreiben
  und lesen. Tests muessen beide Richtungen abdecken:
  * neue v2-Manifeste mit `telemetryOutputRefHash`
  * Legacy-v1-/v2-Manifeste ohne Feld
  * Roundtrip ohne rohe lokale Telemetry-Pfade

Konfiguration fuer Chunk-Events:

```text
--telemetry-chunk-events none|summary|all
```

Default:

```text
summary
```

Semantik:

* `none`: keine Chunk-Events; Run-, Table- und Checkpoint-Events bleiben
  erhalten.
* `summary`: keine Events pro einzelnem Chunk. Stattdessen wird pro Tabelle ein
  `ChunkSummary`-Event mit aggregierten Chunk-Zaehlern und Dauerwerten
  ausgegeben; `TableFinished` enthaelt zusaetzlich die wichtigsten
  Tabellenaggregate fuer einfache Auswertung.
* `all`: jedes bestaetigte Chunk erzeugt ein `ChunkProcessed`-Event.

Checkpoint-Event-Drosselung:

* `CheckpointSaved` darf nicht jeden chunknahen Manifest-Save spiegeln. Export
  und Import speichern Checkpoints heute bei Chunk-Commit/-Progress; eine
  1:1-Emission wuerde `--telemetry-chunk-events none|summary` umgehen.
* Im Modus `none` werden nur initiale, finale und fehlerrelevante
  Checkpoint-Events emittiert; chunknahe Fortschreibungen bleiben still.
* Im Modus `summary` wird hoechstens ein aggregiertes `CheckpointSaved` pro
  Tabelle emittiert, z.B. zusammen mit `ChunkSummary` oder `TableFinished`.
* Nur im Modus `all` duerfen chunknahe Checkpoint-Events emittiert werden.
* Tests muessen sicherstellen, dass grosse Tabellen im Modus `none` oder
  `summary` keine checkpointgetriebene Event-Flut erzeugen.

Zusaetzliche Transfer-Aenderung:

`DataTransferRunner` kann heute nur tabellenweise Fortschritt melden. Fuer
Chunk-Telemetry muss `TransferExecutor` eine typed Callback-Struktur bekommen,
z.B. `onTableStarted`, `onChunkTransferred` und `onTableFinished`. Diese
Callbacks duerfen keine Row-Werte enthalten und muessen Cancellation-Grenzen
respektieren, damit bei Abbruch keine fake Completion-Events entstehen.

### Phase E - CLI-Vertrag

Neue globale Optionen:

```text
--telemetry none|jsonl
--telemetry-output <path>
--telemetry-fail-mode best-effort|strict
--telemetry-chunk-events none|summary|all
--run-id <id>
--trace-id <id>
```

Optionsvalidierung:

* `--telemetry none` ist der Default und darf ohne `--telemetry-output`
  verwendet werden.
* `--telemetry jsonl` erfordert `--telemetry-output <path>`. Fehlt der Pfad,
  endet der Aufruf mit Exit `2`.
* `--telemetry-output`, `--telemetry-fail-mode`,
  `--telemetry-chunk-events`, `--run-id` und `--trace-id` duerfen syntaktisch am
  Root-Command stehen.
* `--run-id` wirkt fuer neue Laeufe von `data export`, `data import` und
  `data transfer` auch ohne aktivierte Telemetry, weil es dieselbe Laufreferenz
  fuer Result, Progress und Checkpoint/Resume setzt. Fuer andere Commands hat
  `--run-id` in diesem Milestone keine Wirkung, solange Telemetry nicht
  aktiviert ist.
* `--trace-id`, `--telemetry-output`, `--telemetry-fail-mode` und
  `--telemetry-chunk-events` haben in diesem Milestone nur bei aktivierter
  Telemetry fuer angebundene Commands Wirkung.
* `--telemetry-fail-mode` hat den Default `best-effort`. Dadurch bleibt ein
  aktivierter lokaler Observability-Adapter standardmaessig diagnostisch und
  bricht Migrationen nicht wegen Telemetry-I/O-Problemen ab.
* Wer das Telemetry-Artefakt als verbindlichen Audit-Nachweis braucht, muss
  explizit `--telemetry-fail-mode strict` setzen.
* `--telemetry-output` mit `--telemetry none` ist eine ungueltige Kombination und
  endet mit Exit `2`, damit nicht versehentlich ein erwartetes Audit-Artefakt
  ausbleibt.
* `--telemetry-fail-mode` und `--telemetry-chunk-events` ohne aktivierte
  Telemetry werden akzeptiert, haben aber keine Wirkung. Dadurch koennen
  Pipeline-Defaults gesetzt werden, ohne `--telemetry none`-Laeufe zu brechen.
* Parent-Verzeichnisse fuer `--telemetry-output` werden nicht implizit angelegt.
  Fehlt das Parent-Verzeichnis oder ist es nicht schreibbar, ist das ein
  Telemetry-Schreibfehler: `best-effort` warnt, `strict` fuehrt ohne
  Primaerfehler zu Exit `7`.
* Neue JSONL-Laeufe verwenden fail-if-exists fuer die Ausgabedatei. Resume-Laeufe
  verwenden Append gemaess Output-Open-Mode in Phase C; fuer checkpoint-faehige
  Resume-Laeufe wird die Telemetry-Ausgabereferenz gegen das Manifest validiert,
  wenn dort ein `telemetryOutputRefHash` vorhanden ist.

Geltungsbereich:

* Die Optionen werden am Root-Command definiert, damit CLI, MCP- und spaetere
  Async-Pfade denselben Vertrag dokumentieren koennen.
* Produktiv konsumiert werden sie in diesem Milestone nur von `data export`,
  `data import` und `data transfer`.
* Wird `--telemetry` mit einem Wert ungleich `none` fuer einen noch nicht
  angebundenen Command verwendet, endet der Aufruf mit Exit `2` und einer
  klaren Meldung, dass Telemetry fuer diesen Command noch nicht unterstuetzt
  ist. Stilles Ignorieren ist nicht erlaubt.
* `--run-id` und `--trace-id` duerfen bei nicht angebundenen Commands nur ohne
  aktivierte Telemetry akzeptiert werden und haben dort in diesem Milestone
  keine Wirkung.

Beispiele:

```bash
d-migrate \
  --telemetry jsonl \
  --telemetry-output ./d-migrate-run.jsonl \
  data transfer \
  --source olddb \
  --target newdb \
  --tables users,orders
```

```bash
d-migrate \
  --run-id release-2026-05-13-001 \
  --telemetry jsonl \
  --telemetry-output ./audit/release-2026-05-13-001.jsonl \
  data import \
  --target prod \
  --source ./export.json \
  --format json
```

Exit-Code-Regeln:

* Telemetry-Schreibfehler im `best-effort`-Modus erzeugen Warnung, aber keinen
  Migration-Fail.
* Telemetry-Schreibfehler im `strict`-Modus fuehren zu Exit `7`
  (`LOCAL_ERROR`), wenn kein spezifischer Primaerfehler bereits den Exit-Code
  bestimmt, weil es sich um lokalen Datei-/I/O-Fehler handelt.
* Schreibfehler aus `strict` duerfen nicht als Export-/Import-/Transfer-Exit
  `5` gemeldet werden. Bestehende Catch-all-Grenzen muessen diese Fehler
  erkennbar durchreichen oder erst nach der zentralen Telemetry-Exit-Entscheidung
  behandeln.
* Ungueltige Telemetry-Konfiguration ist Exit `2`.
* Bei `--resume` muss ein explizites `--run-id` entweder fehlen oder exakt der
  `operationId` des geladenen Checkpoint-Manifests entsprechen. Abweichung ist
  Exit `2`, damit Telemetry und Resume nicht unterschiedliche Lauf-IDs tragen.
* Cancellation bleibt Exit `130`; Telemetry-Flush-/Close-Fehler duerfen diesen
  Exit-Code nicht ueberschreiben.

### Phase F - MCP- und Async-Job-Korrelation

MCP muss nicht sofort alle Events emittieren, aber das Modell muss darauf
vorbereitet sein.

Aufgaben:

1. MCP-Job-ID als `jobId` in `MigrationTelemetryContext` fuehren.
2. Idempotency-Key niemals roh fuehren; optional nur als
   `idempotency_key_hash`-Attribut aufnehmen.
3. Async-Job-Status und Telemetry nicht vermischen.
4. Artifact-Referenzen nur als IDs/Pfade aufnehmen, nicht als Payload.
5. Parent/Child-Runs fuer zusammengesetzte Tool-Aufrufe vorbereiten.

Beispiel:

```json
{
  "run_id": "run-abc",
  "job_id": "mcp-job-123",
  "operation_id": "schema-reverse-456",
  "command": "mcp.schema_reverse"
}
```

### Phase G - Tests und Verifikation

Unit-Tests:

* Eventmodell serialisiert stabil.
* No-op-Port verursacht keine Seiteneffekte.
* JSONL-Adapter schreibt gueltige JSON-Zeilen.
* Secrets werden nicht serialisiert.
* Attribut-Allowlist verhindert freie oder sensitive Keys.
* `MigrationTelemetryAttributes.of(...)` kopiert mutable Input-Maps nach
  Validierung in eine immutable, deterministisch geordnete Map.
* `best-effort` verschluckt kontrolliert Schreibfehler.
* `strict` meldet Schreibfehler sauber.
* JSONL-Adapter oeffnet neue Dateien fail-if-exists und Resume-Dateien im
  Append-Modus.
* JSONL-Adapter verwendet die dokumentierte direkte DSL-JSON-Dependency und
  keine Abhaengigkeit auf `adapters:driven:formats`.
* Telemetry-Output-Referenzhashes werden ohne rohe Pfade erzeugt und validiert.
* `TelemetryEndpointKind` verhindert freie Endpoint-Kind-Werte.

Runner-Tests:

* `data export` emittiert Run-/Table-/ChunkSummary-/Finish-Events im
  Default-Modus `summary` und `ChunkProcessed` im Modus `all`.
* `data import` emittiert Run-/Table-/ChunkSummary-/Finish-/Failure-Events im
  Default-Modus `summary` und `ChunkProcessed` im Modus `all`.
* `data transfer` emittiert Source/Target-Kontext.
* Fehlerpfade emittieren `RunFailed`.
* Cancellation-Pfade emittieren `RunCancelled` mit Exit-Code `130` fuer die
  Runner, die in diesem Milestone bereits einen `CancellationToken`-Pfad besitzen
  (`data import` und `data transfer`).
* `data export` erhaelt in diesem Milestone keine neue produktive
  Cancellation-Schnittstelle. Export-Telemetry muss vorhandene Export-Fehler als
  `RunFailed` abbilden; `RunCancelled` fuer Export bleibt einem Folge-Milestone
  vorbehalten, falls der Export-Runner einen `CancellationToken` bekommt.
* Telemetry-Flush-/Close-Fehler ueberschreiben keinen primaeren Fehler- oder
  Cancellation-Exit-Code.
* `strict`-Publish-Fehler innerhalb von Export-, Import- oder Transfer-
  Ausfuehrung werden nicht von Catch-all-Grenzen in Exit `5` umgemappt,
  sondern fuehren ohne Primaerfehler zu Exit `7`.

CLI-Tests:

* `--telemetry none` bleibt Default.
* `--telemetry jsonl --telemetry-output file` erzeugt Datei.
* `--run-id` akzeptiert nur sichere Checkpoint-Identifier gemaess
  `^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$`.
* `--run-id` mit Slash, Backslash, absolutem Pfad, `.` oder `..` endet mit
  Exit `2` und erzeugt keine Checkpoint-, Staging- oder Telemetry-Artefakte an
  unerwarteten Orten.
* Neuer JSONL-Lauf mit bereits vorhandener Ausgabedatei endet im `strict`-Modus
  ohne Primaerfehler mit Exit `7` und ueberschreibt die Datei nicht.
* Neuer JSONL-Lauf mit bereits vorhandener Ausgabedatei startet im
  `strict`-Modus den fachlichen Runner nicht.
* Neuer JSONL-Lauf mit bereits vorhandener Ausgabedatei degradiert im
  `best-effort`-Modus auf No-op-Telemetry, warnt genau einmal und fuehrt den
  Primaerlauf weiter.
* Neuer JSONL-Lauf mit fehlendem oder nicht schreibbarem Parent-Verzeichnis
  warnt im `best-effort`-Modus und fuehrt den Primaerlauf weiter.
* Neuer JSONL-Lauf mit fehlendem oder nicht schreibbarem Parent-Verzeichnis
  endet im `strict`-Modus ohne Primaerfehler mit Exit `7`.
* `--telemetry jsonl` ohne explizites `--telemetry-fail-mode` verwendet
  `best-effort`.
* `--run-id` erscheint in allen Events.
* `--run-id` auf einem neuen CLI-Lauf setzt dieselbe ID wie `operationId`.
* `--run-id` auf einem neuen `data export`-/`data import`-/`data transfer`-Lauf
  setzt dieselbe `operationId` auch bei `--telemetry none`.
* `--resume --run-id <abweichend>` endet mit Exit `2`.
* Resume mit passendem `--run-id` haengt an eine vorhandene Telemetry-Datei an,
  ohne alte Zeilen zu ueberschreiben.
* Resume mit aktivierter JSONL-Telemetry und abweichendem
  `--telemetry-output` gegen einen vorhandenen `telemetryOutputRefHash` im
  Manifest endet mit Exit `2`.
* Resume mit aktivierter JSONL-Telemetry und abweichendem
  `--telemetry-output` erzeugt vor dem Exit `2` keine neue JSONL-Datei am
  falschen Pfad.
* Resume mit Legacy-Manifest ohne `telemetryOutputRefHash` verwendet die
  explizit angegebene Telemetry-Datei append-only und ueberschreibt nichts.
* Aktivierte Telemetry fuer nicht angebundene Commands endet mit Exit `2`.
* ungueltige Kombinationen enden mit Exit `2`.

Integration:

* SQLite Export/Import mit JSONL-Telemetry.
* PostgreSQL/MySQL Testcontainers Transfer mit Telemetry-Smoke.
* Resume/Checkpoint-Lauf erzeugt `CheckpointSaved`.
* Resume/Checkpoint-Lauf erzeugt bei `--telemetry-chunk-events none|summary`
  keine chunkweise `CheckpointSaved`-Event-Flut.

---

## 6. Betroffene Dateien und Module

Voraussichtlich betroffen:

```text
hexagon/ports-common
  dev/dmigrate/observability/*

hexagon/application
  DataExportRunner
  DataImportRunner
  DataTransferRunner
  ExportCheckpointManager
  ImportCheckpointManager
  TransferExecutor
  ggf. gemeinsame Runner-Kontextobjekte

hexagon/ports-write
  CheckpointManifest mit optionalem Top-Level-telemetryOutputRefHash

adapters/driven/streaming
  FileCheckpointStore YAML-Serialisierung fuer telemetryOutputRefHash
  Manifest-Version- und Legacy-Roundtrip-Tests

adapters/driven
  observability-jsonl/
  observability-jsonl/build.gradle.kts

adapters/driving/cli
  build.gradle.kts
  globale CLI-Optionen
  Wiring
  Tests

settings.gradle.kts

spec/architecture.md
spec/cli-spec.md
spec/mcp-server.md

docs/user/guide.md
docs/user/quality.md
docs/planning/in-progress/roadmap.md
CHANGELOG.md
```

---

## 7. Akzeptanzkriterien

* Es gibt einen neutralen `MigrationTelemetryPort` ohne Vendor-Abhaengigkeit.
* Der Default bleibt voll rueckwaertskompatibel: ohne Flags wird keine
  Telemetry-Datei erzeugt.
* `data export`, `data import` und `data transfer` erzeugen bei aktivierter
  JSONL-Telemetry nachvollziehbare Run-, Table-, Chunk-Summary- und
  Abschlussereignisse.
* Events enthalten keine Secrets, keine JDBC-Credentials und keine Rohdaten.
* Freie Telemetry-Attribute sind allowlistiert, laengenbegrenzt und defensiv
  gescrubbt.
* `--run-id` erlaubt reproduzierbare Korrelation ueber CI/CD-Logs hinweg.
* Im CLI-Pfad gibt es keine divergierenden `runId`-/`operationId`-Werte.
* JSONL-Ausgabe ist deterministisch genug fuer Tests und CI-Auswertung.
* JSONL-Dateien werden bei neuen Laeufen nicht versehentlich ueberschrieben;
  Resume-Laeufe haengen an vorhandene Telemetry-Dateien an.
* Checkpoint-faehige Resume-Laeufe validieren eine aktivierte JSONL-
  Ausgabereferenz gegen den Manifest-`telemetryOutputRefHash`, ohne rohe lokale
  Pfade im Manifest zu speichern.
* No-op-, Erfolgs- und Fehlerpfade sind getestet.
* Cancellation wird fuer die in diesem Milestone cancellable Runner als
  `RunCancelled` mit Exit `130` sichtbar und erzeugt keine falschen
  Abschlussereignisse.
* Telemetry-Flush-/Close-Fehler koennen Primaerfehler oder Cancellation nicht
  maskieren.
* Die bestehende Progress-Ausgabe regressiert nicht.
* Coverage-Gates werden nicht abgesenkt.
* Dokumentation beschreibt klar, dass DataKitchen/OpenTelemetry Adapter spaetere
  optionale Erweiterungen sind.
* Dokumentation beschreibt klar, dass `schema reverse`, `schema compare` und
  `data profile` erst in Folgephasen produktiv an Telemetry angeschlossen
  werden.

---

## 8. Verifikation

Gezielte Tests:

```bash
docker build --target build \
  --build-arg GRADLE_TASKS=":hexagon:ports-common:test :hexagon:ports-write:test :hexagon:application:test :adapters:driven:streaming:test :adapters:driven:observability-jsonl:test :adapters:driving:cli:test --rerun-tasks" \
  -t d-migrate:telemetry-tests .
```

Vollstaendiger Build:

```bash
docker build --target build \
  --build-arg GRADLE_TASKS="build --rerun-tasks" \
  -t d-migrate:telemetry-build .
```

Runtime-Smoke:

```bash
docker build -t d-migrate:telemetry .
```

CLI-Smoke:

```bash
docker run --rm \
  -v "$(pwd):/work" \
  d-migrate:telemetry \
  --telemetry jsonl \
  --telemetry-output /work/build/d-migrate-run.jsonl \
  data export \
  --source sqlite:///work/test-fixtures/telemetry-smoke.db \
  --format json \
  --tables users \
  --output /work/build/telemetry-users.json
```

Der Smoke setzt eine kleine SQLite-Fixture voraus. Diese Fixture wird im
E2E-Test oder im Smoke-Script vor dem Docker-Lauf erzeugt; `schema validate`
ist kein Smoke fuer diesen Milestone, solange Schema-Kommandos nicht im
Telemetry-Scope liegen.

JSONL-Pruefung:

```bash
jq -c . build/d-migrate-run.jsonl > /dev/null
```

Transfer-Smoke mit Testcontainers:

```bash
./scripts/test-integration-docker.sh :adapters:driving:cli:test --tests '*Telemetry*'
```

---

## 9. Risiken und offene Fragen

### 9.1 Event-Flut bei grossen Tabellen

Chunk-Events koennen bei sehr grossen Migrationen schnell zu grossen Dateien
fuehren.

Entscheidung:

* Default `summary`
* `all` nur explizit
* spaeter Sampling/Rate-Limiting moeglich

### 9.2 Vermischung von Progress und Telemetry

Die bestehende CLI-Fortschrittsanzeige darf nicht zur Telemetry-API werden.

Entscheidung:

* Progress bleibt CLI-/stderr-orientiert
* Telemetry bleibt maschinenlesbar und adapterfaehig

### 9.3 Vendor-Leakage

OpenTelemetry- oder DataKitchen-Begriffe duerfen nicht in den Port wandern.

Entscheidung:

* Vendor-Semantik nur in Adaptern
* Port bleibt fachlich

### 9.4 Secret-Leaks

Observability ist ein typischer Ort fuer versehentliche Secret-Leaks.

Entscheidung:

* keine JDBC-URLs im Eventmodell
* Connection-Namen statt URLs
* keine ungepruefte Uebernahme von `ConnectionConfig.database`, weil dieser
  Wert SQLite-Dateipfade oder produktive Datenbanknamen enthalten kann
* Datenbank-/Dateireferenzen nur allowlistiert, gekuerzt oder gehasht
* Attribute nur ueber Allowlist und defensive Redaction
* Tests fuer Credential-Redaction
* Doku in `docs/user/quality.md`

### 9.5 MCP-Korrelation

MCP-Jobs, Idempotenz und Artifacts koennen spaeter komplexer werden.

Entscheidung:

* Kontextfelder jetzt vorbereiten
* vollstaendige MCP-Telemetry erst in Folgephase erzwingen

---

## 10. Aufgeloeste Scope-Entscheidungen

* Der neue Port heisst fachlich `MigrationTelemetryPort`.
* Der Port liegt in `hexagon:ports-common`.
* Der No-op-Default liegt ebenfalls in `hexagon:ports-common`.
* Erster produktiver Adapter ist JSONL.
* DataKitchen ist nicht Teil dieses Milestones.
* OpenTelemetry ist nicht Teil dieses Milestones.
* Produktive Telemetry-Emission fuer `schema reverse`, `schema compare` und
  `data profile` ist nicht Teil dieses Milestones.
* CLI-Default bleibt `--telemetry none`.
* Events enthalten keine Secrets und keine Nutzdaten.
* CLI-`runId` und bestehende `operationId` sind fuer neue Laeufe identisch;
  Resume-Laeufe uebernehmen die Manifest-`operationId`.
* Cancellation ist ein eigener Terminalzustand und bleibt Exit `130`.
* Optionale Attribute sind allowlistiert; Idempotency-Keys werden hoechstens
  gehasht aufgenommen.
* Endpoint-Daten werden nie ungeprueft aus Connection-URLs oder
  `ConnectionConfig.database` serialisiert.
* Progress-Ausgabe und Telemetry bleiben getrennte Schnittstellen.
