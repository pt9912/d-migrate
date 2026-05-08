package dev.dmigrate.cli.commands

import dev.dmigrate.core.cancel.CancellationToken
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
    val tables: List<String>,
    val filter: DataFilter?,
    val chunkSize: Int,
    val importOptions: ImportOptions,
    val cancellationToken: CancellationToken = CancellationToken.none(),
)

open class TransferExecutor {

    open fun execute(context: TransferExecutionContext, onTableTransferred: (String) -> Unit) {
        for (table in context.tables) {
            // Plan §6.4: cancel between tables must not start the next
            // table's reader/writer-open sequence.
            context.cancellationToken.throwIfCancellationRequested()
            transferTable(
                TransferTableContext(
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
            )
            // Plan §4.6: completion-callback is a side effect — cancel here
            // must not emit a fake "table transferred" signal.
            context.cancellationToken.throwIfCancellationRequested()
            onTableTransferred(table)
        }
    }

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
