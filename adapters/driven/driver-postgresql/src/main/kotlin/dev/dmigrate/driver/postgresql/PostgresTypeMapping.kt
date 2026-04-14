package dev.dmigrate.driver.postgresql

import dev.dmigrate.core.model.*
import dev.dmigrate.driver.SchemaReadNote
import dev.dmigrate.driver.SchemaReadSeverity

/**
 * Pure functions for mapping PostgreSQL metadata to neutral types.
 * Extracted from [PostgresSchemaReader] for unit-testability.
 */
internal object PostgresTypeMapping {

    data class MappingResult(
        val type: NeutralType,
        val note: SchemaReadNote? = null,
    )

    fun mapColumn(
        dataType: String,
        udtName: String,
        isPkCol: Boolean,
        isIdentity: Boolean,
        colDefault: String?,
        charMaxLen: Int?,
        numPrecision: Int?,
        numScale: Int?,
        tableName: String,
        colName: String,
    ): MappingResult {
        val dt = dataType.lowercase()
        val udt = udtName.lowercase()

        // Serial/identity → Identifier for integer, BigInteger for bigint
        if (isPkCol && (isIdentity || isSerialDefault(colDefault))) {
            return when {
                udt == "int4" || udt == "int2" || dt == "integer" || dt == "smallint" ->
                    MappingResult(NeutralType.Identifier(autoIncrement = true))
                udt == "int8" || dt == "bigint" ->
                    MappingResult(
                        NeutralType.BigInteger,
                        SchemaReadNote(
                            severity = SchemaReadSeverity.INFO,
                            code = "R300",
                            objectName = "$tableName.$colName",
                            message = "bigint auto-increment mapped to BigInteger (not Identifier) to preserve type width",
                        ),
                    )
                else -> MappingResult(NeutralType.Identifier(autoIncrement = true))
            }
        }

        return when (dt) {
            "integer" -> MappingResult(NeutralType.Integer)
            "bigint" -> MappingResult(NeutralType.BigInteger)
            "smallint" -> MappingResult(NeutralType.SmallInt)
            "boolean" -> MappingResult(NeutralType.BooleanType)
            "text" -> MappingResult(NeutralType.Text())
            "character varying" -> MappingResult(NeutralType.Text(maxLength = charMaxLen))
            "character" -> MappingResult(NeutralType.Char(length = charMaxLen ?: 1))
            "numeric", "decimal" -> {
                if (numPrecision != null && numScale != null) MappingResult(NeutralType.Decimal(numPrecision, numScale))
                else MappingResult(NeutralType.Float())
            }
            "real" -> MappingResult(NeutralType.Float(FloatPrecision.SINGLE))
            "double precision" -> MappingResult(NeutralType.Float(FloatPrecision.DOUBLE))
            "timestamp without time zone" -> MappingResult(NeutralType.DateTime(timezone = false))
            "timestamp with time zone" -> MappingResult(NeutralType.DateTime(timezone = true))
            "date" -> MappingResult(NeutralType.Date)
            "time without time zone", "time with time zone" -> MappingResult(NeutralType.Time)
            "uuid" -> MappingResult(NeutralType.Uuid)
            "json", "jsonb" -> MappingResult(NeutralType.Json)
            "xml" -> MappingResult(NeutralType.Xml)
            "bytea" -> MappingResult(NeutralType.Binary)
            "user-defined" -> mapUserDefined(udt, tableName, colName)
            "array" -> {
                val elementUdt = udt.removePrefix("_")
                MappingResult(NeutralType.Array(mapArrayElementType(elementUdt)))
            }
            else -> MappingResult(
                NeutralType.Text(),
                SchemaReadNote(
                    severity = SchemaReadSeverity.WARNING,
                    code = "R301",
                    objectName = "$tableName.$colName",
                    message = "Unknown PostgreSQL type '$dataType' (udt: $udtName) mapped to text",
                ),
            )
        }
    }

    fun mapUserDefined(udtName: String, tableName: String, colName: String): MappingResult {
        if (udtName == "geometry") return MappingResult(
            NeutralType.Geometry(),
            SchemaReadNote(
                severity = SchemaReadSeverity.INFO,
                code = "R401",
                objectName = "$tableName.$colName",
                message = "PostGIS geometry column — requires PostGIS extension in target database",
            ),
        )
        return MappingResult(NeutralType.Enum(refType = udtName))
    }

    fun mapArrayElementType(elementUdt: String): String = when (elementUdt) {
        "int4", "int2" -> "integer"
        "int8" -> "biginteger"
        "text", "varchar", "bpchar" -> "text"
        "bool" -> "boolean"
        "uuid" -> "uuid"
        "float4", "float8" -> "float"
        "numeric" -> "decimal"
        "json", "jsonb" -> "json"
        else -> "text"
    }

    fun isSerialDefault(default: String?): Boolean {
        if (default == null) return false
        return default.lowercase().contains("nextval(")
    }

    fun parseDefault(raw: String?): DefaultValue? {
        if (raw == null) return null
        val trimmed = raw.trim()
        return when {
            trimmed.equals("NULL", ignoreCase = true) -> null
            trimmed.startsWith("nextval(") -> null
            trimmed.equals("true", ignoreCase = true) -> DefaultValue.BooleanLiteral(true)
            trimmed.equals("false", ignoreCase = true) -> DefaultValue.BooleanLiteral(false)
            trimmed.equals("CURRENT_TIMESTAMP", ignoreCase = true) ||
                trimmed.equals("now()", ignoreCase = true) -> DefaultValue.FunctionCall("current_timestamp")
            trimmed.equals("gen_random_uuid()", ignoreCase = true) -> DefaultValue.FunctionCall("gen_uuid")
            trimmed.startsWith("'") && trimmed.contains("'::") -> {
                val value = trimmed.substringAfter("'").substringBefore("'::")
                DefaultValue.StringLiteral(value.replace("''", "'"))
            }
            trimmed.startsWith("'") && trimmed.endsWith("'") ->
                DefaultValue.StringLiteral(trimmed.substring(1, trimmed.length - 1).replace("''", "'"))
            trimmed.toLongOrNull() != null -> DefaultValue.NumberLiteral(trimmed.toLong())
            trimmed.toDoubleOrNull() != null -> DefaultValue.NumberLiteral(trimmed.toDouble())
            trimmed.contains("::") -> {
                val literal = trimmed.substringBefore("::")
                if (literal.startsWith("'") && literal.endsWith("'")) {
                    DefaultValue.StringLiteral(literal.substring(1, literal.length - 1))
                } else {
                    literal.toLongOrNull()?.let { DefaultValue.NumberLiteral(it) }
                        ?: literal.toDoubleOrNull()?.let { DefaultValue.NumberLiteral(it) }
                        ?: DefaultValue.FunctionCall(trimmed)
                }
            }
            else -> DefaultValue.FunctionCall(trimmed)
        }
    }

    fun mapParamType(pgType: String): String = when (pgType.lowercase()) {
        "int4", "integer" -> "integer"
        "int8", "bigint" -> "biginteger"
        "int2", "smallint" -> "smallint"
        "text", "varchar", "bpchar", "character varying" -> "text"
        "bool", "boolean" -> "boolean"
        "float4", "real" -> "float"
        "float8", "double precision" -> "float"
        "numeric", "decimal" -> "decimal"
        "uuid" -> "uuid"
        "json", "jsonb" -> "json"
        "bytea" -> "binary"
        "void" -> "void"
        else -> pgType
    }

    fun mapCompositeFieldType(pgType: String): NeutralType {
        val lower = pgType.lowercase().trim()
        return when {
            lower == "integer" || lower == "int4" -> NeutralType.Integer
            lower == "bigint" || lower == "int8" -> NeutralType.BigInteger
            lower == "smallint" || lower == "int2" -> NeutralType.SmallInt
            lower == "text" -> NeutralType.Text()
            lower == "boolean" || lower == "bool" -> NeutralType.BooleanType
            lower.startsWith("character varying") || lower.startsWith("varchar") -> {
                val len = Regex("\\((\\d+)\\)").find(lower)?.groupValues?.get(1)?.toIntOrNull()
                NeutralType.Text(maxLength = len)
            }
            lower.startsWith("numeric") || lower.startsWith("decimal") -> {
                val match = Regex("\\((\\d+),(\\d+)\\)").find(lower)
                if (match != null) NeutralType.Decimal(match.groupValues[1].toInt(), match.groupValues[2].toInt())
                else NeutralType.Float()
            }
            lower == "uuid" -> NeutralType.Uuid
            lower == "json" || lower == "jsonb" -> NeutralType.Json
            lower == "bytea" -> NeutralType.Binary
            lower == "date" -> NeutralType.Date
            lower == "time" || lower.startsWith("time ") -> NeutralType.Time
            lower.startsWith("timestamp") -> NeutralType.DateTime(timezone = lower.contains("with time zone"))
            else -> NeutralType.Text()
        }
    }
}
