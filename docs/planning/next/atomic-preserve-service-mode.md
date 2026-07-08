# Plan: Atomic-Preserve Service-Mode (MCP / REST / gRPC) — Sub-Slices C/D/F

> **Status**: Next (2026-06-03 — Split aus
> [`docs/planning/done-archive/ImpPlan-0.9.8-atomic-preserve-AE.md`](../done-archive/ImpPlan-0.9.8-atomic-preserve-AE.md)
> als [ADR-0004](../../adr/0004-documentation-and-planning-structure.md)-strikte Aufteilung: A + E + SIGINT als Closure dort,
> die offenen Sub-Slices C + D + F hier).
>
> **Aktivierungsbedingung** (Move nach `in-progress/`): Mindestens
> einer der drei externen Trigger ist erfüllt, sodass C/D/F einen
> realen Konsumenten haben:
>
> - **MCP-Produkt-Vertrag** für `schema_migrate`-Tool —
>   [`mcp-schema-migrate-tool.md`](mcp-schema-migrate-tool.md)
>   liegt mit Wire-Vertrag V1, Sub-Slice-Schnitt F.1-F.5 und
>   Strawman §3 in `next/`. Sobald F.1 dort startet (Tool-Schema
>   + Discovery-Wiring), wandert dieses Doc gemeinsam nach
>   `in-progress/`, und die natürliche Reihenfolge ist
>   C → D → F-MCP (siehe Dep-Graph in `mcp-schema-migrate-tool.md`
>   §6).
> - **REST 1.2.0-Spezifikation** für den Migrate-Pfad.
> - **gRPC 1.1.8-Spezifikation** für den Migrate-Pfad.

---

## 1. Ziel

Die drei Service-Mode-JVM-Verträge, die der bestehende
Cross-JVM-Carve-Out (Atomic-Preserve §3.2, §6 Risk 8, §8.2)
**nicht** abdeckt:

1. **Connection-Sub-Pool** pro `targetRef`, damit parallele
   Schema-Migrate-Calls + parallele `data_*`-Worker sich nicht auf
   Pool-Exhaustion blocken.
2. **Quota-Plumbing** als Application-Level-Rate-Limit
   **vor** Pool-Exhaustion.
3. **`schema_migrate_start`-Handler-Skeleton**, der C + D mit
   den in
   [`done-archive/ImpPlan-0.9.8-atomic-preserve-AE.md`](../done-archive/ImpPlan-0.9.8-atomic-preserve-AE.md)
   bereits gelieferten A (Lock-Timeout-Tuning) und E (Cancellation-
   Token) komponiert.

Konkretes Tool-Schema, Wire-Vertrag und Sub-Slice-Schnitt für den
MCP-Handler leben in
[`mcp-schema-migrate-tool.md`](mcp-schema-migrate-tool.md); dieser
Plan liefert die **JVM-seitigen Verträge**, die der MCP-Handler
(und später REST/gRPC) konsumieren.

---

## 2. Ausgangslage

### 2.1 Was geliefert ist (siehe Closure-Doc)

- **A** Lock-Timeout-Refactor (`2fcb3846`):
  `SchemaMigrateRunner.lockTimeoutMillis`-Parameter,
  `SchemaMigrateRequest.lockTimeoutMillis`-Override, CLI-Flag
  `--lock-timeout-ms` mit Validation `[10, 60_000]`.
- **E** Cancellation-Token im Executor-Port (`7e6f39ae`):
  `AtomicSequencePreserveExecutor.execute(..., cancellationToken)`,
  drei Cancel-Checkpoints pro Dialekt,
  `AtomicSequencePreserveResult.Cancelled`.
- **E-Follow-up** SIGINT-Bridge im CLI-Pfad (`73fb1f73`).
- **B** Idempotency-Hook als eigenständiger Slice **deferred** —
  Wiring landet direkt im MCP-Handler von F (siehe
  [`done-archive/ImpPlan-0.9.8-atomic-preserve-AE.md`](../done-archive/ImpPlan-0.9.8-atomic-preserve-AE.md)
  §3).

### 2.2 Reuse-Kandidaten für C/D/F

| Vertrag | Bestehender Port / Adapter | Quelle |
| ------- | -------------------------- | ------ |
| Connection-Pool | `HikariConnectionPoolFactory` + `PoolSettings` | `adapters/driven/driver-common/…/connection/HikariConnectionPoolFactory.kt`; `hexagon/ports-common/…/driver/connection/PoolSettings.kt` |
| Cancellation (data-Pfad) | `JobCancelHandler` + Worker-Cancel-Polling | `adapters/driving/mcp/…/registry/JobCancelHandler.kt` |
| Quota / Rate-Limit | `QuotaStore` + `JdbcQuotaStore` + `QuotaReservationSweeper` | `hexagon/ports-common/…/server/ports/quota/QuotaStore.kt`; `JobQuotaScenarioTest.kt` |
| Idempotency | `IdempotencyStore` + `JdbcIdempotencyStore` | `hexagon/ports-common/…/server/ports/IdempotencyStore.kt`; `adapters/driven/persistence-jdbc/…/idempotency/JdbcIdempotencyStore.kt` |

C/D/F komponieren diese Ports, statt parallele
Implementierungen zu bauen.

---

## 3. Leitentscheidungen

### 3.1 Komposition statt Neubau

Die drei Verträge werden durch Komposition bestehender Ports
gelöst, nicht durch parallele Neuimplementierung. Das hält die
Modul-Topologie schlank und vermeidet zwei Quota-Stores / zwei
Pool-Factories nebeneinander.

### 3.2 DB-side Lock-Mechanik unverändert

`pg_advisory_xact_lock` / MySQL `SELECT FOR UPDATE` / SQLite
`BEGIN IMMEDIATE` bleiben die DB-seitige Korrektheits-Grenze. C/D
liefern App-Layer-Feedback (Pool-Borrow-Timeout, Quota-
Exhaustion), das **vor** dem DB-Lock-Wait greift, brechen die
Lock-Garantie aber nicht.

### 3.3 CLI-Pfad bleibt regressionsfrei

Genau wie A+E im CLI-Pfad transparent waren
([`done-archive/ImpPlan-0.9.8-atomic-preserve-AE.md`](../done-archive/ImpPlan-0.9.8-atomic-preserve-AE.md)),
müssen C/D den bestehenden CLI-Pfad unverändert lassen — kein
Sub-Pool-Override, kein Quota-Check (Default
`InMemoryQuotaStore` mit `unlimited`).

---

## 4. Geplante Arbeitspakete

### Sub-Slice C — Connection-Sub-Pool-Plumbing

**Ziel**: Im Server-Pfad bekommt jeder `schema_migrate`-Call eine
**eigene** Connection aus einem getrennten Sub-Pool, sodass
parallele Calls + parallele `data_*`-Worker sich nicht auf
Pool-Exhaustion blocken.

**Akzeptanzkriterien**:

- [ ] Neuer Port `MigratePoolFactory` (oder Erweiterung von
  `HikariConnectionPoolFactory`) mit
  `acquire(targetRef): MigratePoolLease` API. Lease hat
  `connection: Connection` + `release()`-Vertrag.
- [ ] Default-Adapter (`HikariMigratePoolAdapter`) verwendet
  einen separaten Hikari-Pool pro `targetRef`, mit
  konfigurierbarem `maximumPoolSize` (Default `1` für
  Single-Schreiber pro Target, siehe
  [`mcp-schema-migrate-tool.md`](mcp-schema-migrate-tool.md)
  §3.5) und `connectionTimeoutMs` als Borrow-Timeout.
- [ ] Pool-Borrow-Timeout-Fehler werden separat klassifiziert
  (`SERVICE_POOL_EXHAUSTED`), nicht als generischer DB-Fehler.
- [ ] Pool-Lifecycle (Close bei Idle-Eviction) sauber, sonst
  Pool-Explosion bei vielen Targets — siehe Risk #2.
- [ ] Bestehender CLI-Pfad ist regressionsfrei (CLI nutzt den
  Default-Pool wie bisher, kein Sub-Pool-Override).
- [ ] Vertragstest: zwei parallele Acquires gegen denselben
  `targetRef` mit `maximumPoolSize = 1` → zweiter blockt bis
  `connectionTimeoutMs` und bekommt `SERVICE_POOL_EXHAUSTED`.
- [ ] `make ci` grün.

**Betroffene Dateien**:

- Neuer Port: `hexagon/ports-execute/…/MigratePoolFactory.kt`
- Neuer Adapter:
  `adapters/driven/driver-common/…/connection/HikariMigratePoolAdapter.kt`
- Wiring in `SchemaMigrateWiring.kt`

**Dependencies**: keine.

**Risiken**: mittel. Pool-pro-targetRef kann viele Pools
erzeugen, wenn ein Server viele Datenbanken bedient. Pool-
Lifecycle (Close bei Idle-Eviction) muss sauber sein — siehe
Risk #2.

---

### Sub-Slice D — Quota-Plumbing auf Schema-Migrate-Pfad

**Ziel**: Vor dem Sub-Pool-Borrow reserviert die Stage eine Quota
für den Schema-Migrate-Call, damit ein Application-Level-Rate-
Limit **vor** Pool-Exhaustion greift.

**Akzeptanzkriterien**:

- [ ] Auf bestehende
  [`JobStartOrchestrator.reserveQuota`](../../../hexagon/application/src/main/kotlin/dev/dmigrate/server/application/job/JobStartOrchestrator.kt)-
  Mechanik aufgesetzt: `QuotaKey(tenantId, ACTIVE_JOBS,
  principalId, operation="schema_migrate_start")`. Quota läuft
  synchron im Commit-Pfad VOR `jobStartTransaction.commit`
  (Zeile 260ff). Damit ist Quota untrennbar an den Job-Start
  gebunden — siehe
  [`mcp-schema-migrate-tool.md`](mcp-schema-migrate-tool.md)
  §3.6.
- [ ] Quota-Exhaustion → synchroner `RATE_LIMITED` mit
  `retryAfter`/`current`/`limit` in `details` (Bestand-Pattern
  aus `data_transfer_start`).
- [ ] Quota wird **nach** dem Migrate-Call (oder bei Sweeper-
  Expire) freigegeben.
- [ ] Bestehender CLI-Pfad ist regressionsfrei (CLI nutzt
  `InMemoryQuotaStore` mit `unlimited` als Default).
- [ ] Vertragstest analog `JobQuotaScenarioTest`:
  Concurrent-Reserve, Release, Sweeper-Expire.
- [ ] `make ci` grün.

**Betroffene Dateien**:

- `hexagon/ports-common/…/server/ports/quota/QuotaStore.kt`
  (Erweiterung wenn nötig)
- `hexagon/application/…/job/JobStartOrchestrator.kt`
- Neuer Test:
  `hexagon/application/…/job/SchemaMigrateQuotaScenarioTest.kt`

**Dependencies**: C (Connection-Sub-Pool) — Quota schützt den
Pool, also muss der Pool da sein.

**Risiken**: mittel. `targetRef`-spezifische Quota-Granularität
(„max N parallele Jobs pro Ziel-DB") ist im MVP **nicht** Teil
dieses Slices, weil der Bestands-`QuotaKey` tenant/principal/
operation-weit ist. Erweiterung kommt als eigener Service-Mode-
Quota-Slice — siehe Risk #3 und
[`mcp-schema-migrate-tool.md`](mcp-schema-migrate-tool.md) §7
Risk #5.

---

### Sub-Slice F — `schema_migrate`-Handler-Skeleton (Composition)

**Ziel**: Ein MCP-Handler `schema_migrate_start` im
`adapters/driving/mcp`-Modul, der A + C + D + E + die deferred-B-
Wiring-Logik (bestehender `IdempotencyStore`) komponiert und an
den `SchemaMigrateRunner` durchreicht.

**Vertragsschnitt + Wire-Vertrag**: vollständig in
[`mcp-schema-migrate-tool.md`](mcp-schema-migrate-tool.md) §2
(Wire-Vertrag V1) und §5 (Sub-Slices F.1-F.5 mit Akzeptanzkriterien
pro Handler-Phase). Dieser Slice bündelt die JVM-seitige
Komposition; F.1-F.5 in `mcp-schema-migrate-tool.md` sind die
Implementierungs-Sub-Slices.

**Akzeptanzkriterien** (Composition-spezifisch):

- [ ] Wiring: Handler löst `SchemaMigrateRunner` mit den
  Service-Mode-Komponenten auf (`JdbcIdempotencyStore`,
  `HikariMigratePoolAdapter`, `JdbcQuotaStore`, Cancel-Token
  aus `JobCancelHandler`-Polling) — alle aus Bestand bzw. C/D.
- [ ] Pool-Lease wird im Worker (nicht im Handler) erworben, um
  das Job-Start-Pattern nicht zu blockieren — siehe
  [`mcp-schema-migrate-tool.md`](mcp-schema-migrate-tool.md)
  §F.3.
- [ ] Idempotency-Wiring direkt am Handler analog
  `data_transfer_start`, ohne neue Port-Schicht (B-Deferral-
  Konsequenz).
- [ ] `make ci` grün inkl. der E2E-Scenarios aus
  [`mcp-schema-migrate-tool.md`](mcp-schema-migrate-tool.md)
  §F.5.

**Betroffene Dateien**: siehe
[`mcp-schema-migrate-tool.md`](mcp-schema-migrate-tool.md) §F.1-F.5
(Sub-Slice-spezifisch).

**Dependencies**: A (geliefert), C, D, E (geliefert) plus
[`mcp-schema-migrate-tool.md`](mcp-schema-migrate-tool.md) Sub-
Slices F.1-F.4 (Tool-Schema, dryRun-Handler, Pool-Wiring,
Apply-Pfad).

**Risiken**: hoch. Hängt am echten externen Trigger
(`schema_migrate`-Tool als Produkt-Entscheidung). Wenn die
Produkt-Entscheidung nicht kommt, ist F never-built; A/E liefern
aber trotzdem Wert.

---

## 5. Dependency-Graph

```
A (geliefert)  ──────────────────────────┐
                                          │
C (Connection-Sub-Pool)  ───┐             │
                            ↓             │
D (Quota-Plumbing) ◄── braucht C          │
                            │             │
E (geliefert)  ─────────────┤             │
                            ↓             ↓
mcp-schema-migrate-tool ──→ F (Composition)
F.1-F.4 (Tool-Vertrag)
```

- C ist unabhängig (Start-Slice nach Trigger).
- D braucht C.
- F braucht A (geliefert) + C + D + E (geliefert) plus die
  F.1-F.4-Sub-Slices aus
  [`mcp-schema-migrate-tool.md`](mcp-schema-migrate-tool.md).

Natürliche Reihenfolge nach Trigger: **C → D → F**, parallel
dazu F.1+F.2 aus `mcp-schema-migrate-tool.md` (sofort
implementierbar ohne C/D), dann F.3 (parallel zu C in
`atomic-preserve` selbst), dann F.4 (Synthese).

---

## 6. Risiken

1. **Externer Trigger feuert nie.** F (und damit ein Großteil
   von D) ist tote Hose. A/E haben aber eigenständigen Wert
   (geliefert); das Risiko ist begrenzt.
2. **Pool-pro-targetRef-Skalierung.** Viele Datenbanken = viele
   Pools. Bei > 100 `targetRef`s braucht es eine LRU-Eviction-
   Logik. Out-of-Scope; ggf. separater Folge-Slice.
3. **Quota-Granularität nicht targetRef-spezifisch.** MVP nutzt
   `ACTIVE_JOBS` (tenant/principal-weit). targetRef-spezifische
   Quota ist eigener Folge-Slice — siehe
   [`mcp-schema-migrate-tool.md`](mcp-schema-migrate-tool.md) §7
   Risk #5.
4. **Tenant-Modell ändert sich.** Die in D verwendete
   `tenant`/`targetRef`-Konvention könnte vom späteren Tenant-
   Konzept abweichen. Mitigation: erst `tenant = "default"`
   verdrahten; Quota-Scope so designen, dass ein Refactor lokal
   bleibt — siehe
   [`mcp-schema-migrate-tool.md`](mcp-schema-migrate-tool.md) §3.3.

---

## 7. Carve-outs

- **Cross-DB-Lock-Koordination** bleibt Permanent out-of-scope
  ([`carveout.md`](../in-progress/carveout.md) §3).
- **Cross-JVM-Stresstest (CLI-Pfad)** bleibt Permanent
  ([`carveout.md`](../in-progress/carveout.md) §3) — Same-JVM-
  Two-Threads ist äquivalent.
- **REST + gRPC-Migrate-Wiring** sind außerhalb dieses Plans. F
  liefert ein MCP-Handler-Skeleton; REST/gRPC sind Folge-Slices
  in den Milestones 1.1.8 / 1.2.0.
- **MCP-Migrate-Tool als Produkt-Entscheidung** ist abgedeckt
  durch
  [`mcp-schema-migrate-tool.md`](mcp-schema-migrate-tool.md) —
  dieser Plan baut nur die JVM-Verträge **um den Executor
  herum**, nicht den Tool-Vertrag selbst.

---

## 8. Verweise

- [`done-archive/ImpPlan-0.9.8-atomic-preserve-AE.md`](../done-archive/ImpPlan-0.9.8-atomic-preserve-AE.md)
  — Closure für die gelieferten Sub-Slices A + E + SIGINT +
  B-Deferral.
- [`mcp-schema-migrate-tool.md`](mcp-schema-migrate-tool.md) —
  MCP-Tool-Vertrag, der F konsumiert. Sub-Slices F.1-F.5 dort
  sind die MCP-seitigen Implementierungs-Stufen.
- `docs/planning/done-archive/sequence-preserve-atomic-lock-plan.md`
  §3.2 Out-of-Scope, §6 Risiken Nr. 8, §7 Out-of-Scope-Folge-
  Themen, §8.2 Carve-outs.
- `docs/planning/done-archive/atomic-preserve-followups.md` §6 Offene
  Fragen + §8.3 Carve-outs.
- `docs/planning/done-archive/ImpPlan-0.9.6-F.md` — Policy-Gate-Pattern
  für `data_transfer_start`, Referenz für F-Wiring.
- `docs/planning/done-archive/quality-coverage-expansion-plan.md` §3.2
  + §9 — MCP-Migrate-Tool als neues Produkt-Thema.
- [`docs/planning/in-progress/carveout.md`](../in-progress/carveout.md)
  §3 Sequence-Preserve / Atomic-Preserve + §7 Telemetry +
  MCP + Produktscope.
- [`docs/planning/in-progress/roadmap.md`](../in-progress/roadmap.md)
  Milestone 0.9.8 (A+E geliefert), 1.1.8 (gRPC), 1.2.0 (REST).
