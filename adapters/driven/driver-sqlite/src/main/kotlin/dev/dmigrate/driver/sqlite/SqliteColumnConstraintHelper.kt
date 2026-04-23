package dev.dmigrate.driver.sqlite

import dev.dmigrate.core.model.*
import dev.dmigrate.driver.*

internal class SqliteColumnConstraintHelper(
    private val quoteIdentifier: (String) -> String,
    private val typeMapper: TypeMapper,
    private val columnSql: (String, String, ColumnDefinition, SchemaDefinition) -> String,
    private val referentialActionSql: (ReferentialAction) -> String,
) {

    fun generateColumnSql(
        colName: String,
        col: ColumnDefinition,
        schema: SchemaDefinition,
        tableName: String,
        notes: MutableList<TransformationNote>,
        deferredFks: Set<Pair<String, String>> = emptySet()
    ): String {
        val type = col.type

        if (type is NeutralType.Identifier && type.autoIncrement) {
            return generateAutoIncrementColumn(colName, col, type)
        }
        if (type is NeutralType.Enum && type.refType != null) {
            return generateEnumRefTypeColumn(colName, col, type, schema, tableName, deferredFks)
        }
        if (type is NeutralType.Enum && type.values != null) {
            return generateEnumInlineColumn(colName, col, type, tableName, deferredFks)
        }
        if (type is NeutralType.Decimal) {
            notes += TransformationNote(
                type = NoteType.WARNING, code = "W200", objectName = "$tableName.$colName",
                message = "Decimal(${type.precision},${type.scale}) mapped to REAL in SQLite. Precision may be lost.",
                hint = "Store as TEXT if exact decimal precision is required."
            )
        }
        return generateDefaultColumn(colName, col, schema, tableName, deferredFks)
    }

    private fun generateAutoIncrementColumn(colName: String, col: ColumnDefinition, type: NeutralType): String {
        val parts = mutableListOf(quoteIdentifier(colName), typeMapper.toSql(type))
        if (col.default != null) parts += "DEFAULT ${typeMapper.toDefaultSql(col.default!!, type)}"
        if (col.unique) parts += "UNIQUE"
        return parts.joinToString(" ")
    }

    private fun generateEnumRefTypeColumn(
        colName: String, col: ColumnDefinition, type: NeutralType.Enum,
        schema: SchemaDefinition, tableName: String, deferredFks: Set<Pair<String, String>>,
    ): String {
        val customType = schema.customTypes[type.refType]
        val parts = mutableListOf(quoteIdentifier(colName), "TEXT")
        if (col.required) parts += "NOT NULL"
        if (col.default != null) parts += "DEFAULT ${typeMapper.toDefaultSql(col.default!!, type)}"
        if (col.unique) parts += "UNIQUE"
        if (customType != null && customType.kind == CustomTypeKind.ENUM && customType.values != null) {
            val allowed = customType.values!!.joinToString(", ") { "'${it.replace("'", "''")}'" }
            parts += "CHECK (${quoteIdentifier(colName)} IN ($allowed))"
        }
        if (col.references != null && (tableName to colName) !in deferredFks) parts += inlineForeignKey(col.references!!)
        return parts.joinToString(" ")
    }

    private fun generateEnumInlineColumn(
        colName: String, col: ColumnDefinition, type: NeutralType.Enum,
        tableName: String, deferredFks: Set<Pair<String, String>>,
    ): String {
        val parts = mutableListOf(quoteIdentifier(colName), "TEXT")
        if (col.required) parts += "NOT NULL"
        if (col.default != null) parts += "DEFAULT ${typeMapper.toDefaultSql(col.default!!, type)}"
        if (col.unique) parts += "UNIQUE"
        val allowed = type.values!!.joinToString(", ") { "'${it.replace("'", "''")}'" }
        parts += "CHECK (${quoteIdentifier(colName)} IN ($allowed))"
        if (col.references != null && (tableName to colName) !in deferredFks) parts += inlineForeignKey(col.references!!)
        return parts.joinToString(" ")
    }

    private fun generateDefaultColumn(
        colName: String, col: ColumnDefinition, schema: SchemaDefinition,
        tableName: String, deferredFks: Set<Pair<String, String>>,
    ): String {
        val baseSql = columnSql(tableName, colName, col, schema)
        return if (col.references != null && (tableName to colName) !in deferredFks) {
            "$baseSql ${inlineForeignKey(col.references!!)}"
        } else baseSql
    }

    private fun inlineForeignKey(ref: ReferenceDefinition): String {
        val sql = buildString {
            append("REFERENCES ${quoteIdentifier(ref.table)}(${quoteIdentifier(ref.column)})")
            if (ref.onDelete != null) append(" ON DELETE ${referentialActionSql(ref.onDelete!!)}")
            if (ref.onUpdate != null) append(" ON UPDATE ${referentialActionSql(ref.onUpdate!!)}")
        }
        return sql
    }

    fun generateConstraintClause(
        constraint: ConstraintDefinition,
        notes: MutableList<TransformationNote>
    ): String {
        return when (constraint.type) {
            ConstraintType.CHECK -> {
                "CONSTRAINT ${quoteIdentifier(constraint.name)} CHECK (${constraint.expression})"
            }
            ConstraintType.UNIQUE -> {
                val cols = constraint.columns?.joinToString(", ") { quoteIdentifier(it) } ?: ""
                "CONSTRAINT ${quoteIdentifier(constraint.name)} UNIQUE ($cols)"
            }
            ConstraintType.EXCLUDE -> {
                // EXCLUDE constraints are not supported in SQLite
                notes += TransformationNote(
                    type = NoteType.ACTION_REQUIRED,
                    code = "E054",
                    objectName = constraint.name,
                    message = "EXCLUDE constraint '${constraint.name}' is not supported in SQLite.",
                    hint = "Enforce exclusion logic at the application level or use triggers."
                )
                "-- EXCLUDE constraint ${quoteIdentifier(constraint.name)} is not supported in SQLite"
            }
            ConstraintType.FOREIGN_KEY -> {
                val ref = constraint.references!!
                val fromCols = constraint.columns?.joinToString(", ") { quoteIdentifier(it) } ?: ""
                val toCols = ref.columns.joinToString(", ") { quoteIdentifier(it) }
                buildString {
                    append("CONSTRAINT ${quoteIdentifier(constraint.name)} ")
                    append("FOREIGN KEY ($fromCols) ")
                    append("REFERENCES ${quoteIdentifier(ref.table)} ($toCols)")
                    if (ref.onDelete != null) append(" ON DELETE ${referentialActionSql(ref.onDelete!!)}")
                    if (ref.onUpdate != null) append(" ON UPDATE ${referentialActionSql(ref.onUpdate!!)}")
                }
            }
        }
    }
}
