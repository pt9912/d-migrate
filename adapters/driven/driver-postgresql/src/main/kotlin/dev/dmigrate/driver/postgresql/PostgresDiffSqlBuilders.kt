package dev.dmigrate.driver.postgresql

import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.ConstraintDefinition
import dev.dmigrate.core.model.ConstraintType
import dev.dmigrate.core.model.CustomTypeDefinition
import dev.dmigrate.core.model.IndexDefinition
import dev.dmigrate.core.model.IndexType
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.ReferentialAction
import dev.dmigrate.core.model.SequenceDefinition
import dev.dmigrate.core.model.ViewDefinition
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.SqlIdentifiers

/**
 * Stateless SQL fragment builders for the PostgreSQL diff renderer.
 * Owns identifier quoting, column-line composition, constraint
 * rendering, index/view/enum SQL templates, and the safe-implicit-
 * cast allow-list. Kept separate from the operation dispatch so the
 * dispatcher (`PostgresDiffDdlGenerator`) and per-category renderers
 * stay below Detekt's `TooManyFunctions` threshold.
 */
internal class PostgresDiffSqlBuilders(private val typeMapper: PostgresTypeMapper) {

    fun quote(name: String): String = SqlIdentifiers.quoteIdentifier(name, DatabaseDialect.POSTGRESQL)

    fun columnLine(name: String, col: ColumnDefinition): String {
        // Enum-Degradations-Slice (AP2): a `refType` enum references its native
        // PostgreSQL type (created by the CreateCustomType op → `CREATE TYPE … AS
        // ENUM`) instead of degrading to bare TEXT — mirrors the generate path
        // (PostgresColumnConstraintHelper). Only the type NAME is needed, so no
        // schema lookup. Inline-values enums (no refType) stay TEXT (2b / W134).
        // Review F3: only take this fast path with no inline FK — otherwise fall
        // through to the generic body so the `REFERENCES …` clause is preserved
        // (the ENUM type degrades to TEXT there rather than dropping the FK).
        (col.type as? NeutralType.Enum)?.refType?.takeIf { col.references == null }?.let { refType ->
            val refParts = mutableListOf(quote(name), quote(refType))
            if (col.required) refParts += "NOT NULL"
            col.default?.let { refParts += "DEFAULT ${typeMapper.toDefaultSql(it, col.type)}" }
            if (col.unique) refParts += "UNIQUE"
            return refParts.joinToString(" ")
        }
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
        // F.5 Sub-Slice B (2026-05-19): PostgreSQL native CHECK
        // rendering. The expression is interpolated as-is — see the
        // `expression` field doc in ConstraintDefinition: trusted
        // input authored by the schema owner, sanitisation is out
        // of scope at the renderer.
        ConstraintType.CHECK -> {
            val expression = c.expression?.takeIf { it.isNotBlank() } ?: return null
            "CONSTRAINT ${quote(c.name)} CHECK ($expression)"
        }
        // F.5 Sub-Slice B: PostgreSQL native EXCLUDE rendering. The
        // expression carries the element list (`col WITH op, …`); the
        // renderer wraps it with the standard `USING gist (…)` form.
        // Custom operator classes and WHERE predicates are §3.2
        // out-of-scope for the first F.5 tranche — operators who
        // need them block (or embed them inline; behaviour is
        // pinned by Sub-Slice F's tests once the reversibility
        // contract lands).
        ConstraintType.EXCLUDE -> {
            val expression = c.expression?.takeIf { it.isNotBlank() } ?: return null
            "CONSTRAINT ${quote(c.name)} EXCLUDE USING gist ($expression)"
        }
    }

    fun createIndexSql(table: String, idx: IndexDefinition): String {
        val unique = if (idx.unique) "UNIQUE " else ""
        // ADR 0025: a neutral FULLTEXT index expands to a GiST index over the precomputed
        // `tsvector` column (recorded in fullTextVectorColumn); `columns` holds the human
        // source columns that MySQL/SQLite index. The caller's FULLTEXT guard resolves /
        // blocks the vector column, so it is normally present here.
        if (idx.type == IndexType.FULLTEXT) {
            val vec = idx.fullTextVectorColumn
                ?: return "-- FULLTEXT index ${quote(effectiveIndexName(table, idx))} skipped: " +
                    "no backing tsvector column"
            // ADR 0025: restore the recorded access method, clamped to GIN/GiST.
            val method = pgFullTextAccessMethod(idx.fullTextAccessMethod).name
            return "CREATE ${unique}INDEX ${quote(effectiveIndexName(table, idx))} " +
                "ON ${quote(table)} USING $method (${quote(vec)});"
        }
        // VA3: der neutrale räumliche Index (SPATIAL) wird in PostGIS als GIST-
        // Zugriffsmethode emittiert (PostgreSQL kennt kein `USING SPATIAL`).
        val using = if (idx.type != IndexType.BTREE) " USING ${pgAccessMethod(idx.type)}" else ""
        val cols = idx.columns.joinToString(", ") { col ->
            quote(col.name) + (col.direction?.let { " ${it.name}" } ?: "")
        }
        val name = effectiveIndexName(table, idx)
        val whereClause = idx.where?.let { " WHERE $it" } ?: ""
        return "CREATE ${unique}INDEX ${quote(name)} ON ${quote(table)}$using ($cols)$whereClause;"
    }

    /**
     * Single source of truth for the index name across CREATE/DROP
     * paths so the up-side `CREATE INDEX <name>` and the down-side
     * `DROP INDEX <name>` cannot drift if [anonIndexName] ever evolves.
     */
    fun effectiveIndexName(table: String, idx: IndexDefinition): String =
        idx.name ?: anonIndexName(table, idx)

    fun createEnumTypeSql(name: String, t: CustomTypeDefinition): String {
        val values = t.values?.joinToString(", ") { "'${it.replace("'", "''")}'" }.orEmpty()
        return "CREATE TYPE ${quote(name)} AS ENUM ($values);"
    }

    fun createSequenceSql(name: String, seq: SequenceDefinition): String =
        buildString {
            append("CREATE SEQUENCE ${quote(name)}")
            appendSequenceAttributes(seq)
            append(";")
        }

    fun alterSequenceSql(name: String, seq: SequenceDefinition): String =
        buildString {
            append("ALTER SEQUENCE ${quote(name)}")
            appendSequenceAttributes(seq)
            append(";")
        }

    fun createViewSql(name: String, v: ViewDefinition): String {
        val materialized = if (v.materialized) "MATERIALIZED " else ""
        return "CREATE ${materialized}VIEW ${quote(name)} AS ${v.query?.trimEnd(';')};"
    }

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

    fun toDefaultSql(default: dev.dmigrate.core.model.DefaultValue, type: NeutralType): String =
        typeMapper.toDefaultSql(default, type)

    private fun StringBuilder.appendSequenceAttributes(seq: SequenceDefinition) {
        append(" START WITH ${seq.start}")
        append(" INCREMENT BY ${seq.increment}")
        append(seq.minValue?.let { " MINVALUE $it" } ?: " NO MINVALUE")
        append(seq.maxValue?.let { " MAXVALUE $it" } ?: " NO MAXVALUE")
        append(if (seq.cycle) " CYCLE" else " NO CYCLE")
        seq.cache?.let { append(" CACHE $it") }
    }

    /**
     * Allow-list of implicit casts that PostgreSQL accepts without a
     * `USING` clause and without data loss. Anything else surfaces as
     * `DIALECT_UNSUPPORTED_OPERATION`.
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
