package dev.dmigrate.driver.mysql

import dev.dmigrate.core.model.*
import dev.dmigrate.driver.SchemaReadNote
import dev.dmigrate.driver.SchemaReadSeverity

/**
 * Pure functions for mapping MySQL metadata to neutral types.
 * Extracted from [MysqlSchemaReader] for unit-testability.
 */
internal object MysqlTypeMapping {

    data class MappingResult(
        val type: NeutralType,
        val note: SchemaReadNote? = null,
    )

    data class ColumnInput(
        val dataType: String,
        val columnType: String,
        val isAutoIncrement: Boolean,
        val charMaxLen: Int?,
        val numPrecision: Int?,
        val numScale: Int?,
        val tableName: String,
        val colName: String,
    )

    fun mapColumn(input: ColumnInput): MappingResult {
        val dt = input.dataType.lowercase()
        val ct = input.columnType.lowercase()

        if (input.isAutoIncrement) {
            return when (dt) {
                "int" -> MappingResult(NeutralType.Identifier(autoIncrement = true))
                "bigint" -> MappingResult(
                    NeutralType.BigInteger,
                    SchemaReadNote(
                        severity = SchemaReadSeverity.INFO,
                        code = "R300",
                        objectName = "${input.tableName}.${input.colName}",
                        message = "bigint auto_increment mapped to BigInteger (not Identifier) to preserve type width",
                    ),
                )
                else -> MappingResult(NeutralType.Identifier(autoIncrement = true))
            }
        }

        return mapIntegerTypes(dt, ct)
            ?: mapStringTypes(dt, input.charMaxLen, input.tableName, input.colName)
            ?: mapNumericTypes(dt, input.numPrecision, input.numScale)
            ?: mapTemporalTypes(dt)
            ?: mapSpecialTypes(dt, ct, input.tableName, input.colName)
            ?: MappingResult(
                NeutralType.Text(),
                SchemaReadNote(
                    severity = SchemaReadSeverity.WARNING, code = "R301",
                    objectName = "${input.tableName}.${input.colName}",
                    message = "Unknown MySQL type '$dt' mapped to text",
                ),
            )
    }

    private fun mapIntegerTypes(dt: String, ct: String): MappingResult? = when (dt) {
        "int" -> MappingResult(NeutralType.Integer)
        "bigint" -> MappingResult(NeutralType.BigInteger)
        "smallint" -> MappingResult(NeutralType.SmallInt)
        "mediumint" -> MappingResult(NeutralType.Integer)
        "tinyint" -> if (ct == "tinyint(1)") MappingResult(NeutralType.BooleanType) else MappingResult(NeutralType.SmallInt)
        "boolean" -> MappingResult(NeutralType.BooleanType)
        else -> null
    }

    private fun mapStringTypes(dt: String, charMaxLen: Int?, tableName: String, colName: String): MappingResult? = when (dt) {
        "varchar" -> MappingResult(NeutralType.Text(maxLength = charMaxLen))
        "char" -> {
            val len = charMaxLen ?: 1
            if (len == 36) MappingResult(NeutralType.Uuid, SchemaReadNote(
                severity = SchemaReadSeverity.INFO, code = "R310", objectName = "$tableName.$colName",
                message = "char(36) mapped to Uuid — if not a UUID, review manually",
            )) else MappingResult(NeutralType.Char(length = len))
        }
        "text", "mediumtext", "longtext", "tinytext" -> MappingResult(NeutralType.Text())
        else -> null
    }

    private fun mapNumericTypes(dt: String, numPrecision: Int?, numScale: Int?): MappingResult? = when (dt) {
        "decimal", "numeric" -> if (numPrecision != null && numScale != null) {
            MappingResult(NeutralType.Decimal(numPrecision, numScale))
        } else {
            MappingResult(NeutralType.Float())
        }
        "float" -> MappingResult(NeutralType.Float(FloatPrecision.SINGLE))
        "double" -> MappingResult(NeutralType.Float(FloatPrecision.DOUBLE))
        else -> null
    }

    private fun mapTemporalTypes(dt: String): MappingResult? = when (dt) {
        "date" -> MappingResult(NeutralType.Date)
        "time" -> MappingResult(NeutralType.Time)
        "datetime", "timestamp" -> MappingResult(NeutralType.DateTime())
        else -> null
    }

    private fun mapSpecialTypes(dt: String, ct: String, tableName: String, colName: String): MappingResult? = when (dt) {
        "json" -> MappingResult(NeutralType.Json)
        "blob", "mediumblob", "longblob", "tinyblob", "binary", "varbinary" -> MappingResult(NeutralType.Binary)
        "enum" -> MappingResult(NeutralType.Enum(values = extractEnumValues(ct)))
        "set" -> MappingResult(NeutralType.Text(), SchemaReadNote(
            severity = SchemaReadSeverity.ACTION_REQUIRED, code = "R320", objectName = "$tableName.$colName",
            message = "MySQL SET type '$ct' has no neutral equivalent — mapped to text",
            hint = "Review and convert to enum or text with application-level validation",
        ))
        "geometry", "point", "linestring", "polygon", "multipoint", "multilinestring", "multipolygon", "geometrycollection" ->
            MappingResult(NeutralType.Geometry(geometryType = GeometryType.of(dt)))
        else -> null
    }

    fun extractEnumValues(columnType: String): List<String> {
        val match = Regex("enum\\((.+)\\)", RegexOption.IGNORE_CASE).find(columnType)
        return match?.groupValues?.get(1)
            ?.split(",")
            ?.map { it.trim().removeSurrounding("'") }
            ?: emptyList()
    }

    fun parseDefault(raw: String?, type: NeutralType): DefaultValue? {
        if (raw == null) return null
        val trimmed = raw.trim()
        return when {
            trimmed.equals("NULL", ignoreCase = true) -> null
            trimmed == "CURRENT_TIMESTAMP" || trimmed == "current_timestamp()" ->
                DefaultValue.FunctionCall("current_timestamp")
            trimmed == "1" && type is NeutralType.BooleanType -> DefaultValue.BooleanLiteral(true)
            trimmed == "0" && type is NeutralType.BooleanType -> DefaultValue.BooleanLiteral(false)
            trimmed.startsWith("'") && trimmed.endsWith("'") ->
                DefaultValue.StringLiteral(trimmed.substring(1, trimmed.length - 1).replace("''", "'"))
            trimmed.toLongOrNull() != null -> DefaultValue.NumberLiteral(trimmed.toLong())
            trimmed.toDoubleOrNull() != null -> DefaultValue.NumberLiteral(trimmed.toDouble())
            else -> DefaultValue.FunctionCall(trimmed)
        }
    }

    fun mapParamType(mysqlType: String): String = when (mysqlType.lowercase().trim()) {
        "int", "integer" -> "integer"
        "bigint" -> "biginteger"
        "smallint", "tinyint", "mediumint" -> "smallint"
        "varchar", "text", "char", "mediumtext", "longtext" -> "text"
        "boolean", "tinyint(1)" -> "boolean"
        "float", "double", "real" -> "float"
        "decimal", "numeric" -> "decimal"
        "json" -> "json"
        "blob", "binary", "varbinary" -> "binary"
        "date" -> "date"
        "time" -> "time"
        "datetime", "timestamp" -> "datetime"
        else -> mysqlType.lowercase()
    }
}
