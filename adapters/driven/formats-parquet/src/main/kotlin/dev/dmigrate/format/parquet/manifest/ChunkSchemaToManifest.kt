package dev.dmigrate.format.parquet.manifest

import dev.dmigrate.core.model.FloatPrecision
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.format.data.ChunkColumnSchema
import dev.dmigrate.format.data.ChunkSchema

/**
 * Konvertiert ein [ChunkSchema] (Cut-A Schema-Vertrag) in die
 * AP7 §5.2 `manifest.yaml`-Spaltenrepraesentation.
 *
 * Faelt `sqlTypeName`/`jdbcType`/`precision`/`scale`/`timezone`
 * heute nicht (S3b-Lieferumfang: nur das, was aus dem
 * `ChunkSchema` herleitbar ist). Reichhaltige JDBC-Hints sind
 * AP3-Folge-Erweiterung (AP2 §10).
 */
internal object ChunkSchemaToManifest {

    fun toManifestColumns(schema: ChunkSchema): List<ManifestColumn> =
        schema.columns.map(::toManifestColumn)

    private fun toManifestColumn(column: ChunkColumnSchema): ManifestColumn {
        val neutral = column.neutralType
        return ManifestColumn(
            name = column.name,
            nullable = column.nullable,
            neutralType = toManifestNeutralType(neutral),
            sqlTypeName = null,
            jdbcType = null,
            precision = decimalPrecisionOrNull(neutral),
            scale = decimalScaleOrNull(neutral),
            timezone = dateTimeTimezoneOrNull(neutral),
        )
    }

    private fun toManifestNeutralType(neutral: NeutralType): ManifestNeutralType =
        toNumericNeutralType(neutral)
            ?: toTextLikeNeutralType(neutral)
            ?: toTemporalNeutralType(neutral)
            ?: toStructuredNeutralType(neutral)
            ?: error("Unhandled NeutralType in toManifestNeutralType: $neutral")

    private fun toNumericNeutralType(neutral: NeutralType): ManifestNeutralType? = when (neutral) {
        is NeutralType.BooleanType -> ManifestNeutralType("Boolean")
        is NeutralType.SmallInt -> ManifestNeutralType("SmallInt")
        is NeutralType.Integer -> ManifestNeutralType("Integer")
        is NeutralType.Identifier -> ManifestNeutralType(
            "Identifier",
            mapOf("autoIncrement" to neutral.autoIncrement),
        )
        is NeutralType.BigInteger -> ManifestNeutralType("BigInteger")
        is NeutralType.Float -> ManifestNeutralType(
            "Float",
            mapOf(
                "precision" to when (neutral.floatPrecision) {
                    FloatPrecision.SINGLE -> "SINGLE"
                    FloatPrecision.DOUBLE -> "DOUBLE"
                },
            ),
        )
        is NeutralType.Decimal -> ManifestNeutralType(
            "Decimal",
            mapOf("precision" to neutral.precision, "scale" to neutral.scale),
        )
        else -> null
    }

    private fun toTextLikeNeutralType(neutral: NeutralType): ManifestNeutralType? = when (neutral) {
        is NeutralType.Text -> ManifestNeutralType(
            "Text",
            buildMap { neutral.maxLength?.let { put("maxLength", it) } },
        )
        is NeutralType.Char -> ManifestNeutralType("Char", mapOf("length" to neutral.length))
        is NeutralType.Email -> ManifestNeutralType("Email")
        is NeutralType.Binary -> ManifestNeutralType("Binary")
        is NeutralType.Uuid -> ManifestNeutralType("Uuid")
        is NeutralType.Json -> ManifestNeutralType("Json")
        is NeutralType.Xml -> ManifestNeutralType("Xml")
        else -> null
    }

    private fun toTemporalNeutralType(neutral: NeutralType): ManifestNeutralType? = when (neutral) {
        is NeutralType.Date -> ManifestNeutralType("Date")
        is NeutralType.Time -> ManifestNeutralType("Time")
        is NeutralType.DateTime -> ManifestNeutralType(
            "DateTime",
            buildMap { if (neutral.timezone) put("timezone", "UTC") },
        )
        else -> null
    }

    private fun toStructuredNeutralType(neutral: NeutralType): ManifestNeutralType? = when (neutral) {
        is NeutralType.Enum -> ManifestNeutralType(
            "Enum",
            buildMap {
                neutral.values?.let { put("values", it) }
                neutral.refType?.let { put("refType", it) }
            },
        )
        is NeutralType.Array -> ManifestNeutralType(
            "Array",
            mapOf("element" to mapOf("kind" to neutral.elementType)),
        )
        is NeutralType.Geometry -> ManifestNeutralType(
            "Geometry",
            buildMap {
                put("geometryType", neutral.geometryType.schemaName)
                neutral.srid?.let { put("srid", it) }
            },
        )
        else -> null
    }

    private fun decimalPrecisionOrNull(type: NeutralType): Int? =
        (type as? NeutralType.Decimal)?.precision

    private fun decimalScaleOrNull(type: NeutralType): Int? =
        (type as? NeutralType.Decimal)?.scale

    private fun dateTimeTimezoneOrNull(type: NeutralType): String? =
        (type as? NeutralType.DateTime)?.takeIf { it.timezone }?.let { "UTC" }
}
