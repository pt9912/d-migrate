package dev.dmigrate.driver.oracle

import dev.dmigrate.core.model.DefaultValue
import dev.dmigrate.core.model.FloatPrecision
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.SqlIdentifiers
import dev.dmigrate.driver.TypeMapper

/**
 * NeutralType → Oracle-DDL (Generate-Richtung, ADR 0052 Slice 2). Symmetrisch
 * zur Reverse-Richtung in [OracleTypeMapping]: `NUMBER(9)`/`NUMBER(4)`/
 * `NUMBER(18)` falten beim Reverse wieder exakt auf `Integer`/`SmallInt`/
 * `BigInteger`, `NUMBER(1)` auf `BooleanType` (Oracles 0/1-Konvention).
 *
 * Oracle-Grenzen: `VARCHAR2`/`CHAR` tragen unter `MAX_STRING_SIZE=STANDARD`
 * (Default, auch `gvenzl/oracle-free`) höchstens 4000 bzw. 2000 Byte, darüber
 * wird auf `CLOB` geweitet ([isWidenedToClob], W145); `NUMBER`-Präzision ist
 * auf 38 begrenzt ([isPrecisionClamped], W148). `JSON`/`XMLTYPE` sind native
 * Oracle-21c+-Typen (kein NVARCHAR(MAX)-Fallback wie bei MSSQL); Array hat
 * keine native Entsprechung und degradiert zu `JSON` (W149).
 */
class OracleTypeMapper : TypeMapper {
    override val dialect = DatabaseDialect.ORACLE

    override fun toSql(type: NeutralType): String = when (type) {
        is NeutralType.Identifier -> "NUMBER($IDENTIFIER_PRECISION)"
        is NeutralType.Text -> textSql(type.maxLength)
        is NeutralType.Char -> charSql(type.length)
        is NeutralType.Float -> when (type.floatPrecision) {
            FloatPrecision.SINGLE -> "BINARY_FLOAT"
            FloatPrecision.DOUBLE -> "BINARY_DOUBLE"
        }
        is NeutralType.Decimal -> decimalSql(type)
        is NeutralType.DateTime -> if (type.timezone) "TIMESTAMP WITH TIME ZONE" else "DATE"
        // Enum-Spalten mit Werten rendert der Spalten-Helfer als begrenzte
        // VARCHAR2 + CHECK; dieser Zweig ist nur der Fallback ohne Werte.
        is NeutralType.Enum -> "VARCHAR2($MAX_VARCHAR2_LENGTH)"
        is NeutralType.Array -> "JSON"
        // Spatial ist fuer Oracle nicht gescoped (SpatialProfile bleibt NONE,
        // canGenerateSpatial() blockt jede Tabelle mit Geometry-Spalten davor)
        // -- dieser Zweig ist derzeit unerreichbar.
        is NeutralType.Geometry -> "SDO_GEOMETRY"
        else -> simpleToSql(type)
    }

    private fun simpleToSql(type: NeutralType): String = when (type) {
        is NeutralType.Integer -> "NUMBER($INTEGER_PRECISION)"
        is NeutralType.SmallInt -> "NUMBER($SMALLINT_PRECISION)"
        is NeutralType.BigInteger -> "NUMBER($BIGINT_PRECISION)"
        is NeutralType.BooleanType -> "NUMBER($BOOLEAN_PRECISION)"
        // Oracle DATE traegt eine Uhrzeitkomponente -- die einzige Entsprechung
        // ohne separaten reinen Datumstyp (W147 am Spalten-Helfer).
        is NeutralType.Date -> "DATE"
        // Kein nativer Zeit-Typ ohne Datum (W146 am Spalten-Helfer).
        is NeutralType.Time -> "VARCHAR2($TIME_TEXT_LENGTH)"
        is NeutralType.Uuid -> "VARCHAR2($UUID_LENGTH)"
        is NeutralType.Json -> "JSON"
        is NeutralType.Xml -> "XMLTYPE"
        is NeutralType.Binary -> "BLOB"
        is NeutralType.Email -> "VARCHAR2(${NeutralType.Email.MAX_LENGTH})"
        // Kein Volltext-Vektortyp -- Degradierung zu CLOB (W132, geteilter Pool).
        is NeutralType.FullText -> "CLOB"
        else -> error("simpleToSql called for a parametric NeutralType: $type")
    }

    private fun textSql(maxLength: Int?): String =
        if (maxLength != null && maxLength <= MAX_VARCHAR2_LENGTH) "VARCHAR2($maxLength)" else "CLOB"

    private fun charSql(length: Int): String =
        if (length <= MAX_CHAR_LENGTH) "CHAR($length)" else "CLOB"

    private fun decimalSql(type: NeutralType.Decimal): String {
        val precision = minOf(type.precision, MAX_NUMBER_PRECISION)
        val scale = minOf(type.scale, precision)
        return "NUMBER($precision,$scale)"
    }

    /**
     * Eine EXPLIZIT deklarierte Text-/Zeichenlaenge ueber dem VARCHAR2/CHAR-
     * Limit landet auf `CLOB` (Warnung noetig). `Text(maxLength = null)`
     * (kein Laengenlimit deklariert) ist dagegen von Anfang an unbegrenzt --
     * dort wurde nichts geweitet, keine Warnung.
     */
    fun isWidenedToClob(type: NeutralType): Boolean = when (type) {
        is NeutralType.Text -> type.maxLength?.let { it > MAX_VARCHAR2_LENGTH } ?: false
        is NeutralType.Char -> type.length > MAX_CHAR_LENGTH
        else -> false
    }

    /**
     * Typen, die als LOB (`CLOB`/`BLOB`) gerendert werden -- in Oracle keine
     * zulaessigen Schluesselspalten. Ein wertloser Enum OHNE `refType`
     * rendert `plainColumn` als `VARCHAR2(4000)` (siehe `toSql`), ist also
     * kein LOB; nur ein `refType` auf eine (moeglicherweise CLOB-foermige)
     * `DOMAIN` bleibt hier konservativ als LOB behandelt, da diese Funktion
     * ohne Schema-Zugriff nicht aufloesen kann, ob die Domain tatsaechlich
     * auf CLOB faellt.
     */
    fun isLargeObject(type: NeutralType): Boolean = when (type) {
        is NeutralType.Text -> type.maxLength?.let { it > MAX_VARCHAR2_LENGTH } ?: true
        is NeutralType.Char -> type.length > MAX_CHAR_LENGTH
        is NeutralType.Binary, is NeutralType.FullText -> true
        is NeutralType.Enum -> type.values == null && type.refType != null
        else -> false
    }

    /** `NUMBER`-Praezision ueber 38 wird auf 38 gekappt. */
    fun isPrecisionClamped(type: NeutralType): Boolean =
        type is NeutralType.Decimal && type.precision > MAX_NUMBER_PRECISION

    override fun toDefaultSql(default: DefaultValue, type: NeutralType): String = when (default) {
        is DefaultValue.StringLiteral -> "'${default.value.replace("'", "''")}'"
        is DefaultValue.NumberLiteral -> default.value.toString()
        is DefaultValue.BooleanLiteral -> if (default.value) "1" else "0"
        is DefaultValue.FunctionCall -> functionDefaultSql(default.name, type)
        is DefaultValue.SequenceNextVal ->
            "${SqlIdentifiers.quoteIdentifier(default.sequenceName, DatabaseDialect.ORACLE)}.NEXTVAL"
    }

    private fun functionDefaultSql(name: String, type: NeutralType): String = when (name.lowercase()) {
        // TIMESTAMP WITH TIME ZONE braucht den zonentragenden Zeitstempel;
        // SYSDATE (= lokale Wanduhr ohne Zone) wuerde die Zone einfrieren.
        "current_timestamp" ->
            if (type is NeutralType.DateTime && type.timezone) "SYSTIMESTAMP" else "SYSDATE"
        "current_date" -> "TRUNC(SYSDATE)"
        "current_time" -> "TO_CHAR(SYSDATE, 'HH24:MI:SS')"
        // SYS_GUID() liefert 32 Hex-Zeichen ohne Bindestriche -- der Spalten-
        // Helfer haengt fuer diesen Fall eine Notiz an (W150).
        "gen_uuid" -> "RAWTOHEX(SYS_GUID())"
        else -> if (name.trimEnd().endsWith(")")) name else "$name()"
    }

    companion object {
        const val IDENTIFIER_PRECISION = 9
        const val INTEGER_PRECISION = 9
        const val SMALLINT_PRECISION = 4
        const val BIGINT_PRECISION = 18
        const val BOOLEAN_PRECISION = 1
        const val MAX_VARCHAR2_LENGTH = 4000
        const val MAX_CHAR_LENGTH = 2000
        const val MAX_NUMBER_PRECISION = 38
        const val UUID_LENGTH = 36
        const val TIME_TEXT_LENGTH = 8

        /**
         * `VARCHAR2`-Breite fuer eine wertebasierte Enum-Spalte -- der
         * laengste Wert, mindestens 1. Einzige Quelle fuer
         * [OracleColumnConstraintHelper] (Generate) und
         * [OracleNeutralTypeCanonicalizer] (Postcompare-Projektion), damit
         * beide dieselbe Spalte meinen.
         */
        fun enumWidth(values: List<String>): Int = maxOf(values.maxOfOrNull { it.length } ?: 1, 1)
    }
}
