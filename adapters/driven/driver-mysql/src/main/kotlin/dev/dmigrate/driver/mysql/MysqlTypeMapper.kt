package dev.dmigrate.driver.mysql

import dev.dmigrate.core.model.*
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.TypeMapper

class MysqlTypeMapper : TypeMapper {
    override val dialect = DatabaseDialect.MYSQL

    override fun toSql(type: NeutralType): String = when (type) {
        is NeutralType.Identifier -> "INT NOT NULL AUTO_INCREMENT"
        is NeutralType.Text -> if (type.maxLength != null) "VARCHAR(${type.maxLength})" else "TEXT"
        is NeutralType.Char -> "CHAR(${type.length})"
        is NeutralType.Integer -> "INT"
        is NeutralType.SmallInt -> "SMALLINT"
        is NeutralType.BigInteger -> "BIGINT"
        is NeutralType.Float -> when (type.floatPrecision) {
            FloatPrecision.SINGLE -> "FLOAT"
            FloatPrecision.DOUBLE -> "DOUBLE"
        }
        is NeutralType.Decimal -> "DECIMAL(${type.precision},${type.scale})"
        is NeutralType.BooleanType -> "TINYINT(1)"
        is NeutralType.DateTime -> "DATETIME"
        is NeutralType.Date -> "DATE"
        is NeutralType.Time -> "TIME"
        is NeutralType.Uuid -> "CHAR(36)"
        is NeutralType.Json -> "JSON"
        is NeutralType.Xml -> "TEXT"
        is NeutralType.Binary -> "BLOB"
        is NeutralType.Email -> "VARCHAR(${NeutralType.Email.MAX_LENGTH})"
        is NeutralType.Enum -> "TEXT" // Actual ENUM handled inline during table generation
        is NeutralType.Array -> "JSON"
        is NeutralType.Geometry -> type.geometryType.schemaName.uppercase()
    }

    override fun toDefaultSql(default: DefaultValue, type: NeutralType): String = when (default) {
        is DefaultValue.StringLiteral -> "'${default.value.replace("'", "''")}'"
        is DefaultValue.NumberLiteral -> default.value.toString()
        is DefaultValue.BooleanLiteral -> if (default.value) "1" else "0"
        is DefaultValue.FunctionCall -> when (default.name) {
            "current_timestamp" -> "CURRENT_TIMESTAMP"
            "gen_uuid" -> "(UUID())"
            else -> "${default.name}()"
        }
        is DefaultValue.SequenceNextVal ->
            error("SequenceNextVal requires helper_table mode (not yet implemented in 6.3)")
    }
}
