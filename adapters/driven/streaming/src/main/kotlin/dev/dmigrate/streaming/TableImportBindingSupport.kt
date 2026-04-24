package dev.dmigrate.streaming

import dev.dmigrate.core.data.ColumnDescriptor
import dev.dmigrate.core.data.DataChunk
import dev.dmigrate.core.data.ImportSchemaMismatchException
import dev.dmigrate.driver.data.TargetColumn
import dev.dmigrate.format.data.FormatReadOptions
import dev.dmigrate.format.data.JdbcTypeHint
import dev.dmigrate.format.data.ValueDeserializer

internal data class BindingPlan(
    val sourceHeader: List<String>?,
    val boundTargetColumns: List<TargetColumn>,
    val sourceIndexes: List<Int>,
    val positional: Boolean,
)

internal fun buildBindingPlan(
    table: String,
    headerColumns: List<String>?,
    firstChunk: DataChunk?,
    targetColumns: List<TargetColumn>,
): BindingPlan {
    if (headerColumns == null) {
        return BindingPlan(null, targetColumns, targetColumns.indices.toList(), positional = true)
    }

    val duplicates = headerColumns.groupBy { it }.filterValues { it.size > 1 }.keys
    if (duplicates.isNotEmpty()) {
        throw ImportSchemaMismatchException(
            "Header for table '$table' contains duplicate columns: ${duplicates.joinToString()}"
        )
    }

    if (firstChunk != null && firstChunk.columns.isNotEmpty()) {
        val chunkColumns = firstChunk.columns.map { it.name }
        if (chunkColumns != headerColumns) {
            throw ImportSchemaMismatchException(
                "Header/first chunk mismatch for table '$table': expected $headerColumns, got $chunkColumns"
            )
        }
    }

    val targetByName = targetColumns.associateBy { it.name }
    val unknown = headerColumns.filterNot(targetByName::containsKey)
    if (unknown.isNotEmpty()) {
        throw ImportSchemaMismatchException(
            "Target table '$table' has no columns ${unknown.joinToString()}"
        )
    }

    val sourceIndexByName = headerColumns.withIndex().associate { it.value to it.index }
    val boundColumns = targetColumns.filter { sourceIndexByName.containsKey(it.name) }
    val sourceIndexes = boundColumns.map { sourceIndexByName.getValue(it.name) }
    return BindingPlan(headerColumns, boundColumns, sourceIndexes, positional = false)
}

internal fun normalizeChunk(
    chunk: DataChunk,
    table: String,
    bindingPlan: BindingPlan,
    deserializer: ValueDeserializer,
    isCsvSource: Boolean,
): DataChunk {
    if (!bindingPlan.positional && bindingPlan.sourceHeader != null) {
        val currentHeader = chunk.columns.map { it.name }
        if (currentHeader.isNotEmpty() && currentHeader != bindingPlan.sourceHeader) {
            throw ImportSchemaMismatchException(
                "All chunks for table '$table' must use the same header order; " +
                    "expected ${bindingPlan.sourceHeader}, got $currentHeader"
            )
        }
    }

    val normalizedRows = chunk.rows.mapIndexed { rowIndex, row ->
        if (bindingPlan.positional) {
            if (row.size != bindingPlan.boundTargetColumns.size) {
                throw ImportSchemaMismatchException(
                    "Chunk row $rowIndex for table '$table' has ${row.size} values, expected ${bindingPlan.boundTargetColumns.size}"
                )
            }
        } else if (bindingPlan.sourceHeader != null && row.size != bindingPlan.sourceHeader.size) {
            throw ImportSchemaMismatchException(
                "Chunk row $rowIndex for table '$table' has ${row.size} values, expected ${bindingPlan.sourceHeader.size}"
            )
        }

        Array(bindingPlan.boundTargetColumns.size) { index ->
            val targetColumn = bindingPlan.boundTargetColumns[index]
            val sourceValue = row[bindingPlan.sourceIndexes[index]]
            deserializer.deserialize(table, targetColumn.name, sourceValue, isCsvSource)
        }
    }

    return DataChunk(
        chunk.table,
        bindingPlan.boundTargetColumns.map { it.asColumnDescriptor() },
        normalizedRows,
        chunk.chunkIndex,
    )
}

internal fun buildDeserializer(
    targetColumns: List<TargetColumn>,
    readOptions: FormatReadOptions,
): ValueDeserializer {
    val hints = targetColumns.associate { it.name to JdbcTypeHint(it.jdbcType, it.sqlTypeName) }
    return ValueDeserializer(typeHintOf = { hints[it] }, csvNullString = readOptions.csvNullString)
}

internal fun TargetColumn.asColumnDescriptor(): ColumnDescriptor =
    ColumnDescriptor(name, nullable, sqlTypeName)
