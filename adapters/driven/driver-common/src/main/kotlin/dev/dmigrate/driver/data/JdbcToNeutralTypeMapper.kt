package dev.dmigrate.driver.data

import dev.dmigrate.core.model.FloatPrecision
import dev.dmigrate.core.model.NeutralType
import java.sql.Types

/**
 * Mappt JDBC-Spaltentypen auf `NeutralType` per AP2 §8
 * Mapping-Tabelle (`docs/planning/done-archive/parquet-schema-source.md`).
 *
 * Reine Funktion ohne Seiteneffekt. Eingaben sind die vier
 * Felder, die `ResultSetMetaData` pro Spalte liefert:
 * `getColumnType(i)` (JDBC-Typcode), `getColumnTypeName(i)`
 * (dialektspezifischer Name), `getPrecision(i)` und
 * `getScale(i)`. Optionaler [isAutoIncrement] wird vom
 * Aufrufer aus `metadata.isAutoIncrement(i)` gefuettert und
 * loest die `Identifier(autoIncrement=true)`-Sonderregel
 * (AP2 §8 letzte Zeile) aus.
 *
 * Unbekannte JDBC-Typen werden konservativ auf
 * [NeutralType.Text] gemappt; AP3 erweitert die Tabelle, wenn
 * Golden-Roundtrips Luecken aufzeigen (AP2 §10).
 */
internal object JdbcToNeutralTypeMapper {

    fun map(
        jdbcType: Int,
        sqlTypeName: String?,
        precision: Int?,
        scale: Int?,
        isAutoIncrement: Boolean = false,
    ): NeutralType {
        // AP2 §8 letzte Zeile: autoIncrement-Identifier
        // ueberschreibt den INTEGER-Default.
        if (isAutoIncrement && jdbcType == Types.INTEGER) {
            return NeutralType.Identifier(autoIncrement = true)
        }

        val typeNameLower = sqlTypeName?.lowercase()

        // Geometrie wird NICHT hier (dialekt-blind) erkannt: native PG-Typen
        // (point/polygon/line/…) heißen genauso wie OGC-Geometrie-Subtypen, sind
        // aber kein WKB. Die Geometrie-Markierung kommt dialekt-bewusst aus der
        // Metadaten-Vorabfrage (probedColumns, VA1b) und überschreibt das Mapping
        // im ChunkSchema (JdbcChunkSequence). Hier nur das reine JDBC-Mapping.
        return mapByJdbcType(jdbcType, typeNameLower, precision, scale)
    }

    private fun mapByJdbcType(
        jdbcType: Int,
        typeNameLower: String?,
        precision: Int?,
        scale: Int?,
    ): NeutralType = when (jdbcType) {
        Types.BIT, Types.BOOLEAN -> NeutralType.BooleanType
        Types.TINYINT, Types.SMALLINT -> NeutralType.SmallInt
        Types.INTEGER -> NeutralType.Integer
        Types.BIGINT -> NeutralType.BigInteger
        Types.REAL -> NeutralType.Float(FloatPrecision.SINGLE)
        Types.FLOAT, Types.DOUBLE -> NeutralType.Float(FloatPrecision.DOUBLE)
        Types.DECIMAL, Types.NUMERIC -> NeutralType.Decimal(
            precision = precision ?: DEFAULT_DECIMAL_PRECISION,
            scale = scale ?: 0,
        )
        Types.CHAR -> NeutralType.Char(length = precision ?: 1)
        Types.VARCHAR, Types.LONGVARCHAR, Types.NVARCHAR, Types.LONGNVARCHAR ->
            NeutralType.Text(maxLength = precision?.takeIf { it > 0 })
        Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY -> NeutralType.Binary
        Types.DATE -> NeutralType.Date
        Types.TIME, Types.TIME_WITH_TIMEZONE -> NeutralType.Time
        Types.TIMESTAMP -> NeutralType.DateTime(timezone = false)
        Types.TIMESTAMP_WITH_TIMEZONE -> NeutralType.DateTime(timezone = true)
        Types.ARRAY -> NeutralType.Array(elementType = typeNameLower ?: "unknown")
        Types.OTHER -> mapOther(typeNameLower)
        else -> NeutralType.Text(maxLength = null) // konservativer Fallback
    }

    private fun mapOther(typeNameLower: String?): NeutralType = when (typeNameLower) {
        null -> NeutralType.Text(maxLength = null)
        "uuid" -> NeutralType.Uuid
        "json", "jsonb" -> NeutralType.Json
        "xml" -> NeutralType.Xml
        // Geometrie erreicht diesen Zweig zwar, wird aber danach ueberschrieben:
        // die Markierung kommt aus der Metadaten-Vorabfrage des Lesepfads und
        // ersetzt den Typ im `ChunkSchema`. Ein Dialekt ohne diese Vorabfrage
        // liest Geometrie deshalb als Text.
        else -> NeutralType.Text(maxLength = null)
    }

    private const val DEFAULT_DECIMAL_PRECISION = 38
}
