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
        return when (schemaType) {
            is NeutralType.Identifier ->
                targetColumn.jdbcType in setOf(Types.SMALLINT, Types.INTEGER, Types.BIGINT, Types.NUMERIC, Types.DECIMAL)
            is NeutralType.Text,
            is NeutralType.Email ->
                targetColumn.jdbcType in setOf(
                    Types.CHAR, Types.VARCHAR, Types.LONGVARCHAR,
                    Types.NCHAR, Types.NVARCHAR, Types.LONGNVARCHAR, Types.CLOB
                ) || sqlTypeName.contains("TEXT")
            is NeutralType.Char ->
                targetColumn.jdbcType in setOf(Types.CHAR, Types.NCHAR)
            NeutralType.Integer ->
                targetColumn.jdbcType == Types.INTEGER || sqlTypeName == "INT4"
            NeutralType.SmallInt ->
                targetColumn.jdbcType == Types.SMALLINT || sqlTypeName == "INT2"
            NeutralType.BigInteger ->
                targetColumn.jdbcType == Types.BIGINT || sqlTypeName == "INT8"
            is NeutralType.Float ->
                if (schemaType.floatPrecision.name == "SINGLE") {
                    targetColumn.jdbcType in setOf(Types.REAL, Types.FLOAT)
                } else {
                    targetColumn.jdbcType in setOf(Types.DOUBLE, Types.FLOAT, Types.REAL)
                }
            is NeutralType.Decimal ->
                targetColumn.jdbcType in setOf(Types.DECIMAL, Types.NUMERIC)
            NeutralType.BooleanType ->
                targetColumn.jdbcType == Types.BOOLEAN ||
                    (targetColumn.jdbcType == Types.BIT && !isMultiBit(sqlTypeName))
            is NeutralType.DateTime ->
                targetColumn.jdbcType in setOf(Types.TIMESTAMP, Types.TIMESTAMP_WITH_TIMEZONE)
            NeutralType.Date ->
                targetColumn.jdbcType == Types.DATE
            NeutralType.Time ->
                targetColumn.jdbcType in setOf(Types.TIME, Types.TIME_WITH_TIMEZONE)
            NeutralType.Uuid ->
                sqlTypeName == "UUID" ||
                    targetColumn.jdbcType in setOf(Types.CHAR, Types.VARCHAR)
            NeutralType.Json ->
                sqlTypeName in setOf("JSON", "JSONB") ||
                    targetColumn.jdbcType in setOf(Types.VARCHAR, Types.LONGVARCHAR, Types.CLOB)
            NeutralType.Xml ->
                targetColumn.jdbcType == Types.SQLXML ||
                    sqlTypeName == "XML" ||
                    targetColumn.jdbcType in setOf(Types.VARCHAR, Types.LONGVARCHAR, Types.CLOB)
            NeutralType.Binary ->
                targetColumn.jdbcType in setOf(Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY, Types.BLOB)
            is NeutralType.Enum -> {
                val ref = schemaType.refType?.uppercase()
                sqlTypeName == "ENUM" ||
                    targetColumn.jdbcType in setOf(Types.CHAR, Types.VARCHAR, Types.NCHAR, Types.NVARCHAR) ||
                    (targetColumn.jdbcType == Types.OTHER && sqlTypeName.isNotEmpty() &&
                        sqlTypeName !in WELL_KNOWN_OTHER_TYPE_NAMES &&
                        (ref == null || sqlTypeName == ref))
            }
            is NeutralType.Array ->
                targetColumn.jdbcType == Types.ARRAY || sqlTypeName.endsWith("[]")
            is NeutralType.Geometry -> true
        }
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
