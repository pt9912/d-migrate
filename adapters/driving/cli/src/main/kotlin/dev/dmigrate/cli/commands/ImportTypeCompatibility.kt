package dev.dmigrate.cli.commands

import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.driver.data.TargetColumn
import java.sql.Types

/**
 * Pure functions for checking type compatibility between the neutral
 * schema model and JDBC target columns during import preflight.
 */
internal object ImportTypeCompatibility {

    private val WELL_KNOWN_OTHER_TYPE_NAMES = setOf("UUID", "JSON", "JSONB", "XML")

    fun isTypeCompatible(
        schemaType: NeutralType,
        targetColumn: TargetColumn,
    ): Boolean {
        val sqlTypeName = targetColumn.sqlTypeName?.uppercase().orEmpty()
        val jdbcType = targetColumn.jdbcType
        return when (schemaType) {
            is NeutralType.Identifier -> isIdentifierCompatible(jdbcType)
            is NeutralType.Text, is NeutralType.Email -> isTextCompatible(jdbcType, sqlTypeName)
            is NeutralType.Char -> jdbcType in setOf(Types.CHAR, Types.NCHAR)
            NeutralType.Integer -> jdbcType == Types.INTEGER || sqlTypeName == "INT4"
            NeutralType.SmallInt -> jdbcType == Types.SMALLINT || sqlTypeName == "INT2"
            NeutralType.BigInteger -> jdbcType == Types.BIGINT || sqlTypeName == "INT8"
            is NeutralType.Float -> isFloatCompatible(schemaType, jdbcType)
            is NeutralType.Decimal -> jdbcType in setOf(Types.DECIMAL, Types.NUMERIC)
            NeutralType.BooleanType -> isBooleanCompatible(jdbcType, sqlTypeName)
            is NeutralType.DateTime -> jdbcType in setOf(Types.TIMESTAMP, Types.TIMESTAMP_WITH_TIMEZONE)
            NeutralType.Date -> jdbcType == Types.DATE
            NeutralType.Time -> jdbcType in setOf(Types.TIME, Types.TIME_WITH_TIMEZONE)
            NeutralType.Uuid -> sqlTypeName == "UUID" || jdbcType in setOf(Types.CHAR, Types.VARCHAR)
            NeutralType.Json -> isJsonCompatible(jdbcType, sqlTypeName)
            NeutralType.Xml -> isXmlCompatible(jdbcType, sqlTypeName)
            NeutralType.Binary -> jdbcType in setOf(Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY, Types.BLOB)
            is NeutralType.Enum -> isEnumCompatible(schemaType, jdbcType, sqlTypeName)
            is NeutralType.Array -> jdbcType == Types.ARRAY || sqlTypeName.endsWith("[]")
            is NeutralType.Geometry -> true
        }
    }

    private fun isIdentifierCompatible(jdbcType: Int): Boolean =
        jdbcType in setOf(Types.SMALLINT, Types.INTEGER, Types.BIGINT, Types.NUMERIC, Types.DECIMAL)

    private fun isTextCompatible(jdbcType: Int, sqlTypeName: String): Boolean =
        jdbcType in setOf(Types.CHAR, Types.VARCHAR, Types.LONGVARCHAR, Types.NCHAR, Types.NVARCHAR, Types.LONGNVARCHAR, Types.CLOB) || sqlTypeName.contains("TEXT")

    private fun isFloatCompatible(type: NeutralType.Float, jdbcType: Int): Boolean =
        if (type.floatPrecision.name == "SINGLE") jdbcType in setOf(Types.REAL, Types.FLOAT)
        else jdbcType in setOf(Types.DOUBLE, Types.FLOAT, Types.REAL)

    private fun isBooleanCompatible(jdbcType: Int, sqlTypeName: String): Boolean =
        jdbcType == Types.BOOLEAN || (jdbcType == Types.BIT && !isMultiBit(sqlTypeName))

    private fun isJsonCompatible(jdbcType: Int, sqlTypeName: String): Boolean =
        sqlTypeName in setOf("JSON", "JSONB") || jdbcType in setOf(Types.VARCHAR, Types.LONGVARCHAR, Types.CLOB)

    private fun isXmlCompatible(jdbcType: Int, sqlTypeName: String): Boolean =
        jdbcType == Types.SQLXML || sqlTypeName == "XML" || jdbcType in setOf(Types.VARCHAR, Types.LONGVARCHAR, Types.CLOB)

    private fun isEnumCompatible(type: NeutralType.Enum, jdbcType: Int, sqlTypeName: String): Boolean {
        val ref = type.refType?.uppercase()
        return sqlTypeName == "ENUM" ||
            jdbcType in setOf(Types.CHAR, Types.VARCHAR, Types.NCHAR, Types.NVARCHAR) ||
            (jdbcType == Types.OTHER && sqlTypeName.isNotEmpty() && sqlTypeName !in WELL_KNOWN_OTHER_TYPE_NAMES && (ref == null || sqlTypeName == ref))
    }

    fun isMultiBit(sqlTypeName: String): Boolean {
        if (!sqlTypeName.startsWith("BIT")) return false
        val start = sqlTypeName.indexOf('(')
        val end = sqlTypeName.indexOf(')')
        if (start < 0 || end <= start + 1) return false
        return sqlTypeName.substring(start + 1, end).trim().toIntOrNull()?.let { it > 1 } == true
    }

    fun describe(type: NeutralType): String = when (type) {
        is NeutralType.Identifier -> "identifier-compatible integer"
        is NeutralType.Text -> "text-compatible type"
        is NeutralType.Char -> "fixed-width char"
        NeutralType.Integer -> "INTEGER"
        NeutralType.SmallInt -> "SMALLINT"
        NeutralType.BigInteger -> "BIGINT"
        is NeutralType.Float -> if (type.floatPrecision.name == "SINGLE") "single-precision float" else "double-precision float"
        is NeutralType.Decimal -> "DECIMAL/NUMERIC"
        NeutralType.BooleanType -> "BOOLEAN"
        is NeutralType.DateTime -> "TIMESTAMP"
        NeutralType.Date -> "DATE"
        NeutralType.Time -> "TIME"
        NeutralType.Uuid -> "UUID-compatible type"
        NeutralType.Json -> "JSON-compatible type"
        NeutralType.Xml -> "XML-compatible type"
        NeutralType.Binary -> "binary/blob type"
        NeutralType.Email -> "text-compatible type"
        is NeutralType.Enum -> "enum/text-compatible type"
        is NeutralType.Array -> "array-compatible type"
        is NeutralType.Geometry -> "geometry-compatible type"
    }
}
