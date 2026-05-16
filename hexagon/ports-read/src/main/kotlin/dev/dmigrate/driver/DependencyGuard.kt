package dev.dmigrate.driver

import dev.dmigrate.core.diff.migration.DiffOperation
import dev.dmigrate.core.diff.migration.DiffResult

/**
 * E.1 Routine-Migration Slice C.3: rendering-time guard that
 * decides whether a `Disabled`-capability routine operation may
 * fall back to `DROP + CREATE` or must surface as
 * `MANUAL_ACTION_REQUIRED`.
 *
 * The contract is intentionally three-state:
 *
 * - [SAFE]: no concurrent dependent ops in the plan; a routine that
 *   is dropped and re-created cannot leave a dangling reference
 *   behind because nothing else in this run depends on it.
 * - [UNSAFE]: the plan contains at least one other operation. The
 *   stub heuristic in [DependencyGuardEvaluator] cannot resolve the
 *   real dependency graph, so it treats *any* co-resident operation
 *   as a potential dependency.
 * - [UNKNOWN]: reserved for future evaluators that cannot decide
 *   one way or the other (e.g. partial metadata projection). The
 *   Slice C.3 stub never returns this — Slice D will widen the
 *   evaluator and may produce it.
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
