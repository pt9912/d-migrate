package dev.dmigrate.cli.commands

import dev.dmigrate.core.data.ColumnDescriptor
import dev.dmigrate.core.data.DataChunk
import dev.dmigrate.core.data.DataFilter
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.driver.data.DataReader
import dev.dmigrate.driver.data.DataWriter
import dev.dmigrate.driver.data.ImportOptions

internal data class TransferExecutionContext(
    val reader: DataReader,
    val writer: DataWriter,
    val sourcePool: ConnectionPool,
    val targetPool: ConnectionPool,
    val tables: List<String>,
    val filter: DataFilter?,
    val chunkSize: Int,
    val importOptions: ImportOptions,
)

internal class TransferExecutor {

    fun execute(context: TransferExecutionContext, onTableTransferred: (String) -> Unit) {
        for (table in context.tables) {
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
                )
            )
            onTableTransferred(table)
        }
    }

    private fun transferTable(context: TransferTableContext) {
        context.reader.streamTable(
            context.sourcePool,
            context.table,
            context.filter,
            context.chunkSize,
        ).use { sequence ->
            context.writer.openTable(context.targetPool, context.table, context.options).use { session ->
                val targetNames = session.targetColumns.map { it.name }
                var chunkIndex = 0L
                for (chunk in sequence) {
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
                    session.write(normalized)
                    session.commitChunk()
                }
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
    )
}
