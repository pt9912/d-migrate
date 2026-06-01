# Plan: Atomic-Preserve Folge-Slices (Findings + Dead-Code + Docs)

> Dokumenttyp: Backlog-Tracker für Folge-Arbeiten aus dem
> Atomic-Sequence-Preserve-Refactor (Phasen A–C).
>
> Status: Entwurf (2026-06-01)
>
> Referenzen:
> - `docs/planning/in-progress/sequence-preserve-atomic-lock-plan.md`
>   (Quelldokument; §3.2 Out-of-Scope, §5 Phasen D + E, §10 Carve-Outs).
> - `/code-review` 2026-06-01, Commit-Range `9d6dcba3..d72e572f`.
> - `hexagon/ports-read/src/main/kotlin/dev/dmigrate/driver/SequenceCapability.kt`
>   (Ziel der KDoc-Sync-Arbeit).

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
| 6 | niedrig | `{Mysql,Sqlite}SchemaMigrateAtomicPreserveIntegrationTest` | **erledigt 2026-06-01** | MySQL: anonymous `tightTimeoutExecutor`-Decorator entfernt (war redundant — `runnerWith` nimmt `lockTimeoutMillis` bereits als Parameter). SQLite: `freshConnExecutor` (xerial-Pool-Workaround) in `freshConnExecutorWithTimeout(budgetMillis)` umgewandelt; Hardcode-Timeout durch Konstruktor-Parameter ersetzt. |

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
  zusätzliche Aufbau lohnt nicht.

---

## 7. Lebenszyklus

Mit dem Abschluss aller Slices (§3.1 + §3.2 + §4.1 + §4.2 + §4.3)
am 2026-06-01 ist dieses Dokument vollständig abgearbeitet. Beim
nächsten Roadmap-Sync (oder zum 0.9.7-Release-Tag) wandert es nach
`../done/`.
