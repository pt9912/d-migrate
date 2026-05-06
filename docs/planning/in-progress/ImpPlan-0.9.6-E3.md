# Implementierungsplan: 0.9.6 - Phase E3 `Async-Executor Production-Tuning`

> **Milestone**: 0.9.6 - Beta: MCP-Server
> **Phase**: E3 (Sub-Plan zu Phase E — `Async-Executor Production-Tuning`)
> **Status**: Approved (2026-05-06) — Architektur-Approval § 3 erteilt,
> § 10 Q1–Q6 entschieden (siehe § 10 „Resolved"). Vorlauf:
> Entwurf (2026-05-05).
> **Positionierung**: parallel zu `ImpPlan-0.9.6-E2.md` (persistente
> Adapter); kein Hard-Dependency in beiden Richtungen — siehe § 0.
> **Referenz**:
> `hexagon/application/src/main/kotlin/dev/dmigrate/server/application/job/JobDispatcher.kt`
> (`SyncExecutor`-Default + `executor: Executor`-Auspraegungs-Punkt);
> `hexagon/application/src/main/kotlin/dev/dmigrate/server/application/job/JobWorkerFactory.kt`
> (`PassthroughJobWorkerFactory`);
> `hexagon/application/src/main/kotlin/dev/dmigrate/server/application/job/JobStartOrchestrator.kt`
> (Auto-Dispatch-Pfad);
> `adapters/driving/mcp/src/main/kotlin/dev/dmigrate/mcp/registry/PhaseEWiring.kt`
> (`workerExecutor`/`jobWorkerFactory`-Wiring);
> `docs/planning/in-progress/ImpPlan-0.9.6-E.md` (Phase-E-Bezug);
> `spec/job-contract.md`; `spec/ki-mcp.md`.

---

## 0. Positionierung — warum E3, parallel zu E2

E3 ist ein **Sub-Plan zu Phase E**, eigenständig zu E2 (Persistenz):

- **E2** schließt die Atomicity-Lücke: persistente Backings für die
  Phase-E-Ports.
- **E3** schließt die Skalierbarkeits-Lücke: aus dem `SyncExecutor`-
  Default wird ein produktionsfähiger Async-Executor mit Lifecycle,
  Backpressure und Observability — opt-in via Config.

Beide Pläne sind **inhaltlich orthogonal**. E3 berührt keine
persistenten Phase-E-Port-Backings; es erweitert aber den
Start-/Dispatch-Vertrag minimal um Executor-Admission und
RateLimit-Diagnostik. Reihenfolge ist frei wählbar; im Doppel-Lauf
(E2 + E3 parallel) sind nur geringe Merge-Konflikte erwartbar
(`adapters/driven/persistence-jdbc` vs. überwiegend
`hexagon/application` / `adapters/driving/mcp`).

## 1. Ziel

`JobDispatcher` läuft heute per Default auf `SyncExecutor`, der
`Runnable.run()` auf dem Caller-Thread macht. Das ist:

- ✅ ideal für Tests, MVP-Bootstrap, Single-Tenant-Dev
- ❌ ungeeignet für Production: tools/call blockiert für die volle
  Job-Dauer; mehrere konkurrente tools/call serialisieren auf dem
  MCP-Request-Thread; kein Backpressure; kein Lifecycle (Drain bei
  Shutdown).

E3 liefert:

1. einen produktionsfähigen `BoundedAsyncJobExecutor` (bounded
   ThreadPool + Queue + Reject-Policy)
2. ein klar getrenntes Lifecycle-Interface (`JobExecutorLifecycle`),
   das vom Host gemanaged wird — Dispatcher bleibt agnostisch
3. eine Config-getriebene Wahl
   `server.jobs.executor.mode = sync|async` (Default: `sync`,
   damit MVP/Tests stabil bleiben)
4. Backpressure-Pfad: Executor-Admission vor `JobStartTransaction.commit`
   → `JobStartOutcome.RateLimited` mit Reason-Discriminator
   `EXECUTOR_SATURATED` und `retryAfter` aus Config; bei Saturation
   entsteht **kein** JobStore-Eintrag.
5. Cancel-while-queued: queued-but-not-started Jobs bleiben durch den
   bestehenden `JobCancelService`-CAS `QUEUED -> CANCELLED` terminal;
   der spaeter startende Dispatcher fuehrt den Worker nicht mehr aus.
6. strukturierte Observability-Hooks (Log-Events
   `job.dispatch.scheduled` / `…started` / `…finished`; einfache
   Counter-Schnittstelle für Pool-Status)
7. graceful shutdown mit timeout (drain in-flight, reject new)

## 2. Motivation

`JobDispatcher.kt:39-44` (KDoc):

> `[executor]` ist der Auspraegungs-Punkt fuer sync-vs-async:
> - `[SyncExecutor]` (Default) laeuft auf dem Caller-Thread …
> - Eine `ExecutorService.asExecutor()` (Java) waere die Production-
>   Variante; der Dispatcher selbst owned ihre Lifecycle nicht.

Der KDoc ist das Versprechen; E3 löst es ein. Auto-Dispatch
(Phase-E Review-Fix #1) hat den Sync-Pfad fest verdrahtet:
`tools/call` blockiert bis Worker fertig. Für tatsächliche
Production-MCP-Loads ist das nicht haltbar — selbst kurze Jobs
multiplizieren sich, wenn der Server mehrere Tenants bedient.

## 3. Architektur-Entscheidungen

> Jede Entscheidung ist **Vorschlag**. Begründung kompakt; offene
> Fragen in § 10.

### 3.1 SyncExecutor bleibt Default

- **Wahl**: kein Default-Flip auf async. CI/Tests/MVP-Bootstrap
  laufen weiter deterministisch, Phase-E-Akzeptanz-Pins müssen
  nicht angepasst werden.
- Async ist **opt-in**, kein Stiller-Switch.

### 3.2 Async-Executor: bounded `ThreadPoolExecutor`

- **Wahl**: `ThreadPoolExecutor(corePoolSize, maxPoolSize,
  keepAliveSeconds, ArrayBlockingQueue(queueCapacity),
  customRejectedExecutionHandler)` plus vorgelagerter
  `JobDispatchAdmission`-Gate — fixe Pool-Größe, bounded queue,
  benannte Threads (`d-migrate-worker-{n}`), uncaught-handler liftet
  Exceptions in Logs.
- **Defaults**:
  - `corePoolSize = maxPoolSize = 4` (fixer Default; CPU-basierte
    Werte nur per expliziter Config — verhindert überraschend große
    Pools auf großen Hosts)
  - `queueCapacity = 1024`
  - `keepAliveSeconds = 60` (irrelevant bei core==max, falls aber per
    Config `allowCoreThreadTimeOut` aktiviert)
- **Nicht**: `CachedThreadPool` (unbounded, Thread-Storm-Risiko);
  `WorkStealingPool` (für unsere Workload kein Vorteil; weniger
  vorhersagbar).
- **Virtual Threads (Java 21+)**: out of scope für E3 (siehe § 9);
  Architektur erlaubt späteren Drop-in via Config-Flag.

### 3.3 Lifecycle: `JobExecutorLifecycle` getrennt vom Dispatcher

- **Wahl**: neue Interfaces

  ```kotlin
  interface JobDispatchAdmission {
      fun tryAcquire(now: java.time.Instant): JobDispatchAdmissionOutcome
  }

  sealed interface JobDispatchAdmissionOutcome {
      data class Granted(val permit: JobDispatchPermit) : JobDispatchAdmissionOutcome
      data class Saturated(
          val retryAfter: java.time.Duration,
          val current: Long,
          val limit: Long,
      ) : JobDispatchAdmissionOutcome
      data object Closed : JobDispatchAdmissionOutcome
  }

  fun interface JobDispatchPermit : AutoCloseable {
      /** Idempotent, no-throw. Implementierungen loggen/suppressen Release-Fehler. */
      override fun close()
  }

  interface JobExecutorLifecycle {
      fun status(): JobExecutorStatus  // active, queued, completed, rejected, capacity
      fun shutdown(timeout: java.time.Duration): Boolean  // graceful drain
  }
  ```

  Implementations:
  - `SyncJobDispatchAdmission` (immer `Granted`, no-op permit)
  - `BoundedAsyncJobDispatchAdmission` (Semaphore über
    `maxThreads + queueCapacity`; `Closed` waehrend Shutdown)
  - `SyncExecutorLifecycle` (no-op `shutdown`, status-counters lokal)
  - `BoundedAsyncJobExecutorLifecycle` (delegiert an `ThreadPoolExecutor`
    und schliesst Admission vor `shutdown`)
- Der Host (MCP-Server-Bootstrap) registriert das Lifecycle für JVM-
  Shutdown-Hooks. **Dispatcher selbst kennt das Lifecycle-Interface
  NICHT** — er sieht weiter nur `Executor`. Der Orchestrator sieht
  dagegen die Admission-Schnittstelle, weil nur er Saturation vor dem
  Job-Commit in eine Start-Antwort mappen kann.

### 3.4 Configuration-Shape

```yaml
server:
  jobs:
    executor:
      mode: sync                      # sync | async   (default: sync)
      async:
        coreThreads: ${default}       # CPU-based default
        maxThreads: ${default}
        queueCapacity: 1024             # must be >= 1 for ArrayBlockingQueue
        retryAfterMillis: 1000        # converted to RateLimited.retryAfter
        shutdownTimeoutMillis: 30000
```

- `mode: sync` (Default) ⇒ kein Async-Setup, alle Async-Felder
  ignoriert.
- `mode: async` ⇒ Async-Block validiert, Pool + Admission-Gate werden
  zur Bootstrap-Zeit erzeugt.
- **Lokation**: `JobExecutorConfig` lebt **adapter-neutral** in
  `hexagon/application` (Executor-/Admission-Felder, keine YAML-/Env-
  Kenntnisse). `McpJobExecutorConfig` im MCP-Adapter mappt darauf;
  YAML/Env-Loader bleibt strikt im Host-/Entrypoint-Scope.
- Vorgeschlagene Env-Namen (Host-/Entrypoint-Scope, nicht
  `hexagon/application`):
  `D_MIGRATE_SERVER_JOBS_EXECUTOR_MODE`,
  `D_MIGRATE_SERVER_JOBS_EXECUTOR_CORE_THREADS` etc.

### 3.5 Backpressure: Admission-Saturation → `RateLimited`

- **Wahl**: Der Orchestrator fragt **vor** `jobBuilder` und
  `JobStartTransaction.commit` das `JobDispatchAdmission`-Gate. Bei
  `Saturated` mappt er auf die bestehende Wire-Klasse
  `RATE_LIMITED`; der `RateLimited`-DTO bekommt am Ende ein
  rueckwaertskompatibles Feld `reason: String = "ACTIVE_JOBS_QUOTA"`.
  Executor-Saturation setzt `reason = "EXECUTOR_SATURATED"`.
- `reason` ist **immer** im Wire-Envelope sichtbar — auch für
  bestehende Quota-Rejections (`ACTIVE_JOBS_QUOTA`). Operations
  unterscheidet auf einen Blick zwischen Tenant-Quota und Pool-
  Saturation, ohne Log-Korrelation.
- Caller-POV bleibt identisch mit Quota-Reject: „später nochmal";
  `reason` dient nur zur Diagnose/Operations-Reaktion.
- **Nicht**: `CallerRunsPolicy` (mischt Sync- und Async-Semantik —
  unter Last würden tools/call-Threads doch wieder Jobs ausführen);
  unbounded Queue (OOM-Risiko).
- Admission greift VOR dem Job-Commit. Ein abgewiesener Start erzeugt
  deshalb keine JobStore-Zeile und kein Worker-Handle. Die
  Idempotency-Reservation bleibt wie bei anderen retrybaren
  Start-Rejects pending und expired regulaer nach Lease-TTL.
- Admission soll vor der Quota-Reservation laufen. Falls die
  Implementierung aus Code-Naehe erst nach Quota reserviert, muss der
  Executor-Saturation-Pfad den Quota-Owner synchron refunden.
- Nach erfolgreicher Admission darf `executor.execute` im Normalpfad
  nicht mehr wegen Kapazitaet rejecten. Shutdown schliesst zuerst die
  Admission; falls eine Shutdown-Race nach dem Commit dennoch
  `RejectedExecutionException` erzeugt, muss der Orchestrator den
  gerade committeten Job deterministisch terminalisieren
  (`FAILED`, `error.code=EXECUTOR_CLOSED`) und weiter
  `Started(jobId, record, source)` returnen, damit der Caller den
  stabilen Job-Ref pollt. Dieser seltene Race-Pfad bekommt einen
  eigenen Test.
- Nach erfolgreichem `JobStartTransaction.commit` duerfen
  Setup-Fehler nicht als rohe Exception bis zum Tool-Handler
  durchschlagen. Fehlender Worker sowie Fehler in WorkerHandle-Register,
  Worker-Factory oder Dispatch-Submit markieren den bereits committeten
  Job pollbar als `FAILED` (`error.code=WORKER_NOT_REGISTERED`,
  `EXECUTOR_SETUP_FAILED` bzw. `EXECUTOR_CLOSED`). Wenn diese
  primaere Markierung persistiert ist, gibt der Handler weiterhin
  `Started(jobId, record, source)` zurueck. Der Caller erhaelt damit
  einen stabilen Job-Ref und sieht den Fehler ueber `job_status_get`.
  Nach erfolgreicher
  `QUEUED -> FAILED`-Transition gibt der Orchestrator den
  Quota-Owner frei; wenn bereits ein Worker-Handle registriert wurde,
  wird es unregistert.
- `Closed` vor dem Job-Commit darf nicht nur ein synthetisches
  `Failed` returnen. Weil der Idempotency-Slot bereits reserviert ist,
  muss der Orchestrator den Slot via `idempotencyStore.markFailed(...)`
  in einen deterministischen `FAILED`-Replay-Zustand bringen
  (kurze Retention, z.B. `now + retryAfter`/`now + 1s`) oder
  alternativ explizit einen retrybaren `Pending`-Lease-Refresh
  implementieren. E3 waehlt `markFailed`, damit der Start-Pfad keinen
  stale `PENDING`-Slot hinterlaesst.

### 3.6 Cancel-while-queued

- **Problem**: ein submitted-but-not-started Job darf bei eingehendem
  `job_cancel` den Worker nicht starten. Der aktuelle Bestand loest
  QUEUED-Cancel bereits durch `JobCancelService.cancelQueuedJob` als
  CAS `QUEUED -> CANCELLED`; dabei wird **kein**
  `CancellationTokenSource` signalisiert, weil der Worker noch nicht
  laeuft.
- **Wahl**: Der Dispatcher bleibt Status-getrieben. Wenn
  `transitionStatus(QUEUED -> RUNNING)` mit `current=CANCELLED`
  scheitert, wertet er das als `DispatchSkippedCancelled`/
  `JobWorkerOutcome.Cancelled` ohne Worker-Aufruf und ohne
  `applyTerminal`-Write. Der Job ist bereits durch `JobCancelService`
  terminal, inklusive `signalAcked=true`, `ackedAt` und Reason.
- Ein Token-Vorabcheck ist nur ein optionaler Fast-Path fuer RUNNING-
  Cancel-Races. Der korrekte queued-cancel-Vertrag haengt nicht am
  Token, sondern am JobStore-CAS.

### 3.7 Observability

- **Wahl**: drei strukturierte Log-Events am Dispatcher-Boundary:
  - `job.dispatch.scheduled` (jobId, tenant, tool, queueDepth)
  - `job.dispatch.started` (jobId, tenant, tool, waitMs)
  - `job.dispatch.finished` (jobId, tenant, tool, status,
    durationMs, errorCode?)
- `JobExecutorStatus`-Snapshot über `lifecycle.status()` für Host-/
  Test-Zugriff. Ein HTTP-Health-Endpoint ist **nicht** Teil von E3
  (siehe § 9), weil der MCP-Server heute keinen Health-Routing-
  Vertrag hat.
- **Nicht**: Micrometer-/OpenTelemetry-Integration in E3
  (Folgeprojekt — siehe § 9).
- **Kein Audit-Event pro Executor-Reject**: Wire-Code (`RATE_LIMITED`
  + `reason=EXECUTOR_SATURATED`), strukturierte Logs und
  `JobExecutorStatus`-Snapshot reichen für Diagnose. Audit unter
  Saturation könnte selbst zum Bottleneck werden.

### 3.8 Keine Coroutines

- **Wahl**: Plain `java.util.concurrent` — konsistent mit
  bestehendem `JobDispatcher` (`Executor`, `CompletableFuture`).
- **Nicht**: `kotlinx.coroutines.Dispatchers.IO` o.ä. — würde
  Context-Plumbing durch alle Worker erzwingen, ohne neuen Vorteil
  über bounded ThreadPool.

## 4. Konfiguration und Wiring (Skizze)

> Endgültiger Code kommt mit E3.4/E3.5; folgendes ist die
> Architektur-Vorabstimmung.

```kotlin
// neues File: hexagon/application/.../job/JobExecutorFactory.kt
object JobExecutorFactory {
    fun create(config: JobExecutorConfig): JobExecutorBundle = when (config.mode) {
        Mode.SYNC -> JobExecutorBundle(
            executor = SyncExecutor,
            admission = SyncJobDispatchAdmission,
            lifecycle = SyncExecutorLifecycle,
        )
        Mode.ASYNC -> {
            val admission = BoundedAsyncJobDispatchAdmission(config.async)
            val pool = boundedThreadPool(config.async)
            JobExecutorBundle(
                executor = pool,
                admission = admission,
                lifecycle = BoundedAsyncJobExecutorLifecycle(pool, admission, config.async),
            )
        }
    }
}

data class JobExecutorBundle(
    val executor: Executor,
    val admission: JobDispatchAdmission,
    val lifecycle: JobExecutorLifecycle,
)
```

`PhaseEWiring` bekommt zusätzlich `executorBundle: JobExecutorBundle`
(Default: `JobExecutorFactory.create(JobExecutorConfig.SYNC_DEFAULT)`).
`workerExecutor` bleibt als deprecated-/compat-Shortcut erhalten oder
wird intern aus `executorBundle.executor` gespeist. Der MCP-Bootstrap
liest seine typisierte `McpServerConfig`, baut den Bundle, gibt
`executorBundle.executor` an `JobDispatcher` und
`executorBundle.admission` an `JobStartOrchestrator`, und registriert
das Lifecycle für `Runtime.addShutdownHook` bzw. Ktor
`ApplicationStopping`.

## 5. Work-Packages

> Jeder AP endet mit grünem
> `make docker-check MODULES=":hexagon:application :adapters:driving:mcp"`.

| AP | Inhalt | Akzeptanz |
|---|---|---|
| **E3.1** | `JobDispatchAdmission` + `JobExecutorLifecycle` + Sync-Implementierungen + `BoundedAsyncJobDispatchAdmission`/`BoundedAsyncJobExecutor` (Pool-Konstruktion mit benannten Threads, Reject-Handler, Lifecycle-Wrapper) — alles in `hexagon/application/.../job/` | Unit-Tests: Admission vergibt exakt `maxThreads + queueCapacity` Permits; weiteres Acquire liefert `Saturated`; Permit-Release ist idempotent/no-throw und macht Kapazitaet frei; `shutdown(timeout)` schliesst Admission, drainiert in-flight; uncaught Exceptions werden geloggt ohne Pool-Death |
| **E3.2** | `JobExecutorConfig`-Datenklasse + `JobExecutorFactory.create(config)` + Validierung (z.B. `coreThreads > 0`, `maxThreads >= coreThreads`, `queueCapacity > 0` bei `ArrayBlockingQueue`) | Factory-Test: SYNC liefert `SyncExecutor`+SyncAdmission+no-op-Lifecycle; ASYNC liefert Pool/Admission mit konfigurierten Werten; ungültige Werte werfen `IllegalArgumentException` |
| **E3.3** | Admission-Pfad im Auto-Dispatch-Zweig von `JobStartOrchestrator.commitJob` VOR `jobBuilder`/`JobStartTransaction.commit`; `RateLimited.reason="EXECUTOR_SATURATED"`; `Closed` markiert Idempotency als failed; kein Admission-Acquire ohne Dispatcher/Factory; Permit-Release bei Quota-Reject, Commit-Failure, missing Worker und Job-Terminal; post-commit Setup-Fehler terminalisieren den Job pollbar und cleanen Quota/Handle | Tests: voll ausgelastete Admission ⇒ tools/call-Antwort ist `RATE_LIMITED`; KEINE JobStore-Zeile, KEIN WorkerHandle; `Closed` liefert deterministischen Failed-Replay statt stale Pending; Idempotency-Reservation expired regulaer; bei `dispatcher == null` oder `factory == null` wird kein Permit acquired; nach Quota-Reject/`worker == null`/abgeschlossenem Job wird ein Permit frei; `worker == null` setzt den committeten Job auf `FAILED(error.code=WORKER_NOT_REGISTERED)`, released Quota, unregistert das bereits registrierte Handle und returnt nach erfolgreicher Markierung `Started`; `workerHandleRegistry.register`, `factory.create` und `dispatcher.dispatch`-Fehler nach Commit setzen Job `FAILED`, releasen Quota, unregistern ggf. das Handle und returnen nach erfolgreicher Markierung `Started`; JobStore-Fehler beim Markieren werden nicht suppressed |
| **E3.4** | Cancel-while-queued: `JobDispatcher.runOnce` behandelt `transitionStatus(QUEUED→RUNNING)` mit `current=CANCELLED` als skip/cancelled ohne Worker-Aufruf und ohne `applyTerminal` | Tests: Cancel nach Submit, aber vor Worker-Start ⇒ Job bleibt CANCELLED; Worker `execute()` wird NIE aufgerufen; `signalAcked = true`, `ackedAt` und Reason stammen aus `JobCancelService`; Quota wird nicht doppelt released |
| **E3.5** | Wiring: `PhaseEWiring.executorBundle` + `McpServerConfig.jobs.executor` + Bootstrap-Code im MCP-Server-Entrypoint (Bundle-Aufbau, Shutdown-Hook-/ApplicationStopping-Registry) | E2E-Test: Async-Modus startet/stoppt sauber; bei Shutdown laufen in-flight-Jobs zu Ende oder werden nach Timeout interrupted; KEIN Thread-Leak laut JMX-Snapshot/Tests |
| **E3.6** | Observability: drei strukturierte Log-Events + `JobExecutorStatus`-Snapshot über Lifecycle/API fuer Tests und zukuenftige Hosts; kein HTTP-Health-Routing | Tests prüfen die Log-Felder pro Event; Snapshot zeigt active/queued/completed/rejected/capacity-Counts |
| **E3.7** | Doku: Operations-Guide (`docs/operations/job-executor.md` o.ä.) — Pool-Sizing, Saturation-Symptome, Shutdown-Verhalten, Sync-vs-Async-Tradeoffs; Cross-Ref aus `spec/ki-mcp.md`/`spec/job-contract.md` | Doku reviewed; Operations-Workflow durchgespielt: Tenant meldet 429-Spike ⇒ Operator prueft Logs/Status-Snapshot, dreht `coreThreads` oder `queueCapacity` hoch |

Schätzung: E3.1–E3.4 je ~1 Sub-Commit-Zyklus; E3.5+E3.6 zusammen
ein größerer Zyklus; E3.7 doku-only. Gesamt ~6–8 Commits.

## 6. Code-Skizzen für die kritischen Stellen

### 6.1 BoundedAsyncJobExecutor + Admission

```kotlin
class BoundedAsyncJobDispatchAdmission(
    private val cfg: JobExecutorConfig.Async,
) : JobDispatchAdmission {
    private val capacity = cfg.maxThreads + cfg.queueCapacity
    private val permits = Semaphore(capacity)
    private val accepting = AtomicBoolean(true)

    override fun tryAcquire(now: Instant): JobDispatchAdmissionOutcome {
        if (!accepting.get()) return JobDispatchAdmissionOutcome.Closed
        return if (permits.tryAcquire()) {
            JobDispatchAdmissionOutcome.Granted(JobDispatchPermit { permits.release() })
        } else {
            JobDispatchAdmissionOutcome.Saturated(
                retryAfter = cfg.retryAfter,
                current = capacity.toLong(),
                limit = capacity.toLong(),
            )
        }
    }

    fun closeForShutdown() {
        accepting.set(false)
    }
}

internal class BoundedAsyncJobExecutor(
    cfg: JobExecutorConfig.Async,
) : Executor {
    private val rejectedCounter = AtomicLong(0)
    private val pool = ThreadPoolExecutor(
        cfg.coreThreads, cfg.maxThreads,
        cfg.keepAliveSeconds, TimeUnit.SECONDS,
        ArrayBlockingQueue(cfg.queueCapacity),
        NamedDaemonThreadFactory("d-migrate-worker"),
    ).apply {
        rejectedExecutionHandler = RejectedExecutionHandler { _, _ ->
            rejectedCounter.incrementAndGet()
            throw ExecutorClosedException()
        }
    }
    override fun execute(command: Runnable) = pool.execute(command)
    fun shutdown(timeout: Duration): Boolean {
        pool.shutdown()
        return pool.awaitTermination(timeout.toMillis(), TimeUnit.MILLISECONDS)
    }
    fun status(): JobExecutorStatus = JobExecutorStatus(
        active = pool.activeCount,
        queued = pool.queue.size,
        completed = pool.completedTaskCount,
        rejected = rejectedCounter.get(),
        capacity = cfg.maxThreads + cfg.queueCapacity,
    )
}

class ExecutorClosedException : RuntimeException()
```

Der Reject-Handler ist nicht der normale Backpressure-Mechanismus.
Backpressure laeuft ueber Admission **vor** dem Commit. Ein
`RejectedExecutionException`/`ExecutorClosedException` nach erfolgreicher
Admission ist Shutdown-/Lifecycle-Race und wird separat behandelt.

### 6.2 Auto-Dispatch reserviert Admission vor dem Commit

```kotlin
// in JobStartOrchestrator.commitJob, VOR jobBuilder/commit
val dispatcher = jobDispatcher
val factory = jobWorkerFactory
if (dispatcher == null || factory == null) {
    // Bestands-/Test-Wiring ohne Auto-Dispatch: kein Admission-Acquire,
    // weil kein Runnable auf dem Worker-Pool landen wird.
    return commitQueuedWithoutAutoDispatch(request, scope)
}

val permit = when (val admission = dispatchAdmission.tryAcquire(request.now)) {
    is JobDispatchAdmissionOutcome.Granted -> admission.permit
    is JobDispatchAdmissionOutcome.Saturated ->
        return JobStartHandlerOutcome.RateLimited(
            retryAfter = admission.retryAfter,
            current = admission.current,
            limit = admission.limit,
            reason = "EXECUTOR_SATURATED",
        )
    JobDispatchAdmissionOutcome.Closed -> {
        val expiresAt = request.now.plusSeconds(1)
        idempotencyStore.markFailed(
            scope = scope,
            reason = "executor:closed",
            now = request.now,
            retentionUntil = expiresAt,
        )
        return JobStartHandlerOutcome.Failed(
            reason = "executor:closed",
            expiresAt = expiresAt,
        )
    }
}

// Quota-Reserve + JobStartTransaction.commit passieren vor diesem
// Block. Jeder Early-Return vor dem Commit schliesst zuerst das Permit.
val outcome = committedOutcome
var handleRegistrationAttempted = false
try {
    handleRegistrationAttempted = true
    workerHandleRegistry.register(jobId, source)
    val worker = factory.create(outcome.record, request)
    if (worker == null) {
        throw WorkerNotRegisteredException(outcome.record.managedJob.operation)
    }
    dispatcher.dispatch(outcome.record, worker, source.token, permit)
} catch (t: Throwable) {
    closePermitBestEffort(permit, jobId)
    markExecutorSetupFailed(outcome.record, request.now, t, handleRegistrationAttempted)
    return JobStartHandlerOutcome.Started(jobId, outcome.record, source)
}
```

`JobDispatcher.dispatch(..., permit)` released das Permit im `finally`
des Runnable, nach `runOnce` bzw. nach defensivem Exception-Handling.
Wenn `JobStartTransaction.commit` nicht eligible ist, wird das Permit
vor dem `Pending`-Return geschlossen. Wenn kein Dispatcher oder keine
Factory gewired ist, wird gar kein Permit acquired. Wenn die Factory
fuer einen committeten Record `null` liefert, ist das im Auto-Dispatch-
Wiring ein Setup-Fehler: Das Admission-Permit wird sofort geschlossen,
`markExecutorSetupFailed` setzt den Job pollbar auf
`FAILED(error.code=WORKER_NOT_REGISTERED)`, released bei angewandter
Transition die aktive Job-Quota und entfernt das bereits registrierte
Worker-Handle. Dauerhaftes `QUEUED` bleibt nur das Bestands-/Test-
Verhalten, wenn gar kein Dispatcher oder gar keine Factory gewired ist.

Nach erfolgreichem Commit ist der Start-Ref stabil. Darum werden
Setup-Fehler danach nicht mehr als Tool-Exception propagiert:
`markExecutorSetupFailed` macht eine CAS-Transition
`QUEUED -> FAILED(error.code=EXECUTOR_SETUP_FAILED)`; wenn diese
primaere Markierung gelingt, returnt der Handler weiter `Started` und
der Poll-Pfad zeigt den Fehler. Der spezielle
`RejectedExecutionException`-/Shutdown-Fall darf denselben Helfer mit
`error.code=EXECUTOR_CLOSED` verwenden. Falls die Transition applied,
gibt der Helfer den Quota-Slot frei. Falls vor dem Fehler bereits ein
Worker-Handle registriert wurde, unregistert der Helfer es auch dann
best-effort, wenn die primaere Markierung wirft.

### 6.3 Cancel-while-queued

```kotlin
private fun runOnce(record, worker, token, permit): JobWorkerOutcome {
    try {
        val running = jobStore.transitionStatus(
            tenantId = record.tenantId,
            jobId = record.managedJob.jobId,
            allowedFromStatuses = setOf(JobStatus.QUEUED),
        ) { mj -> mj.copy(status = JobStatus.RUNNING, updatedAt = clock.instant()) }

        val runningRecord = when (running) {
            is JobTransitionOutcome.Applied -> running.record
            is JobTransitionOutcome.IllegalTransition -> {
                if (running.currentStatus == JobStatus.CANCELLED) {
                    return JobWorkerOutcome.Cancelled(REASON_GENERIC_CANCEL)
                }
                return JobWorkerOutcome.Failed(REASON_DISPATCH_RACE, "Job not in QUEUED")
            }
            is JobTransitionOutcome.NotFound ->
                return JobWorkerOutcome.Failed(REASON_DISPATCH_NOT_FOUND, "Job not found")
        }

        // Worker wird nur nach erfolgreichem QUEUED -> RUNNING gestartet.
        val outcome = worker.execute(runningRecord, token)
        applyTerminal(record, outcome, clock.instant())
        return outcome
    } finally {
        permit.close()
    }
}
```

Wichtig: Der `CANCELLED`-Skip schreibt nicht erneut terminal. Der
queued-cancel Pfad hat den finalen Record bereits in
`JobCancelService.cancelQueuedJob` geschrieben und den Quota-Slot
freigegeben. Dadurch entsteht kein Doppel-Release.

### 6.4 `RateLimited.reason` rueckwaertskompatibel einfuehren

```kotlin
data class RateLimited(
    val retryAfter: Duration,
    val current: Long,
    val limit: Long,
    val reason: String = REASON_ACTIVE_JOBS_QUOTA,
)
```

Die bestehenden Positional-Callsites bleiben kompilierbar, weil das
neue Feld am Ende mit Default steht. `JobStartHandlerSupport` nimmt
`reason` als zusaetzliches Detail in den `RATE_LIMITED`-Envelope auf.

```kotlin
details = listOf(
    ToolErrorDetail("retryAfter", outcome.retryAfter.seconds.toString()),
    ToolErrorDetail("current", outcome.current.toString()),
    ToolErrorDetail("limit", outcome.limit.toString()),
    ToolErrorDetail("reason", outcome.reason),
)
```

### 6.5 Post-Commit Setup-Fehler

```kotlin
private fun markExecutorSetupFailed(
    record: JobRecord,
    now: Instant,
    error: Throwable,
    handleRegistrationAttempted: Boolean,
) {
    val code = when (error) {
        is RejectedExecutionException -> "EXECUTOR_CLOSED"
        is WorkerNotRegisteredException -> "WORKER_NOT_REGISTERED"
        else -> "EXECUTOR_SETUP_FAILED"
    }
    var transition: JobTransitionOutcome? = null
    try {
        transition = jobStore.transitionStatus(
            tenantId = record.tenantId,
            jobId = record.managedJob.jobId,
            allowedFromStatuses = setOf(JobStatus.QUEUED),
        ) { mj ->
            mj.copy(
                status = JobStatus.FAILED,
                updatedAt = now,
                error = JobError(
                    code = code,
                    message = error.message ?: error::class.simpleName.orEmpty(),
                ),
            )
        }
    } finally {
        if (handleRegistrationAttempted) {
            unregisterSetupHandleBestEffort(record.managedJob.jobId)
        }
    }

    if (transition is JobTransitionOutcome.Applied) {
        transition.record.quotaReservationOwnerId?.let { ownerId ->
            releaseSetupQuotaBestEffort(record.managedJob.jobId, ownerId, now)
        }
    }
}

private fun releaseSetupQuotaBestEffort(
    jobId: String,
    ownerId: String,
    now: Instant,
) {
    try {
        quotaService?.releaseForOwner(ownerId, now)
    } catch (cleanup: Throwable) {
        logSetupCleanupFailure(jobId, cleanup)
    }
}

private fun unregisterSetupHandleBestEffort(jobId: String) {
    try {
        workerHandleRegistry.unregister(jobId)
    } catch (cleanup: Throwable) {
        logSetupCleanupFailure(jobId, cleanup)
    }
}

private fun closePermitBestEffort(permit: JobDispatchPermit, jobId: String) {
    try {
        permit.close()
    } catch (cleanup: Throwable) {
        logSetupCleanupFailure(jobId, cleanup)
    }
}

private fun logSetupCleanupFailure(jobId: String, cleanup: Throwable) {
    // log + suppress: Sekundaerer Cleanup darf nie in den Start-Catch
    // zurueckwerfen, sonst wuerde der Cleanup-Pfad rekursiv laufen.
}

private class WorkerNotRegisteredException(
    operation: String,
) : RuntimeException("No worker registered for operation $operation")
```

Der Handler liefert in diesen seltenen Races weiter `Started`, weil
der Job bereits committet ist; der Poll-Pfad sieht sofort `FAILED`,
**wenn** die primaere `QUEUED -> FAILED`-Transition erfolgreich
persistiert wurde. Die Transition selbst ist kein best-effort Cleanup:
wirft der JobStore beim Markieren, darf der Fehler aus
`markExecutorSetupFailed` propagieren, weil der Handler dann keine
pollbare `FAILED`-Wahrheit garantieren kann.
Quota-Release und Handle-Unregister sind dagegen sekundaere
best-effort Schritte und werden nur geloggt/suppressed, damit
Cleanup-Fehler nicht erneut durch den Start-`catch` laufen.
Der Handle-Cleanup haengt an `handleRegistrationAttempted`, nicht am
erfolgreichen `register`-Return: `WorkerHandleRegistry.unregister` ist
idempotent, darum raeumt der Setup-Failure-Pfad auch partielle
Register-Fehler sicher auf.
Dasselbe gilt fuer `JobDispatchPermit.close()`: Der Permit-Vertrag ist
idempotent/no-throw; der Orchestrator ruft ihn im post-commit
Setup-Failure-Pfad trotzdem ueber `closePermitBestEffort`, damit ein
defekter Permit-Release die primaere `QUEUED -> FAILED`-Persistenz
nicht verhindern kann.
Tests pinnen `workerHandleRegistry.register`-Throw, `factory.create`
liefert `null`, `factory.create`-Throw und `dispatcher.dispatch`-/
`RejectedExecutionException`, jeweils inklusive Quota-Release und
Handle-Unregister, wenn diese Side-Effects vor dem Fehler bereits
stattgefunden haben. Der Register-Throw-Test pinnt explizit, dass
`unregister(jobId)` trotz Throw best-effort gerufen wird.
Zusaetzlich pinnen Cleanup-Failure-Tests, dass Permit-Close-,
Quota-Release- und Handle-Unregister-Fehler nicht zum Start-Handler
zurueckwerfen, waehrend ein JobStore-Fehler beim primaeren
`QUEUED -> FAILED`-Markieren nicht suppressed wird.

## 7. Risiken

| Risiko | Wahrscheinlichkeit | Mitigation |
|---|---|---|
| Thread-Leak bei Test-Shutdown (Pool nicht gestoppt) | mittel | `JobExecutorLifecycle.shutdown(timeout)` ist obligatorisch im Test-Teardown; CI-Hook checkt aktive Threads nach Test-Ende |
| Permit-/Handle-Leak bei Commit-/Dispatch-Race | mittel | Ohne Dispatcher/Factory wird kein Permit acquired; sonst wird `JobDispatchPermit` in jedem pre-commit Fehlerpfad, bei missing Worker, bei post-commit Setup-Fehlern und im Dispatcher-`finally` geschlossen; Permit-Close ist idempotent/no-throw und im post-commit Catch best-effort, damit es die primaere Fehler-Markierung nicht blockiert; nach jedem Register-Versuch wird bei Setup-Failure best-effort unregistered, auch wenn `register` selbst wirft; Tests fuer `IdempotencyNotEligible`, `dispatcher == null`, `factory == null`, `worker == null`, Worker-Exception, queued-cancel, setup-failure und shutdown-reject |
| Post-Commit Setup-Fehler laesst Job dauerhaft QUEUED | mittel | `worker == null`, `workerHandleRegistry.register`, `factory.create` und `dispatcher.dispatch`-Fehler nach Commit werden via `markExecutorSetupFailed` auf `FAILED` gemappt; Handler returnt nur nach erfolgreicher primaerer Markierung `Started`, damit Polling den terminalen Fehler sieht; bei applied Transition wird Quota released, bei bereits registriertem Handle wird unregistered; ein JobStore-Fehler beim Markieren wird nicht suppressed |
| Deadlock: Worker submitted weiteren Job auf denselben Pool | gering | E3 dokumentiert: Pool ist **Single-Layer**. Worker-internes Spawning verboten — Convention statt Mechanik |
| Slow shutdown bei lang laufenden Workern | mittel | `shutdownTimeoutMillis` konfigurierbar; nach Timeout `shutdownNow()` (interrupt) — workers MUESSEN auf `Thread.interrupted()` reagieren (existiert für Cancel-Pfad bereits) |
| Test-Flake durch Timing-Asserts in Async-Tests | mittel | `Awaitility` mit großzügigen Timeouts (5s+); SyncExecutor in Tests, die Ordering-Asserts brauchen |
| `EXECUTOR_SATURATED` als RateLimited-Wire-Code verwirrt Caller (er sieht „Quota") | gering | `RateLimited.reason` wird als Detail dokumentiert; `ACTIVE_JOBS_QUOTA` bleibt Default fuer bestehende Quota-Calls |
| Async-Modus in CI default-aus, könnte real-world-Drift haben | hoch | Eine dedizierte Async-Test-Suite (`JobDispatcherAsyncTest`) läuft in CI mit `coreThreads=2, queueCapacity=4` als Smoke; reicht nicht für Performance, aber für Verträge |
| Cancel-while-queued fuehrt zu Doppel-Release von Quota | mittel | Dispatcher schreibt bei `current=CANCELLED` nicht terminal und ruft keinen Release; Release bleibt allein beim `JobCancelService` |
| Cancel-while-queued räumt Idempotency-Reservation nicht | gering | Das ist Bestand-Verhalten der Reservation-Lease-Semantik; in E3.4-Doku festhalten, dass Cancel KEINEN Reservation-Cleanup auslöst (war nie versprochen) |
| Saturation-Reject während Phase-F Datenoperationen-Bursts | mittel | Operations-Doku (E3.7) zeigt Pool-Sizing-Daumenregel: `coreThreads ≥ erwartete-tools/call-Concurrency`. Phase-F-Acceptance kann mit Async laufen, falls Synchron-Bursts im Test nicht mehr sinnvoll sind |

## 8. Akzeptanz

Phase E3 gilt als done, wenn:

1. ✅ `JobExecutorFactory.create(config)` liefert beide Modi sauber;
   Validierung greift bei ungültigen Werten.
2. ✅ Bestehende Phase-E-Tests laufen unverändert grün (SyncExecutor
   bleibt Default).
3. ✅ Neue Async-Suite (`JobDispatcherAsyncTest`) verifiziert:
   parallele Dispatch, Admission-Saturation vor Commit,
   Closed-Admission ohne stale Pending, kein Acquire ohne Dispatcher/Factory,
   `JobDispatchPermit.close()` idempotent/no-throw,
   Permit-Release bei Quota-Reject/missing Worker/Commit-Race/
   Setup-Fehler/Job-Terminal, missing Worker ->
   pollbares FAILED + Quota-Release + Handle-Unregister,
   post-commit Setup-Failure ->
   pollbares FAILED + Quota-Release + Handle-Unregister,
   Cancel-while-queued, graceful Shutdown.
4. ✅ `JobStartOutcome.RateLimited(reason="EXECUTOR_SATURATED")`
   bzw. `JobStartHandlerOutcome.RateLimited(reason=...)` ist
   rueckwaertskompatibel eingefuehrt und in
   `spec/ki-mcp.md`/`spec/job-contract.md` dokumentiert.
5. ✅ Operations-Guide `docs/operations/job-executor.md` reviewed —
   Pool-Sizing, Shutdown-Behavior, Symptom→Aktion-Mapping vorhanden.
6. ✅ Coverage-Schwelle 90% pro Modul (mit `kover-CI-Flake`-Toleranz).
7. ✅ Plan-Move
   `docs/planning/in-progress/ImpPlan-0.9.6-E3.md → done/`;
   `roadmap.md` aktualisiert.

## 9. Out-of-Scope

- **Virtual Threads** (Java 21+ `Executors.newVirtualThreadPerTaskExecutor`)
  — Architektur kompatibel, aber der Drop-in braucht eine eigene
  Verifikation (Stack-Pinning, Continuation-Cost). Folge-Plan.
- **Per-Tenant-Pool-Partitioning** — heute teilen alle Tenants einen
  Pool. Fairness-Issues lösen wir nicht in E3.
- **Verteilter Dispatch** (mehrere Server-Instanzen, gemeinsame
  Job-Queue) — braucht E2 (persistente JobStore) UND ein
  Coordination-Primitive. Phase F+ oder eigener Plan.
- **Vollständige Metriken-Integration** (Micrometer, OpenTelemetry,
  Prometheus-Endpoint) — eigener Plan.
- **HTTP-Health-Endpoint** (`/health/jobs` o.ae.) — E3 liefert nur den
  Status-Snapshot als API/Objekt; Routing/Auth/Readiness-Semantik sind
  eigener Plan.
- **Generischer YAML-/Env-Config-Loader** — E3 erweitert die typisierte
  Server-Config. Ein Host kann Env/YAML darauf mappen, aber der Loader
  selbst ist nicht Scope.
- **Backpressure-Policy „caller-runs"** — bewusst NICHT angeboten
  (siehe § 3.5).
- **Worker-Timeouts auf Pool-Ebene** — bestehen bereits via
  `OperationCancelSource.RUNNER_TIMEOUT` im Worker-Pfad.

## 10. Resolved (Owner-Entscheidung 2026-05-06)

- **Q1**: Fixed `4` als Default für `coreThreads = maxThreads = 4`.
  CPU-basierte Werte nur per expliziter Config — verhindert
  überraschend große Pools auf großen Hosts. Siehe § 3.2.
- **Q2**: `JobExecutorConfig` lebt adapter-neutral in
  `hexagon/application`; `McpJobExecutorConfig` mappt darauf.
  YAML/Env-Wissen bleibt strikt im Adapter/Host. Siehe § 3.4.
- **Q3**: `JobDispatcher.dispatch(..., permit)` bekommt den optionalen
  Permit-Parameter. Der Dispatcher kennt den echten Terminal-/Skip-
  Punkt und kann zuverlässig releasen. Siehe § 6.2.
- **Q4**: Kein Audit-Event pro Executor-Reject in E3. Wire-Code +
  strukturierte Logs + Status-Snapshot reichen; Audit unter
  Saturation könnte selbst Last verstärken. Siehe § 3.7.
- **Q5**: `RateLimited.reason` immer im Wire ausgeben — bestehende
  Quota-Rejections mit `ACTIVE_JOBS_QUOTA`, Executor-Saturation mit
  `EXECUTOR_SATURATED`. Siehe § 3.5 / § 6.4.
- **Q6**: Keine harte E3 → E2-Abhängigkeit. E3 ist eigenständig
  wertvoll (kein Block auf MCP-Request-Thread). Production-Deploy
  will trotzdem E2 + E3 zusammen. Siehe § 0.

---

**Approval § 3 + § 10 erteilt**: Start mit **E3.1**
(`JobDispatchAdmission` + `BoundedAsyncJobExecutor` +
`JobExecutorLifecycle`-Interface + Unit-Tests) als isolierter,
in-Module-Block ohne Wiring-Auswirkung.
