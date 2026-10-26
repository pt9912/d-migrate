package dev.dmigrate.driver.mysql

import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.driver.NoteType
import dev.dmigrate.driver.TransformationNote

/**
 * Eine Zeitspalte mit Zeitzone, die auf MySQL ihre Zone verliert (`W100`).
 *
 * MySQL hat keinen Typ mit Zeitzone; [MysqlTypeMapper] rendert jedes
 * `DateTime` als `DATETIME`. Die Regel haengt am Typ, nicht an der Art der
 * Spalte: eine gewoehnliche Spalte und eine Domain-Spalte, deren Basistyp
 * `timestamptz` ist, verlieren die Zone gleichermassen.
 */
internal object MysqlTimezoneDegradation {

    const val W_CODE: String = "W100"

    /** Die Generate-Note, oder `null`, wenn der Typ keine Zeitzone traegt. */
    fun noteFor(tableName: String, colName: String, type: NeutralType): TransformationNote? {
        if (type !is NeutralType.DateTime || !type.timezone) return null
        return TransformationNote(
            type = NoteType.WARNING,
            code = W_CODE,
            objectName = "$tableName.$colName",
            message = "DATETIME with timezone on column '$colName' mapped to DATETIME in MySQL " +
                "which does not support time zones.",
            hint = "Store timezone information in a separate column or use UTC consistently.",
        )
    }
}
