package dev.dmigrate.driver.sqlite

import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.ConstraintDefinition
import dev.dmigrate.core.model.ConstraintType
import dev.dmigrate.core.model.IndexDefinition
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.ReferentialAction
import dev.dmigrate.core.model.ViewDefinition
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.SqlIdentifiers

/**
 * Stateless SQL fragment builders for the SQLite diff renderer.
 *
 * SQLite differs from PostgreSQL/MySQL in several significant ways
 * that affect the first-matrix scope:
 *
 * - No `CREATE OR REPLACE VIEW` — `ReplaceView` is rendered as
 *   `DROP VIEW IF EXISTS` + `CREATE VIEW`.
 * - No `ALTER COLUMN`, no `ADD/DROP CONSTRAINT`, no `ALTER PRIMARY KEY`
 *   — those operations require a full table rebuild and are
 *   surfaced as `MANUAL_ACTION_REQUIRED` blockers in D.4.a; the
 *   actual rebuild pipeline ships with D.4.b (Plan §6.4).
 * - No standalone custom types — `CREATE TYPE` doesn't exist.
 * - `DROP COLUMN` works only on SQLite ≥ 3.35.0; we emit it
 *   unconditionally, leaving version-policing to the runner.
 */
internal class SqliteDiffSqlBuilders {

    private val typeMapper = SqliteTypeMapper()

    fun quote(name: String): String = SqlIdentifiers.quoteIdentifier(name, DatabaseDialect.SQLITE)

    fun columnLine(name: String, col: ColumnDefinition): String {
        val parts = mutableListOf<String>()
        parts += quote(name)
        parts += typeMapper.toSql(col.type)
        if (col.required) parts += "NOT NULL"
        if (col.unique) parts += "UNIQUE"
        col.default?.let { parts += "DEFAULT ${typeMapper.toDefaultSql(it, col.type)}" }
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
        ConstraintType.CHECK, ConstraintType.EXCLUDE -> null
    }

    fun createIndexSql(table: String, idx: IndexDefinition): String {
        val unique = if (idx.unique) "UNIQUE " else ""
        // SQLite always uses btree internally; USING clauses are unsupported.
        val cols = idx.columns.joinToString(", ") { col ->
            quote(col.name) + (col.direction?.let { " ${it.name}" } ?: "")
        }
        val name = idx.name ?: anonIndexName(table, idx)
        val whereClause = idx.where?.let { " WHERE $it" } ?: ""
        return "CREATE ${unique}INDEX ${quote(name)} ON ${quote(table)} ($cols)$whereClause;"
    }

    fun dropIndexSql(idx: IndexDefinition, table: String): String =
        "DROP INDEX ${quote(idx.name ?: anonIndexName(table, idx))};"

    fun createViewSql(name: String, v: ViewDefinition): String =
        "CREATE VIEW ${quote(name)} AS ${v.query?.trimEnd(';')};"

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

    // No implicit-cast allow-list here: SQLite cannot ALTER a column type
    // in place at all. AlterColumnType is unconditionally deferred to the
    // RebuildTable pipeline (D.4.b), so the policy lives there.
}
