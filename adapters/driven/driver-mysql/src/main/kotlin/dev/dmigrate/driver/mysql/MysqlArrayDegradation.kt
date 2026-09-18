package dev.dmigrate.driver.mysql

import dev.dmigrate.core.diff.migration.DiffOperation
import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.driver.NoteType
import dev.dmigrate.driver.TransformationNote

/**
 * Eine Array-Spalte, die auf MySQL ihre Array-Eigenschaft verliert.
 *
 * MySQL hat keinen Array-Typ; [MysqlTypeMapper] rendert die Spalte als `JSON`,
 * und ein Reverse liest daraus `json` zurueck — die Elementart ist dann weg.
 * Die Schwesterdialekte melden denselben Verlust laengst (Oracle `W149`,
 * SQL Server `W137`); hier blieb er still.
 *
 * `W162` statt `W149`: SQLite rendert ein Array als `TEXT`, nicht als JSON.
 * Ein Code fuer beide Dialekte traegt die gemeinsame Aussage — die Spalte
 * verliert ihre Array-Eigenschaft — wie `W132` es fuer den Volltext-Verlust
 * tut. Ein Objekt, eine Meldung, damit Generate- und Migrate-Pfad nicht
 * auseinanderlaufen.
 */
internal object MysqlArrayDegradation {

    const val W_CODE: String = "W162"

    fun message(colName: String, elementType: String): String =
        "Array column '$colName' (element type '$elementType') is rendered as JSON: MySQL has no native " +
            "array column type, and a reverse read yields a plain json column without the element type."

    const val HINT: String =
        "Values are stored as a JSON array; adjust application code that expects a native array, " +
            "and declare the element type in the schema file if the target has to carry it."

    /** Die Generate-Note, oder `null`, wenn die Spalte kein Array ist. */
    fun noteFor(tableName: String, colName: String, type: NeutralType): TransformationNote? {
        val array = type as? NeutralType.Array ?: return null
        return TransformationNote(
            type = NoteType.WARNING,
            code = W_CODE,
            objectName = "$tableName.$colName",
            message = message(colName, array.elementType),
            hint = HINT,
        )
    }

    /** Die Migrate-Note an einer Render-Stelle des Diff-Pfads. */
    fun warnIfArray(
        op: DiffOperation,
        ctx: MysqlDiffRenderContext,
        colName: String,
        col: ColumnDefinition,
    ) {
        val array = col.type as? NeutralType.Array ?: return
        ctx.warning(op, message(colName, array.elementType), code = W_CODE)
    }
}
