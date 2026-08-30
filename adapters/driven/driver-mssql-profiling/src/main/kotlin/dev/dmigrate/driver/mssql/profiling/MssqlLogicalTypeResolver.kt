package dev.dmigrate.driver.mssql.profiling

import dev.dmigrate.profiling.port.LogicalTypeResolverPort
import dev.dmigrate.profiling.types.LogicalType

/**
 * T-SQL-Typname auf die logische Profiling-Familie.
 *
 * SQL Server fuehrt kein `boolean`: `bit` ist die Wahrheitsspalte. `money` und
 * `smallmoney` sind Festkomma und zaehlen zu [LogicalType.DECIMAL]; die
 * raeumlichen Typen `geometry` und `geography` haben eine eigene Familie.
 */
class MssqlLogicalTypeResolver : LogicalTypeResolverPort {

    override fun resolve(dbType: String): LogicalType {
        val normalized = dbType.lowercase().trim().substringBefore('(').trim()
        if (normalized.isEmpty()) return LogicalType.UNKNOWN
        return NUMERIC[normalized]
            ?: TEMPORAL[normalized]
            ?: OPAQUE[normalized]
            ?: if (normalized in TEXTUAL) LogicalType.STRING else LogicalType.UNKNOWN
    }

    private companion object {
        val NUMERIC = mapOf(
            "int" to LogicalType.INTEGER,
            "integer" to LogicalType.INTEGER,
            "bigint" to LogicalType.INTEGER,
            "smallint" to LogicalType.INTEGER,
            "tinyint" to LogicalType.INTEGER,
            "bit" to LogicalType.BOOLEAN,
            "decimal" to LogicalType.DECIMAL,
            "numeric" to LogicalType.DECIMAL,
            "money" to LogicalType.DECIMAL,
            "smallmoney" to LogicalType.DECIMAL,
            "float" to LogicalType.DECIMAL,
            "real" to LogicalType.DECIMAL,
        )
        val TEMPORAL = mapOf(
            "date" to LogicalType.DATE,
            "time" to LogicalType.DATETIME,
            "datetime" to LogicalType.DATETIME,
            "datetime2" to LogicalType.DATETIME,
            "smalldatetime" to LogicalType.DATETIME,
            "datetimeoffset" to LogicalType.DATETIME,
        )
        val OPAQUE = mapOf(
            "binary" to LogicalType.BINARY,
            "varbinary" to LogicalType.BINARY,
            "image" to LogicalType.BINARY,
            "geometry" to LogicalType.GEOMETRY,
            "geography" to LogicalType.GEOMETRY,
        )
        val TEXTUAL = setOf(
            "char", "nchar", "varchar", "nvarchar", "text", "ntext",
            "sysname", "uniqueidentifier", "xml",
        )
    }
}
