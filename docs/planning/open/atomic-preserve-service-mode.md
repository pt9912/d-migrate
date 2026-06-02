# Atomic-Sequence-Preserve im Service-Mode (MCP / REST / gRPC)

**Status**: Vorabklärung

**Trigger**: Cross-JVM-Carve-Out im (geschlossenen) Atomic-Preserve-
Slice (`docs/planning/done/sequence-preserve-atomic-lock-plan.md`
§8.2, §6 Risiken Nr. 8) ist für den **CLI-Pfad** formuliert: jeder
`schema migrate`-Aufruf läuft in einem eigenen JVM-Prozess, und das
DB-side Lock-Verhalten (PG `pg_advisory_xact_lock(hashtext(...))` /
MySQL `SELECT … FOR UPDATE` / SQLite `BEGIN IMMEDIATE` +
`PRAGMA busy_timeout`) deckt parallele Aufrufe automatisch ab. Die
drei `*AtomicPreserveCrossPlanDeadlockTest`s pinnen das Same-JVM-
Two-Threads-Szenario als Stresstest.

Heute ist `schema_migrate` / `schema_rollback` **noch nicht** im
MCP-Server-Tool-Set (Handler-Liste in
`adapters/driving/mcp/src/main/.../registry/*Handler.kt`:
`schema_validate`, `schema_compare`, `schema_compare_start`,
`schema_generate`, `schema_reverse_start`, plus die `data_*`-Jobs).
Das fehlende Tool ist als **neues Produkt-/Contract-Thema**
dokumentiert (`docs/planning/done/quality-coverage-expansion-plan.md`
§3.2 Out-of-Scope und §9: „Neues MCP-Migrate-Tool
(`schema_migrate`/`schema_migrate_start`) — neues Produkt-/Contract-
Thema; dieser QA-Plan darf es nicht implizit voraussetzen.") und
fällt damit unter die Tool-Gruppen-Konvention aus
`implementation-plan-0.9.6.md` (read-only Tools mit `dmigrate:read`-
Scope vs. „kontrollierte Write-Operationen"). Sobald das Tool
gebaut wird (oder die Migrate-Pfade in REST/gRPC-Adaptern
freigeschaltet werden), wird das Same-JVM-Multi-Thread-Szenario
aus „Stresstest-Setup" zum **produktiven Hot-Path**.

**Aktivierungsbedingung**: Trigger feuert, sobald einer der drei
Fälle eintritt und in `docs/planning/next/` einen Plan-Doc bekommt:

1. MCP-Server soll `schema_migrate` / `schema_rollback` als
   Tool / Job-Worker exponieren (z. B. analog `data_transfer_start`
   mit Policy-Gate aus `done/ImpPlan-0.9.6-F.md`).
2. gRPC-API (Roadmap-Milestone 1.1.8) erhält den Migrate-RPC.
3. REST-API (Roadmap-Milestone 1.2.0) erhält den Migrate-Endpoint.

Bis dahin Vorabklärung ohne Scope. Der Atomic-Executor-Vertrag
(`AtomicSequencePreserveExecutor.requireOwnedConnection`) und die
DB-side Lock-Mechanik bleiben unverändert; was fehlt, sind die
Service-Mode-Verträge **um den Executor herum**.

---

## Verträge, die der Cross-JVM-Carve-Out *nicht* abdeckt

Der bestehende Carve-Out sagt: „advisory-Lock-Verhalten ist
dasselbe, zusätzlicher Aufbau lohnt nicht". Das gilt für die DB-
Sicht, schweigt aber zu fünf JVM-/Service-seitigen Verträgen:

### 1. Connection-Pool-Vertrag

`AtomicSequencePreserveExecutor.requireOwnedConnection` setzt
`autoCommit=true` + keine umschließende Transaktion voraus. Im
Service-Mode mit shared Hikari-Pool muss jeder parallele
Migrate-Call eine **eigene** Connection bekommen — sonst
serialisiert der Server auf Pool-Exhaustion statt auf DB-Locks, mit
unsichtbarem Latency-Effekt und falscher Lock-Timeout-Semantik
(Pool-Borrow-Timeout statt DB-Lock-Timeout).

**Heutiger Stand im Code:**
- `hexagon/ports-common/.../driver/connection/PoolSettings.kt:20`
  hält die Defaults: `maximumPoolSize = 10`, `minimumIdle = 2`,
  `connectionTimeoutMs = 10_000`. **Pro Named-Connection** ein Pool
  via `HikariConnectionPoolFactory.create`; SQLite-Sonderpfad
  überschreibt `maximumPoolSize = 1` (Z. 33).
- `AtomicSequencePreserveRunner.defaultAcquireConnection`
  (`adapters/driving/cli/...`) ist im CLI-Pfad der Single-Borrow-
  Point. Im CLI-Prozess gibt es genau einen Migrate-Call → keine
  Pool-Konkurrenz.

**Offen — konkrete Sub-Fragen:**
- Soll der Server pro `schema_migrate`-Tool-Call eine **dedizierte
  Mini-Pool**-Instanz öffnen (Borrow-Lifecycle = Request-
  Lifecycle), oder den existierenden Named-Connection-Pool
  teilen? Letzteres benötigt eine explizite Reservation, damit
  parallele Migrate-Calls + parallele Data-Worker
  (`data_transfer_start`) sich nicht gegenseitig auf Pool-
  Exhaustion blocken.
- Default `maximumPoolSize = 10` ist für CLI-Last gewählt — für
  einen Service mit AI-Agenten als Clients vermutlich zu klein
  (jeder Atomic-Preserve hält eine Connection für die Dauer der
  Lock-/Probe-/Restore-Transaktion).
- `connectionTimeoutMs = 10_000` (Hikari-Borrow-Timeout) ist heute
  **getrennt** vom Lock-Timeout (5 s in
  `SchemaMigrateExecutionStage`). Sobald sie kollidieren — Pool
  voll, Borrow-Timeout läuft 10 s — sieht der Client
  `SQLException: Connection is not available` statt einer
  Lock-/Timeout-Diagnostik. **Vertrag fehlt**: Service muss
  Borrow-Timeout-Failures separat klassifizieren
  (`SERVICE_POOL_EXHAUSTED` o. ä.) statt sie als generischen DB-
  Fehler durchzureichen.

### 2. Request-Cancellation

Was passiert, wenn der Client den `schema_migrate`-Call abbricht
(MCP-`job_cancel`, gRPC-Cancellation, REST-Disconnect) **während**
die Atomic-Transaktion läuft?

- PG: Advisory-Lock ist `xact_lock` — Connection-Close rollt
  Transaktion zurück und gibt Lock frei. Wenn die Connection
  stattdessen im Pool zurückgegeben wird ohne Rollback, hält die
  nächste Borrowing-Session den halben Advisory-Lock. **Vertrag
  fehlt**: Cancellation muss strikt `rollback()` + `close()` auf
  der Atomic-Connection erzwingen.
- MySQL: `FOR UPDATE`-Row-Lock auf `dmg_sequences`. Selbes
  Problem.
- SQLite: `BEGIN IMMEDIATE` hält RESERVED-Lock auf die ganze DB.
  Gleiches Problem mit größerem Blast-Radius (alle anderen
  Schreiber blocken).

**Heutiger Stand im Code (Cancellation-Pattern aus dem
data-Pfad):**
- `adapters/driving/mcp/.../registry/JobCancelHandler.kt:35` ist
  der MCP-Handler für `job_cancel`. Outcome-Mapping ist über
  einen `service.cancel(...)`-Call (Z. 69), der ein
  `cancelRequest`-Feld in der `ManagedJob` setzt
  (`cancelRequested=true`, `cancelAckPending=true`,
  Z. 122-129). Der Worker pollt diesen Wert.
- `adapters/driven/streaming/.../StreamingImporterCancelPropagationTest.kt`
  + Verwandte zeigen, wie das Cancel-Signal im Data-Pfad bis zum
  JDBC-Importer durchgereicht wird (Checkpoint-Cancellation).
- **Atomic-Preserve-Pfad** hat **kein** äquivalentes
  Cancel-Polling — der `AtomicSequencePreserveExecutor.execute`-
  Aufruf ist ein synchroner Block, der erst zurückkehrt, wenn
  die Transaktion committed oder rolled-back ist.

**Offen — konkrete Sub-Fragen:**
- Soll der `AtomicSequencePreserveExecutor`-Port um einen
  optionalen `cancellationToken: CancellationToken`-Parameter
  erweitert werden, den der Executor zwischen Probe und Restore
  prüfen kann? Risiko: Cancel zwischen Probe und Restore lässt
  die Sequenz in einem inkonsistenten Zustand (Probe gelesen,
  Restore nicht geschrieben).
- Alternative: Cancel **deactivates** die Connection (Hikari
  `evictConnection` + manueller Rollback), Server gibt
  `CANCELLED` an den Client zurück. Sequenzzustand: Probe ohne
  Restore = ursprünglicher Wert bleibt (advisory-Lock wird mit
  Rollback freigegeben → keine Sperre für nachfolgende
  Calls). Das ist sauber, weil DB-side Lock-Rollback alle drei
  Dialekte automatisch beherrschen.
- REST-Disconnect-Detection: HTTP-Servlets bekommen typischerweise
  kein Pre-Cancel-Signal; erst beim nächsten `write()` auf den
  Response-Stream wird der Broken-Pipe sichtbar. Für lange
  Atomic-Operationen (mehrere Sekunden) braucht es entweder
  einen Polling-Watchdog oder ein expliziteres Cancel-Frame
  (SSE / WebSocket / gRPC bidi).

### 3. Backpressure / Rate-Limiting

100 parallele migrate-Calls von einem Angreifer (oder einem
schlecht konfigurierten AI-Client) können den Pool erschöpfen →
DoS auf den Schreib-Pfad. CLI hat diesen Vektor nicht, weil jeder
Aufruf einen eigenen Prozess + eigenen Pool bedeutet.

**Heutiger Stand im Code (Quota-Pattern aus dem data-Pfad):**
- `hexagon/ports-common/.../server/ports/quota/QuotaStore.kt` ist
  der bestehende Quota-Port. Sub-Ports + Tests sind in
  `hexagon/ports-common/src/testFixtures/.../QuotaStoreContractTests.kt`
  spezifiziert. Es gibt `InMemoryQuotaStore` (Tests) +
  `JdbcQuotaStore`-Adapter (produktiv).
- `hexagon/application/.../server/application/job/JobQuotaScenarioTest.kt`
  zeigt den End-to-End-Pfad: Job-Worker reserviert eine Quota,
  arbeitet, gibt sie frei (oder lässt sie expiren via
  `QuotaReservationSweeper`).
- `test/integration-server-state/.../QuotaReservationSweeperE2ETest.kt`
  belegt den Sweeper-Vertrag.
- **Atomic-Preserve-Pfad** ist **nicht** an `QuotaStore`
  angeschlossen — der Executor wird direkt aus
  `SchemaMigrateExecutionStage` aufgerufen, ohne Tenant-/Schema-
  Reservierung davor.

**Offen — konkrete Sub-Fragen:**
- Reservierungsschlüssel: `QuotaScope("schema_migrate",
  tenant=<tenant>, schema=<target_schema>)`? Oder grobgranularer
  pro Connection-Pool?
- Sweep-Granularität: `QuotaReservationSweeper` läuft heute
  periodisch (Sekunden-Skala). Atomic-Migrate ist typisch < 5 s;
  Sweep muss schnell genug sein, um nicht zur Latency-Quelle zu
  werden.
- Layered Defense: Application-Level-Rate-Limit (Token-Bucket
  pro Tenant) **vor** Pool-Borrow ist die saubere Reihenfolge.
  Pool-Borrow-Timeout als Fail-Safe darunter, mit eigener
  Diagnostik.

### 4. Lock-Timeout-Tuning

5 s Default-LockTimeout (`SchemaMigrateExecutionStage.DEFAULT_LOCK_TIMEOUT_MILLIS`)
ist für CLI konservativ — der Operator wartet vor dem Terminal.
Ein Service braucht möglicherweise:

- **Per-Request-Override** (Client passt das eigene SLO an).
- **Konservativeren Server-Default** (z. B. 30 s), damit
  AI-Agenten weniger oft Timeout-Blocker sehen.
- **Per-Tenant-Override** via Config.

**Heutiger Stand im Code:**
- `SchemaMigrateExecutionStage.lockTimeoutMillis` ist `private
  val` mit Default `DEFAULT_LOCK_TIMEOUT_MILLIS = 5_000L`. Keine
  CLI-Option überschreibt den Default; der Lambda-Vertrag
  `SegmentAwareExecutorFn` trägt den Parameter aber durch
  (`SchemaMigrateRunner.kt:899-904`).
- Test-Override-Pattern: die Live-IT-Tests
  (`{Mysql,Sqlite}SchemaMigrateAtomicPreserveIntegrationTest`)
  verwenden Executor-Decorator-Wrapper (`tightTimeoutExecutor` /
  `freshConnExecutorWithTimeout`), die den durchgereichten
  Lambda-Parameter ignorieren und ein eigenes `budgetMillis`
  injizieren — als Workaround, weil weder Runner noch Stage
  einen Konstruktor-Parameter dafür haben.

**Offen — konkrete Sub-Fragen:**
- Sauberer Refactor: `SchemaMigrateRunner`-Konstruktor um
  `lockTimeoutMillis: Long = DEFAULT_LOCK_TIMEOUT_MILLIS`
  erweitern, Stage liest aus dem Request. Damit entfällt der
  Test-Decorator-Workaround (verlinkt mit Finding #6 aus
  `done/atomic-preserve-followups.md` §8.2).
- Server-seitige Default-Strategie: festes Default vs.
  Config-Datei (`d-migrate.yaml` Block
  `server.atomic_preserve.lock_timeout_ms`)?
- Per-Request-Override-Signatur: MCP-Tool-Argument
  `lockTimeoutMs?` mit Validation (10 ms ≤ x ≤ 60 s)?

### 5. Idempotency-Replay

MCP-Tool-Calls und gRPC-RPCs dürfen retried werden. Die
Preserve-Probe+Restore-Logik **selbst** ist idempotent (Probe liest
den aktuellen Wert, Restore setzt ihn zurück), **aber** der
augmentierte Plan als Ganzes ist es nicht (DDL wird tatsächlich
ausgeführt; ein Replay nach erfolgreichem Apply würde gegen das
schon-angewendete Schema laufen und voraussichtlich mit
Drift-Diagnostic blockieren).

**Heutiger Stand im Code:**
- `hexagon/ports-common/.../server/ports/IdempotencyStore.kt:28`
  spezifiziert den Vertrag. Schlüssel-API:
  `reserve(scope: IdempotencyScope, payloadFingerprint: String,
  now: Instant)`. Sub-Variante:
  `SyncEffectIdempotencyStore.kt` für Reservation+Effect-In-One-Step.
- Adapter:
  `adapters/driven/persistence-jdbc/.../idempotency/JdbcIdempotencyStore.kt`.
- Tests:
  `hexagon/ports-common/src/testFixtures/.../IdempotencyStoreContractTests.kt`
  pinnt das Vertrags-Verhalten (Replay-Detect via
  payloadFingerprint, Expiry, Erst-Reservation vs. Replay).
- Bestehender Konsument: `data_transfer_start` /
  `data_import_start` haken über den Job-Worker-Pfad an den
  `IdempotencyStore` an.

**Offen — konkrete Sub-Fragen:**
- `IdempotencyScope("schema_migrate", target_database_ref)` +
  `payloadFingerprint = MigrationFingerprint.compute(targetSchema)`
  (Wiederverwendung der bestehenden Migration-Fingerprint-
  Logik)? Damit erkennt der Store ein „selber Plan, schon
  ausgeführt"-Replay sauber.
- Replay-Outcome: Server gibt `200 OK` + `replay: true` zurück
  mit dem Original-`ExecutionTrace`? Oder `409 Conflict` mit
  Hinweis, dass das Apply schon stattgefunden hat?
- Edge-Case: Replay nach **fehlgeschlagenem** Apply (z. B.
  partieller Rollback). Der bestehende
  `IdempotencyStoreContractTests`-Pfad „failed-effect"
  beschreibt das — übernehmbar.
- Lifetime der Reservation: für `data_transfer_start` ist sie
  job-lebenslang. Für `schema_migrate` ist die Transaktion
  kürzer (~ s), aber Replay-Window sollte größer sein (~ min
  bis h), damit ein retry nach Netzwerk-Fehler greift.

---

## Heutiger Bestand

- Atomic-Executor + Lock-Mechanik: 100 % geliefert, DB-side
  korrekt. Code: `hexagon/ports-execute/.../AtomicSequencePreserveExecutor.kt`
  + 3 Dialekt-Adapter + 6 Live-IT-Test-Klassen.
- Cross-JVM-Carve-Out: dokumentiert in
  `done/sequence-preserve-atomic-lock-plan.md` §8.2 + §6
  Risiken Nr. 8.
- MCP-Tool-Status: `schema_migrate` / `schema_migrate_start` ist
  als neues Produkt-/Contract-Thema offen
  (`done/quality-coverage-expansion-plan.md` §3.2 + §9, nicht als
  Schutzentscheidung formuliert — schlicht nicht gebaut).
  Bestehende Schreib-Tools (`data_transfer_start`,
  `data_import_start`) haben Policy-Gates aus
  `done/ImpPlan-0.9.6-F.md` als Referenz-Pattern.

## Reuse-Kandidaten

Pattern, die im Server-Pfad schon existieren und für
`schema_migrate` rekombiniert werden können:

| Vertrag | Bestandsteil | Pfad |
| ------- | ------------ | ---- |
| Connection-Pool | `HikariConnectionPoolFactory` + `PoolSettings` | `adapters/driven/driver-common/.../connection/HikariConnectionPoolFactory.kt`; `hexagon/ports-common/.../driver/connection/PoolSettings.kt` |
| Cancellation (data-Pfad) | `JobCancelHandler` + Worker-Cancel-Polling | `adapters/driving/mcp/.../registry/JobCancelHandler.kt`; `adapters/driven/streaming/.../StreamingImporterCancelPropagationTest.kt` |
| Quota / Rate-Limit | `QuotaStore` + `JdbcQuotaStore` + `QuotaReservationSweeper` | `hexagon/ports-common/.../server/ports/quota/QuotaStore.kt`; `JobQuotaScenarioTest.kt`; `QuotaReservationSweeperE2ETest.kt` |
| Idempotency | `IdempotencyStore` + `JdbcIdempotencyStore` | `hexagon/ports-common/.../server/ports/IdempotencyStore.kt`; `adapters/driven/persistence-jdbc/.../idempotency/JdbcIdempotencyStore.kt`; `IdempotencyStoreContractTests.kt` |
| Policy-Gate | `data_transfer_start`-Wiring | `done/ImpPlan-0.9.6-F.md` |
| Migration-Fingerprint (für Idempotency-Key) | `MigrationFingerprint.compute` | `hexagon/core/.../diff/migration/MigrationFingerprint.kt` |

**Konsequenz**: Wenn der Trigger feuert, ist der Promote-nach-
`next/`-Slice **vermutlich keine Neubau-Übung**, sondern eine
**Komposition** der oben genannten Ports + ein dünner
`schema_migrate`-Handler oben drauf. Die Sub-Slice-Sequenz wäre
vermutlich: (a) `SchemaMigrateRunner.lockTimeoutMillis`-Refactor
(siehe Finding #6, Vertrag 4), (b) Idempotency-Hook auf den
Schema-Pfad, (c) Connection-Sub-Pool-Plumbing, (d) Quota-Plumbing,
(e) Cancellation-Token im Executor-Port, (f)
`schema_migrate`-Handler im MCP-Adapter.

---

## Verworfene Alternativen

- **Echter Cross-JVM-Stresstest** (`ProcessBuilder` mit zwei
  `schema migrate`-Subprozessen): bewusst out-of-scope. Das
  Verhalten ist by-DB-design identisch zum Same-JVM-Two-Threads-
  Setup, der Aufbau lohnt sich nicht. Bleibt aus, auch wenn der
  Service-Mode kommt — die hier benannten 5 Verträge betreffen
  alle die JVM-Seite, nicht die DB-Seite.

---

## Verweise

- `docs/planning/done/sequence-preserve-atomic-lock-plan.md`
  §3.2 (Out-of-Scope), §6 Risiken Nr. 8, §7 Out-of-Scope-Folge-
  Themen, §8.2 Carve-outs.
- `docs/planning/done/atomic-preserve-followups.md` §6 Offene
  Fragen (geschlossen 2026-06-01, Cross-Process-Carve-Out).
- `docs/planning/done/ImpPlan-0.9.6-F.md` (Policy-Gate-Pattern
  für `data_transfer_start` etc., als Referenz für einen
  `schema_migrate`-Service-Vertrag).
- `docs/planning/in-progress/roadmap.md` Milestone 1.1.8 (gRPC),
  1.2.0 (REST).
