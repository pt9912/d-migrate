package dev.dmigrate.driver

import dev.dmigrate.core.diff.migration.DiffOperation
import dev.dmigrate.core.diff.migration.DiffResult

/**
 * E.1 Routine-Migration Slice C.3 + D.4: rendering-time guard
 * that decides whether a `Disabled`-capability routine operation
 * may fall back to `DROP + CREATE` or must surface as
 * `MANUAL_ACTION_REQUIRED`.
 *
 * The contract is three-state:
 *
 * - [SAFE]: the [DependencyGuardEvaluator] could not find an
 *   incoming or outgoing dependency edge between this op and any
 *   other op in the plan. Edges come from
 *   [dev.dmigrate.core.diff.migration.RoutineDependencyAnalyzer]
 *   (manifest plus engine-metadata reads from Slices D.2 / D.3).
 * - [UNSAFE]: at least one edge points to or from this op. A
 *   `DROP + CREATE` fallback would risk leaving a dangling
 *   reference, so the renderer blocks with
 *   `MANUAL_ACTION_REQUIRED` instead.
 * - [UNKNOWN]: the evaluator could not decide — for example
 *   because the renderer was invoked without a plan context
 *   (rare; happens in tightly-scoped helper tests). Treated as
 *   `UNSAFE` for the routing decision so the conservative path
 *   wins.
 *
 * Per Plan §3 step 5, only [SAFE] permits the automatic
 * `DROP + CREATE` fallback; [UNSAFE] / [UNKNOWN] always block with
 * `MANUAL_ACTION_REQUIRED`.
 */
enum class DependencyGuard { SAFE, UNSAFE, UNKNOWN }

/**
 * E.1 Routine-Migration Slice D.4: topology-aware evaluator.
 * Replaces the Slice C.3 stub heuristic ("any co-resident op
 * means UNSAFE") with an edge-driven decision: an operation is
 * [DependencyGuard.SAFE] when no other op in the plan declares a
 * dependency on it (incoming edge) and the op itself declares no
 * dependency on any other op (outgoing edge). Two unrelated ops
 * that share a plan are now correctly recognised as independent.
 *
 * Edges come from the Slice D.1 [RoutineDependencyAnalyzer]
 * second-phase pass, populated either from the schema manifest
 * (file-to-file) or from engine-metadata reads in Slice D.2
 * (PostgreSQL `pg_depend` / `pg_trigger`) and Slice D.3 (MySQL
 * `information_schema.TRIGGERS`).
 *
 * The renderer-facing contract is unchanged: `evaluate` returns
 * the same three-state result, and the MySQL renderer consults it
 * via the same call. Reports still tag the consultation with an
 * INFO diagnostic, but the code is now `DEPENDENCY_GUARD_TOPOLOGY`
 * — the bewertung is no longer a heuristic stub.
 */
object DependencyGuardEvaluator {

    fun evaluate(plan: DiffResult, op: DiffOperation): DependencyGuard {
        val opIdsInPlan = plan.operations.mapTo(HashSet()) { it.id }
        val incoming = plan.operations.any { other ->
            other.id != op.id && op.id in other.dependencies
        }
        val outgoing = op.dependencies.any { depId ->
            depId != op.id && depId in opIdsInPlan
        }
        return if (incoming || outgoing) DependencyGuard.UNSAFE else DependencyGuard.SAFE
    }
}
