# Operations: Phase-E Job-Executor

> **Status**: aktiv (2026-05-06)
> **Geltung**: Phase-E3 Async-Executor (Plan: `docs/planning/in-progress/ImpPlan-0.9.6-E3.md`)
> **Cross-Refs**:
> [`spec/ki-mcp.md`](../../spec/ki-mcp.md) — MCP-Server-Vertrag;
> [`spec/job-contract.md`](../../spec/job-contract.md) — Job-Lifecycle;
> [`spec/phase-e2-persistence.md`](../../spec/phase-e2-persistence.md) — Server-State-Persistenz

## Inhalt

1. Sync vs. Async — was wann
2. Async-Pool-Konfiguration
3. Pool-Sizing (Daumenregeln)
4. Saturation-Symptome und Diagnose
5. Shutdown-Verhalten
6. Operations-Workflow: 429-Spike beheben
7. Out-of-Scope

---

## 1. Sync vs. Async — was wann

Der Phase-E Job-Dispatcher hat zwei Modi:

| Modus | Verhalten | Wann verwenden |
|---|---|---|
| **`sync`** (Default) | Worker laufen synchron auf dem MCP-Request-Thread. Kein Pool, keine Queue, keine Backpressure. | MVP-Bootstrap, Tests, Single-Tenant-Dev. CI/Akzeptanz-Pins setzen das voraus. |
| **`async`** | Bounded `ThreadPoolExecutor` + `ArrayBlockingQueue`. Worker laufen auf dedizierten Daemon-Threads (`d-migrate-worker-{n}`); `tools/call` kehrt mit `jobId` zurück, sobald Admission + Quota + Commit durch sind. | Production-MCP-Loads. Mehrere Tenants, kurze tools/call-Latenz, Job-Dauer >> Request-Frequenz. |

**Async ist opt-in.** Server.jobs.executor.mode muss explizit auf
`async` gesetzt sein (per YAML oder
`D_MIGRATE_SERVER_JOBS_EXECUTOR_MODE=async`). Default-Sync hält
Bestands-Tests und MVP-Deployments deterministisch.

---

## 2. Async-Pool-Konfiguration

YAML-Block in der MCP-Server-Config:

```yaml
server:
  jobs:
    executor:
      mode: async
      async:
        coreThreads: 4              # fest (Default)
        maxThreads: 4               # = coreThreads, kein elastischer Pool
        queueCapacity: 1024         # ArrayBlockingQueue (>= 1)
        keepAliveSeconds: 60        # irrelevant bei core==max
        retryAfterMillis: 1000      # → RateLimited.retryAfter bei Saturation
        shutdownTimeoutMillis: 30000
        threadNamePrefix: d-migrate-worker
```

Env-Overrides (gewinnen über YAML pro Feld):

```
D_MIGRATE_SERVER_JOBS_EXECUTOR_MODE=async
D_MIGRATE_SERVER_JOBS_EXECUTOR_CORE_THREADS=8
D_MIGRATE_SERVER_JOBS_EXECUTOR_MAX_THREADS=8
D_MIGRATE_SERVER_JOBS_EXECUTOR_QUEUE_CAPACITY=2048
D_MIGRATE_SERVER_JOBS_EXECUTOR_RETRY_AFTER_MILLIS=500
D_MIGRATE_SERVER_JOBS_EXECUTOR_SHUTDOWN_TIMEOUT_MILLIS=10000
```

**Validierung beim Bootstrap**:

- `coreThreads > 0`
- `maxThreads >= coreThreads`
- `queueCapacity > 0` (Pflicht für `ArrayBlockingQueue`)
- `keepAliveSeconds >= 0`
- `retryAfter`/`shutdownTimeout` nicht-negativ
- `threadNamePrefix` nicht-leer

Ungültige Werte ⇒ `IllegalArgumentException` beim Bootstrap; der Server startet nicht.

---

## 3. Pool-Sizing (Daumenregeln)

| Größe | Wert | Begründung |
|---|---|---|
| **`coreThreads = maxThreads`** | ≥ erwartete tools/call-Concurrency mit langlaufenden Worker (Reverse, Profile, Compare). Default `4` ist konservativ. | Fixe Pool-Größe ist deterministischer als elastische Pools; OOM-Risiko durch unbeschränktes Wachstum entfällt. |
| **`queueCapacity`** | ~ erwarteter peak-Burst pro Sekunde × max tolerierte Wait-Sekunden. Default `1024` reicht für Single-Tenant; Multi-Tenant ggf. höher. | Größere Queue puffert Bursts, verzögert aber `RATE_LIMITED`-Antworten. |
| **`retryAfterMillis`** | ~ mittlere Job-Dauer / 4. Default `1000`. | Caller wartet bevor Retry; zu kurze Werte verursachen Retry-Storm. |
| **`shutdownTimeoutMillis`** | ≥ längster typischer Job × Sicherheitsfaktor 1.5. Default `30000` (30 s). | Bei Shutdown drainiert der Pool die in-flight Jobs; nach Ablauf wird `interrupted()` gesendet. |

**Klassifikations-Regel**: `coreThreads ≥ erwartete-tools/call-Concurrency`.
`queueCapacity` ist die Pufferreserve für Bursts oberhalb dieser
Concurrency.

---

## 4. Saturation-Symptome und Diagnose

### 4.1 Symptome

- Caller bekommen `RATE_LIMITED` mit `reason=EXECUTOR_SATURATED`
  (im Gegensatz zu `reason=ACTIVE_JOBS_QUOTA` für Tenant-Quota).
- `JobExecutorStatus.queued` nähert sich `queueCapacity`.
- Log-Events `job.dispatch.scheduled` zeigen wachsendes `queueDepth`.

### 4.2 Wire-Diskriminator

```json
{
  "code": "RATE_LIMITED",
  "details": [
    {"key": "retryAfter", "value": "1"},
    {"key": "current", "value": "1028"},
    {"key": "limit", "value": "1028"},
    {"key": "reason", "value": "EXECUTOR_SATURATED"}
  ]
}
```

`reason` unterscheidet Operations-Reaktion:

- `ACTIVE_JOBS_QUOTA`: Tenant/Caller-Quota erschöpft → Limit hochsetzen
  oder Tenant kontaktieren.
- `EXECUTOR_SATURATED`: Pool/Queue voll → `coreThreads` und/oder
  `queueCapacity` hochdrehen; siehe § 6.

### 4.3 Status-Snapshot

`JobExecutorBundle.lifecycle.status()` liefert für Tests/Hosts:

```kotlin
data class JobExecutorStatus(
    val active: Long,      // gerade laufende Worker
    val queued: Long,      // in der ArrayBlockingQueue wartend
    val completed: Long,   // ThreadPoolExecutor.completedTaskCount (kumulativ)
    val rejected: Long,    // Saturation-Rejects am Pool-Reject-Handler
    val capacity: Long,    // maxThreads + queueCapacity
)
```

Ein HTTP-Health-Endpoint ist NICHT Teil von E3 (Plan §9). Hosts können
den Snapshot via interner API exponieren oder JMX-Bean publishen — beides
ist Folge-Plan-Material.

### 4.4 Log-Korrelation

Drei strukturierte slf4j-Events am Dispatcher-Boundary:

| Event | Felder |
|---|---|
| `job.dispatch.scheduled` | `jobId`, `tenant`, `tool`, `queueDepth` |
| `job.dispatch.started` | `jobId`, `tenant`, `tool`, `waitMs` |
| `job.dispatch.finished` | `jobId`, `tenant`, `tool`, `status`, `durationMs`, `errorCode` |

Jeder `scheduled` hat genau ein `finished` (auch für skip-Branches:
cancel-while-queued, dispatch-race, not-found). Differenz
`startedAt - scheduledAt = waitMs` ist die Queue-Latenz.

---

## 5. Shutdown-Verhalten

`JobExecutorBundle.lifecycle.shutdown(timeout)`:

1. **Admission schließen**: ab sofort liefert
   `JobDispatchAdmission.tryAcquire(...)` `Closed`. Neue
   `tools/call`-Anfragen, die den Auto-Dispatch erreichen, bekommen
   einen deterministischen `Failed(executor:closed)`-Replay (siehe
   Plan §3.5); KEIN stale `PENDING`-Slot in der Idempotency-Tabelle.
2. **Pool drainieren**: `ThreadPoolExecutor.shutdown()` —
   bereits-eingequeued-te Tasks laufen zu Ende; neue Submissions
   werden vom Reject-Handler mit `ExecutorClosedException`
   zurückgewiesen.
3. **Auf Termination warten**: bis zu `timeout`
   (`shutdownTimeoutMillis`). Liefert `true` wenn vor Ablauf
   gedrained, `false` sonst.

**Bei `false`-Return**: Worker laufen noch. Der Caller (typisch
JVM-Shutdown-Hook im CLI) kann eskalieren über
`JobExecutorBundle.executor.shutdownNow()` (sendet `Thread.interrupt()`
an alle Worker). Worker MÜSSEN auf `Thread.interrupted()` reagieren —
der Phase-E-Cancel-Pfad tut das bereits via
`OperationCancelledException`.

**CLI-Bootstrap (`McpCommand`)** registriert die
`McpCliRuntimeWiring.close()`-Sequenz:

1. `executorLifecycle.shutdown(executorShutdownTimeout)` (drain)
2. `dataSource.close()` (HikariCP-Pool freigeben)

Das passiert beim regulären `Ctrl+C`/SIGINT und beim Test-`use{}`-Pfad.

---

## 6. Operations-Workflow: 429-Spike beheben

**Symptom**: ein Tenant meldet wiederholt `429 RATE_LIMITED`. Operator-
Schritte:

1. **Reason prüfen** — wire-`details[reason]`:
   - `ACTIVE_JOBS_QUOTA` ⇒ Tenant-Quota; weiter Schritt 2.
   - `EXECUTOR_SATURATED` ⇒ Pool-Saturation; weiter Schritt 3.

2. **Quota-Pfad**: Tenant-Quota-Limit aus `QuotaConfig` prüfen.
   Optional Limit erhöhen oder den Tenant über erwartete Job-Volumina
   informieren. Quota-Counters via DB (`quota_counters`,
   `quota_reservation_owners`) inspizieren — siehe
   [`spec/phase-e2-persistence.md`](../../spec/phase-e2-persistence.md).

3. **Pool-Saturation-Pfad**:
   1. Aktuellen Snapshot lesen
      (`JobExecutorBundle.lifecycle.status()` — interne API; HTTP-
      Endpoint kommt später):
      `active`, `queued`, `capacity`.
   2. Logs filtern auf `job.dispatch.scheduled` —
      `queueDepth`-Verlauf zeigt, ob die Queue stetig wächst.
   3. Wenn `active == maxThreads` UND `queued > 0`: Pool ist voll
      ausgelastet. Erhöhe `coreThreads`/`maxThreads` (gleichgesetzt
      lassen für deterministische Sizing).
   4. Wenn `active < maxThreads` aber `queued > 0`: Queue ist
      ungesund (sollte nicht passieren — ThreadPoolExecutor füllt erst
      Threads, dann Queue). Diagnose-Indikator für Worker-Hang;
      Stack-Dump nehmen.
   5. Konfig anpassen, Server neu starten. Bei rolling restart:
      `shutdownTimeoutMillis` >> typische Job-Dauer setzen, sonst
      werden Worker per Interrupt abgebrochen.

4. **Verifizieren**: nach Restart die `RATE_LIMITED`-Rate beobachten
   (z.B. via Audit-Sink) und `queueDepth` aus `scheduled`-Events.

---

## 7. Out-of-Scope

- **Virtual Threads (Java 21+)**: Architektur erlaubt späteren
  Drop-in via Config-Flag, aber nicht in E3 verifiziert
  (Stack-Pinning, Continuation-Cost müssen separat gemessen werden).
- **Per-Tenant-Pool-Partitioning**: heute teilen alle Tenants einen
  Pool. Fairness-Issues lösen wir nicht in E3.
- **Verteilter Dispatch** (mehrere Server-Instanzen, gemeinsame
  Job-Queue): braucht E2 (persistente JobStore) UND ein
  Coordination-Primitive. Phase F+ oder eigener Plan.
- **Vollständige Metriken-Integration** (Micrometer, OpenTelemetry,
  Prometheus-Endpoint): eigener Plan.
- **HTTP-Health-Endpoint** (`/health/jobs`): E3 liefert nur den
  Status-Snapshot als API/Objekt; Routing/Auth/Readiness-Semantik sind
  eigener Plan.
- **Backpressure-Policy „caller-runs"**: bewusst NICHT angeboten
  (siehe Plan §3.5).
- **Worker-Timeouts auf Pool-Ebene**: bestehen bereits via
  `OperationCancelSource.RUNNER_TIMEOUT` im Worker-Pfad.
