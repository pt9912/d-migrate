---
status: accepted
date: 2026-05-16
decision-makers: pt9912
consulted: code-review agents (E.1 Slice D.1 + D.4 post-commit reviews)
informed: E.1 follow-up reviewers
---

# `UNSAFE_DEPENDENCY_PAIR` stays WARNING, not BLOCKER

## Context and Problem Statement

E.1 Slice D.1 introduced `RoutineDependencyAnalyzer`. Its
`UNSAFE_DEPENDENCY_PAIR` finding flags two co-resident routines
in a plan with no manifest-declared dependency edge in either
direction. The original plan §3 wanted this as
`MANUAL_ACTION_REQUIRED` (BLOCKER), with a documented promotion
path: "WARNING in D.1, BLOCKER once engine verification ships in
D.2 / D.3."

By the end of Slice D.4 all three preconditions held: PG
`pg_depend` projection, MySQL trigger reader-wiring, and a
topology-driven `DependencyGuardEvaluator` that bases its routing
on the actual edge graph. The D.4 follow-up review surfaced
three concerns:

1. The D.4 topology evaluator now decides routing itself — an
   edge-free pair evaluates as SAFE under topology and the
   `Disabled`-capability path falls back to `DROP + CREATE`.
2. Promoting `UNSAFE_DEPENDENCY_PAIR` to BLOCKER would lock
   every file-only multi-routine plan out by default — operators
   would need to enumerate every routine pair as
   `dependencies.functions` even when the routines are obviously
   unrelated. The manifest has no "independent-of" marker.
3. A SAFE-driven `DROP + CREATE` would surface three diagnostics
   simultaneously: `UNSAFE_DEPENDENCY_PAIR` WARNING +
   `DEPENDENCY_GUARD_TOPOLOGY` INFO +
   `MYSQL_ROUTINE_DROP_CREATE_NON_ATOMIC` WARNING. Promoting (1)
   to BLOCKER would make a contradictory pair (BLOCKER:
   "independence unprovable" vs. INFO: "topology proves
   independence").

What is the final severity for `UNSAFE_DEPENDENCY_PAIR` now that
the D.4 topology evaluator is in place?

## Decision Drivers

- The topology evaluator already produces the load-bearing
  routing decision; a parallel diagnostic must not override it.
- File-only multi-routine plans are common in practice; a
  BLOCKER-by-default behaviour breaks them.
- Manifest-drift (an operator forgot to declare an edge that
  exists in reality) is still a real concern; the WARNING
  remains a useful safety net.
- ADR-0001 already establishes a precedent for keeping
  operationally-relevant findings at WARNING severity when the
  alternative blocks a sanctioned path.

## Considered Options

- **WARNING-severity (final state)** — operator-visible safety
  net; topology evaluator owns routing.
- **BLOCKER promotion** as the D.1 plan originally intended.
- **Drop the diagnostic entirely** — topology evaluator already
  handles routing.
- **Re-classify as INFO** — quieter signal.

## Decision Outcome

Chosen option: **WARNING-severity, no further promotion
planned**. It functions as an informational safety net: when
two routines co-exist without manifest edges, the WARNING
nudges the operator to declare the relationship explicitly if
the topology evaluator missed a hidden reference, without
overriding the evaluator's routing decision. The D.1
follow-up's documented promotion pathway is now closed; the
diagnostic message and plan text were updated accordingly in
commit `5d19903e`.

### Consequences

- Good, because file-only multi-routine plans keep working out
  of the box.
- Good, because the diagnostic still catches schema-manifest
  drift — the topology evaluator only sees declared / projected
  edges, so a missed declaration would otherwise hide silently.
- Bad, because operators see the WARNING alongside a TOPOLOGY
  INFO that proves independence; the messages are advisory in
  two different registers and could read as noisy.
- Bad, because operators with strict WARNING-escalation
  pipelines may treat it as a hard signal it is not meant to
  be.
- Neutral, because the WARNING wording was tightened in commit
  `5d19903e` to reflect the topology-evaluator world.

### Confirmation

- `DiffPlanner.kt` emits `UNSAFE_DEPENDENCY_PAIR` at WARNING
  severity with the D.4-aware message; the CHANGELOG entry for
  D.1 was corrected from the pre-follow-up "BLOCKER" claim to
  the WARNING reality (commit `5d19903e`).
- `RoutineDependencyAnalyzerTest` and the renderer tests pin the
  WARNING path; no test asserts BLOCKER behaviour for this code.

## Pros and Cons of the Options

### WARNING (chosen)

- Good, because file-only multi-routine plans run without
  manual edge declaration.
- Good, because the topology evaluator's routing decision
  remains load-bearing.
- Neutral, because the operator now sees a WARNING that the
  TOPOLOGY INFO simultaneously says is fine — the operator must
  read both.

### BLOCKER promotion

- Good, because no operator can run a routine plan with
  unprovable independence by accident.
- Bad, because it locks out file-only multi-routine migrations
  by default — there is no "independent-of" marker in the
  manifest.
- Bad, because it contradicts the D.4 topology routing on the
  SAFE path.

### Drop entirely

- Good, because diagnostics stay clean.
- Bad, because manifest drift loses its single safety net —
  the topology evaluator only sees what was declared or
  projected.

### INFO

- Good, because no friction at all.
- Bad, because manifest drift is operationally relevant; INFO
  noise routinely gets skipped and the safety net would be
  effectively invisible.

## More Information

- Implementation: `RoutineDependencyAnalyzer.detectUnsafeRoutinePairs`
  generates the candidates; `DiffPlanner.kt` emits the
  diagnostic.
- Plan reference:
  `docs/planning/done/ImpPlan-0.9.7-E.1-routine-migration.md`
  §D.1 follow-up + §D.4 follow-up.
- Related ADR: ADR-0001 documents the parallel choice for
  `MYSQL_ROUTINE_DROP_CREATE_NON_ATOMIC`.
- Potential supersession: a future slice could introduce an
  explicit "independent-of" marker on the routine manifest
  (e.g. `dependencies.independentOf: ["other_routine"]`); this
  ADR would then be revisited.
