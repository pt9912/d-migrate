package dev.dmigrate.driver.mysql

import dev.dmigrate.core.diff.migration.DiffOperation
import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.driver.NoteType
import dev.dmigrate.driver.TransformationNote
import dev.dmigrate.driver.metadata.EnumValueCheck

/**
 * Ein `values_constraint` (ADR 0069) an einer Inline-Enum, den MySQL nicht umsetzen kann.
 *
 * MySQL rendert die Enum als natives `ENUM('a', 'b')`; einen CHECK, den der Name benennen koennte, gibt es nicht.
 * Das Schema laeuft trotzdem, der Name bleibt ohne Wirkung — und wird gemeldet, damit niemand glaubt, die
 * Constraint-Namen kaemen auf MySQL an.
 *
 * Ein Objekt, eine Meldung, damit Generate- und Migrate-Pfad nicht auseinanderlaufen (wie [MysqlArrayDegradation]).
 */
internal object MysqlEnumCheckNameIgnored {

    const val W_CODE: String = "W165"

    fun message(colName: String, name: String): String =
        "Enum column '$colName' names its value CHECK '$name' (values_constraint), but MySQL renders the enum as a " +
            "native ENUM without a CHECK: the name has no effect."

    const val HINT: String =
        "The name is kept in the schema for the dialects that enforce the enum with a CHECK; drop values_constraint " +
            "if the schema only targets MySQL."

    /** Die Generate-Note, oder `null`, wenn die Spalte keine benannte Inline-Enum ist. */
    fun noteFor(tableName: String, colName: String, type: NeutralType): TransformationNote? {
        val name = EnumValueCheck.explicitName(type) ?: return null
        return TransformationNote(
            type = NoteType.WARNING,
            code = W_CODE,
            objectName = "$tableName.$colName",
            message = message(colName, name),
            hint = HINT,
        )
    }

    /** Die Migrate-Note an einer Render-Stelle des Diff-Pfads. */
    fun warnIfNamed(op: DiffOperation, ctx: MysqlDiffRenderContext, colName: String, col: ColumnDefinition) {
        val name = EnumValueCheck.explicitName(col.type) ?: return
        ctx.warning(op, message(colName, name), code = W_CODE)
    }
}
