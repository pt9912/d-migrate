package dev.dmigrate.driver.sqlite

import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.ConstraintDefinition
import dev.dmigrate.core.model.ConstraintType
import dev.dmigrate.core.model.IndexDefinition
import dev.dmigrate.core.model.IndexType
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.ReferentialAction
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.core.model.TriggerDefinition
import dev.dmigrate.core.model.ViewDefinition
import dev.dmigrate.core.model.toSqlEventClause
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

    fun columnLine(name: String, col: ColumnDefinition, isSolePrimaryKey: Boolean = true): String {
        val parts = mutableListOf<String>()
        parts += quote(name)
        // SQLite renders an `identifier` column inline as `INTEGER PRIMARY KEY AUTOINCREMENT`
        // (single-column rowid alias). When it is only part of a *composite* PK the inline
        // clause would collide with the table-level [primaryKeyClause]; degrade to a plain
        // INTEGER instead (W135, SqliteCompositePkIdentity — warned by the emitting op).
        parts += if (col.type is NeutralType.Identifier && !isSolePrimaryKey) "INTEGER" else typeMapper.toSql(col.type)
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

    /**
     * The table-level `PRIMARY KEY (…)` clause for a `CREATE TABLE`, or `null`
     * when it must be omitted. SQLite renders an `identifier` column inline as
     * `INTEGER PRIMARY KEY AUTOINCREMENT` ([columnLine] → [SqliteTypeMapper]),
     * so a single-column PK on that same column must NOT also emit a table-level
     * clause — SQLite rejects the duplicate PK. Every other PK (multi-column, or
     * a single-column PK on a non-`identifier` column) keeps the clause.
     *
     * Shared by **both** SQLite `CREATE TABLE` emitters — the diff renderer
     * ([SqliteDiffSimpleOps]) and the table-rebuild renderer
     * ([SqliteRebuildRenderer]) — so the dedup can never be forgotten on one
     * path (the generate path [SqliteTableDdlSupport] carries its own, wider
     * variant that also covers `ColumnGeneration.Identity`, which the diff
     * `columnLine` does not render inline).
     */
    fun primaryKeyClause(table: TableDefinition): String? {
        val pk = table.primaryKey
        if (pk.isEmpty()) return null
        if (pk.size == 1 && table.columns[pk.single()]?.type is NeutralType.Identifier) return null
        return "PRIMARY KEY (" + pk.joinToString(", ") { quote(it) } + ")"
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
        // F.5 Sub-Slice D (2026-05-19): SQLite has no in-place
        // `ALTER TABLE ADD CONSTRAINT`, but a CHECK clause embedded in
        // a CREATE TABLE is fully supported and evaluated at runtime.
        // The rebuild pipeline runs `CREATE TABLE <temp>` with the
        // target's constraint list inline; emitting the line here
        // makes the rebuild carry the new/changed CHECK forward.
        ConstraintType.CHECK -> {
            val expression = c.expression?.takeIf { it.isNotBlank() } ?: return null
            "CONSTRAINT ${quote(c.name)} CHECK ($expression)"
        }
        // EXCLUDE is a PostgreSQL-only feature; SQLite has no
        // syntactic equivalent. The dispatcher (SqliteDiffDdlGenerator)
        // blocks any constraint diff that touches EXCLUDE with
        // `EXCLUDE_NOT_SUPPORTED_BY_DIALECT` before the rebuild kicks
        // in, so this branch should never be reached at runtime.
        // Returning null keeps the builder honest and gives the
        // rebuild renderer a defensive skip if a regression slips
        // an EXCLUDE through.
        ConstraintType.EXCLUDE -> null
    }

    fun createIndexSql(table: String, idx: IndexDefinition): String {
        // ADR 0025: a FULLTEXT index has no SQLite equivalent without an FTS5 virtual table
        // (slice P4). Emit the W132 skip marker here — the single SQL source — so EVERY caller
        // (AddIndex, CreateTable, table rebuild, DropIndex-DOWN recreate) degrades instead of
        // silently emitting a plain BTREE over the source columns. renderAddIndex additionally
        // attaches the W132 diagnostic.
        if (idx.type == IndexType.FULLTEXT) {
            return SqliteFullTextDegradation.skipComment(quote(effectiveIndexName(table, idx)))
        }
        val unique = if (idx.unique) "UNIQUE " else ""
        // SQLite always uses btree internally; USING clauses are unsupported.
        val cols = idx.columns.joinToString(", ") { col ->
            quote(col.name) + (col.direction?.let { " ${it.name}" } ?: "")
        }
        val name = effectiveIndexName(table, idx)
        val whereClause = idx.where?.let { " WHERE $it" } ?: ""
        return "CREATE ${unique}INDEX ${quote(name)} ON ${quote(table)} ($cols)$whereClause;"
    }

    /** Parameter order standardised to `(table, idx)` across all dialects. */
    fun dropIndexSql(table: String, idx: IndexDefinition): String =
        "DROP INDEX ${quote(effectiveIndexName(table, idx))};"

    /** Single source of truth for the index name; mirrors PG / MySQL [effectiveIndexName]. */
    fun effectiveIndexName(table: String, idx: IndexDefinition): String =
        idx.name ?: anonIndexName(table, idx)

    fun createViewSql(name: String, v: ViewDefinition): String =
        "CREATE VIEW ${quote(name)} AS ${v.query?.trimEnd(';')};"

    /**
     * Phase H.3a: render `CREATE TRIGGER ...` for a trigger that the
     * SQLite rebuild pipeline must recreate after a `DROP TABLE` +
     * RENAME cycle. Mirrors the format produced by
     * `SqliteRoutineDdlHelper.generateTrigger` so a freshly recreated
     * trigger is bit-identical to the originally-generated one.
     *
     * Returns `null` when the trigger cannot be rendered (missing
     * body, foreign sourceDialect). The renderer surfaces the
     * Null-return as a BLOCKER diagnostic instead of emitting
     * malformed SQL.
     */
    fun createTriggerSql(name: String, trigger: TriggerDefinition): String? {
        val body = trigger.body ?: return null
        if (trigger.sourceDialect != null && trigger.sourceDialect != "sqlite") return null
        val timing = trigger.timing.name
        // F4: single-event sets render as a bare keyword (SQLite has no
        // multi-event trigger grammar); foreign triggers are rejected upstream.
        val event = trigger.events.toSqlEventClause()
        val forEach = trigger.forEach.name
        // E.2 review follow-up: deduplicate the trailing `;` — readers
        // may or may not include it on the body, but the BEGIN..END
        // wrapper always closes with `END;`. Without this normalisation
        // a body ending in `;` produces `... NEW.id;\nEND;` (two
        // terminators); both forms parse, but the deterministic form
        // makes goldenness diffing reader-input-agnostic.
        val normalisedBody = body.trimEnd().trimEnd(';').trimEnd()
        return buildString {
            append("CREATE TRIGGER ${quote(name)}\n")
            append("    $timing $event ON ${quote(trigger.table)}\n")
            append("    FOR EACH $forEach")
            if (trigger.condition != null) append("\n    WHEN ${trigger.condition}")
            append("\nBEGIN\n")
            append(normalisedBody)
            append(";\nEND;")
        }
    }

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
