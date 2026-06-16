# test:integration-concurrency

Per-Dialekt-Race-Reproducer für die `SequencePreserveStage`-Probe →
Restore-Sequenz unter Concurrent Writers, alle gegen die produktiven
`{Postgres,Mysql,Sqlite}AtomicSequencePreserveExecutor`-Adapter.

Plan-Docs:
- Atomic-Preserve-Refactor:
  [`sequence-preserve-atomic-lock-plan.md`](../../docs/planning/in-progress/sequence-preserve-atomic-lock-plan.md)
  (Phasen A + B + C + D + E komplett 2026-06-01; in-progress bis
  zum 0.9.7-Release-Tag).
- Ursprüngliche Coverage-Initiative:
  [`quality-coverage-expansion-plan.md`](../../docs/planning/done-archive/quality-coverage-expansion-plan.md)
  §5.3 (Sub-Slice C).

## Status pro Dialekt *(seit 2026-06-01)*

| Dialekt | Lock-Strategie | Race-Outcome | Test |
|---|---|---|---|
| PostgreSQL | `pg_advisory_xact_lock(hashtext(...))` | **knownRace = true** — Advisory-Lock blockiert App-`nextval` **nicht** (Plan §6 Risiko 8). Der Test pinnt die residuelle Race als Vertrag: Writer macht Forward-Progress während des Lock-Fensters, der Restore überschreibt diese Advances mit dem geprobten Wert. | `PostgresSequencePreserveRaceTest` |
| MySQL | `SELECT … FOR UPDATE` auf `dmg_sequences`-Helper-Row | **race closed** — die Row-Lock blockiert App-`UPDATE`s für die gesamte Dauer des Lock-Fensters. Test pinnt zwei Invarianten: `finalValue >= initial + writerAdvances` UND während des 500 ms-Lock-Fensters macht der Writer ZERO Advances. | `MysqlSequencePreserveRaceTest` |
| SQLite | `BEGIN IMMEDIATE` (DB-weite RESERVED-Lock) | **race closed** — gleiches Vertragsmodell wie MySQL; die RESERVED-Lock ist DB-weit, blockiert also auch Concurrent-Writer auf der `dmg_sequences`-Tabelle. | `SqliteSequencePreserveRaceTest` |

Per Lesart α (No-Carveouts) sind die alten `SequencePreserveRace`-
Harness und die Legacy-Reproducer-Variante mit stalem
`finalValue shouldBe observedProbeValue` **entfernt**. Wer den
historischen Stand sehen will, findet ihn in `git log` vor Commit
`35bfa328`.

## Test-Skelett

Beide race-closed-Tests (MySQL + SQLite) folgen demselben Muster:

```
writer-thread                     atomic-executor (on conn A)
=============                     ===========================
wait for writerStart latch
                                  open transaction + take lock
                                  probe(seqRef)            ──┐
                                  call protected callback:    │
   countDown writerStart  ──►       capture writerCount       │
   start N UPDATE iterations        Thread.sleep(500ms)       │ Lock window:
   each UPDATE blocks               capture writerCount       │ writer makes 0 advances
   on the row/db lock             render restore SQL          │
                                  run restore                 │
                                  commit                    ──┘
                                                              ▼
                                                          lock released
   UPDATEs resume serially
   advance next_value 1, 2, …, N
   finishes
```

Assertions:
1. `finalValue >= initial + writerAdvances` — die N Writer-
   Advances sind im Endergebnis sichtbar.
2. `advancesAtLockEnd - advancesAtLockStart == 0` — während der
   500 ms-Sleep-Phase im protected callback konnte der Writer
   keine UPDATE-Statement abschließen (Row-/RESERVED-Lock-Beweis;
   Finding #5).

Der PostgreSQL-Test ist die **inverse** Form derselben Topologie:
er verwendet den `PostgresAtomicSequencePreserveExecutor` und
asserts, dass der Writer Forward-Progress macht (Advisory-Lock ist
app-blind), während der Atomic-Restore die Advances überschreibt —
genau das Plan-§6-Risiko-8-Verhalten in Vertragsform.

## Running

Das Modul sitzt unter `:test:integration-*` und erbt das
`-PintegrationTests`-Gate aus dem Root-Build. Zusätzlich braucht
es `-PconcurrencyTests`, damit der normale Integration-Sweep die
Race-Tests nicht mit einsammelt:

```
make integration INTEGRATION_TASKS="-PintegrationTests -PconcurrencyTests :test:integration-concurrency:test"
```

Ohne `-PconcurrencyTests` wird die Task auf Modul-Ebene
`onlyIf`-geskipped. Ohne `-PintegrationTests` wird sie bereits
auf Root-Build-Ebene geskipped. Beide Gates müssen aktiv sein.

## Cross-Plan-Deadlock-Tests

Zusätzlich zu den per-Dialekt-Race-Tests gibt es seit Phase D drei
Cross-Plan-Deadlock-Tests, die zwei parallele `schema migrate`-
Aufrufe gegen überlappende Sequenzen fahren und beweisen, dass
die deterministische Lock-Reihenfolge keine Diamant-Deadlocks
zulässt. Diese leben in den dialekt-spezifischen IT-Modulen
(`test/integration-{postgresql,mysql,sqlite}`), nicht hier, weil
sie produktive Adapter-Wiring testen statt der reinen
Race-Reproduktion.
