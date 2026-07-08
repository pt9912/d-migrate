# Implementierungsplan: 0.9.8 — Atomic-Preserve Service-Mode Sub-Slices A + E

> **Milestone**: 0.9.8 (in Bearbeitung) — Service-Mode-Vorarbeiten
> **Status**: ✅ abgeschlossen (2026-06-02) — A + E + SIGINT-Follow-up
> grün; **B** (Idempotency-Hook) als eigenständiger Slice deferred
> und in F gefaltet (siehe §3 unten).
> **Referenz**: [`docs/planning/next/atomic-preserve-service-mode.md`](../next/atomic-preserve-service-mode.md)
> für die verbleibenden Sub-Slices C/D/F;
> [`docs/planning/next/mcp-schema-migrate-tool.md`](../next/mcp-schema-migrate-tool.md)
> für den MCP-Tool-Vertrag, der C/D/F konsumiert.

---

## 1. Kontext

Die Service-Mode-JVM-Verträge (Connection-Pool, Cancellation,
Rate-Limit, Lock-Timeout-Tuning, Idempotency-Replay) wurden
ursprünglich als Sechs-Sub-Slice-Plan (A bis F) in
`docs/planning/open/atomic-preserve-service-mode.md` skizziert
([`7ae4114a`](https://github.com/pt9912/d-migrate/commit/7ae4114a)
Initial, [`98ca9ff1`](https://github.com/pt9912/d-migrate/commit/98ca9ff1)
Verfeinerung). Promote nach `next/` mit Sub-Slice-Ausarbeitung
A–F erfolgte am 2026-06-02
([`0e9d2a1a`](https://github.com/pt9912/d-migrate/commit/0e9d2a1a)),
Promote nach `in-progress/` mit Sub-Slice A Start am selben Tag.

Die Vorzieh-Entscheidung trotz nicht erfüllter externer
Aktivierungsbedingungen (MCP-Migrate-Tool, gRPC 1.1.8, REST 1.2.0)
hatte einen klaren Schulden-Abbau-Wert: A entfernt einen
Test-Decorator-Workaround aus `done-archive/atomic-preserve-followups.md`
§8.2 Finding #6, E ist eine Port-Erweiterung, die auch ohne
Service-Mode dem CLI hilft (Ctrl-C im interaktiven Modus).

Diese Closure dokumentiert genau die in der ersten Tranche
gelieferten Sub-Slices A + E sowie das Deferral von B in F.

---

## 2. Sub-Slice A — `SchemaMigrateRunner.lockTimeoutMillis`-Refactor

**Status**: ✅ implementiert 2026-06-02
([`2fcb3846`](https://github.com/pt9912/d-migrate/commit/2fcb3846)).

**Ziel**: `SchemaMigrateRunner` und `SchemaMigrateExecutionStage`
exponieren `lockTimeoutMillis` als Konstruktor-Parameter, sodass
der Test-Decorator-Workaround
(`{Mysql,Sqlite}SchemaMigrateAtomicPreserveIntegrationTest`
`tightTimeoutExecutor` / `freshConnExecutorWithTimeout`) entfällt
und ein späterer Per-Request-Override aus dem Server-Pfad sauber
durchgereicht werden kann.

**Gelieferte Akzeptanzkriterien**:

- ✅ `SchemaMigrateRunner`-Konstruktor erhält
  `lockTimeoutMillis: Long = SchemaMigrateExecutionStage.DEFAULT_LOCK_TIMEOUT_MILLIS`.
- ✅ `SchemaMigrateExecutionStage`-Konstruktor nimmt
  `lockTimeoutMillis` als Parameter, Default-Wert bleibt
  `5_000L`.
- ✅ `SchemaMigrateRequest.lockTimeoutMillis`-Per-Request-Override.
- ✅ Der Test-Decorator
  `MysqlSchemaMigrateAtomicPreserveIntegrationTest.tightTimeoutExecutor`
  + `SqliteSchemaMigrateAtomicPreserveIntegrationTest.freshConnExecutorWithTimeout`
  ist entfernt; Tests nutzen direkt `runnerWith(...
  lockTimeoutMillis = 1_000L)`.
- ✅ CLI-Flag `--lock-timeout-ms <ms>` als optionaler Flag in
  `SchemaMigrateCommand`. Validation `[10, 60_000]` mit Exit 2.
- ✅ `make ci` grün (alle Atomic-Preserve-IT-Tests).

**Betroffene Dateien** (Stand commit `2fcb3846`):

- `hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/SchemaMigrateRunner.kt`
- `hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/SchemaMigrateExecutionStage.kt`
- `hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/SchemaMigratePreparation.kt`
- `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/commands/SchemaMigrateCommand.kt`
- `test/integration-mysql/src/test/kotlin/dev/dmigrate/driver/mysql/MysqlSchemaMigrateAtomicPreserveIntegrationTest.kt`
- `test/integration-sqlite/src/test/kotlin/dev/dmigrate/driver/sqlite/SqliteSchemaMigrateAtomicPreserveIntegrationTest.kt`
- `test/integration-postgresql/src/test/kotlin/dev/dmigrate/driver/postgresql/PostgresSchemaMigrateAtomicPreserveIntegrationTest.kt`

---

## 3. Sub-Slice B — Idempotency-Hook (Deferred, in F gefaltet)

**Status**: Deferred — als eigenständiger Slice gestrichen, wird
beim Bau von F (siehe `next/atomic-preserve-service-mode.md` §5 F)
direkt am MCP-Handler verdrahtet.

Code-Audit 2026-06-02 ergab drei Probleme mit B als eigenständigem
Slice:

1. **CLI-Pfad hat keinen echten Replay-Wert.** `schema migrate
   --execute` läuft in einem single-shot JVM-Prozess. Ein
   In-Memory-Store überlebt das Prozess-Ende nicht; nur ein
   persistenter Store (File/JDBC) würde CLI-Retry-Safety liefern.
   Das ist deutlich größerer Scope als ursprünglich angenommen.
2. **Der bestehende `IdempotencyStore` ist Job-Start-orientiert,
   nicht ExecutionTrace-Storage**:
   - Port:
     [`hexagon/ports-common/.../IdempotencyStore.kt:28`](../../../hexagon/ports-common/src/main/kotlin/dev/dmigrate/server/ports/IdempotencyStore.kt).
   - JDBC-Adapter:
     [`adapters/driven/persistence-jdbc/.../JdbcIdempotencyStore.kt:20`](../../../adapters/driven/persistence-jdbc/src/main/kotlin/dev/dmigrate/server/persistence/jdbc/idempotency/JdbcIdempotencyStore.kt).
   - MCP-Wiring an Job-Start-Orchestrator:
     [`adapters/driving/mcp/.../OperationalMcpRegistries.kt:21`](../../../adapters/driving/mcp/src/main/kotlin/dev/dmigrate/mcp/registry/OperationalMcpRegistries.kt).
   - `commit(resultRef: String, …)` speichert eine **Job-Ref**,
     nicht den `ExecutionTrace`. Bei MCP-Start-Tools lebt der
     Trace/Report am Job-/Artifact-/Status-Pfad, getrennt vom
     Idempotency-Eintrag.
3. **Ein CLI-Pfad-Hook ohne MCP-Job-Worker wäre eine zweite
   parallele Idempotency-Architektur**, mit der Gefahr, dass sie
   beim Promote nach F (das den bestehenden Store sinnvoll
   konsumiert) wieder umgebaut werden muss.

**Konsequenz**: B verschwindet als eigenständiger Slice aus der
Tabelle. Wenn F (`schema_migrate_start`-Handler-Skeleton) gebaut
wird, hängt der Handler **direkt** den bestehenden
`IdempotencyStore` analog `data_transfer_start` ein — siehe
`next/atomic-preserve-service-mode.md` §5 Sub-Slice F „Wiring".

---

## 4. Sub-Slice E — Cancellation-Token im Executor-Port

**Status**: ✅ implementiert 2026-06-02
([`7e6f39ae`](https://github.com/pt9912/d-migrate/commit/7e6f39ae)).

**Ziel**: Der `AtomicSequencePreserveExecutor`-Port erweitert sich
um einen optionalen `cancellationToken: CancellationToken`-
Parameter. Cancel zwischen Probe und Restore → Rollback + sauberer
Connection-Release.

**Gelieferte Akzeptanzkriterien**:

- ✅ Neuer Vertragstyp `CancellationToken` in `hexagon:ports-common`
  mit `isCancellationRequested(): Boolean` (Polling-Modell).
- ✅ `AtomicSequencePreserveExecutor.execute(...)` nimmt
  `cancellationToken: CancellationToken = CancellationToken.none()`
  als optionalen letzten Parameter.
- ✅ Drei Cancel-Checkpoints pro Dialekt (pre-BEGIN,
  post-probe/pre-protected, post-protected/pre-restore).
- ✅ `AtomicSequencePreserveResult.Cancelled`-Variant in der
  Sealed-Class.
- ✅ Lambda-Plumbing durch `SegmentAwareExecutorFn` /
  `SegmentAwareMigrationExecutor` / `AtomicSequencePreserveRunner`
  bis zum Dialekt-Adapter.
- ✅ CLI-Pfad nutzt `CancellationToken.none()` (kein
  Verhaltenswechsel — siehe E-Follow-up §5).
- ✅ 6 IT-Cancel-Tests (PG/MySQL/SQLite × {pre-BEGIN,
  cancel-in-callback}) pinnen Cancelled-Outcome + Rollback-Vertrag.
- ✅ `make ci` grün.

**Betroffene Dateien** (Stand commit `7e6f39ae`):

- `hexagon/ports-common/src/main/kotlin/dev/dmigrate/cancel/CancellationToken.kt`
- `hexagon/ports-execute/src/main/kotlin/.../AtomicSequencePreserveExecutor.kt`
- `hexagon/ports-execute/src/main/kotlin/.../AtomicSequencePreserveResult.kt`
- 3 Dialekt-Adapter
  (`{Postgres,Mysql,Sqlite}AtomicSequencePreserveExecutor.kt`)
- 3 IT-Tests
  (`{Postgres,Mysql,Sqlite}AtomicSequencePreserveExecutorIntegrationTest.kt`)
  um Cancel-Cases erweitert.

---

## 5. E-Follow-up — CLI SIGINT-Bridge

**Status**: ✅ implementiert 2026-06-02
([`73fb1f73`](https://github.com/pt9912/d-migrate/commit/73fb1f73)).

**Ziel**: CLI SIGINT/SIGTERM →
`CancellationToken.cancel()`-Bridge in
`SchemaMigrateWiring.executeInternal` analog
`McpServerLifecycle`-Pattern. Ctrl-C während `schema migrate
--execute` triggert sauberen Rollback an einem der drei
Atomic-Preserve-Checkpoints + Operator-stderr-Breadcrumb statt
hartem JVM-Kill.

**Gelieferte Akzeptanzkriterien**:

- ✅ SIGINT/SIGTERM-Handler in `SchemaMigrateWiring`-Layer
  installiert.
- ✅ Cancel-Signal wird in `CancellationToken` propagiert.
- ✅ Operator-stderr-Breadcrumb („Cancellation requested by signal
  …") vor JVM-Exit.
- ✅ `make ci` grün.

---

## 6. Carve-outs

- **C** (Connection-Sub-Pool), **D** (Quota-Plumbing), **F**
  (`schema_migrate`-Handler-Skeleton): warten auf externen Trigger
  (MCP-Migrate-Tool / gRPC 1.1.8 / REST 1.2.0). Vollständige
  Akzeptanzkriterien siehe
  [`docs/planning/next/atomic-preserve-service-mode.md`](../next/atomic-preserve-service-mode.md).
  Der MCP-Tool-Trigger ist mit
  [`docs/planning/next/mcp-schema-migrate-tool.md`](../next/mcp-schema-migrate-tool.md)
  effektiv vorbereitet (Wire-Vertrag V1, Sub-Slice-Schnitt
  F.1-F.5).
- **REST 1.2.0 / gRPC 1.1.8**: eigene Roadmap-Slices.

---

## 7. Verweise

- `docs/planning/next/atomic-preserve-service-mode.md` —
  verbleibende Sub-Slices C/D/F mit Akzeptanzkriterien.
- `docs/planning/next/mcp-schema-migrate-tool.md` — MCP-Tool-
  Vertrag, der C/D/F konsumiert.
- `docs/planning/done-archive/atomic-preserve-followups.md` §8.2 Finding
  #6 — Trigger für A (Test-Decorator-Workaround).
- `docs/planning/in-progress/carveout.md` — Cross-JVM-Service-
  Mode-Verträge als Promoted-Eintrag.
- `docs/planning/in-progress/roadmap.md` Milestone 0.9.8 —
  A + E + SIGINT als geliefert markiert.

---

## 8. Lifecycle-Notiz

Das ursprüngliche Plan-Doc lebte als
`docs/planning/open/atomic-preserve-service-mode.md` (Trigger-
Watch) bzw. zeitweise in `docs/planning/in-progress/`. Es wurde
am 2026-06-03 in zwei Dokumente gesplittet:

- Diese Closure (`docs/planning/done-archive/ImpPlan-0.9.8-atomic-preserve-AE.md`)
  für die gelieferten Sub-Slices A + E + SIGINT + B-Deferral.
- [`docs/planning/next/atomic-preserve-service-mode.md`](../next/atomic-preserve-service-mode.md)
  für die offenen Sub-Slices C + D + F.

Die ADR-0004-strikte Aufteilung („Closure für Geliefertes, `next/`
für skizzierten Scope ohne aktive Commits") vermeidet die
Bruchstelle „A+E geliefert + C/D/F ohne Commits" im selben
Lifecycle-Status.
