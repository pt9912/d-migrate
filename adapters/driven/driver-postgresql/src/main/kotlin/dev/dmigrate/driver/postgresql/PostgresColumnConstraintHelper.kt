package dev.dmigrate.driver.postgresql

import dev.dmigrate.core.model.*
import dev.dmigrate.driver.*

internal class PostgresColumnConstraintHelper(
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
        notes: MutableList<TransformationNote>
    ): String {
        val type = col.type

        // For Identifier type (SERIAL), skip NOT NULL since SERIAL implies it
        if (type is NeutralType.Identifier && type.autoIncrement) {
            val parts = mutableListOf<String>()
            parts += quoteIdentifier(colName)
            parts += typeMapper.toSql(type)
            if (col.default != null) parts += "DEFAULT ${typeMapper.toDefaultSql(col.default!!, type)}"
            if (col.unique) parts += "UNIQUE"
            return parts.joinToString(" ")
        }

        // For enum columns with ref_type, use the custom type name
        if (type is NeutralType.Enum) {
            val refType = type.refType
            if (refType != null) {
                val parts = mutableListOf<String>()
                parts += quoteIdentifier(colName)
                parts += quoteIdentifier(refType)
                if (col.required) parts += "NOT NULL"
                if (col.default != null) parts += "DEFAULT ${typeMapper.toDefaultSql(col.default!!, type)}"
                if (col.unique) parts += "UNIQUE"
                return parts.joinToString(" ")
            }
        }

        // For enum columns with inline values, use TEXT + CHECK constraint
        if (type is NeutralType.Enum) {
            val enumValues = type.values
            if (enumValues != null) {
                val parts = mutableListOf<String>()
                parts += quoteIdentifier(colName)
                parts += "TEXT"
                if (col.required) parts += "NOT NULL"
                if (col.default != null) parts += "DEFAULT ${typeMapper.toDefaultSql(col.default!!, type)}"
                if (col.unique) parts += "UNIQUE"
                val allowed = enumValues.joinToString(", ") { "'${it.replace("'", "''")}'" }
                parts += "CHECK (${quoteIdentifier(colName)} IN ($allowed))"
                return parts.joinToString(" ")
            }
        }

        // Default: delegate to base class columnSql
        return columnSql(tableName, colName, col, schema)
    }

    fun buildForeignKeyClause(
        constraintName: String,
        fromColumns: List<String>,
        toTable: String,
        toColumns: List<String>,
        onDelete: ReferentialAction?,
        onUpdate: ReferentialAction?
    ): String {
        val fromCols = fromColumns.joinToString(", ") { quoteIdentifier(it) }
        val toCols = toColumns.joinToString(", ") { quoteIdentifier(it) }
        val sql = buildString {
            append("CONSTRAINT ${quoteIdentifier(constraintName)} FOREIGN KEY ($fromCols) REFERENCES ${quoteIdentifier(toTable)} ($toCols)")
            if (onDelete != null) append(" ON DELETE ${referentialActionSql(onDelete)}")
            if (onUpdate != null) append(" ON UPDATE ${referentialActionSql(onUpdate)}")
        }
        return sql
    }

    fun generateConstraintClause(constraint: ConstraintDefinition): String {
        return when (constraint.type) {
            ConstraintType.CHECK -> {
                "CONSTRAINT ${quoteIdentifier(constraint.name)} CHECK (${constraint.expression})"
            }
            ConstraintType.UNIQUE -> {
                val cols = constraint.columns?.joinToString(", ") { quoteIdentifier(it) } ?: ""
                "CONSTRAINT ${quoteIdentifier(constraint.name)} UNIQUE ($cols)"
            }
            ConstraintType.EXCLUDE -> {
                "CONSTRAINT ${quoteIdentifier(constraint.name)} EXCLUDE (${constraint.expression})"
            }
            ConstraintType.FOREIGN_KEY -> {
                val ref = constraint.references!!
                buildForeignKeyClause(
                    constraint.name,
                    constraint.columns ?: emptyList(),
                    ref.table,
                    ref.columns,
                    ref.onDelete,
                    ref.onUpdate
                )
            }
        }
    }
}
