package dev.dmigrate.driver.oracle

import dev.dmigrate.core.model.ColumnGeneration
import dev.dmigrate.driver.SchemaReadNote
import dev.dmigrate.driver.metadata.GeneratedColumnNotes
import dev.dmigrate.driver.metadata.JdbcOperations

/**
 * Welche Spalten einer Oracle-Tabelle berechnet sind — und mit welchem
 * Ausdruck.
 *
 * **Der Ausdruck kommt in beiden Formen aus `DATA_DEFAULT`**, virtuell wie
 * materialisiert; er muss nicht aus Text geschnitten werden. Was der Katalog
 * nicht hergibt, ist die **Einordnung** der materialisierten Form: dort steht
 * sie wie eine gewoehnliche Spalte mit `DEFAULT` (gemessen an Oracle 23:
 * `VIRTUAL_COLUMN = 'NO'`, `USER_GENERATED = 'YES'`). Dafuer — und nur dafuer —
 * wird die DDL gelesen ([OracleGeneratedColumnScanner]).
 *
 * **Die DDL wird nicht fuer jede Tabelle geholt.** Ein `GET_DDL` je Tabelle
 * waere auf einem grossen Schema teuer, und die weit ueberwiegende Zahl der
 * Defaults kann keine Berechnung sein: ein einfaches Literal
 * ([isPlainDefault]) ist als `GENERATED ALWAYS AS (…)` nicht von einem
 * `DEFAULT` zu unterscheiden — weder im Katalog noch im Verhalten, das ein
 * Reverse abbilden kann. Alles darueber (`"qty"*"price"`, `1+1`, `SYS_GUID()`)
 * macht die Tabelle zum Kandidaten und kostet den einen Aufruf.
 *
 * **Die Grenze, gemessen.** Oracle nimmt `GENERATED ALWAYS AS (1+1)
 * MATERIALIZED` an — ein Ausdruck muss keine Spalte nennen. Deshalb reicht
 * „nennt eine Nachbarspalte" als Filter nicht; `1+1` ist kein einfaches
 * Literal und faellt in den Kandidatenfall. Was bleibt, ist der Ausdruck, der
 * *selbst* nur ein Literal ist (`GENERATED ALWAYS AS (7) MATERIALIZED`): der
 * kommt als `DEFAULT 7` durch. Im Katalog ist er von einem Default in keinem
 * Feld unterscheidbar (gemessen ueber alle Spalten von `ALL_TAB_COLS`), und
 * eine stets konstante gespeicherte Spalte ist der Fall, den niemand baut.
 */
internal object OracleGeneratedColumns {

    fun read(
        session: JdbcOperations,
        schema: String,
        table: String,
        columns: List<OracleMetadataQueries.ColumnRow>,
        notes: MutableList<SchemaReadNote>,
    ): Map<String, ColumnGeneration.Computed> {
        // Dieselbe Abfrage, die der Schreibpfad benutzt, um nicht in virtuelle
        // Spalten zu schreiben.
        val virtual = OracleMetadataQueries.virtualColumns(session, schema, table)

        val result = mutableMapOf<String, ColumnGeneration.Computed>()
        columns.filter { it.name in virtual }.forEach { row ->
            val expression = row.defaultDefinition?.takeIf { it.isNotBlank() }
            if (expression == null) {
                // Der Ausdruck einer virtuellen Spalte steht in `data_default`.
                // Steht er dort nicht, ist er nicht zu retten.
                notes += GeneratedColumnNotes.expressionDropped(table, row.name, row.defaultDefinition)
            } else {
                result[row.name] = ColumnGeneration.Computed(expression.trim(), stored = false)
            }
        }

        val candidates = columns.filter { row ->
            row.name !in virtual &&
                !row.isIdentity &&
                !row.defaultDefinition.isNullOrBlank() &&
                !isPlainDefault(row.defaultDefinition)
        }
        if (candidates.isEmpty()) return result

        val ddl = OracleMetadataQueries.tableDdl(session, schema, table)
        if (ddl == null) {
            notes += GeneratedColumnNotes.generationUndecidable(table, candidates.map { it.name })
            return result
        }
        val materialized = OracleGeneratedColumnScanner.materializedColumns(ddl)
        candidates.filter { it.name in materialized }.forEach { row ->
            result[row.name] = ColumnGeneration.Computed(row.defaultDefinition!!.trim(), stored = true)
        }
        return result
    }

    /**
     * Ist [definition] ein einfaches Literal — eine Zahl, ein Textliteral,
     * `NULL` oder eines der Datums-/Zeit-Schluesselwoerter?
     *
     * Nur dann ist ausgeschlossen, dass die Spalte berechnet ist *und* der
     * Unterschied im Reverse ueberhaupt ankaeme.
     */
    private fun isPlainDefault(definition: String): Boolean {
        val text = definition.trim().removeSuffix(";").trim()
        return NUMERIC.matches(text) ||
            TEXT_LITERAL.matches(text) ||
            text.uppercase() in PLAIN_KEYWORDS
    }

    private val NUMERIC = Regex("""[+-]?(\d+(\.\d*)?|\.\d+)([eE][+-]?\d+)?""")

    /** Ein einzelnes Textliteral; verdoppelte Anfuehrungen gehoeren dazu. */
    private val TEXT_LITERAL = Regex("""'([^']|'')*'""")

    private val PLAIN_KEYWORDS = setOf(
        "NULL",
        "SYSDATE",
        "SYSTIMESTAMP",
        "CURRENT_DATE",
        "CURRENT_TIMESTAMP",
        "LOCALTIMESTAMP",
        "USER",
        "SYS_GUID()",
    )
}
