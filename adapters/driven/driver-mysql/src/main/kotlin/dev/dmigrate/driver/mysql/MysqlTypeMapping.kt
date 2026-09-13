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
        val generation: ColumnGeneration? = null,
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
        // VA2 (Spatial): SRID-Constraint einer Geometriespalte aus
        // information_schema.columns.srs_id; null, wenn keine SRID gesetzt ist.
        val srsId: Int? = null,
    )

    fun mapColumn(input: ColumnInput): MappingResult {
        val dt = input.dataType.lowercase()
        val ct = input.columnType.lowercase()

        if (input.isAutoIncrement) {
            return when (dt) {
                "int" -> MappingResult(NeutralType.Identifier(autoIncrement = true))
                "bigint" -> MappingResult(
                    NeutralType.BigInteger,
                    generation = ColumnGeneration.Identity(legacySerialSyntax = true),
                )
                else -> MappingResult(NeutralType.Identifier(autoIncrement = true))
            }
        }

        return mapIntegerTypes(dt, ct)
            ?: mapStringTypes(dt, input.charMaxLen, input.tableName, input.colName)
            ?: mapNumericTypes(dt, input.numPrecision, input.numScale)
            ?: mapTemporalTypes(dt)
            ?: mapSpecialTypes(dt, ct, input.columnType, input.tableName, input.colName, input.srsId)
            ?: MappingResult(
                NeutralType.Text(),
                note = SchemaReadNote(
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
            if (len == 36) MappingResult(NeutralType.Uuid, note = SchemaReadNote(
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

    private fun mapSpecialTypes(
        dt: String,
        ct: String,
        rawColumnType: String,
        tableName: String,
        colName: String,
        srsId: Int?,
    ): MappingResult? = when (dt) {
        "json" -> MappingResult(NeutralType.Json)
        "blob", "mediumblob", "longblob", "tinyblob", "binary", "varbinary" -> MappingResult(NeutralType.Binary)
        // I-03: Enum-Werte aus dem Original-Case-columnType extrahieren, nicht aus
        // dem für die Typ-Erkennung kleingeschriebenen `ct` (sonst Wert-Korruption).
        "enum" -> MappingResult(NeutralType.Enum(values = extractEnumValues(rawColumnType)))
        "set" -> MappingResult(NeutralType.Text(), note = SchemaReadNote(
            severity = SchemaReadSeverity.ACTION_REQUIRED, code = "R320", objectName = "$tableName.$colName",
            message = "MySQL SET type '$ct' has no neutral equivalent — mapped to text",
            hint = "Review and convert to enum or text with application-level validation",
        ))
        "geometry", "point", "linestring", "polygon", "multipoint", "multilinestring", "multipolygon", "geometrycollection" ->
            MappingResult(NeutralType.Geometry(geometryType = GeometryType.of(dt), srid = srsId))
        else -> null
    }

    fun extractEnumValues(columnType: String): List<String> {
        val match = Regex("enum\\((.+)\\)", RegexOption.IGNORE_CASE).find(columnType)
        return match?.groupValues?.get(1)
            ?.split(",")
            ?.map { it.trim().removeSurrounding("'") }
            ?: emptyList()
    }

    /**
     * Der Default einer MySQL-Spalte.
     *
     * **[isExpression] entscheidet, nicht der Text.** MySQL gibt einen
     * String-Default **ohne** Anfuehrungszeichen zurueck: `DEFAULT \'x\'` steht in
     * `COLUMN_DEFAULT` als `x`. Am Text allein ist ein Literal deshalb nicht von
     * einem Ausdruck zu unterscheiden — und das ging schief: aus `DEFAULT \'x\'`
     * wurde ein `FunctionCall("x")`, gerendert als `DEFAULT x()`. Der Fall, der
     * es zur Korrektheitsfrage macht, ist `DEFAULT \'UPPER(a)\'`: ein
     * Zeichenketten-Literal, das wie ein Aufruf aussieht und als solcher im
     * Ziel **ausgefuehrt** wuerde.
     *
     * Die Unterscheidung liefert der Server mit, in `EXTRA` — gemessen an 9.7.2:
     *
     * | Default | `COLUMN_DEFAULT` | `EXTRA` |
     * | --- | --- | --- |
     * | `\'x\'` | `x` | leer |
     * | `\'UPPER(a)\'` | `UPPER(a)` | leer |
     * | `7` | `7` | leer |
     * | `TRUE` | `1` | leer |
     * | `CURRENT_TIMESTAMP` | `CURRENT_TIMESTAMP` | `DEFAULT_GENERATED` |
     * | `(UUID())` | `uuid()` | `DEFAULT_GENERATED` |
     * | `(1 + 2)` | `(1 + 2)` | `DEFAULT_GENERATED` |
     *
     * Ohne das Flag ist der Text also ein Literal, und **welches** sagt der
     * Spaltentyp: `\'7\'` in einer `VARCHAR`-Spalte ist eine Zeichenkette, keine
     * Zahl.
     */
    fun parseDefault(raw: String?, type: NeutralType, isExpression: Boolean = false): DefaultValue? {
        if (raw == null) return null
        val trimmed = raw.trim()
        if (trimmed.equals("NULL", ignoreCase = true)) return null
        // Die Zeit-Schluesselwoerter behalten ihre Normalisierung: sie kommen als
        // Ausdruck (`DEFAULT_GENERATED`), und das neutrale Modell fuehrt sie als
        // Funktionsaufruf.
        knownTimeFunction(trimmed)?.let { return it }
        if (isExpression) return DefaultValue.FunctionCall(trimmed)
        // Eine bereits zitierte Form kommt aus anderen Quellen (Tests, aeltere
        // Server); sie bleibt eine Zeichenkette.
        if (trimmed.length >= 2 && trimmed.startsWith("'") && trimmed.endsWith("'")) {
            return DefaultValue.StringLiteral(trimmed.substring(1, trimmed.length - 1).replace("''", "'"))
        }
        return when {
            type is NeutralType.BooleanType && trimmed == "1" -> DefaultValue.BooleanLiteral(true)
            type is NeutralType.BooleanType && trimmed == "0" -> DefaultValue.BooleanLiteral(false)
            // Der Typ entscheidet ueber die Literalart, nicht die Gestalt des
            // Textes: sonst wuerde `\'7\'` in einer Textspalte zur Zahl.
            isTextLike(type) -> DefaultValue.StringLiteral(trimmed)
            trimmed.toLongOrNull() != null -> DefaultValue.NumberLiteral(trimmed.toLong())
            trimmed.toDoubleOrNull() != null -> DefaultValue.NumberLiteral(trimmed.toDouble())
            else -> DefaultValue.StringLiteral(trimmed)
        }
    }

    private fun knownTimeFunction(trimmed: String): DefaultValue? = when {
        trimmed == "CURRENT_TIMESTAMP" || trimmed.equals("current_timestamp()", ignoreCase = true) ->
            DefaultValue.FunctionCall("current_timestamp")
        trimmed.equals("CURRENT_DATE", ignoreCase = true) ||
            trimmed.equals("curdate()", ignoreCase = true) ||
            trimmed.equals("current_date()", ignoreCase = true) -> DefaultValue.FunctionCall("current_date")
        trimmed.equals("CURRENT_TIME", ignoreCase = true) ||
            trimmed.equals("curtime()", ignoreCase = true) ||
            trimmed.equals("current_time()", ignoreCase = true) -> DefaultValue.FunctionCall("current_time")
        else -> null
    }

    private fun isTextLike(type: NeutralType): Boolean =
        type is NeutralType.Text || type is NeutralType.Enum || type is NeutralType.Uuid

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
