# Plan: Atomic-Preserve Service-Mode (MCP / REST / gRPC)

> Status: Entwurf (2026-06-02) — Vorzieh-Entscheidung trotz nicht
> erfüllter externer Aktivierungsbedingungen (MCP-Migrate-Tool,
> gRPC 1.1.8, REST 1.2.0). Begründung: die Sub-Slices A/B/E haben
> eigenständigen Wert (Refactor-Schulden + Test-Hygiene + Port-
> Erweiterung), unabhängig davon ob das `schema_migrate`-Tool
> jemals exponiert wird.
>
> Ableitung aus open/-Vorabklärung
> (commits `7ae4114a` Initial + `98ca9ff1` Verfeinerung) durch
> Promote nach `next/`.
>
> Vorbedingung: keine. Die Sub-Slices komponieren bestehende Ports
> (HikariConnectionPoolFactory, JobCancelHandler, QuotaStore,
> IdempotencyStore, MigrationFingerprint) statt neue
> Architektur zu bauen.

---

## 1. Ziel

JVM-/Service-seitige Verträge **um den
`AtomicSequencePreserveExecutor` herum** in vorbereiteten Slices
liefern, sodass ein späterer `schema_migrate`-Tool-/REST-/gRPC-
Slice die Verträge nur noch komponieren muss, statt sie ad hoc
miterfinden zu müssen.

Konkret heißt das fünf Verträge, die der bestehende
Cross-JVM-Carve-Out (Atomic-Preserve §3.2, §6 Risk 8, §8.2)
**nicht** abdeckt — der Carve-Out gilt nur für die DB-side
Lock-Mechanik:

1. Connection-Pool-Vertrag (eigene Connection pro Migrate-Call)
2. Request-Cancellation (sauber `rollback()` + `close()`)
3. Backpressure / Rate-Limiting (Application-Level vor Pool)
4. Lock-Timeout-Tuning (Per-Request-Override + Server-Default)
5. Idempotency-Replay (Migrate-Tool retried sicher)

---

## 2. Ausgangslage

### 2.1 Was heute steht

- **Atomic-Executor + Lock-Mechanik** geliefert (0.9.7, plan-doc
  `done/sequence-preserve-atomic-lock-plan.md`): drei Dialekt-
  Adapter (`PostgresAtomicSequencePreserveExecutor`,
  `MysqlAtomicSequencePreserveExecutor`,
  `SqliteAtomicSequencePreserveExecutor`); Cross-Plan-Deadlock-
  Tests; `supportsAtomicPreserveAllInPlan = true` pro Dialekt;
  Stage-AllInPlan-Gate.
- **`AtomicSequencePreserveExecutor.requireOwnedConnection`**
  setzt `autoCommit = true` + keine umschließende Transaktion
  voraus. Owner-Vertrag ist im Port-Companion gepinnt.
- **CLI-Pfad** ist Single-Process; jeder `schema migrate`-Call
  hat einen eigenen JVM-Prozess + eigenen Hikari-Pool.
  `AtomicSequencePreserveRunner.defaultAcquireConnection`
  (`adapters/driving/cli/...`) ist der Single-Borrow-Point.

### 2.2 Was fehlt für Service-Mode

- **Im Server-Pfad** wird der Migrate-Call zu einem Tool-Call /
  REST-Endpoint / gRPC-RPC. Mehrere parallele Calls teilen den
  Server-Prozess und damit den Hikari-Pool. Same-JVM-Multi-Thread
  wird vom Stresstest-Setup zum **produktiven Hot-Path**.
- Bestehende `data_*`-Worker (`data_transfer_start`,
  `data_import_start`) haben Pattern für Idempotency
  (`IdempotencyStore`), Cancellation (`JobCancelHandler`),
  Quota (`QuotaStore`). Diese sind **nicht** an den Atomic-
  Preserve-/Schema-Migrate-Pfad angeschlossen.

### 2.3 Reuse-Kandidaten

| Vertrag | Bestandsteil | Pfad |
| ------- | ------------ | ---- |
| Connection-Pool | `HikariConnectionPoolFactory` + `PoolSettings` | `adapters/driven/driver-common/.../connection/HikariConnectionPoolFactory.kt`; `hexagon/ports-common/.../driver/connection/PoolSettings.kt` |
| Cancellation (data-Pfad) | `JobCancelHandler` + Worker-Cancel-Polling | `adapters/driving/mcp/.../registry/JobCancelHandler.kt`; `adapters/driven/streaming/.../StreamingImporterCancelPropagationTest.kt` |
| Quota / Rate-Limit | `QuotaStore` + `JdbcQuotaStore` + `QuotaReservationSweeper` | `hexagon/ports-common/.../server/ports/quota/QuotaStore.kt`; `JobQuotaScenarioTest.kt`; `QuotaReservationSweeperE2ETest.kt` |
| Idempotency | `IdempotencyStore` + `JdbcIdempotencyStore` | `hexagon/ports-common/.../server/ports/IdempotencyStore.kt`; `adapters/driven/persistence-jdbc/.../idempotency/JdbcIdempotencyStore.kt`; `IdempotencyStoreContractTests.kt` |
| Migration-Fingerprint (Idempotency-Key) | `MigrationFingerprint.compute` | `hexagon/core/.../diff/migration/MigrationFingerprint.kt` |

---

## 3. Scope

### 3.1 In Scope

Sechs Sub-Slices (A-F), siehe §5. Vier davon (A/B/C/E) sind
**unabhängig** und können in beliebiger Reihenfolge / parallel
landen. D braucht C. F komponiert A+B+C+D+E.

### 3.2 Bewusst nicht Teil dieses Plans

- Der `schema_migrate`-Tool-Handler im MCP-Adapter
  (Sub-Slice F bleibt **Skeleton + Composition** — der
  produktive Tool-Vertrag ist ein eigener Folge-Slice mit MCP-
  Schema-Spec, Policy-Gate-Konfig, Approval-Flow, Audit-
  Trail).
- REST- und gRPC-Wiring (Milestones 1.1.8 / 1.2.0). Die hier
  gebauten Ports + Reservation-Logik sind transport-agnostisch
  und sollten bei Bedarf nur dünn nachverdrahtet werden.
- Tenant-Modell. Falls Tenant-Isolation kommt, ist das ein
  Querschnitt über alle Pfade, nicht atomic-preserve-spezifisch.
- DB-side Cross-DB-Lock-Koordination. Bleibt
  [`carveout.md`](../in-progress/carveout.md) §3 „Cross-DB-Lock-
  Koordination" Permanent.

### 3.3 Aktivierungsbedingung-Status

Die externen Trigger (MCP-Migrate-Tool, gRPC 1.1.8, REST 1.2.0)
sind heute **nicht erfüllt**. Promote nach `next/` ist eine
bewusste Vorzieh-Entscheidung:

- **A** (Lock-Timeout-Refactor) ist Schulden-Abbau aus
  `done/atomic-preserve-followups.md` §8.2 Finding #6 — der
  Test-Decorator-Workaround ist eine bekannte Hygiene-Lücke.
- **B** (Idempotency-Hook) ist ein Vertrag, der auch dem CLI-
  Pfad (`schema migrate --execute`) helfen würde, wenn er
  retry-fähig werden soll.
- **E** (Cancellation-Token) ist eine Port-Erweiterung, die
  auch ohne Service-Mode für lange Schema-Migrationen sinnvoll
  ist (z. B. Operator wirft `Ctrl-C` im interaktiven Modus).

C/D/F warten effektiv auf den externen Trigger und liefern erst
in einer späteren Tranche, wenn `schema_migrate` als Tool
geplant wird.

---

## 4. Leitentscheidungen

### 4.1 Komposition statt Neubau

Die fünf Verträge werden durch Komposition bestehender Ports
(siehe §2.3) gelöst, nicht durch parallele Neuimplementierung.
Das hält die Modul-Topologie schlank und vermeidet zwei
Idempotency-Stores / zwei Quota-Stores / zwei Cancel-Mechaniken
nebeneinander.

### 4.2 DB-side Lock-Mechanik unverändert

`pg_advisory_xact_lock` / MySQL `SELECT FOR UPDATE` / SQLite
`BEGIN IMMEDIATE` bleiben bit-für-bit unverändert. Die Service-
Mode-Verträge sind eine Schicht **um** den Executor, nicht
**in** ihm.

### 4.3 CLI-Pfad bleibt regressionsfrei

Jede Sub-Slice-Änderung muss die bestehenden Live-IT-Tests
(`{Postgres,Mysql,Sqlite}SchemaMigrateAtomicPreserveIntegrationTest`,
`Cross-PlanDeadlockTest`, `SequencePreserveRaceTest`) bytemäßig
grün lassen. Idempotency und Cancellation sind im CLI-Pfad
optional / no-op.

### 4.4 Sub-Slice-Reihenfolge folgt Wertschöpfung, nicht Zwang

A, B, E können einzeln, sofort, ohne Service-Mode-Kontext
landen und liefern Wert auch wenn F nie kommt. C/D/F warten auf
den externen Trigger.

---

## 5. Geplante Arbeitspakete

### Sub-Slice A — `SchemaMigrateRunner.lockTimeoutMillis`-Refactor

**Ziel**: `SchemaMigrateRunner` und `SchemaMigrateExecutionStage`
exponieren `lockTimeoutMillis` als Konstruktor-Parameter, sodass
der Test-Decorator-Workaround
(`{Mysql,Sqlite}SchemaMigrateAtomicPreserveIntegrationTest`
`tightTimeoutExecutor` / `freshConnExecutorWithTimeout`) entfällt
und ein späterer Per-Request-Override aus dem Server-Pfad
sauber durchgereicht werden kann.

**Akzeptanzkriterien**:
- [ ] `SchemaMigrateRunner`-Konstruktor erhält
  `lockTimeoutMillis: Long = SchemaMigrateExecutionStage.DEFAULT_LOCK_TIMEOUT_MILLIS`
  (oder eine `SchemaMigrateRunnerConfig`-Datenklasse, falls die
  Konstruktor-Signatur zu breit wird).
- [ ] `SchemaMigrateExecutionStage`-Konstruktor nimmt
  `lockTimeoutMillis` als Parameter, Default-Wert bleibt
  `5_000L`.
- [ ] Der Test-Decorator
  `MysqlSchemaMigrateAtomicPreserveIntegrationTest.tightTimeoutExecutor`
  + `SqliteSchemaMigrateAtomicPreserveIntegrationTest.freshConnExecutorWithTimeout`
  ist entfernt; Tests nutzen direkt `runnerWith(...
  lockTimeoutMillis = 1_000L)`.
- [ ] CLI-Option `--lock-timeout-ms <ms>` als optionaler Flag in
  `SchemaMigrateCommand`. Bereich: 10 ≤ x ≤ 60_000. Validation
  in `SchemaMigratePreparation.validateRequest`.
- [ ] `make ci` grün (alle Atomic-Preserve-IT-Tests).

**Betroffene Dateien**:
- `hexagon/application/.../cli/commands/SchemaMigrateRunner.kt`
- `hexagon/application/.../cli/commands/SchemaMigrateExecutionStage.kt`
- `hexagon/application/.../cli/commands/SchemaMigratePreparation.kt`
- `adapters/driving/cli/.../SchemaMigrateCommand.kt`
- `test/integration-mysql/.../MysqlSchemaMigrateAtomicPreserveIntegrationTest.kt`
- `test/integration-sqlite/.../SqliteSchemaMigrateAtomicPreserveIntegrationTest.kt`
- `test/integration-postgresql/.../PostgresSchemaMigrateAtomicPreserveIntegrationTest.kt`

**Dependencies**: keine.

**Risiken**: niedrig. Reiner Konstruktor-Parameter-Hoist;
Default bleibt 5_000 ms.

---

### Sub-Slice B — Idempotency-Hook auf Schema-Migrate-Pfad

**Ziel**: `schema migrate --execute` kann mit einem
`--idempotency-key <key>` aufgerufen werden; eine wiederholte
Ausführung mit demselben Key + demselben Schema-Fingerprint
liefert den Original-`ExecutionTrace` zurück (Replay).

**Akzeptanzkriterien**:
- [ ] `SchemaMigrateRequest` lernt
  `idempotencyKey: String? = null`.
- [ ] `SchemaMigrateExecutionStage` ruft (falls Key gesetzt)
  `IdempotencyStore.reserve(scope = IdempotencyScope("schema_migrate",
  targetRef), payloadFingerprint = MigrationFingerprint.compute(targetSchema),
  now)` **vor** der Pipeline.
- [ ] Replay-Detect → Stage gibt den persistierten
  `ExecutionTrace` zurück + Report-Feld
  `replayFromIdempotencyKey: <key>`.
- [ ] Default-Adapter: `InMemoryIdempotencyStore` für den
  CLI-Pfad (kein JDBC-Store erforderlich, weil CLI-Prozess
  flüchtig ist und Idempotency-Replay nur innerhalb desselben
  Aufrufs Sinn macht — der Server-Pfad wird in F den
  `JdbcIdempotencyStore` einhängen).
- [ ] CLI-Option `--idempotency-key <key>` (optional).
- [ ] Vertragstest analog
  `IdempotencyStoreContractTests`: First-Call-Reserves,
  Second-Call-Returns-Replay, Different-Fingerprint-Conflicts.
- [ ] `make ci` grün.

**Betroffene Dateien**:
- `hexagon/application/.../cli/commands/SchemaMigrateRequest.kt`
- `hexagon/application/.../cli/commands/SchemaMigrateExecutionStage.kt`
- `hexagon/application/.../cli/commands/SchemaMigrateReport.kt` (neues Feld)
- `adapters/driving/cli/.../SchemaMigrateCommand.kt`
- Neue Tests:
  `hexagon/application/.../cli/commands/SchemaMigrateIdempotencyTest.kt`

**Dependencies**: keine.

**Risiken**: niedrig-mittel. `MigrationFingerprint.compute`
muss deterministisch sein (ist es per Plan-Artefakt-Vertrag).
Replay-Window-Default abstimmen (z. B. 24 h).

---

### Sub-Slice C — Connection-Sub-Pool-Plumbing

**Ziel**: Im Server-Pfad bekommt jeder `schema_migrate`-Call
eine **eigene** Connection aus einem getrennten Sub-Pool, sodass
parallele Calls + parallele `data_*`-Worker sich nicht auf
Pool-Exhaustion blocken.

**Akzeptanzkriterien**:
- [ ] Neuer Port `MigratePoolFactory` (oder Erweiterung von
  `HikariConnectionPoolFactory`) mit
  `acquire(targetRef): MigratePoolLease` API. Lease hat
  `connection: Connection` + `release()`-Vertrag.
- [ ] Default-Adapter (`HikariMigratePoolAdapter`) verwendet
  einen separaten Hikari-Pool pro `targetRef`, mit
  konfigurierbarem `maximumPoolSize` (Default z. B. 4 für
  Schreib-Pfad-Begrenzung) und `connectionTimeoutMs` als
  Borrow-Timeout.
- [ ] Pool-Borrow-Timeout-Fehler werden separat klassifiziert
  (`SERVICE_POOL_EXHAUSTED`), nicht als generischer DB-Fehler.
- [ ] Bestehender CLI-Pfad ist regressionsfrei (CLI nutzt den
  Default-Pool wie bisher, kein Sub-Pool-Override).
- [ ] Vertragstest: zwei parallele Acquires gegen denselben
  `targetRef` mit `maximumPoolSize = 1` → zweiter blockt bis
  `connectionTimeoutMs` und bekommt
  `SERVICE_POOL_EXHAUSTED`.
- [ ] `make ci` grün.

**Betroffene Dateien**:
- Neuer Port:
  `hexagon/ports-execute/.../MigratePoolFactory.kt`
- Neuer Adapter:
  `adapters/driven/driver-common/.../connection/HikariMigratePoolAdapter.kt`
- Wiring in `SchemaMigrateWiring.kt`

**Dependencies**: keine (unabhängig von A/B/E).

**Risiken**: mittel. Pool-pro-targetRef kann viele Pools
erzeugen, wenn ein Server viele Datenbanken bedient. Pool-
Lifecycle (Close bei Idle-Eviction) muss sauber sein.

---

### Sub-Slice D — Quota-Plumbing auf Schema-Migrate-Pfad

**Ziel**: Vor dem Sub-Pool-Borrow reserviert die Stage eine
Quota für den Schema-Migrate-Call, damit ein Application-Level-
Rate-Limit **vor** Pool-Exhaustion greift.

**Akzeptanzkriterien**:
- [ ] `QuotaScope("schema_migrate", tenant=<tenant>,
  schema=<targetRef>)` als neuer Scope-Typ.
- [ ] `SchemaMigrateExecutionStage` ruft
  `QuotaStore.reserve(scope, payload, now)` vor dem
  Pool-Borrow.
- [ ] Quota-Exhaustion → Stage liefert
  `SERVICE_RATE_LIMITED` zurück (eigener Blocker-Code), ohne
  Pool zu borrowen.
- [ ] Quota wird **nach** dem Migrate-Call (oder bei Sweeper-
  Expire) freigegeben.
- [ ] Bestehender CLI-Pfad ist regressionsfrei (CLI nutzt
  `InMemoryQuotaStore` mit `unlimited` als Default).
- [ ] Vertragstest analog
  `JobQuotaScenarioTest`: Concurrent-Reserve, Release,
  Sweeper-Expire.
- [ ] `make ci` grün.

**Betroffene Dateien**:
- `hexagon/ports-common/.../server/ports/quota/QuotaStore.kt`
  (Erweiterung wenn nötig)
- `hexagon/application/.../cli/commands/SchemaMigrateExecutionStage.kt`
- Neuer Test:
  `hexagon/application/.../cli/commands/SchemaMigrateQuotaTest.kt`

**Dependencies**: C (Connection-Sub-Pool) — Quota schützt
den Pool, also muss der Pool da sein.

**Risiken**: mittel. Tenant-Schlüssel muss konsistent mit
zukünftigem Tenant-Modell sein; bis dahin
`tenant = "default"`-Konvention.

---

### Sub-Slice E — Cancellation-Token im Executor-Port

**Ziel**: Der `AtomicSequencePreserveExecutor`-Port erweitert
sich um einen optionalen `cancellationToken: CancellationToken`-
Parameter. Cancel zwischen Probe und Restore → Rollback +
sauberer Connection-Release.

**Akzeptanzkriterien**:
- [ ] Neuer Vertragstyp
  `CancellationToken` in `hexagon:ports-common` mit
  `isCancellationRequested(): Boolean` (Polling-Modell —
  einfacher als Coroutines-Cancel).
- [ ] `AtomicSequencePreserveExecutor.execute(...)` nimmt
  `cancellationToken: CancellationToken = NoOp` als
  optionalen letzten Parameter.
- [ ] Executor prüft den Token zwischen Lock-Acquire und
  Restore. Cancel zwischen Lock und Restore → Rollback,
  `AtomicSequencePreserveResult.Cancelled`-Variant in der
  Sealed-Class.
- [ ] CLI-Pfad nutzt `CancellationToken.NoOp` (kein
  Verhaltenswechsel).
- [ ] Vertragstest pro Dialekt: Cancel vor Probe → Cancelled +
  Connection released. Cancel zwischen Probe und Restore →
  Cancelled + Rollback + Sequenz unverändert.
- [ ] `make ci` grün.

**Betroffene Dateien**:
- Neuer Typ:
  `hexagon/ports-common/.../CancellationToken.kt`
- `hexagon/ports-execute/.../AtomicSequencePreserveExecutor.kt`
- `hexagon/ports-execute/.../AtomicSequencePreserveResult.kt`
  (neuer `Cancelled`-Variant)
- 3 Dialekt-Adapter
  (`{Postgres,Mysql,Sqlite}AtomicSequencePreserveExecutor.kt`)
- 3 IT-Tests
  (`{Postgres,Mysql,Sqlite}AtomicSequencePreserveExecutorIntegrationTest.kt`)
  um Cancel-Cases erweitert.

**Dependencies**: keine.

**Risiken**: mittel. SQLite hält RESERVED-Lock auf die ganze
DB; Cancel zwischen Lock und Restore muss verlässlich
rollbacken, sonst blockt der Lock alle anderen Schreiber bis
Connection-Close.

---

### Sub-Slice F — `schema_migrate`-Handler-Skeleton (Composition)

**Ziel**: Ein MCP-Handler `schema_migrate` (oder
`schema_migrate_start` analog `data_transfer_start`) im
`adapters/driving/mcp`-Modul, der die Sub-Slices A+B+C+D+E
komponiert und an den `SchemaMigrateRunner` durchreicht.

**Akzeptanzkriterien**:
- [ ] Neuer MCP-Handler `SchemaMigrateStartHandler.kt` analog
  `DataTransferStartHandler`-Pattern (Job-Worker statt
  Sync-Tool, weil Migrate-Operationen sekundenlang dauern).
- [ ] Tool-Schema in `McpToolSchemas.kt` registriert mit
  Parametern: `source`, `target`, `idempotencyKey?`,
  `lockTimeoutMs?`, `tenant?`.
- [ ] Policy-Gate analog `data_transfer_start`-Pattern (aus
  `done/ImpPlan-0.9.6-F.md`): Approval-Flow, Audit-Trail,
  Rate-Limit über Quota.
- [ ] Wiring: Handler löst `SchemaMigrateRunner` mit den
  Service-Mode-Komponenten auf (`JdbcIdempotencyStore`,
  `HikariMigratePoolAdapter`, `JdbcQuotaStore`, Cancel-Token
  aus `JobCancelHandler`-Polling).
- [ ] E2E-Test:
  `McpSchemaMigrateStartScenarioTest.kt` mit
  Happy-Path + Idempotency-Replay + Quota-Exhaustion +
  Cancel.
- [ ] `make ci` grün inkl. Integration-Tests.

**Betroffene Dateien**:
- Neuer Handler:
  `adapters/driving/mcp/.../registry/SchemaMigrateStartHandler.kt`
- `adapters/driving/mcp/.../schema/McpToolSchemas.kt`
- `adapters/driving/mcp/.../registry/OperationalMcpRegistries.kt`
  (Handler-Registration)
- Neuer E2E-Test:
  `adapters/driving/mcp/.../integration/McpSchemaMigrateStartScenarioTest.kt`

**Dependencies**: A + B + C + D + E (alle).

**Risiken**: hoch. Hängt am echten externen Trigger
(`schema_migrate`-Tool als Produkt-Entscheidung). Wenn die
Produkt-Entscheidung nicht kommt, ist F never-built; A/B/E
liefern aber trotzdem Wert.

---

## 6. Dependency-Graph

```
A (Lock-Timeout-Refactor)        ──┐
B (Idempotency-Hook)             ──┤
C (Connection-Sub-Pool)          ──┼──► F (schema_migrate-Handler)
D (Quota-Plumbing) ──depends─►C ──┤
E (Cancellation-Token)           ──┘
```

- A, B, C, E sind unabhängig und können in beliebiger
  Reihenfolge / parallel landen.
- D braucht C.
- F braucht alle fünf.

Empfohlene Schubrichtung (falls externer Trigger nicht zeitnah
feuert): **A vor B vor E**, weil A reiner Schulden-Abbau ist und
die Test-Suite bereinigt, B und E Vertrags-Erweiterungen sind,
die für sich genommen den CLI-Pfad robuster machen.

---

## 7. Risiken

1. **Externer Trigger feuert nie** — F (und damit ein Großteil
   von D) ist tote Hose. A/B/E haben aber eigenständigen Wert,
   das Risiko ist begrenzt.
2. **Tenant-Modell ändert sich** — die in C/D verwendete
   `tenant`/`targetRef`-Konvention könnte vom späteren Tenant-
   Konzept abweichen. Mitigation: erst `tenant = "default"`
   verdrahten, Quota-Scope so designen, dass ein Refactor lokal
   bleibt.
3. **SQLite-Cancellation hält RESERVED-Lock** — falls Cancel
   nicht sauber rollbacked, blockt der Lock alle anderen
   Schreiber. Mitigation: explizite IT-Tests pro Dialekt
   (E-Akzeptanzkriterien).
4. **Idempotency-Replay-Window-Default** — zu kurz → retry nach
   Netzwerk-Fehler scheitert; zu lang → Store wird unbeschränkt
   groß. Default 24 h ist Vorschlag; Sweeper-Konfig nötig.
5. **Pool-pro-targetRef-Skalierung** — viele Datenbanken =
   viele Pools. Bei > 100 targetRefs braucht es eine
   LRU-Eviction-Logik. Out-of-Scope; ggf. separater Folge-Slice.

---

## 8. Carve-outs

- **Cross-DB-Lock-Koordination** bleibt Permanent out-of-scope
  ([`carveout.md`](../in-progress/carveout.md) §3).
- **Cross-JVM-Stresstest (CLI-Pfad)** bleibt Permanent
  ([`carveout.md`](../in-progress/carveout.md) §3) — Same-JVM-
  Two-Threads ist äquivalent.
- **REST + gRPC-Migrate-Wiring** sind ausserhalb dieses Plans.
  Sub-Slice F liefert ein MCP-Handler-Skeleton; REST/gRPC sind
  Folge-Slices in den Milestones 1.1.8 / 1.2.0.
- **MCP-Migrate-Tool als Produkt-Entscheidung** ist
  weiterhin offen ([`carveout.md`](../in-progress/carveout.md)
  §7) — dieser Plan baut nur die JVM-Verträge **um den
  Executor herum**, nicht den Tool-Vertrag selbst.

---

## 9. Verweise

- `docs/planning/done/sequence-preserve-atomic-lock-plan.md`
  §3.2 Out-of-Scope, §6 Risiken Nr. 8, §7 Out-of-Scope-Folge-
  Themen, §8.2 Carve-outs.
- `docs/planning/done/atomic-preserve-followups.md` §6 Offene
  Fragen + §8.3 Carve-outs.
- `docs/planning/done/ImpPlan-0.9.6-F.md` — Policy-Gate-Pattern
  für `data_transfer_start`, Referenz für Sub-Slice F.
- `docs/planning/done/quality-coverage-expansion-plan.md` §3.2
  + §9 — MCP-Migrate-Tool als neues Produkt-Thema.
- [`docs/planning/in-progress/carveout.md`](../in-progress/carveout.md)
  §3 Sequence-Preserve / Atomic-Preserve + §7 Telemetry +
  MCP + Produktscope.
- [`docs/planning/in-progress/roadmap.md`](../in-progress/roadmap.md)
  Milestone 1.1.8 (gRPC), 1.2.0 (REST).
