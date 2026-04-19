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
        return when {
            normalized == "integer" || normalized.startsWith("int") ||
                normalized == "bigint" || normalized == "smallint" ||
                normalized == "tinyint" || normalized == "mediumint" -> LogicalType.INTEGER

            normalized == "real" || normalized == "float" || normalized == "double" ||
                normalized.startsWith("decimal") || normalized.startsWith("numeric") -> LogicalType.DECIMAL

            normalized == "boolean" || normalized == "tinyint(1)" -> LogicalType.BOOLEAN

            normalized == "date" -> LogicalType.DATE

            normalized == "datetime" || normalized == "timestamp" ||
                normalized == "time" -> LogicalType.DATETIME

            normalized == "blob" -> LogicalType.BINARY

            normalized == "json" -> LogicalType.JSON

            normalized == "text" || normalized.startsWith("varchar") ||
                normalized.startsWith("char") || normalized == "clob" -> LogicalType.STRING

            normalized.isEmpty() -> LogicalType.UNKNOWN

            else -> LogicalType.STRING // SQLite TEXT affinity default
        }
    }
}
