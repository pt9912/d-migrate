# test:integration-concurrency

Concurrent-Writer race reproducers for the `SequencePreserveStage`
probe→restore window across PostgreSQL, MySQL and SQLite.

Plan-Doc:
[quality-coverage-expansion-plan.md](../../docs/planning/done/quality-coverage-expansion-plan.md)
§5.3 (Sub-Slice C).

Atomic-lock follow-up plan:
[sequence-preserve-atomic-lock-plan.md](../../docs/planning/in-progress/sequence-preserve-atomic-lock-plan.md)
(In Progress 2026-05-31 — Phase A + B abgeschlossen, Phasen C–E
offen: Stage-/Runner-Refactor, Multi-Sequence-Deadlock-Test und
Capability-Flag-Flip stehen aus; bis dahin bleibt der
`knownRace = true`-Vertrag in diesem Modul aktiv).

## Why this module exists

`SequencePreserveStage` reads a sequence's current value (probe),
later writes that value back (restore). The two operations are not
in a single transaction; any writer that advances the sequence
between probe and restore has its advance overwritten by the stale
restore.

This module pins that behaviour as the **legacy race baseline**:
every spec sets `knownRace = true` on its [SequencePreserveRace]
observation and asserts the stale-restore shape. The reproducer is
the canonical evidence the race exists today; it is **not** a
correctness vow. The atomic-lock slice will:

1. Implement an atomic probe + restore so the writer cannot land
   inside the probe→restore window. Phase B of the atomic-lock plan
   (2026-05-31) has landed the three per-dialect executors
   (`PostgresAtomicSequencePreserveExecutor` with
   `pg_advisory_xact_lock`, `MysqlAtomicSequencePreserveExecutor`
   with `SELECT FOR UPDATE` on `dmg_sequences`,
   `SqliteAtomicSequencePreserveExecutor` with `BEGIN IMMEDIATE`).
   They are **not yet wired into `SequencePreserveStage`** —
   Phase C of the atomic-lock plan ties the runner-side refactor
   that actually invokes them.
2. Flip the assertion in each spec from
   `finalValue shouldBe observedProbeValue` (stale) to
   `finalValue shouldBe(GreaterThanOrEqual) postWriterMaximum`
   (writer's progress preserved). For PG the atomic-lock plan §6
   Risk 8 documents that the residual app-side `nextval` race is
   unaffected by the new executor — the PG flip will need to keep
   the legacy reproducer as `knownRace = true` until app-side
   advisory-lock cooperation is documented.
3. Either remove the legacy assertion, mark the spec as
   `knownRace = false` for the new gate, or keep the legacy
   reproducer in a quarantine list as historical documentation.

The plan-doc forbids both assertions being active simultaneously,
so the flip is mechanical: change the assertion line, change the
`knownRace` field. Until then the legacy gate is the active gate.

## Pattern

The shared [SequencePreserveRace] harness uses two `CountDownLatch`
instances to position the writer thread exactly inside the probe→
restore window:

```
probe-thread                          writer-thread
============                          =============
read currentValue                     wait for probeObserved
signal probeObserved          ──►     run nextval × N
wait for writerFinished       ◄──     signal writerFinished
restore(currentValue)
read finalValue
```

A free-running writer (no latches) is forbidden: it could advance
the sequence before the probe (the probe sees the advanced value
and the restore is harmless) or after the restore (the post-restore
writer hides whether the restore actually overwrote anything). The
race is real only inside the bounded window; the test must reproduce
that bound precisely.

## Per-dialect adapters

| Dialect    | Storage                              | Probe                                  | Advance                                  | Restore                                          |
| ---------- | ------------------------------------ | -------------------------------------- | ---------------------------------------- | ------------------------------------------------ |
| PostgreSQL | native `CREATE SEQUENCE`             | `SELECT last_value FROM <seq>`         | `SELECT nextval('<seq>')`                | `SELECT setval('<seq>', value, false)`           |
| MySQL      | `dmg_sequences` helper table         | `SELECT next_value FROM dmg_sequences` | `UPDATE … SET next_value = next_value+1` | `UPDATE dmg_sequences SET next_value = value`    |
| SQLite     | file-backed `dmg_sequences` table    | `SELECT next_value FROM dmg_sequences` | `UPDATE … SET next_value = next_value+1` | `UPDATE dmg_sequences SET next_value = value`    |

Each adapter opens a fresh autocommit connection per call so
visibility across threads matches what `SequencePreserveStage` sees
in production (no shared transaction snapshot).

## Running

The module sits under `:test:integration-*` and inherits the
`-PintegrationTests` gate from the root build. It additionally
requires `-PconcurrencyTests` so the normal integration sweep does
not pick up the race tests:

```
make integration INTEGRATION_TASKS="-PintegrationTests -PconcurrencyTests :test:integration-concurrency:test"
```

Without `-PconcurrencyTests`, the test task is `onlyIf`-skipped at
the module level. Without `-PintegrationTests`, it is skipped at
the root build level. Both gates must be active.

## Flipping after the atomic-lock slice

When [sequence-preserve-atomic-lock-plan.md](../../docs/planning/in-progress/sequence-preserve-atomic-lock-plan.md)
lands, each of the three specs needs exactly two changes:

1. Replace
   ```kotlin
   observation.finalValue shouldBe observation.observedProbeValue
   observation.knownRace shouldBe true
   ```
   with
   ```kotlin
   observation.finalValue shouldBeGreaterThanOrEqual observation.postWriterMaximum
   observation.knownRace shouldBe false
   ```
2. Flip the `knownRace` constant in
   [SequencePreserveRace.runAgainst] (or in the per-spec
   adapter call) so the observation reflects the new contract.

Both changes belong in the same commit as the atomic-lock
implementation so the gate transition is visible in one place.
