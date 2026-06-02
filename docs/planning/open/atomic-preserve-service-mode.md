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

**Offen**: Sollte der Service einen dedizierten Sub-Pool für
Atomic-Migrate-Calls vorhalten, oder beim Borrow die
`SchemaMigrateExecutionStage.DEFAULT_LOCK_TIMEOUT_MILLIS` plus
Borrow-Timeout strikt voneinander unterscheiden?

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

### 3. Backpressure / Rate-Limiting

100 parallele migrate-Calls von einem Angreifer (oder einem
schlecht konfigurierten AI-Client) können den Pool erschöpfen →
DoS auf den Schreib-Pfad. CLI hat diesen Vektor nicht, weil jeder
Aufruf einen eigenen Prozess + eigenen Pool bedeutet.

**Offen**: Server-Mode braucht Per-Tenant-/Per-Schema-Rate-Limit
auf migrate-Calls. Idealerweise auf Application-Level vor dem
Pool-Borrow, damit Pool-Exhaustion nie der erste Rate-Limiter
ist.

### 4. Lock-Timeout-Tuning

5 s Default-LockTimeout (`SchemaMigrateExecutionStage.DEFAULT_LOCK_TIMEOUT_MILLIS`)
ist für CLI konservativ — der Operator wartet vor dem Terminal.
Ein Service braucht möglicherweise:

- **Per-Request-Override** (Client passt das eigene SLO an).
- **Konservativeren Server-Default** (z. B. 30 s), damit
  AI-Agenten weniger oft Timeout-Blocker sehen.
- **Per-Tenant-Override** via Config.

### 5. Idempotency-Replay

MCP-Tool-Calls und gRPC-RPCs dürfen retried werden. Die
Preserve-Probe+Restore-Logik **selbst** ist idempotent (Probe liest
den aktuellen Wert, Restore setzt ihn zurück), **aber** der
augmentierte Plan als Ganzes ist es nicht (DDL wird tatsächlich
ausgeführt; ein Replay nach erfolgreichem Apply würde gegen das
schon-angewendete Schema laufen und voraussichtlich mit
Drift-Diagnostic blockieren).

**Offen**: Brauchen wir einen `idempotency-key`-Vertrag auf dem
Migrate-Tool-Call analog zu `data_transfer_start`'s
`IdempotencyStore`-Vertrag (`adapters/driven/persistence-jdbc`
`JdbcIdempotencyStore`)?

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
- Idempotency-Infrastruktur: `JdbcIdempotencyStore` existiert für
  Data-Worker-Jobs; nicht verdrahtet auf Schema-Pfad.

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
