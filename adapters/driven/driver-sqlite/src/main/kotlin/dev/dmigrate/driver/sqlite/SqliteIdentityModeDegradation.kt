package dev.dmigrate.driver.sqlite

import dev.dmigrate.core.diff.migration.DiffOperation
import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.ColumnGeneration
import dev.dmigrate.core.model.IdentityMode
import dev.dmigrate.driver.NoteType
import dev.dmigrate.driver.TransformationNote

/**
 * `mode: always` ohne Entsprechung auf SQLite.
 *
 * Das neutrale Modell unterscheidet zwei Identity-Modi: `always` weist einen
 * ausdruecklich gesetzten Wert zurueck, `by_default` nimmt ihn an. SQLite
 * kennt nur `INTEGER PRIMARY KEY AUTOINCREMENT`, und das nimmt jeden
 * gesetzten Wert an (gemessen) — die Zusage von `always` ist am Ziel also
 * nicht durchgesetzt, und ein Reverse liest sie nicht zurueck.
 *
 * Derselbe Code wie auf MySQL ([MysqlIdentityModeDegradation] im
 * MySQL-Treiber): dieselbe Aussage, zwei Ziele ohne Modus — Muster `W132`.
 * SQL Server meldet den Schwesterfall mit `W140`.
 *
 * **Nur bei `always`**, und nur dort, wo die Spalte wirklich als Autowert
 * entsteht: in einem zusammengesetzten Schluessel faellt die Erzeugung ganz
 * weg, und das sagt `W135` ([SqliteCompositePkIdentity]) staerker.
 */
internal object SqliteIdentityModeDegradation {

    const val W_CODE: String = "W163"

    fun message(colName: String): String =
        "Identity column '$colName' declares mode 'always', but SQLite renders it as " +
            "INTEGER PRIMARY KEY AUTOINCREMENT, which accepts an explicitly supplied value: the mode is " +
            "not enforced in the target, and a reverse read does not carry it back."

    const val HINT: String =
        "Remove explicit values for this column from writes against the target, or accept that SQLite " +
            "does not reject them; SQLite has no ALWAYS equivalent."

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
        ctx: SqliteDiffRenderContext,
        colName: String,
        col: ColumnDefinition,
        isSolePrimaryKey: Boolean,
    ) {
        if (!isSolePrimaryKey || !appliesTo(col)) return
        if (!SqliteRowidIdentity.byGeneration(col)) return
        ctx.warning(op, message(colName), code = W_CODE)
    }
}
