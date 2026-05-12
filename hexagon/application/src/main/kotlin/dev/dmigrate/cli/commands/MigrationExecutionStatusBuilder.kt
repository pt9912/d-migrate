package dev.dmigrate.cli.commands

import dev.dmigrate.driver.migration.ExecutionRecoverability
import dev.dmigrate.driver.migration.MigrationDdlStatement
import dev.dmigrate.driver.migration.MigrationExecutionStatementGroup
import dev.dmigrate.driver.migration.TransactionBehavior
import dev.dmigrate.driver.migration.TransactionBoundary
import dev.dmigrate.driver.migration.TransactionScope

/**
 * Builds the stable Plan-2 §G.3 execution view from rendered
 * statements and executor observations. This deliberately uses
 * structured statement fields only; SQL text remains opaque.
 */
internal object MigrationExecutionStatusBuilder {

    fun statementGroups(statements: List<MigrationDdlStatement>): List<MigrationExecutionStatementGroup> {
        if (statements.isEmpty()) return emptyList()
        val raw = mutableListOf<RawGroup>()
        var streamTxSeen = false
        statements.forEachIndexed { index, statement ->
            val boundary = transactionBoundary(statement, streamTxSeen)
            if (opensStreamTransaction(statement)) {
                streamTxSeen = true
            }
            val opIds = statement.operationIds.toSortedSet()
            val baseId = baseGroupId(opIds)
            val previous = raw.lastOrNull()
            if (previous != null && previous.canExtend(baseId, opIds, statement.transactionScope, boundary, index)) {
                raw[raw.lastIndex] = previous.copy(endExclusive = index + 1)
            } else {
                raw += RawGroup(
                    baseId = baseId,
                    operationIds = opIds,
                    startInclusive = index,
                    endExclusive = index + 1,
                    transactionScope = statement.transactionScope,
                    transactionBoundary = boundary,
                )
            }
        }
        val counts = raw.groupingBy { it.baseId }.eachCount()
        val seen = mutableMapOf<String, Int>()
        return raw.map { group ->
            val next = (seen[group.baseId] ?: 0) + 1
            seen[group.baseId] = next
            val id = if ((counts[group.baseId] ?: 0) > 1) "${group.baseId}#$next" else group.baseId
            MigrationExecutionStatementGroup(
                statementGroupId = id,
                operationIds = group.operationIds,
                statementStartInclusive = group.startInclusive,
                statementEndExclusive = group.endExclusive,
                transactionScope = group.transactionScope,
                transactionBoundary = group.transactionBoundary,
            )
        }
    }

    fun recoverability(trace: ExecutionTrace): ExecutionRecoverability? {
        if (trace.executionError == null) return null
        return when {
            trace.transactionRolledBack && !trace.sideEffectsPossible ->
                ExecutionRecoverability.FULL_ROLLBACK_CONFIRMED
            trace.transactionRolledBack ->
                ExecutionRecoverability.ROLLBACK_ATTEMPTED
            trace.sideEffectsPossible ->
                ExecutionRecoverability.PARTIAL_STATE_POSSIBLE
            else ->
                ExecutionRecoverability.UNKNOWN
        }
    }

    private fun transactionBoundary(
        statement: MigrationDdlStatement,
        streamTxSeen: Boolean,
    ): TransactionBoundary = when (statement.transactionScope) {
        TransactionScope.RUNNER_OWNED -> TransactionBoundary.INSIDE
        TransactionScope.NO_TRANSACTION -> TransactionBoundary.NONE
        TransactionScope.STREAM_OWNED -> when (statement.hints.transactionBehavior) {
            TransactionBehavior.NOT_TRANSACTIONAL -> if (streamTxSeen) {
                TransactionBoundary.AFTER
            } else {
                TransactionBoundary.BEFORE
            }
            else -> TransactionBoundary.INSIDE
        }
    }

    private fun opensStreamTransaction(statement: MigrationDdlStatement): Boolean =
        statement.transactionScope == TransactionScope.STREAM_OWNED &&
            statement.hints.transactionBehavior == TransactionBehavior.FULLY_TRANSACTIONAL

    private fun baseGroupId(operationIds: Set<String>): String = when (operationIds.size) {
        0 -> "statement"
        1 -> operationIds.single()
        else -> operationIds.joinToString("+")
    }

    private fun RawGroup.canExtend(
        baseId: String,
        operationIds: Set<String>,
        transactionScope: TransactionScope,
        transactionBoundary: TransactionBoundary,
        index: Int,
    ): Boolean =
        this.baseId == baseId &&
        this.operationIds == operationIds &&
        this.transactionScope == transactionScope &&
        this.transactionBoundary == transactionBoundary &&
        this.endExclusive == index

    private data class RawGroup(
        val baseId: String,
        val operationIds: Set<String>,
        val startInclusive: Int,
        val endExclusive: Int,
        val transactionScope: TransactionScope,
        val transactionBoundary: TransactionBoundary,
    )
}
