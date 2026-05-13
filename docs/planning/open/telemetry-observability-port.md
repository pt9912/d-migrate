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

- Migrationen, Exporte, Importe, Transfers und Profiling-Laeufe nachvollziehbar machen
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
  - perspektivisch `schema reverse`
  - perspektivisch `data profile`
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
* `close()` delegiert auf `flush()`, damit Adapter im CLI- und Async-Pfad
  einheitlich in `finally`-Bloecken geschlossen werden koennen.
* Der rohe Port kennt keinen CLI-Fail-Mode. `best-effort` und `strict` werden
  im Wiring ueber einen Guard/Decorator umgesetzt:
  * `best-effort` faengt Schreib-/Flush-Fehler ab, gibt hoechstens eine
    deduplizierte Warnung aus und laesst den Primaerlauf weiterlaufen.
  * `strict` laesst Schreib-/Flush-Fehler bis zum Runner/CLI-Wiring
    propagieren; wenn kein spezifischer Primaerfehler bereits den Exit-Code
    bestimmt, wird daraus Exit `7` (`LOCAL_ERROR`).

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

Nicht erlaubt:

* komplette JDBC-URL mit Credentials
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
und defensives Secret-Scrubbing. Fuer den ersten Milestone reichen technische
Keys wie `environment`, `artifact_ref`, `checkpoint_ref` und
`idempotency_key_hash`. Keine Call-Site darf einen rohen Idempotency-Key, eine
URL, SQL oder Filterliterale als Attribut durchreichen.

Endpoint:

```kotlin
data class TelemetryEndpoint(
    val kind: String,
    val name: String? = null,
    val dialect: DatabaseDialect? = null,
    val database: String? = null,
    val schema: String? = null,
)
```

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
data class StepStarted(...)
data class StepFinished(...)
data class TableStarted(...)
data class TableFinished(...)
data class ChunkProcessed(...)
data class CheckpointSaved(...)
```

`SchemaDriftDetected` bleibt fuer spaetere Schema-/Compare-Pfade vorgemerkt,
ist aber kein verbindlicher Eventtyp dieses Milestones, solange
`schema reverse`, `schema compare` oder `data profile` nicht produktiv an den
Telemetry-Port angeschlossen werden.

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
2. `adapters/driven/observability-jsonl/build.gradle.kts` haengt an
   `:hexagon:ports-common` und verwendet den bestehenden JSON-Stack.
3. `adapters/driving/cli/build.gradle.kts` bekommt eine
   `implementation(project(":adapters:driven:observability-jsonl"))`-Abhaengigkeit.

Aufgaben:

1. Event-Serialisierung mit bestehendem JSON-Stack.
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

Beispielausgabe:

```json
{"type":"run_started","run_id":"...","command":"data transfer","timestamp":"..."}
{"type":"table_started","run_id":"...","table":"customers","timestamp":"..."}
{"type":"chunk_processed","run_id":"...","table":"customers","chunk_index":1,"rows_read":10000,"rows_written":10000,"duration_ms":812}
{"type":"table_finished","run_id":"...","table":"customers","status":"SUCCESS","rows_read":10000,"rows_written":10000,"duration_ms":920}
{"type":"run_finished","run_id":"...","status":"SUCCESS","duration_ms":18441}
```

### Phase D - Application-Wiring

Betroffene Runner:

* `DataExportRunner`
* `DataImportRunner`
* `DataTransferRunner`
* `TransferExecutor`
* spaeter `SchemaReverseRunner`
* spaeter `DataProfileRunner`

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
* `summary`: keine Events pro einzelnem Chunk. Stattdessen werden pro Tabelle
  aggregierte Chunk-Zaehler und Dauerwerte in `TableFinished` und optional in
  einem `ChunkSummary`-Event ausgegeben.
* `all`: jedes bestaetigte Chunk erzeugt ein `ChunkProcessed`-Event.

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
* Ungueltige Telemetry-Konfiguration ist Exit `2`.

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
* `best-effort` verschluckt kontrolliert Schreibfehler.
* `strict` meldet Schreibfehler sauber.

Runner-Tests:

* `data export` emittiert Run-/Table-/Chunk-/Finish-Events.
* `data import` emittiert Run-/Table-/Finish-/Failure-Events.
* `data transfer` emittiert Source/Target-Kontext.
* Fehlerpfade emittieren `RunFailed`.

CLI-Tests:

* `--telemetry none` bleibt Default.
* `--telemetry jsonl --telemetry-output file` erzeugt Datei.
* `--run-id` erscheint in allen Events.
* ungueltige Kombinationen enden mit Exit `2`.

Integration:

* SQLite Export/Import mit JSONL-Telemetry.
* PostgreSQL/MySQL Testcontainers Transfer mit Telemetry-Smoke.
* Resume/Checkpoint-Lauf erzeugt `CheckpointSaved`.

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
  ggf. gemeinsame Runner-Kontextobjekte

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
* JSONL-Ausgabe ist deterministisch genug fuer Tests und CI-Auswertung.
* No-op-, Erfolgs- und Fehlerpfade sind getestet.
* Die bestehende Progress-Ausgabe regressiert nicht.
* Coverage-Gates werden nicht abgesenkt.
* Dokumentation beschreibt klar, dass DataKitchen/OpenTelemetry Adapter spaetere
  optionale Erweiterungen sind.

---

## 8. Verifikation

Gezielte Tests:

```bash
docker build --target build \
  --build-arg GRADLE_TASKS=":hexagon:ports-common:test :hexagon:application:test :adapters:driven:observability-jsonl:test :adapters:driving:cli:test --rerun-tasks" \
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
* CLI-Default bleibt `--telemetry none`.
* Events enthalten keine Secrets und keine Nutzdaten.
* Optionale Attribute sind allowlistiert; Idempotency-Keys werden hoechstens
  gehasht aufgenommen.
* Progress-Ausgabe und Telemetry bleiben getrennte Schnittstellen.
