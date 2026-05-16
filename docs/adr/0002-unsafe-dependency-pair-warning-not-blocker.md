# ADR-0002: `UNSAFE_DEPENDENCY_PAIR` stays WARNING, not BLOCKER

- **Status**: Accepted
- **Date**: 2026-05-16
- **Workstream**: E.1 (Routine-Migration) — Slice D.1 → D.4 follow-ups
- **Related**: `docs/planning/done/ImpPlan-0.9.7-E.1-routine-migration.md`

## Context

Slice D.1 introduced `RoutineDependencyAnalyzer` and a finding it
emits as `UNSAFE_DEPENDENCY_PAIR`: two co-resident routines in a
plan with no manifest-declared dependency edge in either
direction. The plan's original intent (§3 line 829–830) was to
treat this as `MANUAL_ACTION_REQUIRED` (BLOCKER), with a
documented promotion path: "WARNING in D.1, BLOCKER once Engine-
Verification ships in D.2 / D.3" (the D.1 follow-up
commit `ec32fc3a` recorded this).

After Slice D.2 (PG `pg_depend` projection), D.3 (MySQL
`information_schema.TRIGGERS` reader-wiring), and D.4 (topology-
driven `DependencyGuardEvaluator` body), the question came back
up: should we promote the WARNING to BLOCKER now that engine
verification is in place?

The D.4 follow-up review (`5d19903e`) raised three observations:

1. The D.4 topology evaluator now routes the decision itself: an
   edge-free pair evaluates as SAFE under topology and the
   `Disabled`-capability path falls back to `DROP + CREATE`.
2. Promoting `UNSAFE_DEPENDENCY_PAIR` to BLOCKER would lock every
   file-only multi-routine plan out by default — operators would
   need to enumerate every routine-pair as `dependencies.functions`
   even when the routines are obviously unrelated. There is no
   "independent of" marker in the manifest.
3. The operator already sees three diagnostics on a SAFE-driven
   DROP + CREATE: `UNSAFE_DEPENDENCY_PAIR` WARNING +
   `DEPENDENCY_GUARD_TOPOLOGY` INFO + `MYSQL_ROUTINE_DROP_CREATE_NON_ATOMIC`
   WARNING. Promoting (1) to BLOCKER on top of the others would
   produce a noisy contradiction (BLOCKER says "cannot prove
   independence", INFO says "topology proves independence").

## Decision

`UNSAFE_DEPENDENCY_PAIR` stays at **WARNING severity** as the
final E.1 state — no further promotion planned. It functions as
an informational safety net: when two routines co-exist without
manifest edges, the WARNING nudges the operator to declare the
relationship explicitly if a hidden reference exists, without
overriding the topology evaluator's routing decision.

## Alternatives considered

- **BLOCKER promotion** (the original D.1 plan): too strict for
  file-only plans, contradicts D.4's topology routing, locks out
  every realistic multi-routine migration. Rejected.
- **Drop the diagnostic entirely**: D.4 topology handles the
  routing, so the WARNING is technically redundant. Rejected
  because the diagnostic still helps operators spot
  schema-manifest drift (the topology evaluator can only see
  edges that were declared or projected — a missed declaration
  hides from both).
- **Re-message as INFO**: a SAFE-evaluated pair without manifest
  edges is not an error condition. But the operator-action
  ("declare the relationship if the topology evaluator missed
  it") is non-trivial enough to deserve WARNING-level visibility
  rather than INFO noise.

## Consequences

- Operator reports for any file-only multi-routine plan carry a
  WARNING per missing-edge pair. Topology evaluator's routing is
  the load-bearing decision; the WARNING is advisory.
- The D.1 follow-up's documented "promotion to BLOCKER once D.2 /
  D.3 ship" pathway is now closed; the D.4 follow-up
  (`5d19903e`) updated the diagnostic message and plan text to
  describe the final state.
- If a future slice adds an explicit "independent-of" marker to
  the manifest (or a per-routine "I do not depend on X" assertion),
  this ADR may be superseded.

## Implementation pointers

- `DiffPlanner.kt` emits `UNSAFE_DEPENDENCY_PAIR` at WARNING
  severity with the D.4-aware message.
- `RoutineDependencyAnalyzer.detectUnsafeRoutinePairs` produces
  the candidates (Routine ↔ Routine without manifest edge in
  either direction).
- The CHANGELOG's Slice-D.1 entry was corrected from the
  pre-D.1-follow-up "BLOCKER" claim to the WARNING reality
  (commit `5d19903e`).
