package dev.dmigrate.driver.mysql

import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.ConstraintDefinition
import dev.dmigrate.core.model.ConstraintType
import dev.dmigrate.core.model.DefaultValue
import dev.dmigrate.core.model.IndexDefinition
import dev.dmigrate.core.model.IndexType
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.ReferentialAction
import dev.dmigrate.core.model.ViewDefinition
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.SqlIdentifiers

/**
 * Stateless SQL fragment builders for the MySQL diff renderer.
 * Mirrors `PostgresDiffSqlBuilders` shape; the differences are
 * mostly identifier quoting (backticks via SqlIdentifiers) and
 * MySQL-specific clauses (no standalone ENUM types, `MODIFY COLUMN`
 * instead of `ALTER COLUMN … TYPE`, etc.).
 */
internal class MysqlDiffSqlBuilders(private val typeMapper: MysqlTypeMapper) {

    fun quote(name: String): String = SqlIdentifiers.quoteIdentifier(name, DatabaseDialect.MYSQL)

    fun columnLine(name: String, col: ColumnDefinition): String {
        val parts = mutableListOf<String>()
        parts += quote(name)
        parts += typeMapper.toSql(col.type)
        if (col.required) parts += "NOT NULL"
        if (col.unique) parts += "UNIQUE"
        col.default?.let { default ->
            // E.3 Sub-Slice F: `SequenceNextVal`-Defaults emit no
            // inline `DEFAULT` clause on MySQL — the helper-table
            // emulation handles the value via a per-column
            // `BEFORE INSERT` trigger that calls `dmg_nextval('<seq>')`
            // when the inserted value is NULL. Mirrors the
            // `AbstractDdlGenerator.columnSql` →
            // `resolveSequenceDefault` bypass in the full-schema
            // DDL pipeline. The triggers themselves are emitted by
            // `MysqlDiffTableOps` after the column-bearing statement.
            if (default is DefaultValue.SequenceNextVal) return@let
            parts += "DEFAULT ${typeMapper.toDefaultSql(default, col.type)}"
        }
        col.references?.let { ref ->
            val onDelete = ref.onDelete?.let { " ON DELETE ${referentialActionSql(it)}" } ?: ""
            val onUpdate = ref.onUpdate?.let { " ON UPDATE ${referentialActionSql(it)}" } ?: ""
            parts += "REFERENCES ${quote(ref.table)}(${quote(ref.column)})$onDelete$onUpdate"
        }
        return parts.joinToString(" ")
    }

    fun constraintLine(c: ConstraintDefinition): String? = when (c.type) {
        ConstraintType.UNIQUE -> {
            val cols = c.columns?.joinToString(", ") { quote(it) } ?: return null
            "CONSTRAINT ${quote(c.name)} UNIQUE ($cols)"
        }
        ConstraintType.FOREIGN_KEY -> {
            val cols = c.columns?.joinToString(", ") { quote(it) } ?: return null
            val ref = c.references ?: return null
            val refCols = ref.columns.joinToString(", ") { quote(it) }
            "CONSTRAINT ${quote(c.name)} FOREIGN KEY ($cols) REFERENCES ${quote(ref.table)}($refCols)"
        }
        // F.5 Sub-Slice C (2026-05-19): MySQL / MariaDB native CHECK
        // clause. The enforcement gate
        // (`MysqlCheckEnforcementCapability`) is checked by the
        // renderer (`MysqlDiffOtherOps`), not here — the builder
        // unconditionally produces the syntactic form, the renderer
        // decides whether to emit or block.
        ConstraintType.CHECK -> {
            val expression = c.expression?.takeIf { it.isNotBlank() } ?: return null
            "CONSTRAINT ${quote(c.name)} CHECK ($expression)"
        }
        // EXCLUDE is a PostgreSQL-only contract; MySQL has no
        // syntactic equivalent. The renderer blocks unconditionally
        // with `EXCLUDE_NOT_SUPPORTED_BY_DIALECT`; the builder mirrors
        // that by refusing to render anything.
        ConstraintType.EXCLUDE -> null
    }

    /** MySQL: `DROP FOREIGN KEY` for FK, `DROP INDEX` for UNIQUE, otherwise `DROP CONSTRAINT` (8.0.13+). */
    fun dropConstraintSql(table: String, c: ConstraintDefinition): String? = when (c.type) {
        ConstraintType.FOREIGN_KEY ->
            "ALTER TABLE ${quote(table)} DROP FOREIGN KEY ${quote(c.name)};"
        ConstraintType.UNIQUE ->
            "ALTER TABLE ${quote(table)} DROP INDEX ${quote(c.name)};"
        // F.5 Sub-Slice C: MySQL ≥ 8.0.16 / MariaDB ≥ 10.2.1 support
        // `ALTER TABLE … DROP CHECK <name>`. The renderer
        // (`MysqlDiffOtherOps.renderDropConstraint`) gates the call by
        // capability `known`-ness; the builder produces the syntactic
        // form once that gate is open.
        ConstraintType.CHECK ->
            "ALTER TABLE ${quote(table)} DROP CHECK ${quote(c.name)};"
        ConstraintType.EXCLUDE -> null
    }

    fun createIndexSql(table: String, idx: IndexDefinition): String {
        val unique = if (idx.unique) "UNIQUE " else ""
        val using = if (idx.type != IndexType.BTREE && idx.type != IndexType.HASH) {
            // MySQL only natively supports BTREE/HASH; FULLTEXT / SPATIAL not modelled here.
            ""
        } else if (idx.type == IndexType.HASH) {
            " USING HASH"
        } else {
            ""
        }
        val cols = idx.columns.joinToString(", ") { col ->
            quote(col.name) +
                (col.prefixLength?.let { "($it)" } ?: "") +
                (col.direction?.let { " ${it.name}" } ?: "")
        }
        val name = effectiveIndexName(table, idx)
        return "CREATE ${unique}INDEX ${quote(name)} ON ${quote(table)}$using ($cols);"
    }

    /** MySQL drops indexes with `DROP INDEX … ON tbl`. */
    fun dropIndexSql(table: String, idx: IndexDefinition): String =
        "DROP INDEX ${quote(effectiveIndexName(table, idx))} ON ${quote(table)};"

    /** Single source of truth for the index name; mirrors PG / SQLite [effectiveIndexName]. */
    fun effectiveIndexName(table: String, idx: IndexDefinition): String =
        idx.name ?: anonIndexName(table, idx)

    fun createViewSql(name: String, v: ViewDefinition): String =
        "CREATE VIEW ${quote(name)} AS ${v.query?.trimEnd(';')};"

    fun replaceViewSql(name: String, v: ViewDefinition): String =
        "CREATE OR REPLACE VIEW ${quote(name)} AS ${v.query?.trimEnd(';')};"

    fun referentialActionSql(action: ReferentialAction): String = when (action) {
        ReferentialAction.RESTRICT -> "RESTRICT"
        ReferentialAction.CASCADE -> "CASCADE"
        ReferentialAction.SET_NULL -> "SET NULL"
        ReferentialAction.SET_DEFAULT -> "SET DEFAULT"
        ReferentialAction.NO_ACTION -> "NO ACTION"
    }

    fun anonIndexName(table: String, idx: IndexDefinition): String =
        "${table}_${idx.columns.joinToString("_") { it.name }}_idx"

    fun toSql(type: NeutralType): String = typeMapper.toSql(type)

    fun toDefaultSql(default: DefaultValue, type: NeutralType): String =
        typeMapper.toDefaultSql(default, type)

    /**
     * Allow-list of implicit casts that MySQL accepts without explicit
     * conversion. Mirrors PostgreSQL with the same widening rules —
     * MySQL's implicit conversions are slightly more permissive but
     * we stay strict to keep cross-dialect behavior consistent.
     */
    fun isSafeImplicitCast(before: NeutralType, after: NeutralType): Boolean {
        if (before == after) return true
        return when {
            before is NeutralType.Text && after is NeutralType.Text -> {
                val b = before.maxLength
                val a = after.maxLength
                a == null || (b != null && a >= b)
            }
            before is NeutralType.SmallInt && after is NeutralType.Integer -> true
            before is NeutralType.SmallInt && after is NeutralType.BigInteger -> true
            before is NeutralType.Integer && after is NeutralType.BigInteger -> true
            else -> false
        }
    }
}
