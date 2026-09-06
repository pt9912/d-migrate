package dev.dmigrate.driver.oracle

import dev.dmigrate.core.model.ColumnGeneration
import dev.dmigrate.core.model.DefaultValue
import dev.dmigrate.core.model.FloatPrecision
import dev.dmigrate.core.model.IdentityMode
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.driver.SchemaReadNote
import dev.dmigrate.driver.SchemaReadSeverity

/**
 * Oracle-Typ → NeutralType-Mapping plus Default-Wert-Parsing für den
 * Oracle-Reverse-Read.
 *
 * Mapping-Entscheidungen (docs/planning/in-progress/oracle-dialect-scoping.md):
 * `NUMBER(p,0)` faltet je nach Präzision auf `SmallInt`/`Integer`/`BigInteger`;
 * eine bloße `NUMBER` (kein Precision/Scale) faellt konservativ auf
 * `Decimal(38, 10)` -- Oracles Maximalpraezision mit Skalen-Spielraum, da eine
 * ungebundene NUMBER sowohl Ganzzahlen als auch Bruchzahlen tragen kann.
 * `VARCHAR2`/`CHAR` laengen sind bereits Zeichenlaengen (kein Byte/Unicode-
 * Split wie bei MSSQL, da `data_length` in Zeichen gemeldet wird, wenn die
 * Spalte mit `NLS_LENGTH_SEMANTICS=CHAR` angelegt wurde -- Byte-Semantik ist
 * der DB-Default und wird hier als Naeherung uebernommen).
 */
internal object OracleTypeMapping {

    data class ColumnInput(
        val typeName: String,
        val length: Int?,
        val precision: Int?,
        val scale: Int?,
        val isIdentity: Boolean,
        val identityGeneration: String?,
        val identitySequenceName: String?,
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

    private fun mapIdentity(input: ColumnInput): MappingResult {
        val mode = if (input.identityGeneration == "ALWAYS") IdentityMode.ALWAYS else IdentityMode.BY_DEFAULT
        val generation = ColumnGeneration.Identity(mode = mode, sequenceName = input.identitySequenceName)
        val type = when {
            input.scale != null && input.scale > 0 -> NeutralType.Decimal(input.precision ?: 38, input.scale)
            input.precision == null -> NeutralType.BigInteger
            input.precision <= 9 -> NeutralType.Integer
            input.precision <= 18 -> NeutralType.BigInteger
            else -> NeutralType.Decimal(input.precision, 0)
        }
        return MappingResult(type = type, generation = generation, note = null)
    }

    private fun mapBaseType(input: ColumnInput): NeutralType =
        mapNumeric(input) ?: mapTextual(input) ?: mapTemporal(input.typeName) ?: mapOpaque(input.typeName)
            ?: NeutralType.Text(maxLength = null)

    private fun mapNumeric(input: ColumnInput): NeutralType? {
        val typeName = input.typeName.uppercase()
        return when {
            typeName == "FLOAT" || typeName == "BINARY_DOUBLE" -> NeutralType.Float(FloatPrecision.DOUBLE)
            typeName == "BINARY_FLOAT" -> NeutralType.Float(FloatPrecision.SINGLE)
            typeName == "NUMBER" -> mapNumberPrecision(input.precision, input.scale)
            else -> null
        }
    }

    private fun mapNumberPrecision(precision: Int?, scale: Int?): NeutralType = when {
        precision == null -> NeutralType.Decimal(38, 10)
        scale != null && scale > 0 -> NeutralType.Decimal(precision, scale)
        // NUMBER(1) ist Oracles Boolean-Konvention (0/1).
        precision == 1 -> NeutralType.BooleanType
        precision <= 4 -> NeutralType.SmallInt
        precision <= 9 -> NeutralType.Integer
        precision <= 18 -> NeutralType.BigInteger
        else -> NeutralType.Decimal(precision, scale ?: 0)
    }

    private fun mapTextual(input: ColumnInput): NeutralType? = when (input.typeName.uppercase()) {
        "VARCHAR2", "NVARCHAR2" -> NeutralType.Text(maxLength = input.length)
        "CHAR", "NCHAR" -> NeutralType.Char(length = input.length ?: 1)
        "CLOB", "NCLOB", "LONG" -> NeutralType.Text(maxLength = null)
        else -> null
    }

    private fun mapTemporal(typeName: String): NeutralType? = when {
        typeName.equals("DATE", ignoreCase = true) ->
            // Oracle DATE traegt eine Uhrzeit-Komponente -- anders als der
            // SQL-Standard-DATE-Typ. Reverse faltet auf DateTime statt Date,
            // um die Uhrzeit nicht stillschweigend zu verlieren.
            NeutralType.DateTime(timezone = false)
        typeName.startsWith("TIMESTAMP", ignoreCase = true) && typeName.contains("TIME ZONE", ignoreCase = true) ->
            NeutralType.DateTime(timezone = true)
        typeName.startsWith("TIMESTAMP", ignoreCase = true) -> NeutralType.DateTime(timezone = false)
        else -> null
    }

    private fun mapOpaque(typeName: String): NeutralType? = when (typeName.uppercase()) {
        "RAW", "LONG RAW", "BLOB" -> NeutralType.Binary
        // Native Oracle-21c+-Typen (OracleTypeMapper.simpleToSql rendert Json/Xml
        // dorthin); ohne diese Zweige fielen beide auf den generischen
        // Text(maxLength=null)-Fallback zurueck -- ein echter Fidelity-Verlust,
        // nicht nur eine Kanonisierungs-Feinheit (Slice 4 beim NeutralType-
        // Canonicalizer-Bau gefunden).
        "JSON" -> NeutralType.Json
        "XMLTYPE" -> NeutralType.Xml
        else -> null
    }

    private val KNOWN_TYPES = setOf(
        "NUMBER", "FLOAT", "BINARY_DOUBLE", "BINARY_FLOAT", "VARCHAR2", "NVARCHAR2",
        "CHAR", "NCHAR", "CLOB", "NCLOB", "LONG", "DATE", "RAW", "LONG RAW", "BLOB",
        "JSON", "XMLTYPE",
    )

    private fun unknownTypeNote(columnName: String, typeName: String): SchemaReadNote? {
        val normalized = typeName.uppercase()
        val known = normalized in KNOWN_TYPES ||
            normalized.startsWith("TIMESTAMP")
        return if (known) {
            null
        } else {
            SchemaReadNote(
                severity = SchemaReadSeverity.WARNING,
                code = "R301",
                objectName = columnName,
                message = "Unknown Oracle type '$typeName' mapped to text.",
                hint = "Review the column and adjust the schema manually if needed.",
            )
        }
    }

    /**
     * Parst `all_tab_columns.data_default` (roher Ausdruckstext, z.B.
     * `0`, `'x'`, `sysdate`, `myseq.nextval`) in einen neutralen
     * [DefaultValue].
     */
    fun parseDefault(raw: String?, type: NeutralType): DefaultValue? {
        if (raw == null) return null
        val value = raw.trim().removeSuffix(",").trim()
        if (value.isEmpty()) return null

        val stringLiteral = matchStringLiteral(value)
        if (stringLiteral != null) return DefaultValue.StringLiteral(stringLiteral)

        if (type is NeutralType.BooleanType && (value == "0" || value == "1")) {
            return DefaultValue.BooleanLiteral(value == "1")
        }

        value.toLongOrNull()?.let { return DefaultValue.NumberLiteral(it) }
        value.toDoubleOrNull()?.let { return DefaultValue.NumberLiteral(it) }

        NEXT_VALUE_FOR.find(value)?.let { match ->
            return DefaultValue.SequenceNextVal(match.groupValues[1].trim().removeSurrounding("\"", "\""))
        }

        // Generate-Gegenstuecke aus OracleTypeMapper.functionDefaultSql --
        // ohne diese Erkennung wuerde ein schema-generate/schema-reverse-
        // Rundgang die neutrale Default-Bedeutung verlieren (opaker
        // FunctionCall statt gen_uuid/current_date/current_time).
        if (GEN_UUID.matches(value)) return DefaultValue.FunctionCall("gen_uuid")
        if (CURRENT_DATE.matches(value)) return DefaultValue.FunctionCall("current_date")
        if (CURRENT_TIME.matches(value)) return DefaultValue.FunctionCall("current_time")

        // sysdate/systimestamp/current_timestamp sind Oracle-Spellings des
        // neutralen CURRENT_TIMESTAMP -- kanonisieren fuer Cross-Dialekt-
        // Portabilitaet.
        return when (value.lowercase()) {
            "sysdate", "systimestamp", "current_timestamp" ->
                DefaultValue.FunctionCall("current_timestamp")
            else -> DefaultValue.FunctionCall(value)
        }
    }

    // '..' mit ''-Escape; Oracle kennt kein N'..'-Praefix wie T-SQL.
    private fun matchStringLiteral(value: String): String? {
        if (!(value.startsWith("'") && value.endsWith("'") && value.length >= 2)) return null
        val body = value.substring(1, value.length - 1)
        if (body.replace("''", "").contains('\'')) return null
        return body.replace("''", "'")
    }

    // <schema>.<sequence>.NEXTVAL oder <sequence>.NEXTVAL
    private val NEXT_VALUE_FOR = Regex("""(?i)^"?(?:[A-Za-z0-9_$#]+"?\.)?"?([A-Za-z0-9_$#]+)"?\.NEXTVAL$""")

    private val GEN_UUID = Regex("""(?i)^RAWTOHEX\s*\(\s*SYS_GUID\s*\(\s*\)\s*\)$""")
    private val CURRENT_DATE = Regex("""(?i)^TRUNC\s*\(\s*SYSDATE\s*\)$""")
    private val CURRENT_TIME = Regex("""(?i)^TO_CHAR\s*\(\s*SYSDATE\s*,\s*'HH24:MI:SS'\s*\)$""")
}
