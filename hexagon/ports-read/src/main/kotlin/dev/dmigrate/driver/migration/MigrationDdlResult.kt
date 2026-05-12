package dev.dmigrate.driver.migration

import dev.dmigrate.core.diff.migration.DiffDiagnostic
import dev.dmigrate.driver.ExtensionDependencyReport

/**
 * Output of [DiffDdlGenerator.generateUp] / [DiffDdlGenerator.generateDown].
 *
 * The result is a *plan*, not an execution trace — it describes what
 * the renderer chose to emit, what it skipped, and why the caller
 * should or should not proceed. Execution-time fields
 * ([executionStarted] etc.) are populated only after the runner has
 * begun executing against a live target; they are never set by the
 * renderer alone (Plan §6.1).
 *
 * Rendering invariants:
 *
 * - `operationsRendered` and `operationsSkipped` partition the planner's
 *   `operations` set: an operation appears in exactly one.
 * - `manualActions` and `destructiveOperations` are subsets of
 *   `operationsRendered` — they describe attributes of *rendered*
 *   work, not skipped work.
 * - `blockers` is non-empty iff the plan must not be executed. A
 *   non-empty blocker list does not prevent rendering — the plan is
 *   still useful for diagnostic display.
 * - `primaryBlockedReason` is a CLI shortcut and never replaces the
 *   full [blockers] list.
 *
 * Statement ordering follows the planner's topological sort. The
 * renderer does not re-order; it may aggregate (multiple ops → one
 * statement) or split (one op → multiple statements) but preserves
 * the relative order of the operations that produced each statement.
 */
data class MigrationDdlResult(
    val statements: List<MigrationDdlStatement>,
    val operationsRendered: Set<String>,
    val operationsSkipped: Set<String> = emptySet(),
    val manualActions: Set<String> = emptySet(),
    val destructiveOperations: Set<String> = emptySet(),
    val nonReversibleOperations: Set<String> = emptySet(),
    val requiresConfirmation: Boolean = false,
    val blockers: List<MigrationBlocker> = emptyList(),
    val primaryBlockedReason: MigrationBlockedReason? = null,
    val diagnostics: List<DiffDiagnostic> = emptyList(),
    /** Set when a runner has begun executing against the target DB. */
    val executionStarted: Boolean = false,
    /** Set when a runner has finished (successfully or not). */
    val executionCompleted: Boolean = false,
    val statementsAttempted: Int = 0,
    val lastStatementOperationIds: Set<String> = emptySet(),
    val transactionRolledBack: Boolean = false,
    val sideEffectsPossible: Boolean = false,
    val executionError: String? = null,
    val extensionDependencies: List<ExtensionDependencyReport> = emptyList(),
) {
    /** Convenience: the plan must not execute. */
    val isBlocked: Boolean get() = blockers.isNotEmpty()

    /** Convenience: the plan emitted no statements. */
    val isEmpty: Boolean get() = statements.isEmpty()

    init {
        require(operationsRendered.intersect(operationsSkipped).isEmpty()) {
            "operationsRendered and operationsSkipped must be disjoint"
        }
        require((manualActions - operationsRendered).isEmpty()) {
            "manualActions must be a subset of operationsRendered"
        }
        require((destructiveOperations - operationsRendered).isEmpty()) {
            "destructiveOperations must be a subset of operationsRendered"
        }
        require((nonReversibleOperations - operationsRendered).isEmpty()) {
            "nonReversibleOperations must be a subset of operationsRendered"
        }
        require(statements.flatMap { it.operationIds }.toSet().subtract(operationsRendered).isEmpty()) {
            "every statement's operationIds must be a subset of operationsRendered"
        }
        if (primaryBlockedReason != null) {
            require(blockers.isNotEmpty()) {
                "primaryBlockedReason set without any blockers"
            }
            require(blockers.any { it.reason == primaryBlockedReason }) {
                "primaryBlockedReason must appear in blockers list"
            }
        }
    }
}
