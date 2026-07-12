package dev.dmigrate.cli.commands

import dev.dmigrate.core.cancel.CancellationToken
import dev.dmigrate.core.concurrency.ParallelWorkExecutor
import dev.dmigrate.core.data.ColumnDescriptor
import dev.dmigrate.core.data.DataChunk
import dev.dmigrate.core.data.DataFilter
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.driver.data.DataReader
import dev.dmigrate.driver.data.DataWriter
import dev.dmigrate.driver.data.ImportOptions

data class TransferExecutionContext(
    val reader: DataReader,
    val writer: DataWriter,
    val sourcePool: ConnectionPool,
    val targetPool: ConnectionPool,
    /** Flat table order (sequential path + `--verify`/`--atomic` scope). */
    val tables: List<String>,
    val filter: DataFilter?,
    val chunkSize: Int,
    val importOptions: ImportOptions,
    val cancellationToken: CancellationToken = CancellationToken.none(),
    /** LN-007: FK-safe concurrency layers (only consulted when [parallelism] > 1). */
    val layers: List<List<String>> = emptyList(),
    /** LN-008: parent table → schema-qualified child partitions to transfer per-child. */
    val partitionChildren: Map<String, List<String>> = emptyMap(),
    /** LN-007/LN-008: max concurrent tables/partitions; 1 = the sequential path. */
    val parallelism: Int = 1,
)

open class TransferExecutor(
    private val parallelExecutor: ParallelWorkExecutor = ParallelWorkExecutor("transfer-worker"),
) {

    open fun execute(context: TransferExecutionContext, onTableTransferred: (String) -> Unit) {
        if (context.parallelism > 1) {
            executeParallel(context, onTableTransferred)
        } else {
            executeSequential(context, onTableTransferred)
        }
    }

    private fun executeSequential(context: TransferExecutionContext, onTableTransferred: (String) -> Unit) {
        for (table in context.tables) {
            // LF-010 / LF-013 / LN-009 / LN-011: cancel between tables must not start the next
            // table's reader/writer-open sequence.
            context.cancellationToken.throwIfCancellationRequested()
            transferTable(tableContext(context, table))
            // LF-008 / LF-009 / LF-013: completion-callback is a side effect — cancel here
            // must not emit a fake "table transferred" signal.
            context.cancellationToken.throwIfCancellationRequested()
            onTableTransferred(table)
        }
    }

    /**
     * LN-007/LN-008: run each FK layer through the bounded [parallelExecutor];
     * a partitioned parent (per [TransferExecutionContext.partitionChildren])
     * fans out into one work unit per child partition. Layers are a barrier —
     * layer i+1 starts only after layer i fully completes. Progress callbacks
     * fire from this (main) thread after each layer, per parent table.
     */
    private fun executeParallel(context: TransferExecutionContext, onTableTransferred: (String) -> Unit) {
        for (layer in context.layers) {
            context.cancellationToken.throwIfCancellationRequested()
            val units = layer.flatMap { parent -> unitsForTable(context, parent) }
            parallelExecutor.run(units, context.parallelism, context.cancellationToken)
            context.cancellationToken.throwIfCancellationRequested()
            for (parent in layer) onTableTransferred(parent)
        }
    }

    private fun unitsForTable(context: TransferExecutionContext, parent: String): List<() -> Unit> {
        val children = context.partitionChildren[parent]
        return if (children.isNullOrEmpty()) {
            listOf { transferTable(tableContext(context, parent)) }
        } else {
            children.map { child -> { transferTable(tableContext(context, child)) } }
        }
    }

    private fun tableContext(context: TransferExecutionContext, table: String) = TransferTableContext(
        reader = context.reader,
        writer = context.writer,
        sourcePool = context.sourcePool,
        targetPool = context.targetPool,
        table = table,
        filter = context.filter,
        chunkSize = context.chunkSize,
        options = context.importOptions,
        cancellationToken = context.cancellationToken,
    )

    private fun transferTable(context: TransferTableContext) {
        context.cancellationToken.throwIfCancellationRequested()
        context.reader.streamTable(
            context.sourcePool,
            context.table,
            context.filter,
            context.chunkSize,
        ).use { sequence ->
            context.cancellationToken.throwIfCancellationRequested()
            context.writer.openTable(context.targetPool, context.table, context.options).use { session ->
                val targetNames = session.targetColumns.map { it.name }
                var chunkIndex = 0L
                for (chunk in sequence) {
                    context.cancellationToken.throwIfCancellationRequested()
                    val sourceNames = chunk.columns.map { it.name }
                    val sourceIndexes = targetNames.map { target -> sourceNames.indexOf(target) }
                    val reordered = chunk.rows.map { row ->
                        Array(targetNames.size) { index ->
                            val sourceIndex = sourceIndexes[index]
                            if (sourceIndex >= 0) row[sourceIndex] else null
                        }
                    }
                    val targetDescriptors = session.targetColumns.map {
                        ColumnDescriptor(it.name, it.nullable, it.sqlTypeName)
                    }
                    val normalized = DataChunk(context.table, targetDescriptors, reordered, chunkIndex++)
                    context.cancellationToken.throwIfCancellationRequested()
                    session.write(normalized)
                    context.cancellationToken.throwIfCancellationRequested()
                    session.commitChunk()
                }
                context.cancellationToken.throwIfCancellationRequested()
                session.finishTable()
            }
        }
    }

    private data class TransferTableContext(
        val reader: DataReader,
        val writer: DataWriter,
        val sourcePool: ConnectionPool,
        val targetPool: ConnectionPool,
        val table: String,
        val filter: DataFilter?,
        val chunkSize: Int,
        val options: ImportOptions,
        val cancellationToken: CancellationToken,
    )
}
