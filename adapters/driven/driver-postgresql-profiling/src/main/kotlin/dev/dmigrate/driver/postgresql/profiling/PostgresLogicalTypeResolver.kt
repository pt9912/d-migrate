package dev.dmigrate.driver.postgresql.profiling

import dev.dmigrate.profiling.port.LogicalTypeResolverPort
import dev.dmigrate.profiling.types.LogicalType

/**
 * PostgreSQL-specific logical type resolution from raw DB type strings.
 */
class PostgresLogicalTypeResolver : LogicalTypeResolverPort {

    override fun resolve(dbType: String): LogicalType {
        val normalized = dbType.lowercase().trim()
        return when {
            normalized.startsWith("int") || normalized == "serial" ||
                normalized == "bigserial" || normalized == "smallint" ||
                normalized == "bigint" || normalized == "smallserial" -> LogicalType.INTEGER

            normalized.startsWith("numeric") || normalized.startsWith("decimal") ||
                normalized == "real" || normalized.startsWith("double") ||
                normalized == "float4" || normalized == "float8" ||
                normalized == "money" -> LogicalType.DECIMAL

            normalized == "boolean" || normalized == "bool" -> LogicalType.BOOLEAN

            normalized == "date" -> LogicalType.DATE

            normalized.startsWith("timestamp") || normalized.startsWith("time") ||
                normalized == "interval" -> LogicalType.DATETIME

            normalized == "bytea" -> LogicalType.BINARY

            normalized == "json" || normalized == "jsonb" -> LogicalType.JSON

            normalized.startsWith("geometry") || normalized.startsWith("geography") -> LogicalType.GEOMETRY

            normalized.startsWith("varchar") || normalized.startsWith("character") ||
                normalized == "text" || normalized == "char" ||
                normalized == "name" || normalized == "uuid" ||
                normalized == "citext" || normalized == "xml" ||
                normalized.endsWith("[]") -> LogicalType.STRING

            else -> LogicalType.UNKNOWN
        }
    }
}
