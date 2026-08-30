package dev.dmigrate.driver.migration.preserve

import dev.dmigrate.driver.migration.MigrationDdlStatement
import dev.dmigrate.driver.migration.TransactionScope

/**
 * Atomic-Preserve Phase C (Sub-Slice C.2): derive the runner-internal
 * `List<ExecutableSegment>` view of a rendered plan from the single
 * source-of-truth `MigrationDdlResult.statements`.
 *
 * The function is the **single producer** of segments — Pre-C.1 the
 * stage does not yet supply an [AtomicSequencePreserveBatch] and the
 * runner (Sub-Slice C.3) calls this function with `atomicBatch=null`,
 * producing a degenerate single [PlainSqlSegment] that is
 * behaviorally identical to today's path.
 *
 * Rules:
 *
 * - Statements rendered with `transactionScope = NO_TRANSACTION` form
 *   their own [NoTransactionSegment]s. The split happens **first**, in
 *   planner order, so what came before is committed before they run —
 *   that is the whole point of the scope: the database refuses these
 *   statements inside an open transaction.
 *
 * - When [atomicBatch] is `null` or carries neither protected nor
 *   internal follow-up IDs, the result is a single
 *   [PlainSqlSegment] containing every input statement in its
 *   original order. This is the master-grün Pre-C.1 path.
 * - When [atomicBatch] is non-null, a statement is **atomic** iff
 *   **any** of its `operationIds` matches either a
 *   `batch.protectedOperationIds` value or a
 *   `batch.internalFollowUpIds` entry.
 * - At most one [AtomicPreserveSegment] is emitted; its statements
 *   are the contiguous run of atomic statements in the input.
 * - Plain statements before the atomic run form one
 *   [PlainSqlSegment]; plain statements after form another. Empty
 *   plain segments are dropped (no empty buckets in the output).
 *
 * Invariants enforced (`require`):
 *
 * - Every input statement appears in **exactly one** segment.
 * - Concatenating `segments.flatMap { it.statements }` reproduces
 *   the input list in order (no reordering, no duplication, no
 *   drops).
 * - Atomic statements are contiguous in the input. Plain statements
 *   interleaved between two atomic statements indicate a planner
 *   bug — the segmenter throws [IllegalStateException] rather than
 *   silently re-ordering, because the atomic transaction must
 *   cover all protected ops as a single block.
 */
fun segmentForExecute(
    statements: List<MigrationDdlStatement>,
    atomicBatch: AtomicSequencePreserveBatch?,
): List<ExecutableSegment> {
    if (statements.isEmpty()) return emptyList()
    val runs = splitByTransactionScope(statements)
    val atomicRuns = runs.count { !it.outsideTransaction && carriesAtomic(it.statements, atomicBatch) }
    check(atomicRuns <= 1) {
        "AtomicPreserveSegment requires all protected statements in one contiguous block, but " +
            "statements outside any transaction (NO_TRANSACTION) separate them into $atomicRuns blocks."
    }
    return runs.flatMap { run ->
        if (run.outsideTransaction) {
            listOf(NoTransactionSegment(run.statements))
        } else {
            transactionalSegments(run.statements, atomicBatch)
        }
    }
}

private data class ScopeRun(
    val outsideTransaction: Boolean,
    val statements: List<MigrationDdlStatement>,
)

/** Aufeinanderfolgende Anweisungen desselben Ausfuehrungsmodells, in Planreihenfolge. */
private fun splitByTransactionScope(statements: List<MigrationDdlStatement>): List<ScopeRun> {
    val runs = mutableListOf<ScopeRun>()
    var current = mutableListOf<MigrationDdlStatement>()
    var currentOutside = statements.first().transactionScope == TransactionScope.NO_TRANSACTION
    for (statement in statements) {
        val outside = statement.transactionScope == TransactionScope.NO_TRANSACTION
        if (outside != currentOutside && current.isNotEmpty()) {
            runs += ScopeRun(currentOutside, current.toList())
            current = mutableListOf()
        }
        currentOutside = outside
        current += statement
    }
    if (current.isNotEmpty()) runs += ScopeRun(currentOutside, current.toList())
    return runs
}

private fun carriesAtomic(
    statements: List<MigrationDdlStatement>,
    atomicBatch: AtomicSequencePreserveBatch?,
): Boolean {
    val ids = atomicOperationIds(atomicBatch)
    return ids.isNotEmpty() && statements.any { stmt -> stmt.operationIds.any { it in ids } }
}

private fun atomicOperationIds(atomicBatch: AtomicSequencePreserveBatch?): Set<String> =
    if (atomicBatch == null) {
        emptySet()
    } else {
        buildSet {
            atomicBatch.protectedOperationIds.forEach { add(it.value) }
            addAll(atomicBatch.internalFollowUpIds)
        }
    }

private fun transactionalSegments(
    statements: List<MigrationDdlStatement>,
    atomicBatch: AtomicSequencePreserveBatch?,
): List<ExecutableSegment> {
    val atomicOpIds = atomicOperationIds(atomicBatch)
    if (atomicOpIds.isEmpty()) {
        return listOf(PlainSqlSegment(statements.toList()))
    }
    val atomicMask: BooleanArray = BooleanArray(statements.size) { i ->
        statements[i].operationIds.any { it in atomicOpIds }
    }
    val firstAtomic = atomicMask.indexOfFirst { it }
    if (firstAtomic < 0) {
        return listOf(PlainSqlSegment(statements.toList()))
    }
    val lastAtomic = atomicMask.indexOfLast { it }
    val interleavedPlainIndex = (firstAtomic..lastAtomic).firstOrNull { !atomicMask[it] }
    check(interleavedPlainIndex == null) {
        "AtomicPreserveSegment requires contiguous atomic statements in " +
            "planner order. Statement at index $interleavedPlainIndex is plain " +
            "but lies between atomic statements at indices $firstAtomic and " +
            "$lastAtomic — planner must group all protected and internal " +
            "follow-up statements into one contiguous block."
    }
    val plainBefore = statements.subList(0, firstAtomic)
    val atomic = statements.subList(firstAtomic, lastAtomic + 1)
    val plainAfter = statements.subList(lastAtomic + 1, statements.size)
    return buildList {
        if (plainBefore.isNotEmpty()) add(PlainSqlSegment(plainBefore.toList()))
        add(AtomicPreserveSegment(batch = atomicBatch!!, statements = atomic.toList()))
        if (plainAfter.isNotEmpty()) add(PlainSqlSegment(plainAfter.toList()))
    }
}
