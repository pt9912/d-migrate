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
 * MySQL enums always carry inline `values` (MySQL has no standalone enum
 * type), so this narrow signature — no `schema` / `tableName` — is
 * sufficient for the diff path, which has neither.
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
