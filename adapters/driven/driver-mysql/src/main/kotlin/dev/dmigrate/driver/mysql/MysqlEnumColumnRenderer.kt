package dev.dmigrate.driver.mysql

import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.DefaultValue
import dev.dmigrate.core.model.NeutralType

/**
 * Shared MySQL inline-`ENUM` column renderer.
 *
 * The single source of truth for `<col> ENUM('a','b') [NOT NULL]
 * [DEFAULT …] [UNIQUE]`, reachable by **both** the full-generate helper
 * ([MysqlColumnConstraintHelper.columnEnumInline]) and the diff renderer
 * ([MysqlDiffSqlBuilders.columnLine]). Before this extraction the diff
 * path had no enum branch and degraded a MySQL enum to bare `TEXT`
 * (values dropped, no enforcement) — inconsistent with `schema generate`
 * (Review F1/F5 of the enum-degradation slice).
 *
 * This narrow signature (no `schema` / `tableName`) renders the inline-`values`
 * form only — sufficient for the diff path, which has no schema. A `refType`-
 * modeled MySQL enum (whose values the generate path resolves from
 * `schema.customTypes`) is therefore NOT rendered natively by the diff path; in
 * the realistic cross-dialect flow its accompanying `CreateCustomType` op is
 * hard-blocked for MySQL (`MysqlDiffOtherOps.renderCreateCustomType` →
 * DIALECT_UNSUPPORTED), so the migration is blocked (loud), not silently
 * degraded (tracked: `enum-generate-silent-degradation.md`, Review F1).
 */
internal object MysqlEnumColumnRenderer {

    fun inline(
        quotedName: String,
        col: ColumnDefinition,
        values: List<String>,
        toDefaultSql: (DefaultValue, NeutralType) -> String,
    ): String {
        val enumDef = values.joinToString(", ") { "'${it.replace("'", "''")}'" }
        val parts = mutableListOf(quotedName, "ENUM($enumDef)")
        if (col.required) parts += "NOT NULL"
        col.default?.let { parts += "DEFAULT ${toDefaultSql(it, col.type)}" }
        if (col.unique) parts += "UNIQUE"
        return parts.joinToString(" ")
    }
}
