package dev.dmigrate.driver.mysql

import dev.dmigrate.core.model.*
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.SqlIdentifiers
import dev.dmigrate.driver.TypeMapper

class MysqlTypeMapper : TypeMapper {
    override val dialect = DatabaseDialect.MYSQL

    // Parametric/decision-bearing types stay here; parameterless literals go to
    // [simpleToSql] to keep each dispatch under the cyclomatic complexity limit.
    override fun toSql(type: NeutralType): String = when (type) {
        is NeutralType.Text -> if (type.maxLength != null) "VARCHAR(${type.maxLength})" else "TEXT"
        is NeutralType.Char -> "CHAR(${type.length})"
        is NeutralType.Float -> when (type.floatPrecision) {
            FloatPrecision.SINGLE -> "FLOAT"
            FloatPrecision.DOUBLE -> "DOUBLE"
        }
        is NeutralType.Decimal -> "DECIMAL(${type.precision},${type.scale})"
        is NeutralType.Email -> "VARCHAR(${NeutralType.Email.MAX_LENGTH})"
        is NeutralType.Enum -> "TEXT" // Actual ENUM handled inline during table generation
        is NeutralType.Geometry -> type.geometryType.schemaName.uppercase()
        else -> simpleToSql(type)
    }

    private fun simpleToSql(type: NeutralType): String = when (type) {
        is NeutralType.Identifier -> "INT NOT NULL AUTO_INCREMENT"
        is NeutralType.Integer -> "INT"
        is NeutralType.SmallInt -> "SMALLINT"
        is NeutralType.BigInteger -> "BIGINT"
        is NeutralType.BooleanType -> "TINYINT(1)"
        is NeutralType.DateTime -> "DATETIME"
        is NeutralType.Date -> "DATE"
        is NeutralType.Time -> "TIME"
        is NeutralType.Uuid -> "CHAR(36)"
        is NeutralType.Json -> "JSON"
        is NeutralType.Xml -> "TEXT"
        is NeutralType.Binary -> "BLOB"
        is NeutralType.Array -> "JSON"
        // ADR 0015: MySQL's full-text search is a FULLTEXT *index* on a regular
        // TEXT/CHAR column, not a precomputed-vector column type. The column
        // degrades to TEXT here; emitting the matching FULLTEXT index is a
        // future cross-dialect slice.
        is NeutralType.FullText -> "TEXT"
        else -> error("simpleToSql called for a parametric NeutralType: $type")
    }

    override fun toDefaultSql(default: DefaultValue, type: NeutralType): String = when (default) {
        is DefaultValue.StringLiteral -> SqlIdentifiers.quoteStringLiteral(default.value, DatabaseDialect.MYSQL)
        is DefaultValue.NumberLiteral -> default.value.toString()
        is DefaultValue.BooleanLiteral -> if (default.value) "1" else "0"
        is DefaultValue.FunctionCall -> when (default.name) {
            "current_timestamp" -> "CURRENT_TIMESTAMP"
            // MySQL allows non-CURRENT_TIMESTAMP function defaults only as a
            // parenthesised expression (8.0.13+); a bare CURRENT_DATE is ERROR 1067.
            "current_date" -> "(CURRENT_DATE)"
            "current_time" -> "(CURRENT_TIME)"
            "gen_uuid" -> "(UUID())"
            else -> "${default.name}()"
        }
        is DefaultValue.SequenceNextVal ->
            error("SequenceNextVal requires helper_table mode (not yet implemented in 6.3)")
    }
}
