package dev.dmigrate.driver.sqlite.profiling

import dev.dmigrate.profiling.port.LogicalTypeResolverPort
import dev.dmigrate.profiling.types.LogicalType

/**
 * SQLite-specific logical type resolution from raw DB type strings.
 *
 * SQLite uses type affinity, so many types map broadly. The resolver
 * normalizes based on SQLite's affinity rules and common type names
 * used in CREATE TABLE statements.
 */
class SqliteLogicalTypeResolver : LogicalTypeResolverPort {

    override fun resolve(dbType: String): LogicalType {
        val normalized = dbType.lowercase().trim()
        if (normalized.isEmpty()) return LogicalType.UNKNOWN
        return resolveInteger(normalized)
            ?: resolveDecimal(normalized)
            ?: resolveTemporalOrOther(normalized)
            ?: LogicalType.STRING
    }

    private fun resolveInteger(n: String): LogicalType? = when {
        n == "integer" || n.startsWith("int") || n == "bigint" ||
            n == "smallint" || n == "tinyint" || n == "mediumint" -> LogicalType.INTEGER
        else -> null
    }

    private fun resolveDecimal(n: String): LogicalType? = when {
        n == "real" || n == "float" || n == "double" ||
            n.startsWith("decimal") || n.startsWith("numeric") -> LogicalType.DECIMAL
        else -> null
    }

    private fun resolveTemporalOrOther(n: String): LogicalType? = when {
        n == "boolean" || n == "tinyint(1)" -> LogicalType.BOOLEAN
        n == "date" -> LogicalType.DATE
        n == "datetime" || n == "timestamp" || n == "time" -> LogicalType.DATETIME
        n == "blob" -> LogicalType.BINARY
        n == "json" -> LogicalType.JSON
        n == "text" || n.startsWith("varchar") || n.startsWith("char") || n == "clob" -> LogicalType.STRING
        else -> null
    }
}
