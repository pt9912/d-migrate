package dev.dmigrate.driver.migration

/**
 * Execution trace returned by a live migration executor after it has
 * attempted to run rendered DDL against a target database.
 */
data class MigrationExecutionTrace(
    val executionStarted: Boolean,
    val executionCompleted: Boolean,
    val statementsAttempted: Int = 0,
    val lastStatementOperationIds: Set<String> = emptySet(),
    val transactionRolledBack: Boolean = false,
    val sideEffectsPossible: Boolean = false,
    val executionError: String? = null,
    val statementGroups: List<MigrationExecutionStatementGroup> = emptyList(),
    val recoverability: ExecutionRecoverability? = null,
)
