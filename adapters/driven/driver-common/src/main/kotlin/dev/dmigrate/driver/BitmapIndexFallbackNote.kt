package dev.dmigrate.driver

import dev.dmigrate.core.model.IndexDefinition
import dev.dmigrate.core.model.IndexType

/**
 * Der Bitmap-Index ist Oracle-eigen; kein anderer der gefuehrten Dialekte hat
 * ihn. Anders als GIN/GiST/BRIN/HASH ist er aber **kein** Index mit eigener
 * Semantik, sondern nur eine andere Ablageform ueber gewoehnliche Spalten —
 * ein B-Tree ueber denselben Spalten beantwortet dieselben Abfragen, nur mit
 * anderem Speicher- und Sperrverhalten. Er wird deshalb ueberall als
 * gewoehnlicher Index gerendert statt verworfen, und dieser Hinweis macht den
 * Wechsel der Zugriffsmethode sichtbar.
 *
 * Fuer Oracle selbst faellt der Hinweis weg (dort wird `CREATE BITMAP INDEX`
 * gerendert) — mit einer Ausnahme, die der Oracle-Index-Builder traegt: einen
 * als `unique` deklarierten Bitmap-Index gibt es auch in Oracle nicht.
 */
object BitmapIndexFallbackNote {

    fun forDialect(
        index: IndexDefinition,
        indexName: String,
        tableName: String,
        dialect: String,
    ): List<TransformationNote> {
        if (index.type != IndexType.BITMAP) return emptyList()
        return listOf(
            TransformationNote(
                type = NoteType.WARNING,
                code = "W102",
                objectName = indexName,
                message = "BITMAP index '$indexName' on table '$tableName' was created as an ordinary index: " +
                    "$dialect has no bitmap access method.",
                hint = "The indexed columns are unchanged; only the access method differs. Bitmap indexes " +
                    "are typically chosen for low-cardinality columns in read-mostly schemas.",
            )
        )
    }
}
