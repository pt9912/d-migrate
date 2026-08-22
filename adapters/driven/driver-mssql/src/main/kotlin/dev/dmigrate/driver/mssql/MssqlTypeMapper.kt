package dev.dmigrate.driver.mssql

import dev.dmigrate.core.model.DefaultValue
import dev.dmigrate.core.model.FloatPrecision
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.SqlIdentifiers
import dev.dmigrate.driver.TypeMapper

/**
 * NeutralType → T-SQL (Generate-Richtung, [ADR 0047]). Entscheidungen aus
 * dem Scoping-Plan und `spec/type-mapping.md`: die Text-Familie wird
 * Unicode-sicher als `NVARCHAR`/`NCHAR` gerendert, `boolean` → `BIT`,
 * `datetime(timezone)` → `DATETIMEOFFSET` (sonst `DATETIME2`),
 * `uuid` → `UNIQUEIDENTIFIER`, `binary` → `VARBINARY(MAX)`. JSON, Arrays
 * und Volltext-Vektoren haben keinen nativen Spaltentyp und degradieren zu
 * `NVARCHAR(MAX)` — die zugehörigen Hinweise (W137/W132) hängt der
 * Spalten-Helfer an, der Mapper bleibt eine reine Typfunktion.
 *
 * Grenzen des Zielsystems: `NVARCHAR(n)`/`NCHAR(n)` tragen höchstens 4000
 * Zeichen, darüber wird auf `(MAX)` geweitet ([isWidenedToMax], W136);
 * `DECIMAL` erlaubt höchstens Präzision 38 ([isPrecisionClamped], W139).
 */
class MssqlTypeMapper : TypeMapper {
    override val dialect = DatabaseDialect.MSSQL

    // Parametrische Typen hier, parameterlose Literale in [simpleToSql]
    // (Cyclomatic-Complexity-Schnitt wie bei den anderen Mappern).
    override fun toSql(type: NeutralType): String = when (type) {
        is NeutralType.Identifier -> if (type.autoIncrement) "INT IDENTITY(1,1)" else "INT"
        is NeutralType.Text -> unicodeText(type.maxLength)
        is NeutralType.Char ->
            if (type.length <= MAX_UNICODE_LENGTH) "NCHAR(${type.length})" else "NVARCHAR(MAX)"
        is NeutralType.Float -> when (type.floatPrecision) {
            FloatPrecision.SINGLE -> "REAL"
            FloatPrecision.DOUBLE -> "FLOAT"
        }
        is NeutralType.Decimal -> decimalSql(type)
        is NeutralType.DateTime -> if (type.timezone) "DATETIMEOFFSET" else "DATETIME2"
        is NeutralType.Email -> "NVARCHAR(${NeutralType.Email.MAX_LENGTH})"
        // Enums mit Werten rendert der Spalten-Helfer als begrenzte NVARCHAR +
        // CHECK; ohne auflösbare Werte bleibt nur unbegrenzter Text.
        is NeutralType.Enum -> "NVARCHAR(MAX)"
        // SQL Server trennt planares `geometry` von geodätischem `geography`;
        // Subtyp und SRID bleiben Eigenschaften des Werts (W120 am Spalten-Helfer).
        is NeutralType.Geometry -> spatialTypeSql(type)
        else -> simpleToSql(type)
    }

    private fun simpleToSql(type: NeutralType): String = when (type) {
        is NeutralType.Integer -> "INT"
        is NeutralType.SmallInt -> "SMALLINT"
        is NeutralType.BigInteger -> "BIGINT"
        is NeutralType.BooleanType -> "BIT"
        is NeutralType.Date -> "DATE"
        is NeutralType.Time -> "TIME"
        is NeutralType.Uuid -> "UNIQUEIDENTIFIER"
        is NeutralType.Json -> "NVARCHAR(MAX)"
        is NeutralType.Xml -> "XML"
        is NeutralType.Binary -> "VARBINARY(MAX)"
        is NeutralType.Array -> "NVARCHAR(MAX)"
        // ADR 0015: kein Volltext-Vektortyp in T-SQL; Degradierung zu Text.
        is NeutralType.FullText -> "NVARCHAR(MAX)"
        else -> error("simpleToSql called for a parametric NeutralType: $type")
    }

    /**
     * `geography` für geodätische SRIDs (EPSG-Geographic-Block 4000–4999, z. B.
     * 4326/WGS 84), sonst — auch ohne SRID — planares `geometry`.
     */
    fun spatialTypeSql(type: NeutralType.Geometry): String =
        if (isGeodeticSrid(type.srid)) "geography" else "geometry"

    fun isGeodeticSrid(srid: Int?): Boolean = srid != null && srid in GEODETIC_SRID_RANGE

    /** `NVARCHAR(n)` bis 4000 Zeichen, sonst (und ohne Länge) `NVARCHAR(MAX)`. */
    fun unicodeText(maxLength: Int?): String =
        if (maxLength != null && maxLength <= MAX_UNICODE_LENGTH) "NVARCHAR($maxLength)" else "NVARCHAR(MAX)"

    private fun decimalSql(type: NeutralType.Decimal): String {
        val precision = minOf(type.precision, MAX_DECIMAL_PRECISION)
        val scale = minOf(type.scale, precision)
        return "DECIMAL($precision,$scale)"
    }

    /** Eine deklarierte Text-/Zeichenlänge über 4000 landet auf `NVARCHAR(MAX)` (Länge geht verloren). */
    fun isWidenedToMax(type: NeutralType): Boolean = when (type) {
        is NeutralType.Text -> type.maxLength?.let { it > MAX_UNICODE_LENGTH } ?: false
        is NeutralType.Char -> type.length > MAX_UNICODE_LENGTH
        else -> false
    }

    /**
     * Typen, die als LOB gerendert werden (`NVARCHAR(MAX)`, `VARBINARY(MAX)`,
     * `XML`) — in SQL Server keine zulässigen Schlüssel-/Indexspalten.
     */
    fun isLargeObject(type: NeutralType): Boolean = when (type) {
        is NeutralType.Text -> type.maxLength?.let { it > MAX_UNICODE_LENGTH } ?: true
        is NeutralType.Char -> type.length > MAX_UNICODE_LENGTH
        is NeutralType.Json, is NeutralType.Xml, is NeutralType.Binary, is NeutralType.Array,
        is NeutralType.FullText -> true
        is NeutralType.Enum -> type.values == null
        else -> false
    }

    /** `DECIMAL`-Präzision über 38 wird auf 38 gekappt. */
    fun isPrecisionClamped(type: NeutralType): Boolean =
        type is NeutralType.Decimal && type.precision > MAX_DECIMAL_PRECISION

    override fun toDefaultSql(default: DefaultValue, type: NeutralType): String = when (default) {
        // Unicode-Literal, passend zur NVARCHAR-Familie; der Reverse-Parser
        // liest N'…' wieder als StringLiteral.
        is DefaultValue.StringLiteral -> "N" + SqlIdentifiers.quoteStringLiteral(default.value, DatabaseDialect.MSSQL)
        is DefaultValue.NumberLiteral -> default.value.toString()
        is DefaultValue.BooleanLiteral -> if (default.value) "1" else "0"
        is DefaultValue.FunctionCall -> functionDefaultSql(default.name, type)
        is DefaultValue.SequenceNextVal ->
            "NEXT VALUE FOR ${SqlIdentifiers.quoteIdentifier(default.sequenceName, DatabaseDialect.MSSQL)}"
    }

    private fun functionDefaultSql(name: String, type: NeutralType): String = when (name.lowercase()) {
        // DATETIMEOFFSET braucht den offset-tragenden Zeitstempel; CURRENT_TIMESTAMP
        // (= GETDATE(), lokale Wanduhr) würde mit +00:00 eingefroren.
        "current_timestamp" ->
            if (type is NeutralType.DateTime && type.timezone) "SYSDATETIMEOFFSET()" else "CURRENT_TIMESTAMP"
        "current_date" -> "CAST(GETDATE() AS DATE)"
        "current_time" -> "CAST(GETDATE() AS TIME)"
        "gen_uuid" -> "NEWID()"
        // Der MSSQL-Reverse liefert unbekannte Funktions-Defaults mit
        // Klammern (`sysdatetimeoffset()`); nur nackte Namen bekommen `()`.
        else -> if (name.trimEnd().endsWith(")")) name else "$name()"
    }

    companion object {
        /**
         * Spaltenbreite einer Enum-Spalte: der laengste Wert, mindestens 1.
         * T-SQL kennt keinen Enum-Typ, der Spalten-Helfer rendert stattdessen
         * `NVARCHAR(<Breite>)` + CHECK. Die Regel steht hier und nicht im
         * Helfer, weil auch [MssqlNeutralTypeCanonicalizer] genau die Spalte
         * projizieren muss, die der Generator schreibt.
         */
        fun enumWidth(values: List<String>): Int = maxOf(values.maxOfOrNull { it.length } ?: 1, 1)

        const val MAX_UNICODE_LENGTH = 4000
        const val MAX_DECIMAL_PRECISION = 38

        /** EPSG geographic CRS block (2D/3D geodetic systems, incl. 4326 WGS 84). */
        val GEODETIC_SRID_RANGE: IntRange = 4000..4999

        /** SQL Server's default SRID for `geography` values. */
        const val GEOGRAPHY_DEFAULT_SRID = 4326
    }
}
