package dev.dmigrate.driver.postgresql.profiling

import dev.dmigrate.profiling.port.LogicalTypeResolverPort
import dev.dmigrate.profiling.types.LogicalType

/**
 * PostgreSQL-specific logical type resolution from raw DB type strings.
 */
class PostgresLogicalTypeResolver : LogicalTypeResolverPort {

    override fun resolve(dbType: String): LogicalType {
        val normalized = dbType.lowercase().trim()
        return resolveNumeric(normalized)
            ?: resolveTemporalAndBool(normalized)
            ?: resolveStructuredTypes(normalized)
            ?: resolveStringTypes(normalized)
            ?: LogicalType.UNKNOWN
    }

    private fun resolveNumeric(n: String): LogicalType? = when {
        n.startsWith("int") || n == "serial" || n == "bigserial" ||
            n == "smallint" || n == "bigint" || n == "smallserial" -> LogicalType.INTEGER
        n.startsWith("numeric") || n.startsWith("decimal") ||
            n == "real" || n.startsWith("double") ||
            n == "float4" || n == "float8" || n == "money" -> LogicalType.DECIMAL
        else -> null
    }

    private fun resolveTemporalAndBool(n: String): LogicalType? = when {
        n == "boolean" || n == "bool" -> LogicalType.BOOLEAN
        n == "date" -> LogicalType.DATE
        n.startsWith("timestamp") || n.startsWith("time") || n == "interval" -> LogicalType.DATETIME
        else -> null
    }

    private fun resolveStructuredTypes(n: String): LogicalType? = when {
        n == "bytea" -> LogicalType.BINARY
        n == "json" || n == "jsonb" -> LogicalType.JSON
        n.startsWith("geometry") || n.startsWith("geography") -> LogicalType.GEOMETRY
        else -> null
    }

    private fun resolveStringTypes(n: String): LogicalType? = when {
        n.startsWith("varchar") || n.startsWith("character") ||
            n == "text" || n == "char" || n == "name" || n == "uuid" ||
            n == "citext" || n == "xml" || n.endsWith("[]") -> LogicalType.STRING
        else -> null
    }
}
