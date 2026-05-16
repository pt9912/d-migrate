---
status: accepted
date: 2026-05-16
decision-makers: pt9912
consulted: code-review agents (E.1 Slice C.3 post-commit review)
informed: E.1 follow-up reviewers
---

# `MYSQL_ROUTINE_DROP_CREATE_NON_ATOMIC` is WARNING, not BLOCKER

## Context and Problem Statement

E.1 Slice C.3 introduced a `DROP + CREATE` fallback for MySQL
routines whose `RoutineCapability` resolves to `Disabled` (no
`CREATE OR REPLACE` for the routine kind on the target server).
MySQL DDL implicitly commits between statements: if `DROP`
succeeds but `CREATE` fails (syntax error, privilege change,
recompile OOM), the routine is gone with no automatic rollback.
The dependency guard only knows whether the edge graph is `SAFE`
for the fallback at all — it cannot model the operational risk
between the two statements. `ReplaceFunction` /
`ReplaceProcedure` carry `risk.up = SAFE` because the operator's
intent is a body swap, so the destructive guard
(`--allow-destructive`) does not flag the pair either. What
severity should the renderer use to surface the implicit-commit
risk?

## Decision Drivers

- Operator must see the risk before running the migration.
- The SAFE-guard `DROP + CREATE` path is the only practical
  alternative to `MANUAL_ACTION_REQUIRED` when the target server
  cannot offer `CREATE OR REPLACE` for the routine kind.
- The existing destructive-guard pipeline works at the
  *operation* level, not the *statement* level.

## Considered Options

- **WARNING-severity diagnostic** alongside the rendered
  statements.
- **BLOCKER-severity diagnostic** (would suppress the fallback).
- **INFO-severity diagnostic** (purely advisory).
- **Mark the `Replace*` op as destructive at risk level**.

## Decision Outcome

Chosen option: **WARNING-severity diagnostic**, because it
surfaces the implicit-commit risk in the operator-facing report
without blocking the fallback path the dependency guard
explicitly authorised. Plan §3 step 5 says SAFE permits the
fallback; BLOCKER would contradict that, and INFO is too quiet
given that operators routinely skim INFO-level entries.

### Consequences

- Good, because operator reports for any
  `Disabled`-capability + `SAFE`-guard MySQL routine `Replace`
  carry the WARNING; tooling that escalates on WARNING severity
  will catch it.
- Good, because the SAFE-guard fallback the dependency guard
  authorised stays reachable — no extra manual gate.
- Bad, because operators who automate `WARNING` triage may
  expect blocking semantics; the WARNING is advisory, not a
  hard stop.
- Neutral, because if MySQL ever ships transactional DDL
  (MariaDB has partial support), this ADR may be superseded.

### Confirmation

`MysqlDiffRoutineOpsTest` pins the WARNING on every
SAFE-guard path and asserts its absence on UNSAFE / UNKNOWN
block paths — the test suite directly confirms the contract.

## Pros and Cons of the Options

### WARNING

- Good, because it is operator-visible without blocking the
  fallback.
- Good, because `DiffDiagnostic.Severity.WARNING` already has a
  pre-existing meaning in the report and tooling pipeline.
- Neutral, because automated WARNING-escalation downstream still
  has to decide whether to treat it as blocking.

### BLOCKER

- Good, because operator cannot accidentally run the risky
  fallback.
- Bad, because it forces every routine `Replace` on a
  `Disabled` capability into `MANUAL_ACTION_REQUIRED` — the
  fallback path becomes unreachable, defeating Slice C.3.
- Bad, because it contradicts Plan §3 step 5's explicit grant
  of `DROP + CREATE` under SAFE guard.

### INFO

- Good, because it adds no friction to existing tooling.
- Bad, because operators routinely skim INFO noise; the
  implicit-commit risk would frequently be missed.

### Op-level destructive risk

- Good, because it would integrate with the existing
  destructive-guard pipeline.
- Bad, because the `ReplaceFunction` / `ReplaceProcedure` op is
  semantically a body swap; the renderer's choice to emit
  `DROP + CREATE` is a fallback, not the operator's intent.
  Marking the op destructive would also trip every regular
  `CREATE OR REPLACE` path, which is wrong.

## More Information

- Implementation: `MysqlDiffRoutineOps.warnDropCreateNonAtomic`
  emits the diagnostic;
  `MysqlDiffRenderContext.warning(...)` was added for this
  use case and reused by Slice D.4.
- Plan reference:
  `docs/planning/done/ImpPlan-0.9.7-E.1-routine-migration.md`
  §3 step 5.
- Related ADR: ADR-0002 documents the parallel choice on
  `UNSAFE_DEPENDENCY_PAIR`.
