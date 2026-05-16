# ADR-0001: `MYSQL_ROUTINE_DROP_CREATE_NON_ATOMIC` is WARNING, not BLOCKER

- **Status**: Accepted
- **Date**: 2026-05-16
- **Workstream**: E.1 (Routine-Migration) — Slice C.3 follow-up
- **Related**: `docs/planning/done/ImpPlan-0.9.7-E.1-routine-migration.md`

## Context

Slice C.3 introduced a `DROP + CREATE` fallback for MySQL
routines whose `RoutineCapability` resolves to `Disabled` (no
`CREATE OR REPLACE` for the routine kind on the live server). The
fallback emits two implicit-commit DDL statements back-to-back.

MySQL DDL implicitly commits between statements: if `DROP` succeeds
but `CREATE` fails (e.g. syntax error, privilege drop between
statements, OOM during recompile), the original routine is gone
with no automatic rollback. The dependency guard cannot detect
that operational risk — it only knows whether the routine's
edge graph is `SAFE` for the fallback at all.

The `ReplaceFunction` / `ReplaceProcedure` operations themselves
carry `risk.up = SAFE`, because the operator's intent is a body
swap. The destructive-guard pipeline (`--allow-destructive`) does
not flag these statements as destructive even though the pair
behaves destructively on failure.

The C.3 follow-up review (`5d19903e`) raised the gap and asked
for an explicit operator-facing diagnostic.

## Decision

The renderer emits `MYSQL_ROUTINE_DROP_CREATE_NON_ATOMIC` as a
**WARNING-severity** `DiffDiagnostic` whenever a SAFE-guard
`DROP + CREATE` fallback is rendered. The diagnostic does not
block the plan; it documents the risk so the operator can choose
to:

- run the migration in a controlled window,
- wait for `CREATE OR REPLACE` capability (target server upgrade
  or capability config),
- accept the risk on the SAFE path the dependency guard already
  validated.

## Alternatives considered

- **BLOCKER severity**: would force every MySQL routine `Replace`
  with `Disabled` capability into `MANUAL_ACTION_REQUIRED`. The
  dependency-guard `DROP + CREATE` path becomes unreachable;
  operators with a perfectly safe routine swap are blocked. Plan
  §3 §5 already says SAFE-guard permits the fallback — BLOCKER
  contradicts that.
- **INFO severity**: too quiet. The risk is real for the
  failure-after-DROP case; operators routinely skim INFO-level
  noise.
- **Mark the DROP statement as `destructive`**: the destructive
  guard works at the operation level, not the statement level.
  Marking the whole `ReplaceFunction` op as destructive would
  also trip every regular `CREATE OR REPLACE` path through
  `--allow-destructive`, which is wrong (those are atomic).

## Consequences

- Operator reports for any `Disabled`-capability + `SAFE`-guard
  MySQL routine Replace carry the WARNING; tooling that escalates
  on WARNING severity will see it.
- The atomicity caveat is operator-visible without blocking the
  fallback path the dependency guard explicitly authorised.
- If MySQL ever ships transactional DDL (mariadb has partial
  support), this ADR may be superseded.

## Implementation pointers

- `MysqlDiffRoutineOps.warnDropCreateNonAtomic` emits the
  diagnostic.
- `MysqlDiffRoutineOpsTest` pins the WARNING on each SAFE-guard
  path and the absence on UNSAFE/UNKNOWN block paths.
- `MysqlDiffRenderContext.warning(...)` was added for this
  diagnostic (D.4 reuses the same helper for other WARNINGs).
