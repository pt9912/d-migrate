package dev.dmigrate.driver.postgresql

import dev.dmigrate.core.model.*
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.TypeMapper

class PostgresTypeMapper : TypeMapper {
    override val dialect = DatabaseDialect.POSTGRESQL

    override fun toSql(type: NeutralType): String = when (type) {
        is NeutralType.Identifier -> if (type.autoIncrement) "SERIAL" else "INTEGER"
        is NeutralType.Text -> if (type.maxLength != null) "VARCHAR(${type.maxLength})" else "TEXT"
        is NeutralType.Char -> "CHAR(${type.length})"
        is NeutralType.Integer -> "INTEGER"
        is NeutralType.SmallInt -> "SMALLINT"
        is NeutralType.BigInteger -> "BIGINT"
        is NeutralType.Float -> when (type.floatPrecision) {
            FloatPrecision.SINGLE -> "REAL"
            FloatPrecision.DOUBLE -> "DOUBLE PRECISION"
        }
        is NeutralType.Decimal -> "DECIMAL(${type.precision},${type.scale})"
        is NeutralType.BooleanType -> "BOOLEAN"
        is NeutralType.DateTime -> if (type.timezone) "TIMESTAMP WITH TIME ZONE" else "TIMESTAMP"
        is NeutralType.Date -> "DATE"
        is NeutralType.Time -> "TIME"
        is NeutralType.Uuid -> "UUID"
        is NeutralType.Json -> "JSONB"
        is NeutralType.Xml -> "XML"
        is NeutralType.Binary -> "BYTEA"
        is NeutralType.Email -> "VARCHAR(${NeutralType.Email.MAX_LENGTH})"
        is NeutralType.Enum -> "TEXT" // Actual ENUM handled via CREATE TYPE
        is NeutralType.Array -> "${toSql(resolveElementType(type.elementType))}[]"
        is NeutralType.Geometry -> {
            val pgType = GEOMETRY_PG_NAMES[type.geometryType.schemaName]
                ?: type.geometryType.schemaName.replaceFirstChar { it.uppercase() }
            val srid = type.srid ?: 0
            "geometry($pgType, $srid)"
        }
    }

    override fun toDefaultSql(default: DefaultValue, type: NeutralType): String = when (default) {
        is DefaultValue.StringLiteral -> "'${default.value.replace("'", "''")}'"
        is DefaultValue.NumberLiteral -> default.value.toString()
        is DefaultValue.BooleanLiteral -> if (default.value) "TRUE" else "FALSE"
        is DefaultValue.FunctionCall -> when (default.name) {
            "current_timestamp" -> "CURRENT_TIMESTAMP"
            "gen_uuid" -> "gen_random_uuid()"
            else -> "${default.name}()"
        }
        is DefaultValue.SequenceNextVal -> "nextval('${default.sequenceName}')"
    }

    private fun resolveElementType(name: String): NeutralType = when (name) {
        "text" -> NeutralType.Text()
        "integer" -> NeutralType.Integer
        "boolean" -> NeutralType.BooleanType
        "uuid" -> NeutralType.Uuid
        else -> NeutralType.Text()
    }

    companion object {
        /** Tabellarische Geometry-Typ-Zuordnung: schemaName → PostGIS PascalCase */
        private val GEOMETRY_PG_NAMES = mapOf(
            "geometry" to "Geometry",
            "point" to "Point",
            "linestring" to "LineString",
            "polygon" to "Polygon",
            "multipoint" to "MultiPoint",
            "multilinestring" to "MultiLineString",
            "multipolygon" to "MultiPolygon",
            "geometrycollection" to "GeometryCollection",
        )
    }
}
