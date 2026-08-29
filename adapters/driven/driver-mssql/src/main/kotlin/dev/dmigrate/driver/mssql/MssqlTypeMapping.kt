package dev.dmigrate.driver.mssql

import dev.dmigrate.core.model.ColumnGeneration
import dev.dmigrate.core.model.DefaultValue
import dev.dmigrate.core.model.FloatPrecision
import dev.dmigrate.core.model.FunctionDefaultCompatibility
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
            note = unknownTypeNote(columnName, input.typeName) ?: geographyNote(columnName, input.typeName),
        )
    }

    // `geography` trägt den SRID je Wert; der SQL-Server-Default 4326 (WGS 84)
    // hält den Round-Trip mit der Generate-Regel "geodätischer SRID → geography"
    // stabil und wird als Annahme ausgewiesen.
    private fun geographyNote(columnName: String, typeName: String): SchemaReadNote? =
        if (typeName.equals("geography", ignoreCase = true)) {
            SchemaReadNote(
                severity = SchemaReadSeverity.INFO,
                code = "R345",
                objectName = columnName,
                message = "geography column read as geometry with SRID 4326 (SQL Server default); " +
                    "per-value SRIDs and the geometry subtype are not carried in the catalog.",
            )
        } else {
            null
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

    /**
     * T-SQL-Typname eines Routinen-Parameters auf den neutralen Typnamen.
     *
     * Das neutrale Modell fuehrt Parameter- und Rueckgabetypen als neutrale
     * Namen, nicht als Dialekt-Typen: der kanonische Routinen-Key setzt sich
     * aus ihnen zusammen (`calc(in:integer,in:integer)`,
     * `spec/neutral-model-spec.md` Abschnitt 6.3), und ein nativer Name dort
     * liesse dieselbe Routine je nach Quell-Dialekt unter einem anderen Key
     * stehen. Laengen fallen dabei weg — PostgreSQL und MySQL bilden `varchar`
     * ebenso auf `text` ab.
     */
    fun mapParamType(typeName: String): String = when (typeName.lowercase().trim()) {
        "int" -> "integer"
        "bigint" -> "biginteger"
        "smallint", "tinyint" -> "smallint"
        "bit" -> "boolean"
        "decimal", "numeric", "money", "smallmoney" -> "decimal"
        "float", "real" -> "float"
        "varchar", "nvarchar", "char", "nchar", "text", "ntext", "sysname" -> "text"
        "uniqueidentifier" -> "uuid"
        "varbinary", "binary", "image" -> "binary"
        "date" -> "date"
        "time" -> "time"
        "datetime", "datetime2", "smalldatetime", "datetimeoffset" -> "datetime"
        "geometry", "geography" -> "geometry"
        else -> typeName.lowercase()
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
        // Subtyp und SRID sind in SQL Server Eigenschaften des Werts, nicht
        // der Spalte — der Reverse liest den generischen Geometrietyp; für
        // `geography` gilt der Default-SRID 4326 (R345).
        "geometry" -> NeutralType.Geometry()
        "geography" -> NeutralType.Geometry(srid = MssqlTypeMapper.GEOGRAPHY_DEFAULT_SRID)
        else -> null
    }

    private val KNOWN_TYPES = setOf(
        "int", "bigint", "smallint", "tinyint", "bit", "decimal", "numeric", "money",
        "smallmoney", "float", "real", "nvarchar", "sysname", "varchar", "nchar", "char",
        "ntext", "text", "uniqueidentifier", "varbinary", "binary", "image", "date",
        "time", "datetime", "datetime2", "smalldatetime", "datetimeoffset", "xml",
        "geometry", "geography",
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

        // Die T-SQL-Spellings der vier neutralen Funktions-Defaults zurueck auf
        // den neutralen Namen. Das ist keine Kosmetik: das neutrale Format
        // kennt nur diese vier als Funktion, jeder andere Text wird beim
        // YAML-Round-Trip zum String-Literal. Ein nicht erkannter Default
        // landete deshalb als `DEFAULT 'CONVERT([date],getdate())'` im
        // Zielskript (live am Pagila-Leg gefunden).
        //
        // SQL Server speichert nicht die geschriebene Form, sondern seine
        // eigene: aus `CAST(GETDATE() AS DATE)` wird im Katalog
        // `CONVERT([date],getdate())`. Erkannt werden beide.
        return when (functionKey(value)) {
            "getdate()", "sysdatetime()", "sysdatetimeoffset()", "current_timestamp" ->
                neutralFunctionDefault("current_timestamp", value, type)
            "convert([date],getdate())", "convert(date,getdate())", "cast(getdate() as date)" ->
                neutralFunctionDefault("current_date", value, type)
            "convert([time],getdate())", "convert(time,getdate())", "cast(getdate() as time)" ->
                neutralFunctionDefault("current_time", value, type)
            "newid()" -> neutralFunctionDefault("gen_uuid", value, type)
            else -> DefaultValue.FunctionCall(value)
        }
    }

    /**
     * Kanonisiert nur, wenn der neutrale Name auf DIESEM Spaltentyp zulaessig
     * ist. Sonst waere der Reverse zwar neutraler, produzierte aber ein Schema,
     * das die eigene Validierung mit E009 abweist: `gen_uuid` gilt nur auf
     * `uuid`, ein `CHAR(36) DEFAULT NEWID()` (haeufiges T-SQL-Idiom) wuerde also
     * einen Validierungsfehler statt einer Ungenauigkeit erben. In dem Fall
     * bleibt der Default wortwoertlich stehen.
     */
    private fun neutralFunctionDefault(name: String, raw: String, type: NeutralType): DefaultValue =
        if (FunctionDefaultCompatibility.isCompatible(name, type)) {
            DefaultValue.FunctionCall(name)
        } else {
            DefaultValue.FunctionCall(raw)
        }

    /**
     * Vergleichsform eines Funktions-Defaults: kleingeschrieben, Whitespace
     * zusammengezogen und um Kommas entfernt. Damit fallen die Katalog-Form
     * (`CONVERT([date],getdate())`) und die geschriebene Form
     * (`CONVERT([date], getdate())`) auf denselben Schluessel.
     */
    private fun functionKey(value: String): String =
        value.lowercase().replace(COLLAPSE_WHITESPACE, " ").replace(WHITESPACE_AROUND_COMMA, ",").trim()

    private val LOWERCASE_IDENTIFIER = Regex("""[a-z_][a-z0-9_]*""")
    private val COLLAPSE_WHITESPACE = Regex("""\s+""")
    private val WHITESPACE_AROUND_COMMA = Regex("""\s*,\s*""")

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

    /**
     * Bringt einen `sys.check_constraints.definition`-Ausdruck in die neutrale
     * Form. Der Reverse liefert T-SQL-**Oberflaechensyntax**; im neutralen
     * Modell steht derselbe Ausdruck in dialektfreier Schreibweise:
     *
     * - der Unicode-Literal-Praefix `N'…'` faellt weg — `N` ist Syntax, kein
     *   Wert. Ohne das liest der Validator das `N` als Spaltenbezug und lehnt
     *   jedes reverse-gelesene MSSQL-Schema mit einem String-CHECK mit E012 ab.
     * - Klammer-Quoting `[col]` wird zum unquotierten Namen, bei
     *   quotierungsbeduerftigen Namen zum ANSI-Doppelquote `"col"`. Ohne das
     *   traegt jedes andere Ziel T-SQL-Quoting im CHECK und scheitert an der
     *   Syntax.
     *
     * Beides live am Pagila-Leg gefunden (`[rating]=N'NC-17' OR …`, Slice 4).
     * Der Ausdruck bleibt ansonsten unveraendert — das neutrale Modell
     * transpiliert CHECK-Ausdruecke nicht.
     *
     * Literal-bewusst: ein `N` oder eine Klammer INNERHALB eines Literals
     * (`'ABN'`, `'[x]'`) und ein `N` als Namensbestandteil bleiben unberuehrt.
     */
    fun normalizeCheckExpression(raw: String): String {
        val value = unwrapOuterParens(raw)
        val out = StringBuilder(value.length)
        var index = 0
        while (index < value.length) {
            val char = value[index]
            when {
                char == '\'' -> {
                    val end = endOfStringLiteral(value, index)
                    out.append(value, index, end)
                    index = end
                }
                startsUnicodeLiteral(value, index) -> {
                    val end = endOfStringLiteral(value, index + 1)
                    out.append(value, index + 1, end)
                    index = end
                }
                char == '[' -> {
                    val end = endOfBracketedName(value, index)
                    out.append(neutralIdentifier(value.substring(index + 1, end - 1)))
                    index = end
                }
                else -> {
                    out.append(char)
                    index++
                }
            }
        }
        return out.toString()
    }

    private fun startsUnicodeLiteral(value: String, index: Int): Boolean =
        (value[index] == 'N' || value[index] == 'n') &&
            index + 1 < value.length && value[index + 1] == '\'' &&
            !isIdentifierPart(value.getOrNull(index - 1))

    private fun isIdentifierPart(char: Char?): Boolean =
        char != null && (char.isLetterOrDigit() || char == '_' || char == '@' || char == '#')

    // Index HINTER der schliessenden Klammer (`]]` = Escape); Stringende, wenn
    // die Klammer unterminiert ist.
    private fun endOfBracketedName(value: String, openBracket: Int): Int {
        var index = openBracket + 1
        while (index < value.length) {
            index += when {
                value[index] != ']' -> 1
                index + 1 < value.length && value[index + 1] == ']' -> 2
                else -> return index + 1
            }
        }
        return value.length
    }

    /**
     * `[col]` → `col`, aber NUR bei rein kleingeschriebenen Namen; sonst
     * ANSI-Doppelquote.
     *
     * Die Kleinschreibung ist die Grenze, nicht die blosse Wohlgeformtheit:
     * die Generatoren quoten Spaltennamen immer ([SqlIdentifiers]), eine
     * PascalCase-Spalte steht im PostgreSQL-Ziel also als `"CustomerID"` und
     * ein unquotiertes `CustomerID` im CHECK faltet dort auf `customerid` —
     * `column "customerid" does not exist`. PascalCase ist in SQL Server die
     * Regel, nicht die Ausnahme. Dieselbe Konvention liefert PostgreSQLs
     * eigener Reverse (`pg_get_constraintdef` quotet genau diese Faelle).
     *
     * Bekannte Restluecke: ein kleingeschriebenes reserviertes Wort
     * (`[order]`) bleibt unquotiert — dafuer braeuchte es eine
     * zieldialekt-abhaengige Schluesselwortliste, die es im neutralen Modell
     * nicht gibt.
     */
    private fun neutralIdentifier(bracketed: String): String {
        val name = bracketed.replace("]]", "]")
        return if (LOWERCASE_IDENTIFIER.matches(name)) name else "\"" + name.replace("\"", "\"\"") + "\""
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
