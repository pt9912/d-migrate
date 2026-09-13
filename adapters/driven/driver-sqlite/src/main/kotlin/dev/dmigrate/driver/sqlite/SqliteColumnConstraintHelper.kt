package dev.dmigrate.driver.sqlite

import dev.dmigrate.core.model.*
import dev.dmigrate.driver.*
import dev.dmigrate.driver.RawSqlExpressionPortability
import dev.dmigrate.driver.metadata.ComputedColumnClause
import dev.dmigrate.driver.metadata.EnumValueCheck
import dev.dmigrate.driver.metadata.NamedUniqueConstraints

internal class SqliteColumnConstraintHelper(
    private val quoteIdentifier: (String) -> String,
    private val typeMapper: TypeMapper,
    private val columnSql: (String, String, ColumnDefinition, SchemaDefinition) -> String,
    private val referentialActionSql: (ReferentialAction) -> String,
    private val sequenceSupport: SqliteSequenceDdlSupport,
) {

    fun generateColumnSql(
        colName: String,
        col: ColumnDefinition,
        schema: SchemaDefinition,
        tableName: String,
        notes: MutableList<TransformationNote>,
        deferredFks: Set<Pair<String, String>> = emptySet(),
        // SQLite AUTOINCREMENT is a single-column rowid alias: the inline
        // `INTEGER PRIMARY KEY AUTOINCREMENT` form is only valid when this column
        // is the whole primary key. In a composite PK the identity column degrades
        // to a plain INTEGER (W135) and the composite key is emitted table-level.
        isSolePrimaryKey: Boolean = true,
        skipped: MutableList<SkippedObject>? = null,
    ): String {
        val type = col.type

        // Eine berechnete Spalte bekommt ihren Wert aus dem Ausdruck; NOT NULL,
        // DEFAULT und UNIQUE sind dort keine Frage. SQLite kennt beide
        // Speicherformen und nimmt ohne Angabe die virtuelle.
        // Traegt der Ausdruck fremde Grammatik, faellt die Berechnung weg und
        // die Spalte bleibt gewoehnlich — benannt, nicht still. Der Reverse
        // uebersetzt rohen Text nicht; ihn hier weiterzureichen ergaebe SQL,
        // das erst dem Zielserver auffaellt.
        ComputedColumnClause.of(col)?.let { computed ->
            val refusal = RawSqlExpressionPortability.computedRefusal(
                colName, computed.expression, DatabaseDialect.SQLITE,
            )
            if (refusal != null) {
                notes += refusal
                skipped?.add(SkippedObject("computed_expression", colName, refusal.message, code = refusal.code))
            } else {
                return listOf(
                    quoteIdentifier(colName),
                    typeMapper.toSql(type),
                    ComputedColumnClause.clause(computed, if (computed.stored) "STORED" else "VIRTUAL"),
                ).joinToString(" ")
            }
        }

        val isRowidIdentity = col.generation is ColumnGeneration.Identity && supportsRowidIdentity(type)
        val isAutoIncrementIdentifier = type is NeutralType.Identifier && type.autoIncrement
        if ((isRowidIdentity || isAutoIncrementIdentifier) && !isSolePrimaryKey) {
            return generateCompositePkIdentityColumn(colName, col, type, tableName, notes)
        }
        if (isRowidIdentity) {
            return generateRowidIdentityColumn(colName, col)
        }
        if (isAutoIncrementIdentifier) {
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
        if (type is NeutralType.FullText) {
            // ADR 0015: full-text degrades to a plain TEXT column cross-dialect;
            // SQLite full-text search is an FTS5 virtual table, not a column type.
            notes += TransformationNote(
                type = NoteType.WARNING, code = "W132", objectName = "$tableName.$colName",
                message = "Full-text column '$colName' degraded to TEXT; " +
                    "SQLite full-text search is an FTS5 virtual table, not a column type.",
                hint = "To restore full-text search, create an FTS5 virtual table over the source " +
                    "text with sync triggers; structural cross-dialect translation is a future slice."
            )
        }
        return generateDefaultColumn(colName, col, schema, tableName, deferredFks)
    }

    private fun supportsRowidIdentity(type: NeutralType): Boolean =
        type is NeutralType.Integer || type is NeutralType.BigInteger

    private fun generateRowidIdentityColumn(colName: String, col: ColumnDefinition): String {
        val parts = mutableListOf(quoteIdentifier(colName), "INTEGER PRIMARY KEY AUTOINCREMENT")
        if (NamedUniqueConstraints.rendersInline(col)) parts += "UNIQUE"
        return parts.joinToString(" ")
    }

    private fun generateAutoIncrementColumn(colName: String, col: ColumnDefinition, type: NeutralType): String {
        val parts = mutableListOf(quoteIdentifier(colName), typeMapper.toSql(type))
        if (col.default != null) parts += "DEFAULT ${typeMapper.toDefaultSql(col.default!!, type)}"
        if (NamedUniqueConstraints.rendersInline(col)) parts += "UNIQUE"
        return parts.joinToString(" ")
    }

    /**
     * An identity/autoincrement column that is only *part* of a composite primary key.
     * SQLite's AUTOINCREMENT is a single-column rowid alias, so it cannot back a composite
     * key — the column degrades to a plain `INTEGER` member of the table-level `PRIMARY KEY`
     * clause and loses server-side auto-generation (W135). Rendering both the inline
     * `INTEGER PRIMARY KEY AUTOINCREMENT` and the composite clause would yield two primary
     * keys, which SQLite rejects outright.
     */
    private fun generateCompositePkIdentityColumn(
        colName: String,
        col: ColumnDefinition,
        type: NeutralType,
        tableName: String,
        notes: MutableList<TransformationNote>,
    ): String {
        notes += TransformationNote(
            type = NoteType.WARNING, code = SqliteCompositePkIdentity.W_CODE, objectName = "$tableName.$colName",
            message = SqliteCompositePkIdentity.message(colName),
            hint = SqliteCompositePkIdentity.HINT,
        )
        val parts = mutableListOf(quoteIdentifier(colName), "INTEGER")
        if (col.required) parts += "NOT NULL"
        if (col.default != null) parts += "DEFAULT ${typeMapper.toDefaultSql(col.default!!, type)}"
        if (NamedUniqueConstraints.rendersInline(col)) parts += "UNIQUE"
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
        if (NamedUniqueConstraints.rendersInline(col)) parts += "UNIQUE"
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
        if (NamedUniqueConstraints.rendersInline(col)) parts += "UNIQUE"
        parts += EnumValueCheck.namedClause(tableName, colName, type.values!!, quoteIdentifier)
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
        notes: MutableList<TransformationNote>,
        tableName: String = "",
        skipped: MutableList<SkippedObject>? = null,
    ): String? {
        return when (constraint.type) {
            ConstraintType.CHECK -> {
                if (tableName.isNotEmpty() &&
                    sequenceSupport.shouldSuppressCheckConstraint(tableName, constraint.expression ?: "")
                ) {
                    return null
                }
                // Ein CHECK-Ausdruck reist als roher Dialekt-Text; was SQLite
                // nicht parsen kann, wird benannt verworfen statt ungueltig
                // gerendert.
                val verdict = RawSqlExpressionPortability.assess(constraint.expression, DatabaseDialect.SQLITE)
                if (!verdict.portable) {
                    notes += RawSqlExpressionPortability.notPortableNote(
                        "constraint", constraint.name, "CHECK expression", verdict.reason, DatabaseDialect.SQLITE,
                    )
                    skipped?.add(SkippedObject("constraint", constraint.name, verdict.reason.orEmpty(), code = "E053"))
                    return null
                }
                "CONSTRAINT ${quoteIdentifier(constraint.name)} CHECK (${constraint.expression})"
            }
            ConstraintType.UNIQUE -> {
                val cols = constraint.columns?.joinToString(", ") { quoteIdentifier(it) } ?: ""
                "CONSTRAINT ${quoteIdentifier(constraint.name)} UNIQUE ($cols)"
            }
            ConstraintType.EXCLUDE -> {
                // EXCLUDE constraints are not supported in SQLite
                val reason = "EXCLUDE constraint '${constraint.name}' is not supported in SQLite."
                notes += TransformationNote(
                    type = NoteType.ACTION_REQUIRED,
                    code = "E054",
                    objectName = constraint.name,
                    message = reason,
                    hint = "Enforce exclusion logic at the application level or use triggers."
                )
                skipped?.add(SkippedObject("constraint", constraint.name, reason, code = "E054"))
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
