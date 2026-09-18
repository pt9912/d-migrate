package dev.dmigrate.driver.sqlite

import dev.dmigrate.core.diff.migration.DiffOperation
import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.driver.NoteType
import dev.dmigrate.driver.TransformationNote

/**
 * Eine Array-Spalte, die auf SQLite ihre Array-Eigenschaft verliert.
 *
 * SQLite hat keinen Array-Typ; [SqliteTypeMapper] rendert die Spalte als
 * `TEXT`, und ein Reverse liest daraus `text` zurueck — die Elementart ist
 * dann weg. Die Schwesterdialekte melden denselben Verlust laengst (Oracle
 * `W149`, SQL Server `W137`); hier blieb er still.
 *
 * `W162` ist derselbe Code wie auf MySQL ([MysqlArrayDegradation] im
 * MySQL-Treiber): die Aussage ist dieselbe — die Spalte verliert ihre
 * Array-Eigenschaft —, nur die Zielform unterscheidet sich (`TEXT` statt
 * `JSON`). Ein Objekt je Dialekt, damit Generate- und Migrate-Pfad nicht
 * auseinanderlaufen; dasselbe Muster wie bei `W132`.
 */
internal object SqliteArrayDegradation {

    const val W_CODE: String = "W162"

    fun message(colName: String, elementType: String): String =
        "Array column '$colName' (element type '$elementType') is rendered as TEXT: SQLite has no native " +
            "array column type, and a reverse read yields a plain text column without the element type."

    const val HINT: String =
        "Values are stored as text; adjust application code that expects a native array, " +
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
        ctx: SqliteDiffRenderContext,
        colName: String,
        col: ColumnDefinition,
    ) {
        val array = col.type as? NeutralType.Array ?: return
        ctx.warning(op, message(colName, array.elementType), code = W_CODE)
    }
}
