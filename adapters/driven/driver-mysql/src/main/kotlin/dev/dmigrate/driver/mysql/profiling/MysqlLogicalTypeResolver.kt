package dev.dmigrate.driver.mysql.profiling

import dev.dmigrate.profiling.port.LogicalTypeResolverPort
import dev.dmigrate.profiling.types.LogicalType

/**
 * MySQL-specific logical type resolution from raw DB type strings.
 */
class MysqlLogicalTypeResolver : LogicalTypeResolverPort {

    override fun resolve(dbType: String): LogicalType {
        val normalized = dbType.lowercase().trim()
        return when {
            normalized.startsWith("tinyint(1)") -> LogicalType.BOOLEAN

            normalized.startsWith("int") || normalized.startsWith("tinyint") ||
                normalized.startsWith("smallint") || normalized.startsWith("mediumint") ||
                normalized.startsWith("bigint") -> LogicalType.INTEGER

            normalized.startsWith("decimal") || normalized.startsWith("numeric") ||
                normalized == "float" || normalized == "double" ||
                normalized.startsWith("double") -> LogicalType.DECIMAL

            normalized == "date" -> LogicalType.DATE

            normalized.startsWith("datetime") || normalized.startsWith("timestamp") ||
                normalized.startsWith("time") || normalized == "year" -> LogicalType.DATETIME

            normalized == "blob" || normalized == "tinyblob" ||
                normalized == "mediumblob" || normalized == "longblob" ||
                normalized.startsWith("binary") || normalized.startsWith("varbinary") -> LogicalType.BINARY

            normalized == "json" -> LogicalType.JSON

            normalized == "point" || normalized == "linestring" ||
                normalized == "polygon" || normalized == "geometry" ||
                normalized == "multipoint" || normalized == "multilinestring" ||
                normalized == "multipolygon" || normalized == "geometrycollection" -> LogicalType.GEOMETRY

            normalized.startsWith("varchar") || normalized.startsWith("char") ||
                normalized == "text" || normalized == "tinytext" ||
                normalized == "mediumtext" || normalized == "longtext" ||
                normalized.startsWith("enum") || normalized.startsWith("set") -> LogicalType.STRING

            else -> LogicalType.UNKNOWN
        }
    }
}
