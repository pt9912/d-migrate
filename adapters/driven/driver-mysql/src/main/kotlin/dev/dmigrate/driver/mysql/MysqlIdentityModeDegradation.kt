package dev.dmigrate.driver.mysql

import dev.dmigrate.core.diff.migration.DiffOperation
import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.ColumnGeneration
import dev.dmigrate.core.model.IdentityMode
import dev.dmigrate.driver.NoteType
import dev.dmigrate.driver.TransformationNote

/**
 * `mode: always` ohne Entsprechung auf MySQL.
 *
 * Das neutrale Modell unterscheidet zwei Identity-Modi: `always` weist einen
 * ausdruecklich gesetzten Wert zurueck, `by_default` nimmt ihn an. MySQL
 * kennt nur `AUTO_INCREMENT`, und das nimmt jeden gesetzten Wert an — die
 * Zusage von `always` ist am Ziel also nicht durchgesetzt, und ein Reverse
 * liest `by_default` zurueck.
 *
 * SQL Server meldet den Schwesterfall laengst (`W140`, `BY DEFAULT` ohne
 * Entsprechung); hier blieb er still. `W163` ist ein Code fuer MySQL und
 * SQLite (Muster `W132`): dieselbe Aussage, zwei Ziele ohne Modus.
 *
 * **Nur bei `always`.** Eine `by_default`-Spalte verliert nichts, und der Typ
 * `identifier` nennt gar keinen Modus — beide melden nichts.
 */
internal object MysqlIdentityModeDegradation {

    const val W_CODE: String = "W163"

    fun message(colName: String): String =
        "Identity column '$colName' declares mode 'always', but MySQL renders it as AUTO_INCREMENT, " +
            "which accepts an explicitly supplied value: the mode is not enforced in the target, and a " +
            "reverse read yields 'by_default'."

    const val HINT: String =
        "Remove explicit values for this column from writes against the target, or accept that MySQL " +
            "does not reject them; MySQL has no ALWAYS equivalent."

    /** `true`, wenn die Spalte ihre Identity mit `mode: always` erklaert. */
    fun appliesTo(col: ColumnDefinition): Boolean =
        (col.generation as? ColumnGeneration.Identity)?.mode == IdentityMode.ALWAYS

    /** Die Generate-Note, oder `null`. */
    fun noteFor(tableName: String, colName: String, col: ColumnDefinition): TransformationNote? {
        if (!appliesTo(col)) return null
        return TransformationNote(
            type = NoteType.WARNING,
            code = W_CODE,
            objectName = "$tableName.$colName",
            message = message(colName),
            hint = HINT,
        )
    }

    /** Die Migrate-Note an einer Render-Stelle des Diff-Pfads. */
    fun warnIfAlways(
        op: DiffOperation,
        ctx: MysqlDiffRenderContext,
        colName: String,
        col: ColumnDefinition,
    ) {
        if (!appliesTo(col)) return
        ctx.warning(op, message(colName), code = W_CODE)
    }
}
