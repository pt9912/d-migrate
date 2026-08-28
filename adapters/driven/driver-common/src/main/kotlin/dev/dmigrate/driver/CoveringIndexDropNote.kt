package dev.dmigrate.driver

import dev.dmigrate.core.model.IndexDefinition

/**
 * [ADR 0049]: MySQL und SQLite kennen weder INCLUDE-Spalten noch eine Steuerung
 * der Ablage. Beides faellt beim Generate weg — gueltiges DDL, aber ein anderer
 * Index als der beschriebene, und deshalb nicht stillschweigend.
 *
 * Die eingeschlossenen Spalten werden **nicht** an die Schluesselspalten
 * angehaengt. Das waere die naheliegende Rettung, aendert bei einem
 * `unique`-Index aber die Eindeutigkeit: `UNIQUE (a) INCLUDE (b)` erzwingt einen
 * Wert je `a`, `UNIQUE (a, b)` nur je Paar. Lieber ein Index, der weniger
 * abdeckt, als einer, der andere Zeilen zulaesst.
 */
object CoveringIndexDropNote {

    fun forDialect(
        index: IndexDefinition,
        indexName: String,
        dialect: String,
    ): List<TransformationNote> = buildList {
        if (index.includeColumns.isNotEmpty()) {
            add(
                TransformationNote(
                    type = NoteType.WARNING,
                    code = "W142",
                    objectName = indexName,
                    message = "INCLUDE columns (${index.includeColumns.joinToString(", ")}) on index " +
                        "'$indexName' were dropped: $dialect has no covering-index concept; only the key " +
                        "columns are indexed.",
                    hint = "Queries relying on the index alone may now hit the table. Add the columns to the " +
                        "key if the changed uniqueness semantics are acceptable.",
                ),
            )
        }
        if (index.clustered) {
            add(
                TransformationNote(
                    type = NoteType.WARNING,
                    code = "W143",
                    objectName = indexName,
                    message = "Index '$indexName' was created as an ordinary index: $dialect does not let the " +
                        "table's physical storage be steered.",
                    hint = "MySQL clusters on the primary key (InnoDB), SQLite on the rowid; neither is " +
                        "selectable. Model the desired order as the primary key if it matters.",
                ),
            )
        }
    }
}
