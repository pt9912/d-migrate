package dev.dmigrate.driver.sqlite

import dev.dmigrate.core.model.*
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.TypeMapper

class SqliteTypeMapper : TypeMapper {
    override val dialect = DatabaseDialect.SQLITE

    override fun toSql(type: NeutralType): String = when (type) {
        is NeutralType.Identifier -> "INTEGER PRIMARY KEY AUTOINCREMENT"
        is NeutralType.Text -> "TEXT"
        is NeutralType.Char -> "TEXT"
        is NeutralType.Integer -> "INTEGER"
        is NeutralType.SmallInt -> "INTEGER"
        is NeutralType.BigInteger -> "INTEGER"
        is NeutralType.Float -> "REAL"
        is NeutralType.Decimal -> "REAL"
        is NeutralType.BooleanType -> "INTEGER"
        is NeutralType.DateTime -> "TEXT"
        is NeutralType.Date -> "TEXT"
        is NeutralType.Time -> "TEXT"
        is NeutralType.Uuid -> "TEXT"
        is NeutralType.Json -> "TEXT"
        is NeutralType.Xml -> "TEXT"
        is NeutralType.Binary -> "BLOB"
        is NeutralType.Email -> "TEXT"
        is NeutralType.Enum -> "TEXT" // CHECK constraint added during table generation
        is NeutralType.Array -> "TEXT"
        is NeutralType.Geometry -> "GEOMETRY" // Not used inline; SpatiaLite uses AddGeometryColumn()
        // ADR 0015: SQLite's full-text search is FTS5 — a *virtual table*
        // (`CREATE VIRTUAL TABLE … USING fts5(…)`) that indexes source text,
        // NOT a column type. Bridging a tsvector column to FTS5 is a structural
        // transform (separate virtual table + sync triggers) and belongs to a
        // future cross-dialect slice; at the column level we degrade to TEXT.
        is NeutralType.FullText -> "TEXT"
    }

    override fun toDefaultSql(default: DefaultValue, type: NeutralType): String = when (default) {
        is DefaultValue.StringLiteral -> "'${default.value.replace("'", "''")}'"
        is DefaultValue.NumberLiteral -> default.value.toString()
        is DefaultValue.BooleanLiteral -> if (default.value) "1" else "0"
        is DefaultValue.FunctionCall -> when (default.name) {
            "current_timestamp" -> "(datetime('now'))"
            "current_date" -> "CURRENT_DATE"
            "current_time" -> "CURRENT_TIME"
            "gen_uuid" -> "(" +
                "lower(hex(randomblob(4)))||'-'||" +
                "lower(hex(randomblob(2)))||'-4'||" +
                "substr(lower(hex(randomblob(2))),2)||'-'||" +
                "substr('89ab',abs(random())%4+1,1)||" +
                "substr(lower(hex(randomblob(2))),2)||'-'||" +
                "lower(hex(randomblob(6)))" +
                ")"
            else -> "${default.name}()"
        }
        is DefaultValue.SequenceNextVal ->
            error("SequenceNextVal is not supported for SQLite")
    }
}
