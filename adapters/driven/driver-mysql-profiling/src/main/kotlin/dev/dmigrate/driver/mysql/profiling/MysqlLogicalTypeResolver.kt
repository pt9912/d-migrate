package dev.dmigrate.driver.mysql.profiling

import dev.dmigrate.profiling.port.LogicalTypeResolverPort
import dev.dmigrate.profiling.types.LogicalType

/**
 * MySQL-specific logical type resolution from raw DB type strings.
 */
class MysqlLogicalTypeResolver : LogicalTypeResolverPort {

    override fun resolve(dbType: String): LogicalType {
        val normalized = dbType.lowercase().trim()
        if (normalized.startsWith("tinyint(1)")) return LogicalType.BOOLEAN
        return resolveNumeric(normalized)
            ?: resolveTemporalTypes(normalized)
            ?: resolveBinaryAndSpecial(normalized)
            ?: resolveStringTypes(normalized)
            ?: LogicalType.UNKNOWN
    }

    private fun resolveNumeric(n: String): LogicalType? = when {
        n.startsWith("int") || n.startsWith("tinyint") || n.startsWith("smallint") ||
            n.startsWith("mediumint") || n.startsWith("bigint") -> LogicalType.INTEGER
        n.startsWith("decimal") || n.startsWith("numeric") ||
            n == "float" || n == "double" || n.startsWith("double") -> LogicalType.DECIMAL
        else -> null
    }

    private fun resolveTemporalTypes(n: String): LogicalType? = when {
        n == "date" -> LogicalType.DATE
        n.startsWith("datetime") || n.startsWith("timestamp") ||
            n.startsWith("time") || n == "year" -> LogicalType.DATETIME
        else -> null
    }

    private fun resolveBinaryAndSpecial(n: String): LogicalType? = when {
        n == "blob" || n == "tinyblob" || n == "mediumblob" || n == "longblob" ||
            n.startsWith("binary") || n.startsWith("varbinary") -> LogicalType.BINARY
        n == "json" -> LogicalType.JSON
        n in GEOMETRY_TYPES -> LogicalType.GEOMETRY
        else -> null
    }

    private fun resolveStringTypes(n: String): LogicalType? = when {
        n.startsWith("varchar") || n.startsWith("char") ||
            n == "text" || n == "tinytext" || n == "mediumtext" || n == "longtext" ||
            n.startsWith("enum") || n.startsWith("set") -> LogicalType.STRING
        else -> null
    }

    private companion object {
        val GEOMETRY_TYPES = setOf(
            "point", "linestring", "polygon", "geometry",
            "multipoint", "multilinestring", "multipolygon", "geometrycollection",
        )
    }
}
