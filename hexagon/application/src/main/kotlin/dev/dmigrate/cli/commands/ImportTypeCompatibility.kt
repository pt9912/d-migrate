package dev.dmigrate.cli.commands

import dev.dmigrate.core.model.GeometryType
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.driver.data.JdbcTypeCodes
import dev.dmigrate.driver.data.TargetColumn

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
        val jdbcType = targetColumn.jdbcType
        return when (schemaType) {
            is NeutralType.Identifier -> isIdentifierCompatible(jdbcType)
            is NeutralType.Text,
            is NeutralType.Email,
            is NeutralType.Char -> isTextFamilyCompatible(schemaType, jdbcType, sqlTypeName)
            NeutralType.Integer,
            NeutralType.SmallInt,
            NeutralType.BigInteger,
            is NeutralType.Float,
            is NeutralType.Decimal,
            NeutralType.BooleanType -> isNumericFamilyCompatible(schemaType, jdbcType, sqlTypeName)
            is NeutralType.DateTime,
            NeutralType.Date,
            NeutralType.Time -> isTemporalFamilyCompatible(schemaType, jdbcType)
            NeutralType.Uuid,
            NeutralType.Json,
            NeutralType.Xml,
            NeutralType.Binary -> isStructuredFamilyCompatible(schemaType, jdbcType, sqlTypeName)
            is NeutralType.Enum -> isEnumCompatible(schemaType, jdbcType, sqlTypeName)
            is NeutralType.Array -> jdbcType == JdbcTypeCodes.ARRAY || sqlTypeName.endsWith("[]")
            is NeutralType.Geometry -> isGeometryCompatible(sqlTypeName)
            is NeutralType.FullText -> true
        }
    }

    private fun isIdentifierCompatible(jdbcType: Int): Boolean =
        jdbcType in setOf(JdbcTypeCodes.SMALLINT, JdbcTypeCodes.INTEGER, JdbcTypeCodes.BIGINT, JdbcTypeCodes.NUMERIC, JdbcTypeCodes.DECIMAL)

    private fun isTextFamilyCompatible(
        schemaType: NeutralType,
        jdbcType: Int,
        sqlTypeName: String,
    ): Boolean = when (schemaType) {
        is NeutralType.Char -> jdbcType in setOf(JdbcTypeCodes.CHAR, JdbcTypeCodes.NCHAR)
        else -> isTextCompatible(jdbcType, sqlTypeName)
    }

    private fun isTextCompatible(jdbcType: Int, sqlTypeName: String): Boolean =
        jdbcType in setOf(
            JdbcTypeCodes.CHAR,
            JdbcTypeCodes.VARCHAR,
            JdbcTypeCodes.LONGVARCHAR,
            JdbcTypeCodes.NCHAR,
            JdbcTypeCodes.NVARCHAR,
            JdbcTypeCodes.LONGNVARCHAR,
            JdbcTypeCodes.CLOB,
        ) || sqlTypeName.contains("TEXT")

    private fun isNumericFamilyCompatible(
        schemaType: NeutralType,
        jdbcType: Int,
        sqlTypeName: String,
    ): Boolean = when (schemaType) {
        NeutralType.Integer -> jdbcType == JdbcTypeCodes.INTEGER || sqlTypeName == "INT4"
        NeutralType.SmallInt -> jdbcType == JdbcTypeCodes.SMALLINT || sqlTypeName == "INT2"
        NeutralType.BigInteger -> jdbcType == JdbcTypeCodes.BIGINT || sqlTypeName == "INT8"
        is NeutralType.Float -> isFloatCompatible(schemaType, jdbcType)
        is NeutralType.Decimal -> jdbcType in setOf(JdbcTypeCodes.DECIMAL, JdbcTypeCodes.NUMERIC)
        NeutralType.BooleanType -> isBooleanCompatible(jdbcType, sqlTypeName)
        else -> false
    }

    private fun isFloatCompatible(type: NeutralType.Float, jdbcType: Int): Boolean =
        if (type.floatPrecision.name == "SINGLE") jdbcType in setOf(JdbcTypeCodes.REAL, JdbcTypeCodes.FLOAT)
        else jdbcType in setOf(JdbcTypeCodes.DOUBLE, JdbcTypeCodes.FLOAT, JdbcTypeCodes.REAL)

    private fun isBooleanCompatible(jdbcType: Int, sqlTypeName: String): Boolean =
        jdbcType == JdbcTypeCodes.BOOLEAN || (jdbcType == JdbcTypeCodes.BIT && !isMultiBit(sqlTypeName))

    private fun isTemporalFamilyCompatible(
        schemaType: NeutralType,
        jdbcType: Int,
    ): Boolean = when (schemaType) {
        is NeutralType.DateTime ->
            jdbcType in setOf(JdbcTypeCodes.TIMESTAMP, JdbcTypeCodes.TIMESTAMP_WITH_TIMEZONE)
        NeutralType.Date -> jdbcType == JdbcTypeCodes.DATE
        NeutralType.Time -> jdbcType in setOf(JdbcTypeCodes.TIME, JdbcTypeCodes.TIME_WITH_TIMEZONE)
        else -> false
    }

    private fun isStructuredFamilyCompatible(
        schemaType: NeutralType,
        jdbcType: Int,
        sqlTypeName: String,
    ): Boolean = when (schemaType) {
        NeutralType.Uuid ->
            sqlTypeName == "UUID" || jdbcType in setOf(JdbcTypeCodes.CHAR, JdbcTypeCodes.VARCHAR)
        NeutralType.Json -> isJsonCompatible(jdbcType, sqlTypeName)
        NeutralType.Xml -> isXmlCompatible(jdbcType, sqlTypeName)
        NeutralType.Binary ->
            jdbcType in setOf(
                JdbcTypeCodes.BINARY,
                JdbcTypeCodes.VARBINARY,
                JdbcTypeCodes.LONGVARBINARY,
                JdbcTypeCodes.BLOB,
            )
        else -> false
    }

    private fun isJsonCompatible(jdbcType: Int, sqlTypeName: String): Boolean =
        sqlTypeName in setOf("JSON", "JSONB") ||
            jdbcType in setOf(JdbcTypeCodes.VARCHAR, JdbcTypeCodes.LONGVARCHAR, JdbcTypeCodes.CLOB)

    private fun isXmlCompatible(jdbcType: Int, sqlTypeName: String): Boolean =
        jdbcType == JdbcTypeCodes.SQLXML ||
            sqlTypeName == "XML" ||
            jdbcType in setOf(JdbcTypeCodes.VARCHAR, JdbcTypeCodes.LONGVARCHAR, JdbcTypeCodes.CLOB)

    /**
     * VA1d (Spatial-Slice): eine Geometrie-Quellspalte ist kompatibel **nur** mit
     * einem Geometrie-Ziel (typeName in [GeometryType.KNOWN_VALUES] — der
     * verlustfreie WKB-Round-Trip via VA1b/VA1c). Andere Ziele sind inkompatibel —
     * vorher winkte `Geometry -> true` jedes Ziel durch (False-Green).
     * **Bewusst NICHT** Geometry→Text: der Wertpfad (VA1b) liefert WKB-`byte[]`,
     * kein WKT; ein WKB-`byte[]` in eine Text-Spalte ergäbe Binärmüll. Ein klarer
     * Preflight-Fehler ist ehrlicher als stiller Datenmüll. (Eine echte WKT-
     * Degradation für Text-Ziele bräuchte einen ziel-bewussten Read-Pfad — eigene
     * Folgearbeit, nicht VA1.)
     */
    private fun isGeometryCompatible(sqlTypeName: String): Boolean =
        sqlTypeName.lowercase() in GeometryType.KNOWN_VALUES

    private fun isEnumCompatible(type: NeutralType.Enum, jdbcType: Int, sqlTypeName: String): Boolean {
        val ref = type.refType?.uppercase()
        return sqlTypeName == "ENUM" ||
            jdbcType in setOf(JdbcTypeCodes.CHAR, JdbcTypeCodes.VARCHAR, JdbcTypeCodes.NCHAR, JdbcTypeCodes.NVARCHAR) ||
            (
                jdbcType == JdbcTypeCodes.OTHER &&
                    sqlTypeName.isNotEmpty() &&
                    sqlTypeName !in WELL_KNOWN_OTHER_TYPE_NAMES &&
                    (ref == null || sqlTypeName == ref)
                )
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
        is NeutralType.FullText -> "text-compatible type"
    }
}
