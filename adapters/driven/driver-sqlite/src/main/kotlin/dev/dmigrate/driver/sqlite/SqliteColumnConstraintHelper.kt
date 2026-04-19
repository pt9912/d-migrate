package dev.dmigrate.driver.sqlite

import dev.dmigrate.core.model.*
import dev.dmigrate.driver.*

internal class SqliteColumnConstraintHelper(
    private val quoteIdentifier: (String) -> String,
    private val typeMapper: TypeMapper,
    private val columnSql: (String, ColumnDefinition, SchemaDefinition) -> String,
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

        // Identifier type: TypeMapper returns "INTEGER PRIMARY KEY AUTOINCREMENT" (already includes PK)
        if (type is NeutralType.Identifier && type.autoIncrement) {
            val parts = mutableListOf<String>()
            parts += quoteIdentifier(colName)
            parts += typeMapper.toSql(type)
            // NOT NULL is implicit for INTEGER PRIMARY KEY in SQLite
            if (col.default != null) parts += "DEFAULT ${typeMapper.toDefaultSql(col.default!!, type)}"
            if (col.unique) parts += "UNIQUE"
            return parts.joinToString(" ")
        }

        // Enum with ref_type: resolve from custom types and inline CHECK
        if (type is NeutralType.Enum && type.refType != null) {
            val customType = schema.customTypes[type.refType]
            val parts = mutableListOf<String>()
            parts += quoteIdentifier(colName)
            parts += "TEXT"
            if (col.required) parts += "NOT NULL"
            if (col.default != null) parts += "DEFAULT ${typeMapper.toDefaultSql(col.default!!, type)}"
            if (col.unique) parts += "UNIQUE"
            // If the custom type has enum values, add inline CHECK
            if (customType != null && customType.kind == CustomTypeKind.ENUM && customType.values != null) {
                val allowed = customType.values!!.joinToString(", ") { "'${it.replace("'", "''")}'" }
                parts += "CHECK (${quoteIdentifier(colName)} IN ($allowed))"
            }
            // Inline reference if present
            if (col.references != null && (tableName to colName) !in deferredFks) {
                parts += inlineForeignKey(col.references!!)
            }
            return parts.joinToString(" ")
        }

        // Enum with inline values: TEXT + CHECK
        if (type is NeutralType.Enum && type.values != null) {
            val parts = mutableListOf<String>()
            parts += quoteIdentifier(colName)
            parts += "TEXT"
            if (col.required) parts += "NOT NULL"
            if (col.default != null) parts += "DEFAULT ${typeMapper.toDefaultSql(col.default!!, type)}"
            if (col.unique) parts += "UNIQUE"
            val allowed = type.values!!.joinToString(", ") { "'${it.replace("'", "''")}'" }
            parts += "CHECK (${quoteIdentifier(colName)} IN ($allowed))"
            // Inline reference if present
            if (col.references != null && (tableName to colName) !in deferredFks) {
                parts += inlineForeignKey(col.references!!)
            }
            return parts.joinToString(" ")
        }

        // Decimal type: warn about precision loss
        if (type is NeutralType.Decimal) {
            notes += TransformationNote(
                type = NoteType.WARNING,
                code = "W200",
                objectName = "$tableName.$colName",
                message = "Decimal(${type.precision},${type.scale}) mapped to REAL in SQLite. Precision may be lost.",
                hint = "Store as TEXT if exact decimal precision is required."
            )
        }

        // Default path: use base columnSql and then append inline FK if present
        val baseSql = columnSql(colName, col, schema)
        return if (col.references != null && (tableName to colName) !in deferredFks) {
            "$baseSql ${inlineForeignKey(col.references!!)}"
        } else {
            baseSql
        }
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
                    append("CONSTRAINT ${quoteIdentifier(constraint.name)} FOREIGN KEY ($fromCols) REFERENCES ${quoteIdentifier(ref.table)} ($toCols)")
                    if (ref.onDelete != null) append(" ON DELETE ${referentialActionSql(ref.onDelete!!)}")
                    if (ref.onUpdate != null) append(" ON UPDATE ${referentialActionSql(ref.onUpdate!!)}")
                }
            }
        }
    }
}
