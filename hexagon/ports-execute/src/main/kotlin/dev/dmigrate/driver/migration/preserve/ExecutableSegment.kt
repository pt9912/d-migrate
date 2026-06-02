package dev.dmigrate.driver.migration.preserve

import dev.dmigrate.driver.migration.MigrationDdlStatement

/**
 * Atomic-Preserve Phase C (Sub-Slice C.2): runner-internal projection
 * of a rendered migration plan. Each [MigrationDdlStatement] from
 * `MigrationDdlResult.statements` lives inside **exactly one**
 * segment, segments preserve the planner's topological order, and
 * segment boundaries describe execution semantics rather than
 * rendering output.
 *
 * Plan-Doc:
 * `docs/planning/in-progress/sequence-preserve-atomic-lock-plan.md`
 * §5 Phase C / Sub-Slice C.2. The matching segmentation function
 * lives in [segmentForExecute].
 *
 * Two implementations exist today:
 *
 * - [PlainSqlSegment] — statements that run on today's execute
 *   path: no lock, no Probe + Restore window. The runner consumes
 *   the statement list directly.
 * - [AtomicPreserveSegment] — statements that must run inside the
 *   [AtomicSequencePreserveExecutor.execute] window: one owned
 *   connection holds the lock, runs the protected statements
 *   between Probe and Restore, and commits or rolls back as a unit.
 *
 * The hierarchy is sealed because the runner case-analyses the
 * subtype to pick its execution strategy; adding a third strategy
 * requires touching the runner.
 */
sealed interface ExecutableSegment {
    val statements: List<MigrationDdlStatement>
}

/**
 * Default segment: statements run on the standard execute path
 * (HikariCP pool, per-statement or per-segment connection, no
 * dialect-specific lock). [PlainSqlSegment] carries no
 * [AtomicSequencePreserveBatch] — its statements are independent
 * of the Atomic-Preserve flow.
 */
data class PlainSqlSegment(
    override val statements: List<MigrationDdlStatement>,
) : ExecutableSegment

/**
 * Atomic Probe + protected ops + Restore segment.
 *
 * [batch] carries the `AtomicSequencePreserveRequest`s and the
 * [ProtectedOperationId]s the runner hands to the executor's
 * `executeProtectedOperations` callback.
 *
 * [statements] is the runner's view of the protected statements
 * (planner-rendered SQL plus their per-statement metadata). The
 * runner feeds them to `executor.execute(...)` on the same connection
 * that holds the lock — they MUST NOT be executed on a separate
 * connection.
 */
data class AtomicPreserveSegment(
    val batch: AtomicSequencePreserveBatch,
    override val statements: List<MigrationDdlStatement>,
) : ExecutableSegment
