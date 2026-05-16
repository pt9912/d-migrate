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
 * E.1 Routine-Migration Slice C.3: evaluator with an explicitly
 * conservative stub heuristic. Real topology-based evaluation
 * arrives in Slice D and will replace the body of [evaluate]
 * without changing the public contract.
 *
 * Slice C.3 stub: a routine operation is [DependencyGuard.SAFE]
 * iff the plan contains no other operation. The moment any other
 * op (routine, view, trigger, table, sequence, ...) co-exists in
 * the same run, the guard flips to [DependencyGuard.UNSAFE] —
 * the stub cannot tell whether the other op references the
 * routine in question.
 *
 * Renderers must also surface the diagnostic
 * `DEPENDENCY_GUARD_HEURISTIC` whenever they consult this
 * evaluator so reports document that the guard is a stub bewertung,
 * not a topology proof.
 */
object DependencyGuardEvaluator {

    fun evaluate(plan: DiffResult, op: DiffOperation): DependencyGuard {
        val others = plan.operations.filter { it.id != op.id }
        return if (others.isEmpty()) DependencyGuard.SAFE else DependencyGuard.UNSAFE
    }
}
