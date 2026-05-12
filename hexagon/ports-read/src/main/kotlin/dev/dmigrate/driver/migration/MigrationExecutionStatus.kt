package dev.dmigrate.driver.migration

/**
 * Stable post-execute grouping surfaced in `schema migrate` reports
 * (Plan-2 §G.3). Statement indexes are zero-based and use
 * start-inclusive / end-exclusive ranges over the rendered statement
 * list, not byte offsets in SQL artefacts.
 */
data class MigrationExecutionStatementGroup(
    val statementGroupId: String,
    val operationIds: Set<String>,
    val statementStartInclusive: Int,
    val statementEndExclusive: Int,
    val transactionScope: TransactionScope,
    val transactionBoundary: TransactionBoundary,
)

/**
 * Position of a statement group relative to the effective transaction
 * observed by the runner.
 */
enum class TransactionBoundary {
    BEFORE,
    INSIDE,
    AFTER,
    NONE,
}

/**
 * Conservative recoverability assessment after an execution error.
 */
enum class ExecutionRecoverability {
    FULL_ROLLBACK_CONFIRMED,
    ROLLBACK_ATTEMPTED,
    PARTIAL_STATE_POSSIBLE,
    UNKNOWN,
}
