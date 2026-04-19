package dev.dmigrate.driver.mysql

import dev.dmigrate.core.model.*
import dev.dmigrate.driver.*

/**
 * Column and constraint DDL helpers for MySQL, extracted from
 * [MysqlDdlGenerator] for structural clarity.
 */
internal class MysqlColumnConstraintHelper(
    private val quoteIdentifier: (String) -> String,
    private val typeMapper: TypeMapper,
    private val columnSql: (String, ColumnDefinition, SchemaDefinition) -> String,
    private val referentialActionSql: (ReferentialAction) -> String,
) {

    fun generateColumnSql(
        colName: String,
        col: ColumnDefinition,
        schema: SchemaDefinition,
        notes: MutableList<TransformationNote>,
    ): String = when {
        col.type is NeutralType.Identifier && (col.type as NeutralType.Identifier).autoIncrement ->
            columnAutoIncrement(colName, col)
        col.type is NeutralType.Enum -> columnEnum(colName, col, schema)
        col.type is NeutralType.Geometry -> columnGeometry(colName, col, notes)
        else -> columnSql(colName, col, schema)
    }

    private fun columnAutoIncrement(colName: String, col: ColumnDefinition): String {
        val parts = mutableListOf(quoteIdentifier(colName), typeMapper.toSql(col.type))
        if (col.default != null) parts += "DEFAULT ${typeMapper.toDefaultSql(col.default!!, col.type)}"
        if (col.unique) parts += "UNIQUE"
        return parts.joinToString(" ")
    }

    private fun columnEnum(colName: String, col: ColumnDefinition, schema: SchemaDefinition): String {
        val type = col.type as NeutralType.Enum
        if (type.refType != null) {
            val customType = schema.customTypes[type.refType]
            if (customType != null && customType.kind == CustomTypeKind.DOMAIN) {
                return columnDomain(colName, col, customType)
            }
            val enumValues = customType?.values
            if (enumValues != null) {
                return columnEnumInline(colName, col, enumValues)
            }
        }
        if (type.values != null) {
            return columnEnumInline(colName, col, type.values!!)
        }
        return columnSql(colName, col, schema)
    }

    private fun columnEnumInline(colName: String, col: ColumnDefinition, values: List<String>): String {
        val enumDef = values.joinToString(", ") { "'${it.replace("'", "''")}'" }
        val parts = mutableListOf(quoteIdentifier(colName), "ENUM($enumDef)")
        if (col.required) parts += "NOT NULL"
        if (col.default != null) parts += "DEFAULT ${typeMapper.toDefaultSql(col.default!!, col.type)}"
        if (col.unique) parts += "UNIQUE"
        return parts.joinToString(" ")
    }

    private fun columnDomain(colName: String, col: ColumnDefinition, customType: CustomTypeDefinition): String {
        val parts = mutableListOf(quoteIdentifier(colName), customType.baseType ?: "TEXT")
        if (col.required) parts += "NOT NULL"
        if (col.default != null) parts += "DEFAULT ${typeMapper.toDefaultSql(col.default!!, col.type)}"
        if (col.unique) parts += "UNIQUE"
        if (customType.check != null) parts += "CHECK (${customType.check})"
        return parts.joinToString(" ")
    }

    private fun columnGeometry(colName: String, col: ColumnDefinition, notes: MutableList<TransformationNote>): String {
        val type = col.type as NeutralType.Geometry
        val parts = mutableListOf(quoteIdentifier(colName))
        val baseType = typeMapper.toSql(type)
        val srid = type.srid
        if (srid != null) {
            parts += "$baseType /*!80003 SRID $srid */"
            notes += TransformationNote(
                type = NoteType.WARNING, code = "W120",
                objectName = colName, message = "SRID $srid emitted as MySQL comment hint; " +
                    "full SRID constraint support depends on MySQL 8.0+",
            )
        } else {
            parts += baseType
        }
        if (col.required) parts += "NOT NULL"
        return parts.joinToString(" ")
    }

    fun buildForeignKeyClause(
        constraintName: String,
        fromColumns: List<String>,
        toTable: String,
        toColumns: List<String>,
        onDelete: ReferentialAction?,
        onUpdate: ReferentialAction?,
    ): String {
        val fromCols = fromColumns.joinToString(", ") { quoteIdentifier(it) }
        val toCols = toColumns.joinToString(", ") { quoteIdentifier(it) }
        return buildString {
            append("CONSTRAINT ${quoteIdentifier(constraintName)} FOREIGN KEY ($fromCols) REFERENCES ${quoteIdentifier(toTable)} ($toCols)")
            if (onDelete != null) append(" ON DELETE ${referentialActionSql(onDelete)}")
            if (onUpdate != null) append(" ON UPDATE ${referentialActionSql(onUpdate)}")
        }
    }

    fun generateConstraintClause(
        constraint: ConstraintDefinition,
        notes: MutableList<TransformationNote>,
    ): String? = when (constraint.type) {
        ConstraintType.CHECK ->
            "CONSTRAINT ${quoteIdentifier(constraint.name)} CHECK (${constraint.expression})"
        ConstraintType.UNIQUE -> {
            val cols = constraint.columns?.joinToString(", ") { quoteIdentifier(it) } ?: ""
            "CONSTRAINT ${quoteIdentifier(constraint.name)} UNIQUE ($cols)"
        }
        ConstraintType.EXCLUDE -> {
            val action = ManualActionRequired(
                code = "E054", objectType = "constraint", objectName = constraint.name,
                reason = "EXCLUDE constraint '${constraint.name}' is not supported in MySQL.",
                hint = "Consider using CHECK constraints or application-level validation instead.",
            )
            notes += action.toNote()
            null
        }
        ConstraintType.FOREIGN_KEY -> {
            val ref = constraint.references!!
            buildForeignKeyClause(constraint.name, constraint.columns ?: emptyList(),
                ref.table, ref.columns, ref.onDelete, ref.onUpdate)
        }
    }
}
