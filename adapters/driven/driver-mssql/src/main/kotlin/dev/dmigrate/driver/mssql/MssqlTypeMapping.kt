package dev.dmigrate.driver.mssql

import dev.dmigrate.core.model.ColumnGeneration
import dev.dmigrate.core.model.DefaultValue
import dev.dmigrate.core.model.FloatPrecision
import dev.dmigrate.core.model.IdentityMode
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.driver.SchemaReadNote
import dev.dmigrate.driver.SchemaReadSeverity

/**
 * T-SQL → NeutralType mapping plus default-constraint parsing for the
 * MSSQL reverse read.
 *
 * Mapping decisions from the scoping plan
 * (docs/planning/in-progress/mssql-dialect-scoping.md): `bit` folds to
 * [NeutralType.BooleanType], `uniqueidentifier` to [NeutralType.Uuid],
 * Unicode and non-Unicode text both fold to the neutral text family
 * (the generate direction renders `NVARCHAR`), `datetimeoffset` is the
 * timezone-aware [NeutralType.DateTime].
 */
internal object MssqlTypeMapping {

    data class ColumnInput(
        val typeName: String,
        /** `sys.columns.max_length` in BYTES (`-1` = MAX). */
        val maxLength: Int?,
        val precision: Int?,
        val scale: Int?,
        val isIdentity: Boolean,
    )

    data class MappingResult(
        val type: NeutralType,
        val generation: ColumnGeneration?,
        val note: SchemaReadNote?,
    )

    fun mapColumn(columnName: String, input: ColumnInput): MappingResult {
        if (input.isIdentity) return mapIdentity(input)
        return MappingResult(
            type = mapBaseType(input),
            generation = null,
            note = unknownTypeNote(columnName, input.typeName),
        )
    }

    // int IDENTITY(1,1) ist der 32-bit-identifier-Vertrag; größere/abweichende
    // Basistypen behalten ihren Typ und tragen die Erzeugung als
    // Identity-Generation. T-SQL-IDENTITY erlaubt kein direktes INSERT ohne
    // SET IDENTITY_INSERT → Modus ALWAYS.
    private fun mapIdentity(input: ColumnInput): MappingResult = when (input.typeName.lowercase()) {
        "int" -> MappingResult(NeutralType.Identifier(autoIncrement = true), generation = null, note = null)
        "bigint" -> MappingResult(
            NeutralType.BigInteger,
            generation = ColumnGeneration.Identity(mode = IdentityMode.ALWAYS),
            note = null,
        )
        "smallint", "tinyint" -> MappingResult(
            NeutralType.SmallInt,
            generation = ColumnGeneration.Identity(mode = IdentityMode.ALWAYS),
            note = null,
        )
        else -> MappingResult(
            NeutralType.Decimal(input.precision ?: 18, input.scale ?: 0),
            generation = ColumnGeneration.Identity(mode = IdentityMode.ALWAYS),
            note = null,
        )
    }

    private fun mapBaseType(input: ColumnInput): NeutralType =
        mapNumeric(input)
            ?: mapTextual(input)
            ?: mapTemporal(input.typeName)
            ?: mapOpaque(input.typeName)
            ?: NeutralType.Text(maxLength = null)

    private fun mapNumeric(input: ColumnInput): NeutralType? = when (input.typeName.lowercase()) {
        "int" -> NeutralType.Integer
        "bigint" -> NeutralType.BigInteger
        "smallint", "tinyint" -> NeutralType.SmallInt
        "bit" -> NeutralType.BooleanType
        "decimal", "numeric" -> NeutralType.Decimal(input.precision ?: 18, input.scale ?: 0)
        "money" -> NeutralType.Decimal(19, 4)
        "smallmoney" -> NeutralType.Decimal(10, 4)
        "float" -> NeutralType.Float(FloatPrecision.DOUBLE)
        "real" -> NeutralType.Float(FloatPrecision.SINGLE)
        else -> null
    }

    private fun mapTextual(input: ColumnInput): NeutralType? {
        // Unicode-Typen zaehlen `sys.columns.max_length` in Bytes (2 je Zeichen).
        val unicodeLength = input.maxLength?.let { if (it == -1) null else it / 2 }
        val byteLength = input.maxLength?.let { if (it == -1) null else it }
        return when (input.typeName.lowercase()) {
            "nvarchar", "sysname" -> NeutralType.Text(maxLength = unicodeLength)
            "varchar" -> NeutralType.Text(maxLength = byteLength)
            "nchar" -> NeutralType.Char(length = unicodeLength ?: 1)
            "char" -> NeutralType.Char(length = byteLength ?: 1)
            "ntext", "text" -> NeutralType.Text(maxLength = null)
            else -> null
        }
    }

    private fun mapTemporal(typeName: String): NeutralType? = when (typeName.lowercase()) {
        "date" -> NeutralType.Date
        "time" -> NeutralType.Time
        "datetime", "datetime2", "smalldatetime" -> NeutralType.DateTime(timezone = false)
        "datetimeoffset" -> NeutralType.DateTime(timezone = true)
        else -> null
    }

    private fun mapOpaque(typeName: String): NeutralType? = when (typeName.lowercase()) {
        "uniqueidentifier" -> NeutralType.Uuid
        "varbinary", "binary", "image" -> NeutralType.Binary
        "xml" -> NeutralType.Xml
        else -> null
    }

    private val KNOWN_TYPES = setOf(
        "int", "bigint", "smallint", "tinyint", "bit", "decimal", "numeric", "money",
        "smallmoney", "float", "real", "nvarchar", "sysname", "varchar", "nchar", "char",
        "ntext", "text", "uniqueidentifier", "varbinary", "binary", "image", "date",
        "time", "datetime", "datetime2", "smalldatetime", "datetimeoffset", "xml",
    )

    private fun unknownTypeNote(columnName: String, typeName: String): SchemaReadNote? =
        if (typeName.lowercase() in KNOWN_TYPES) {
            null
        } else {
            SchemaReadNote(
                severity = SchemaReadSeverity.WARNING,
                code = "R301",
                objectName = columnName,
                message = "Unknown MSSQL type '$typeName' mapped to text.",
                hint = "Review the column and adjust the schema manually if needed.",
            )
        }

    /**
     * Parses a `sys.default_constraints.definition` (always wrapped in at
     * least one pair of parentheses, e.g. `((0))`, `('x')`, `(getdate())`)
     * into a neutral [DefaultValue].
     */
    fun parseDefault(raw: String?, type: NeutralType): DefaultValue? {
        if (raw == null) return null
        val value = unwrapOuterParens(raw).trim()
        if (value.isEmpty()) return null

        val stringLiteral = matchStringLiteral(value)
        if (stringLiteral != null) return DefaultValue.StringLiteral(stringLiteral)

        if (type is NeutralType.BooleanType && (value == "0" || value == "1")) {
            return DefaultValue.BooleanLiteral(value == "1")
        }

        value.toLongOrNull()?.let { return DefaultValue.NumberLiteral(it) }
        value.toDoubleOrNull()?.let { return DefaultValue.NumberLiteral(it) }

        NEXT_VALUE_FOR.find(value)?.let { match ->
            return DefaultValue.SequenceNextVal(sequenceNameOf(match.groupValues[1]))
        }

        // getdate()/sysdatetime() sind die T-SQL-Spellings des neutralen
        // current_timestamp — kleingeschrieben kanonisieren, wie es die
        // MySQL-/PG-Reverse-Parser tun (Compare/Fingerprint vergleichen
        // case-sensitiv).
        return when (value.lowercase()) {
            "getdate()", "sysdatetime()", "current_timestamp" ->
                DefaultValue.FunctionCall("current_timestamp")
            else -> DefaultValue.FunctionCall(value)
        }
    }

    // Letztes Segment einer ggf. schema-qualifizierten, ggf. eckig
    // geklammerten Referenz; Punkte innerhalb von [..] trennen nicht und
    // `]]` ist das Escape fuer `]`.
    private fun sequenceNameOf(reference: String): String {
        val text = reference.trim()
        val segments = mutableListOf(StringBuilder())
        var inBrackets = false
        var index = 0
        while (index < text.length) {
            val ch = text[index]
            when {
                inBrackets && ch == ']' ->
                    if (index + 1 < text.length && text[index + 1] == ']') {
                        segments.last().append(']')
                        index++
                    } else {
                        inBrackets = false
                    }
                !inBrackets && ch == '[' -> inBrackets = true
                !inBrackets && ch == '.' -> segments += StringBuilder()
                else -> segments.last().append(ch)
            }
            index++
        }
        return segments.last().toString().trim()
    }

    /**
     * Strips balanced outer parenthesis pairs (`((0))` → `0`). A pair is
     * only stripped when the opening parenthesis matches the closing one —
     * `(a) AND (b)` stays untouched.
     */
    fun unwrapOuterParens(raw: String): String {
        var value = raw.trim()
        while (isWrappedInParens(value)) {
            value = value.substring(1, value.length - 1).trim()
        }
        return value
    }

    private fun isWrappedInParens(value: String): Boolean {
        if (value.length < 2 || value.first() != '(') return false
        return value.last() == ')' && coversWhole(value)
    }

    // Klammern innerhalb von T-SQL-String-Literalen ('' = Escape) zaehlen
    // nicht — sonst bliebe z. B. `('(')` fuer immer eingewickelt.
    private fun coversWhole(value: String): Boolean {
        var depth = 0
        var index = 0
        while (index < value.length) {
            when (value[index]) {
                '\'' -> index = endOfStringLiteral(value, index)
                '(' -> {
                    depth++
                    index++
                }
                ')' -> {
                    depth--
                    if (depth == 0 && index < value.length - 1) return false
                    index++
                }
                else -> index++
            }
        }
        return depth == 0
    }

    // Index HINTER dem schliessenden Quote ('' = Escape); Stringende, wenn
    // das Literal unterminiert ist.
    private fun endOfStringLiteral(value: String, openQuote: Int): Int {
        var index = openQuote + 1
        while (index < value.length) {
            index += when {
                value[index] != '\'' -> 1
                index + 1 < value.length && value[index + 1] == '\'' -> 2
                else -> return index + 1
            }
        }
        return index
    }

    // N'..' / '..' mit ''-Escape; alles andere ist kein String-Literal.
    private fun matchStringLiteral(value: String): String? {
        val body = when {
            value.startsWith("N'") && value.endsWith("'") -> value.substring(2, value.length - 1)
            value.startsWith("'") && value.endsWith("'") -> value.substring(1, value.length - 1)
            else -> return null
        }
        // Ein unmaskiertes ' im Rumpf hieße, das Literal endete früher —
        // dann konservativ als Nicht-Literal behandeln.
        if (body.replace("''", "").contains('\'')) return null
        return body.replace("''", "'")
    }

    private val NEXT_VALUE_FOR = Regex("""(?i)^NEXT\s+VALUE\s+FOR\s+(.+)$""")
}
