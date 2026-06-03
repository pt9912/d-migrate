# Plan: Atomic-Preserve Folge-Slices (Findings + Dead-Code + Docs)

> Dokumenttyp: Backlog-Tracker für Folge-Arbeiten aus dem
> Atomic-Sequence-Preserve-Refactor (Phasen A–C).
>
> Status: Closure (2026-06-02) — alle Slices (§3.1 + §3.2 + §4.1 +
> §4.2 + §4.3 alle sechs Findings) im Code verifiziert; Audit
> in §8 Closure. Quelldokument ist seit 2026-06-02 in
> `done/sequence-preserve-atomic-lock-plan.md`.
>
> Referenzen:
> - `docs/planning/done/sequence-preserve-atomic-lock-plan.md`
>   (Quelldokument; §3.2 Out-of-Scope, §5 Phasen D + E, §10 Carve-Outs;
>   geschlossen 2026-06-02).
> - `/code-review` 2026-06-01, Commit-Range `9d6dcba3..d72e572f`.
> - `hexagon/ports-read/src/main/kotlin/dev/dmigrate/driver/SequenceCapability.kt`
>   (Ziel der KDoc-Sync-Arbeit).
> - `docs/planning/next/atomic-preserve-service-mode.md`
>   (Service-Mode-Folge-Thema aus §6 Cross-JVM-Carve-Out).

---

## 1. Ziel

Die Phasen A + B + C des Atomic-Preserve-Refactors sind gelandet
(`174c3891`, `1c09147d`, `8c2e0a07`, `11d04e57`,
`b4f548b0`+`39bcaa29`+`d72e572f`). Dabei sind drei Klassen von
Folge-Arbeiten entstanden, die nicht im laufenden Phase-C-Slice
adressiert wurden:

- **Code-Review-Findings 2026-06-01** (eines davon ein
  Production-Crash-Pfad).
- **Dead-Code des alten Probe-Pfads** (Port-Interface +
  3 Adapter-Implementierungen + Tests).
- **Phase D + Phase E** aus dem Plan-Doc, insbesondere die
  Docs-Synchronisation auf `SequenceCapability` inklusive eines neuen
  KDoc-Verweises auf den §3.2 Out-of-Scope-Block (cross-DB-Lock,
  App-Retry, globaler Schema-Lock).

Dieses Dokument bündelt diese Folge-Slices, ordnet sie nach
Release-Wirkung (vor / nach 0.9.7) und benennt pro Slice eine
DoD-Kurzform. Detail-DoD bleibt im Quelldokument.

---

## 2. Ausgangslage

Phase A + B + C sind grün auf `develop`; CI hat den Atomic-Preserve-
Pfad als neuen Default für alle drei Dialekte (PostgreSQL, MySQL,
SQLite). Carve-Outs sind im Quelldokument §3.2 (permanent, per
Produktentscheidung) sowie §10 (temporär, aus Code-Review)
festgehalten.

Releaseplanung 0.9.7 wartet auf Klärung, welche Slices noch vor dem
Release gemacht werden.

---

## 3. Scope-Skizze — Pre-Release-Slices

### 3.1 Finding #1 — Contiguity-Crash absichern *(high)* — **erledigt 2026-06-01**

`SchemaMigrateExecutionStage.kt` rief `segmentForExecute(...)`
außerhalb des try-Blocks. Eine `IllegalStateException` aus
`segmentForExecute` (z. B. nicht-zusammenhängender Atomic-Bereich)
propagierte unhandled bis zum CLI-Top-Level.

**DoD F1** *(erledigt)*

- [x] `segmentForExecute`-Aufruf in den try-Block der ExecutionStage
      verschoben.
- [x] `IllegalStateException` wird in einen strukturierten
      `ExecutionTrace` gemappt (`executionStarted = false`,
      `transactionRolledBack = true`, `sideEffectsPossible = false`,
      `executionError = "Atomic-preserve plan shape invalid: ..."`).
- [x] Unit-Test in
      `hexagon/application/src/test/.../SchemaMigrateExecutionStagePlanShapeTest.kt`
      mit synthetischer Plan-Liste (atomic-A, plain, atomic-B), die
      die Contiguity-Verletzung provoziert.
- [x] Bestehende Tests brechen nicht (`make ci` grün).

### 3.2 Phase E — Docs + KDoc-Sync + neuer SequenceCapability-Verweis — **erledigt 2026-06-01**

Phase E aus dem Plan-Doc war reine Dokumentationsarbeit.

**DoD E** *(erledigt)*

- [x] CHANGELOG-Eintrag „atomic-preserve" für 0.9.7 (Feature-
      Eintrag mit allen Sub-Phasen A+B+C+D+E + Carve-Out-Sektion).
- [x] User-Guide-Eintrag: dialekt-spezifische Beschreibung des
      atomaren Pfads inkl. PG-App-`nextval`-Race als verbleibendes
      Restrisiko (Plan-Doc §6 Risiko Nr. 8).
- [x] KDoc-Update auf `SequencePreserveStage` mit Restrictions-Block
      (AllInPlan = Phase D; §3.2 Out-of-Scope).
- [x] KDoc-Update auf
      `SequenceCapability.transactionalProtectedSequenceOperations`
      ersetzt den Phase-B-Wortlaut durch den korrekten C.4-Verweis
      (inkl. Commit-Hash `11d04e57`).
- [x] KDoc-Block auf `SequenceCapability` mit explizitem Verweis auf
      §3.2 Out-of-Scope des Plan-Docs (cross-DB-Lock, App-Retry,
      globaler Schema-Lock).
- [x] KDoc auf `SequenceCurrentValueProbe`: dead-code-Status-Header
      seit Phase C eingefügt; verweist auf den Dead-Code-Cleanup-
      Slice §4.2 unten.

---

## 4. Scope-Skizze — Post-Release-Slices (0.9.8 oder Patch)

### 4.1 Phase D — Cross-Plan-Deadlock + AllInPlan-Flag — **erledigt 2026-06-01**

Vollständige DoD im Quelldokument §5 Phase D.

- [x] Cross-Plan-Deadlock-Test pro Dialekt
      (`PostgresAtomicPreserveCrossPlanDeadlockTest`,
      `MysqlAtomicPreserveCrossPlanDeadlockTest`,
      `SqliteAtomicPreserveCrossPlanDeadlockTest`) — PG / MySQL mit
      positivem + negativem Smoke; SQLite nur positiv (DB-weite
      RESERVED-Lock macht Deadlock-Diamant konstruktiv unmöglich).
- [x] `SequenceCapabilityDefaults.supportsAtomicPreserveAllInPlan =
      true` für PG / MySQL / SQLite.
- [x] Stage emittiert `SEQUENCE_PRESERVE_ATOMIC_UNSUPPORTED` bei
      Multi-Seq-Plan + Capability-Flag `false`; Unit-Test mit
      synthetischer Capability-Override in
      `SequencePreserveStageTest`.

### 4.2 Dead-Code-Cleanup Probe-Interface — **erledigt 2026-06-01**

Aus C.1: Der `SequenceCurrentValueProbe`-Port war strukturell tot —
die Atomic-Executoren rufen die drei dialekt-spezifischen
Singleton-Adapter (`{Postgres,Mysql,Sqlite}SequenceCurrentValueProbe`)
direkt auf, ohne über die Interface-Dispatch zu gehen. Bei der
Analyse für diesen Slice hat sich gezeigt, dass die ursprüngliche
DoD-Formulierung („Adapter-Klassen + Tests löschen") **falsch** war —
die Adapter sind aktive Executor-Dependencies und ihre Tests
pinnen weiterhin lebendes Verhalten.

**DoD F2** *(erledigt — korrigierter Scope)*

- [x] `SequenceCurrentValueProbe`-Interface gelöscht
      (hexagon/ports-read).
- [x] `: SequenceCurrentValueProbe` und `override`-Modifier von den
      drei Adapter-Singletons entfernt — sie bleiben als
      `object`-Singletons live und werden weiterhin von den
      Atomic-Executoren verwendet.
- [x] Adapter-Klassen + ihre Unit-/IT-Tests **bleiben** (waren nie
      dead-code; die ursprüngliche DoD-Formulierung war auf einer
      Fehlinterpretation des C.1-Refactors aufgebaut).
- [x] `SequenceCurrentValueProbeResult` bleibt; KDoc aktualisiert
      mit der korrekten Historie (port deleted, adapters live).
- [x] KDoc-Verweise auf das gelöschte Interface in
      `SequenceCapability`, `SequenceCapabilityDefaults`,
      `SequenceDefinition`, `SequenceObjectRef`, `DiffOperation`
      und `SequenceObjectRefTest` auf
      `{Postgres,Mysql,Sqlite}SequenceCurrentValueProbe`
      aktualisiert.
- [x] `make ci` grün.

### 4.3 Findings #2–6 *(mittel/niedrig)* — **erledigt 2026-06-01**

Alle fünf Code-Review-Findings wurden zusammen mit dem
Dead-Code-Cleanup adressiert.

| # | Severity | Datei / Stelle | Status | Kurzbeschreibung |
|---|---|---|---|---|
| 2 | mittel | `{Postgres,Mysql,Sqlite}DiffSequenceOps.renderAlterSequenceCurrentValue` | **erledigt 2026-06-01** | Sentinel `0L` (ATOMIC_PRESERVE_SENTINEL_CURRENT_VALUE) rendert jetzt einen `-- atomic-preserve audit:`-Kommentar im UP-Pfad statt `setval('seq', 0, true)` / `UPDATE next_value = 0`. Unit-Tests pro Dialekt. |
| 3 | mittel | `SegmentAwareMigrationExecutor.mapAtomicResultToTrace` | **erledigt 2026-06-01** | `statementsAttempted` zählte interne Follow-ups mit. Fix: `protectedStatements`-Parameter; `Applied`-Branch meldet nur deren Anzahl. |
| 4 | niedrig | `SchemaMigratePreparation.validateRequest` | **erledigt 2026-06-01** | Stummer Fallback bei unbekanntem `--mysql/sqlite-named-sequences`-Wert. Fix: Exit-2 mit erwartetem Allowed-List-Hint analog zu `schema generate`; zusätzlich Dialekt-Kontext-Check nach `resolveDialect` (Flag nur valide für entsprechenden Target-Dialekt). Zwei neue Unit-Tests in `SchemaMigrateRunnerCliExitCodeTest`. |
| 5 | mittel | `Mysql/SqliteSequencePreserveRaceTest` | **erledigt 2026-06-01** | Assertion `finalValue >= initial + writerAdvances` verstärkt um „während des 500 ms-Lock-Fensters macht der Writer ZERO Advances" (Counter-Snapshot inside protected callback). Pinnt den Row-Lock- (MySQL) bzw. RESERVED-Lock-Vertrag (SQLite) direkt. |
| 6 | niedrig | `{Mysql,Sqlite}SchemaMigrateAtomicPreserveIntegrationTest` | **erledigt 2026-06-01** | MySQL: `tightTimeoutExecutor`-Decorator bleibt, weil weder `SchemaMigrateRunner` noch `SchemaMigrateExecutionStage` einen CLI-Override für `lockTimeoutMillis` exponieren (Stage hält `DEFAULT_LOCK_TIMEOUT_MILLIS = 5_000L` als private Konstanten-Default; der Lambda-Vertrag `SegmentAwareExecutorFn` trägt den Parameter durch, aber kein Aufrufer überschreibt ihn). Der vormals im Decorator-Body hardcoded `1_000L`-Timeout ist nach `tightTimeoutExecutor(budgetMillis: Long)` Factory-Parameter gehoben, sodass der Test-Call-Site die Single Source of Truth ist. SQLite: analog — `freshConnExecutor` (xerial-Pool-Workaround) in `freshConnExecutorWithTimeout(budgetMillis)` umgewandelt. |

**DoD F3** *(erledigt)*

- [x] Findings 2, 3, 5 gefixt (mittel-Severity).
- [x] Findings 4, 6 gefixt.
- [x] Keine Regression in den Atomic-Preserve-ITs (`make ci` grün).

---

## 5. Vorbedingungen

- Plan-Doc `sequence-preserve-atomic-lock-plan.md` ist Source of
  Truth — alle DoD-Details werden dort geführt; dieses Dokument
  verweist nur.
- Code-Review-Findings sind im Plan-Doc §10 als
  „Bekannte Carve-Outs" mit Commit-Range fixiert.
- Pre-Release-Slices §3.1 + §3.2 müssen vor dem 0.9.7-Tag landen,
  wenn das Release das atomare Verhalten als Headline bewirbt.

---

## 6. Offene Fragen *(geschlossen 2026-06-01)*

- ~~Soll Finding #1 als hotfix-Patch direkt nach 0.9.7 möglich~~
  ~~bleiben, falls er nicht mehr pre-Release reinpasst?~~ — Finding
  #1 vor 0.9.7-Release gefixt.
- ~~Wird der Dead-Code-Cleanup §4.2 vor oder nach dem Release~~
  ~~gemacht?~~ — Cleanup ist Teil dieses Slices; Adapter-Klassen
  bleiben live (DoD wurde während der Umsetzung korrigiert, siehe
  §4.2).
- ~~Phase D §4.1: reicht der heutige~~
  ~~`:test:integration-concurrency`-Aufbau für den Cross-Process-~~
  ~~Stresstest~~ — die drei
  `{Postgres,Mysql,Sqlite}AtomicPreserveCrossPlanDeadlockTest`s
  fahren zwei parallele Atomic-Executor-Calls in zwei Threads
  innerhalb derselben JVM; das deckt den heute relevanten
  Single-Process-Stresstest ab. Ein echter Cross-JVM-Stresstest
  (ProcessBuilder mit zwei `schema migrate`-Subprozessen) bleibt
  out-of-scope; das advisory-Lock-Verhalten ist dasselbe, der
  zusätzliche Aufbau lohnt nicht. **Service-Mode-Folge-Thema**:
  sobald `schema_migrate` als MCP-Tool, REST-Endpoint oder
  gRPC-RPC exponiert wird, kippt das Same-JVM-Multi-Thread-
  Szenario aus „Stresstest" in „produktiver Hot-Path" — fünf
  JVM-seitige Verträge (Connection-Pool, Cancellation,
  Rate-Limit, Lock-Timeout-Tuning, Idempotency-Replay) hängen
  als Vorabklärung in
  [`../next/atomic-preserve-service-mode.md`](../next/atomic-preserve-service-mode.md).

---

## 7. Lebenszyklus

Mit dem Abschluss aller Slices (§3.1 + §3.2 + §4.1 + §4.2 + §4.3)
am 2026-06-01 ist dieses Dokument vollständig abgearbeitet. Move
nach `../done/` erfolgt am 2026-06-02 unabhängig vom 0.9.7-
Release-Tag, analog zum Move des Quelldokuments
`sequence-preserve-atomic-lock-plan.md`.

---

## 8. Closure (2026-06-02)

### 8.1 Per-Slice Code-Verifikation

| Slice | Status laut Plan-Doc | Code-Beleg (verifiziert 2026-06-02) |
| ----- | -------------------- | ----------------------------------- |
| §3.1 Finding #1 Contiguity-Crash | erledigt 2026-06-01 | `SchemaMigrateExecutionStage.kt`: `segmentForExecute`-Aufruf in try-Block; `IllegalStateException` → strukturierter `ExecutionTrace` (`executionStarted=false`, `transactionRolledBack=true`, `sideEffectsPossible=false`); Test `SchemaMigrateExecutionStagePlanShapeTest` |
| §3.2 Phase E Docs + KDoc-Sync | erledigt 2026-06-01 | CHANGELOG-Eintrag `CHANGELOG.md` Z. 12+; User-Guide §„preserveCurrentValue (atomar unter Lock seit 0.9.7)" `docs/user/guide.md` Z. 653; KDoc-Sync auf `SequencePreserveStage`, `SequenceCapability`, `SequenceCurrentValueProbe` (alle drei Klassen-KDocs aktualisiert) |
| §4.1 Phase D Cross-Plan-Deadlock + AllInPlan-Flag | erledigt 2026-06-01 | `{Postgres,Mysql,Sqlite}AtomicPreserveCrossPlanDeadlockTest.kt` (PG/MySQL mit positivem + negativem Smoke; SQLite nur positiv mit dokumentiertem Carve-Out); `SequenceCapabilityDefaults.kt` `supportsAtomicPreserveAllInPlan = true` für alle drei Dialekte; Stage-AllInPlan-Gate-Block in `SequencePreserveStageTest` |
| §4.2 Dead-Code-Cleanup Probe-Interface | erledigt 2026-06-01 (korrigierter Scope) | `SequenceCurrentValueProbe.kt` enthält nur noch sealed `SequenceCurrentValueProbeResult` (Result-Klasse); Interface gelöscht; KDoc Z. 6-17 dokumentiert die Cleanup-Begründung. Adapter-Klassen (`{Postgres,Mysql,Sqlite}SequenceCurrentValueProbe`) bleiben live, weil sie von den Atomic-Executoren direkt aufgerufen werden |
| §4.3 Findings #2-6 | erledigt 2026-06-01 | siehe §8.2 |

### 8.2 Findings-Detail mit Code-Belegen

| # | Severity | Endzustand | Code-Beleg |
| - | -------- | ---------- | ---------- |
| 2 | mittel | UP-Sentinel rendert Audit-Kommentar statt `setval(seq, 0)` / `UPDATE next_value = 0` | `SqliteDiffSequenceOps.kt:156-159` (`if (op.currentValue == ATOMIC_PRESERVE_SENTINEL_CURRENT_VALUE) → "-- atomic-preserve audit: UPDATE dmg_sequences for "+ref"`); analog `Postgres-` und `MysqlDiffSequenceOps.kt`; Unit-Tests pro Dialekt |
| 3 | mittel | `statementsAttempted` zählt nur `protectedStatements` | `SegmentAwareMigrationExecutor.kt:135` filtert `segment.statements`; Z. 160 nimmt `protectedStatements: List<MigrationDdlStatement>` als expliziten Parameter; `Applied`-Branch meldet `protectedStatements.size` |
| 4 | niedrig | Unbekannter `--mysql/sqlite-named-sequences`-Wert → Exit 2 mit Allowed-List-Hint, plus Dialekt-Kontext-Check | `SchemaMigrateCommand.kt` + `SchemaMigrateWiring.kt`; Tests in `SchemaMigrateRunnerCliExitCodeTest` |
| 5 | mittel | Race-Test-Assertion verstärkt: Writer macht ZERO Advances während Lock-Fenster | `MysqlSequencePreserveRaceTest.kt:117-137` (writerAdvances counter, 50 writer threads, Counter-Snapshot inside protected callback); analog `Sqlite-` und `PostgresSequencePreserveRaceTest.kt` |
| 6 | niedrig | Decorator parametric refactort (hardcoded `1_000L` → Factory-Parameter); nicht entfernt | MySQL: `MysqlSchemaMigrateAtomicPreserveIntegrationTest.kt:118` (`fun tightTimeoutExecutor(budgetMillis: Long)`); Call-Site Z. 284 (`tightTimeoutExecutor(budgetMillis = 1_000L)`); Decorator nötig weil `SchemaMigrateExecutionStage.lockTimeoutMillis` ein privater Default ohne CLI-Override ist. SQLite: `SqliteSchemaMigrateAtomicPreserveIntegrationTest.kt:142-157` (`fun freshConnExecutorWithTimeout(budgetMillis: Long)`); Call-Site Z. 312-313 |

### 8.3 Carve-outs / Folge-Themen

- **Cross-JVM-Stresstest (CLI-Pfad)**: bewusst out-of-scope —
  DB-side Lock-Verhalten identisch zum Same-JVM-Two-Threads-Setup.
  §6 dokumentiert.
- **Service-Mode-Verträge** (MCP/REST/gRPC-Exposition von
  `schema_migrate`): aus §6 abgespalten in eigene Vorabklärung
  `docs/planning/next/atomic-preserve-service-mode.md`. Fünf
  JVM-seitige Verträge: Connection-Pool, Cancellation,
  Rate-Limit, Lock-Timeout-Tuning, Idempotency-Replay.
- **Probe-Adapter-Implementierungen löschen** (`{Postgres,Mysql,
  Sqlite}SequenceCurrentValueProbe`-Klassen): §4.2-Scope-
  Korrektur 2026-06-01 hat sie als „bleiben live" beschlossen,
  weil sie von den Atomic-Executoren direkt aufgerufen werden.
  Kein Folge-Slice.

### 8.4 Release-Bezug

Die Inhalte sind Teil von **Milestone 0.9.7 §E.3** (siehe
`done/sequence-preserve-atomic-lock-plan.md` §8 Closure +
`done/diffresult-migration-plan-2.md` §14.1 E.3-Zeile). Move
nach `done/` erfolgt unabhängig vom 0.9.7-Release-Tag, analog
zum bereits am 2026-06-02 gemoveten Quelldokument.
