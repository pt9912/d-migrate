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
        val generation: ColumnGeneration? = null,
        val note: SchemaReadNote? = null,
    )

    data class ColumnInput(
        val dataType: String,
        val udtName: String,
        val isPkCol: Boolean,
        val isIdentity: Boolean,
        val identityGeneration: String?,
        val colDefault: String?,
        val generatedSequenceName: String?,
        val charMaxLen: Int?,
        val numPrecision: Int?,
        val numScale: Int?,
        val tableName: String,
        val colName: String,
    )

    fun mapColumn(input: ColumnInput): MappingResult {
        val dt = input.dataType.lowercase()
        val udt = input.udtName.lowercase()

        // N5: a `nextval` default is identity/serial ONLY for a true IDENTITY
        // column or a serial PK. A non-PK `DEFAULT nextval('s')` is a named-
        // sequence reference (→ SequenceNextVal default), not an identity column.
        val isGenerated = input.isIdentity || (isSerialDefault(input.colDefault) && input.isPkCol)
        if (isGenerated && (udt == "int8" || dt == "bigint")) {
            return MappingResult(
                NeutralType.BigInteger,
                generation = identityGeneration(input),
            )
        }

        // Legacy 32-bit serial/identity PKs keep the existing Identifier contract.
        if (input.isPkCol && isGenerated) {
            return when {
                udt == "int4" || udt == "int2" || dt == "integer" || dt == "smallint" ->
                    MappingResult(NeutralType.Identifier(autoIncrement = true))
                else -> MappingResult(NeutralType.Identifier(autoIncrement = true))
            }
        }

        return mapIntegerTypes(dt)
            ?: mapStringTypes(dt, input.charMaxLen)
            ?: mapNumericTypes(dt, input.numPrecision, input.numScale)
            ?: mapTemporalTypes(dt)
            ?: mapSpecialTypes(dt, udt, input.tableName, input.colName)
            ?: MappingResult(
                type = NeutralType.Text(),
                note = SchemaReadNote(
                    severity = SchemaReadSeverity.WARNING, code = "R301",
                    objectName = "${input.tableName}.${input.colName}",
                    message = "Unknown PostgreSQL type '${input.dataType}' (udt: ${input.udtName}) mapped to text",
                ),
            )
    }

    private fun identityGeneration(input: ColumnInput): ColumnGeneration.Identity =
        ColumnGeneration.Identity(
            mode = when (input.identityGeneration?.lowercase()) {
                "always" -> IdentityMode.ALWAYS
                else -> IdentityMode.BY_DEFAULT
            },
            sequenceName = input.generatedSequenceName,
            legacySerialSyntax = !input.isIdentity && isSerialDefault(input.colDefault),
        )

    private fun mapIntegerTypes(dt: String): MappingResult? = when (dt) {
        "integer" -> MappingResult(NeutralType.Integer)
        "bigint" -> MappingResult(NeutralType.BigInteger)
        "smallint" -> MappingResult(NeutralType.SmallInt)
        "boolean" -> MappingResult(NeutralType.BooleanType)
        else -> null
    }

    private fun mapStringTypes(dt: String, charMaxLen: Int?): MappingResult? = when (dt) {
        "text" -> MappingResult(NeutralType.Text())
        "character varying" -> MappingResult(NeutralType.Text(maxLength = charMaxLen))
        "character" -> MappingResult(NeutralType.Char(length = charMaxLen ?: 1))
        else -> null
    }

    private fun mapNumericTypes(dt: String, numPrecision: Int?, numScale: Int?): MappingResult? = when (dt) {
        "numeric", "decimal" -> if (numPrecision != null && numScale != null) {
            MappingResult(NeutralType.Decimal(numPrecision, numScale))
        } else {
            MappingResult(NeutralType.Float())
        }
        "real" -> MappingResult(NeutralType.Float(FloatPrecision.SINGLE))
        "double precision" -> MappingResult(NeutralType.Float(FloatPrecision.DOUBLE))
        else -> null
    }

    private fun mapTemporalTypes(dt: String): MappingResult? = when (dt) {
        "timestamp without time zone" -> MappingResult(NeutralType.DateTime(timezone = false))
        "timestamp with time zone" -> MappingResult(NeutralType.DateTime(timezone = true))
        "date" -> MappingResult(NeutralType.Date)
        "time without time zone", "time with time zone" -> MappingResult(NeutralType.Time)
        else -> null
    }

    private fun mapSpecialTypes(dt: String, udt: String, tableName: String, colName: String): MappingResult? = when (dt) {
        "uuid" -> MappingResult(NeutralType.Uuid)
        "json", "jsonb" -> MappingResult(NeutralType.Json)
        "xml" -> MappingResult(NeutralType.Xml)
        "bytea" -> MappingResult(NeutralType.Binary)
        // ADR 0015: tsvector is a first-class neutral FullText type — captured
        // faithfully instead of degrading to text (R301).
        "tsvector" -> MappingResult(NeutralType.FullText)
        "user-defined" -> mapUserDefined(udt, tableName, colName)
        "array" -> MappingResult(NeutralType.Array(mapArrayElementType(udt.removePrefix("_"))))
        else -> null
    }

    fun mapUserDefined(udtName: String, tableName: String, colName: String): MappingResult {
        if (udtName == "geometry") return MappingResult(
            type = NeutralType.Geometry(),
            note = SchemaReadNote(
                severity = SchemaReadSeverity.INFO,
                code = "R401",
                objectName = "$tableName.$colName",
                message = "PostGIS geometry column uses the PostGIS extension",
                hint = "Extension installation is reported separately by reverse note R400",
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

    /** N5: the bare sequence name out of `nextval('schema.seq'::regclass)`, or null. */
    fun sequenceNameFromNextval(default: String?): String? {
        val raw = Regex("""nextval\('([^']+)'""", RegexOption.IGNORE_CASE)
            .find(default ?: return null)?.groupValues?.get(1) ?: return null
        return raw.substringAfterLast('.').removeSurrounding("\"")
    }

    fun parseDefault(raw: String?): DefaultValue? {
        if (raw == null) return null
        val trimmed = raw.trim()
        parseFunctionDefault(trimmed)?.let { return it }
        return when {
            trimmed.equals("NULL", ignoreCase = true) -> null
            trimmed.startsWith("nextval(") -> null
            trimmed.equals("true", ignoreCase = true) -> DefaultValue.BooleanLiteral(true)
            trimmed.equals("false", ignoreCase = true) -> DefaultValue.BooleanLiteral(false)
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

    /** Recognised PostgreSQL function/keyword defaults, normalised to canonical names. */
    private fun parseFunctionDefault(trimmed: String): DefaultValue.FunctionCall? = when {
        trimmed.equals("CURRENT_TIMESTAMP", ignoreCase = true) ||
            trimmed.equals("now()", ignoreCase = true) -> DefaultValue.FunctionCall("current_timestamp")
        trimmed.equals("CURRENT_DATE", ignoreCase = true) -> DefaultValue.FunctionCall("current_date")
        trimmed.equals("CURRENT_TIME", ignoreCase = true) -> DefaultValue.FunctionCall("current_time")
        trimmed.equals("gen_random_uuid()", ignoreCase = true) -> DefaultValue.FunctionCall("gen_uuid")
        else -> null
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
