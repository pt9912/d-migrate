package dev.dmigrate.driver.sqlite

import dev.dmigrate.core.diff.migration.DiffOperation
import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.driver.metadata.EnumValueCheck

/**
 * Ein Enum, dessen Wertevorrat in SQLite nirgends landet.
 *
 * SQLite hat keinen Enum-Typ; der Vorrat wird als `CHECK (spalte IN …)` an der
 * Textspalte durchgesetzt, in beiden Pfaden gleich. Uebrig bleibt der Fall, in
 * dem es nichts durchzusetzen gibt: ein Enum ohne Werte. Dann entsteht eine
 * blanke Textspalte, und das wird **laut** gemeldet statt still hingenommen —
 * an jeder Render-Stelle: `CREATE TABLE`, `ADD COLUMN` und der Tabellen-Neubau.
 * Ein Praedikat und eine Meldung, damit die drei Stellen nicht auseinanderlaufen.
 */
internal object SqliteEnumDegradation {

    fun message(colName: String): String =
        "Enum column `$colName` carries no values; it is migrated as bare TEXT and nothing is " +
            "enforced in the target (SQLite has no native enum type — the value vocabulary is " +
            "enforced by a CHECK constraint, and an empty one enforces nothing)."

    fun warnIfEnum(
        op: DiffOperation,
        ctx: SqliteDiffRenderContext,
        colName: String,
        col: ColumnDefinition,
    ) {
        if (col.type is NeutralType.Enum && EnumValueCheck.inlineValues(col.type) == null) {
            ctx.warning(op, message(colName), code = "W134")
        }
    }
}
