package dev.dmigrate.format.data.yaml

import dev.dmigrate.core.data.ColumnDescriptor
import dev.dmigrate.core.data.ImportSchemaMismatchException
import org.snakeyaml.engine.v2.common.ScalarStyle
import org.snakeyaml.engine.v2.events.ScalarEvent

internal data class YamlSchemaBinding(
    val headerNames: List<String>,
) {
    private val fieldIndex = headerNames.withIndex().associate { (index, name) -> name to index }
    val columns: List<ColumnDescriptor> = headerNames.map { ColumnDescriptor(it, nullable = true) }

    fun toFirstRow(map: Map<String, Any?>): Array<Any?> =
        Array(headerNames.size) { index -> map[headerNames[index]] }

    fun normalizeRow(table: String, map: Map<String, Any?>): Array<Any?> {
        val row = arrayOfNulls<Any?>(headerNames.size)
        for ((key, value) in map) {
            val slot = fieldIndex[key]
            if (slot == null) {
                throw ImportSchemaMismatchException(
                    "Table '$table': YAML mapping contains key '$key' " +
                        "which is not present in the first row's schema $headerNames",
                )
            }
            row[slot] = value
        }
        return row
    }
}

internal fun resolveYamlScalar(event: ScalarEvent): Any? {
    val value = event.value
    if (event.scalarStyle != ScalarStyle.PLAIN) return value
    if (value == "null" || value == "~" || value.isEmpty()) return null
    if (value == "true") return true
    if (value == "false") return false
    value.toLongOrNull()?.let { return it }
    if (value == ".inf" || value == "+.inf") return Double.POSITIVE_INFINITY
    if (value == "-.inf") return Double.NEGATIVE_INFINITY
    if (value == ".nan") return Double.NaN
    value.toDoubleOrNull()?.let { return it }
    return value
}
