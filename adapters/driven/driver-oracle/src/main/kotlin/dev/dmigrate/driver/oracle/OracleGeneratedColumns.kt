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

        val candidates = materializedCandidates(columns, virtual)
        if (candidates.isEmpty()) return result

        val materialized = materializedNames(session, schema, table)
        if (materialized == null) {
            notes += GeneratedColumnNotes.generationUndecidable(table, candidates.map { it.name })
            return result
        }
        candidates.filter { it.name in materialized }.forEach { row ->
            result[row.name] = ColumnGeneration.Computed(row.defaultDefinition!!.trim(), stored = true)
        }
        return result
    }

    /**
     * Die **Namen** der berechneten Spalten -- virtuell wie materialisiert.
     *
     * Der Schreibpfad braucht nur sie: Oracle lehnt jeden Wert fuer eine
     * berechnete Spalte ab (ORA-54013), und *welcher* Ausdruck dahintersteht,
     * aendert daran nichts. Am `GET_DDL` kommt aber auch er nicht vorbei --
     * die materialisierte Form ist im Katalog von einem `DEFAULT` nicht zu
     * unterscheiden (siehe oben); ist das Paket nicht ausfuehrbar, bleibt die
     * Spalte fuer den Import unsichtbar und der Treiberfehler steht. Anders als
     * beim Lesen gibt es dafuer keinen Kanal fuer eine Notiz: der Import
     * scheitert in diesem Fall so, wie er es ohne diese Pruefung immer taete.
     */
    fun names(session: JdbcOperations, schema: String, table: String): Set<String> {
        val virtual = OracleMetadataQueries.virtualColumns(session, schema, table)
        val candidates = materializedCandidates(
            OracleMetadataQueries.listColumns(session, schema, table),
            virtual,
        )
        if (candidates.isEmpty()) return virtual
        val materialized = materializedNames(session, schema, table) ?: return virtual
        return virtual + candidates.map { it.name }.filter { it in materialized }
    }

    /**
     * Spalten, bei denen eine materialisierte Berechnung ueberhaupt in Frage
     * kommt -- alles andere braucht den DDL-Aufruf nicht ([isPlainDefault]).
     */
    private fun materializedCandidates(
        columns: List<OracleMetadataQueries.ColumnRow>,
        virtual: Set<String>,
    ): List<OracleMetadataQueries.ColumnRow> = columns.filter { row ->
        row.name !in virtual &&
            !row.isIdentity &&
            !row.defaultDefinition.isNullOrBlank() &&
            !isPlainDefault(row.defaultDefinition)
    }

    /**
     * Die materialisiert berechneten Spalten laut abgelegter DDL -- `null`, wenn
     * `DBMS_METADATA` nicht ausfuehrbar ist (die Einordnung bleibt dann offen).
     * Nur aufrufen, wenn es Kandidaten gibt: der Aufruf kostet.
     */
    private fun materializedNames(
        session: JdbcOperations,
        schema: String,
        table: String,
    ): Set<String>? {
        val ddl = OracleMetadataQueries.tableDdl(session, schema, table) ?: return null
        return OracleGeneratedColumnScanner.materializedColumns(ddl)
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
