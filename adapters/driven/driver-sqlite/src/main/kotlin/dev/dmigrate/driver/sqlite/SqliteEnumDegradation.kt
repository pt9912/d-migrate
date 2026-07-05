package dev.dmigrate.driver.sqlite

import dev.dmigrate.core.diff.migration.DiffOperation
import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.NeutralType

/**
 * Enum-Degradations-Slice (AP3, W134). SQLite has no native enum type, so the
 * migrate/diff path renders every enum column as bare TEXT — without the
 * `CHECK (col IN …)` that the full-generate path adds (that inline fidelity is
 * Option 2b). Make the value-enforcement loss **loud** instead of silent
 * (DoD-Invariante) at every column-render site: `CREATE TABLE`, `ADD COLUMN`
 * and the table-rebuild. Single predicate + message so the three sites can
 * never drift (Review F1/F5).
 */
internal object SqliteEnumDegradation {

    fun message(colName: String): String =
        "Enum column `$colName` is migrated as bare TEXT; the declared values are not enforced " +
            "in the target (SQLite has no native enum type, and the migrate/diff path does not " +
            "emit a CHECK constraint for it)."

    fun warnIfEnum(
        op: DiffOperation,
        ctx: SqliteDiffRenderContext,
        colName: String,
        col: ColumnDefinition,
    ) {
        if (col.type is NeutralType.Enum) {
            ctx.warning(op, message(colName), code = "W134")
        }
    }
}
