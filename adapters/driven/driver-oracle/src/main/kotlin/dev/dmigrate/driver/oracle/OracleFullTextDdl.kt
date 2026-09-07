package dev.dmigrate.driver.oracle

import dev.dmigrate.core.model.IndexDefinition
import dev.dmigrate.driver.DdlStatement
import dev.dmigrate.driver.ManualActionRequired
import dev.dmigrate.driver.NoteType
import dev.dmigrate.driver.TransformationNote

/**
 * Volltext-Indizes fuer Oracle Text (ADR 0052).
 *
 * ```sql
 * CREATE INDEX "ft_docs_body" ON "docs" ("body")
 *     INDEXTYPE IS CTXSYS.CONTEXT PARAMETERS ('SYNC (ON COMMIT)');
 * ```
 *
 * Drei gemessene Eigenschaften bestimmen die Form (gvenzl/oracle-free:23,
 * 2026-09-07):
 *
 * - **Genau eine Spalte.** `ORA-29851: cannot build a domain index on more
 *   than one column`. Das neutrale Modell laesst mehrere Quellspalten zu
 *   (MySQL und SQL Server tragen sie nativ); fuer Oracle braeuchte es einen
 *   `MULTI_COLUMN_DATASTORE` — eine benannte, schema-globale
 *   `CTX_DDL`-Preference, also PL/SQL statt DDL und ein Objekt, das das
 *   neutrale Modell nicht kennt. Mehrspaltig wird deshalb abgelehnt, nicht
 *   in mehrere Einzelindizes zerlegt: `CONTAINS(a, …)` ueber getrennte
 *   Indizes ist etwas anderes als eine Suche ueber beide Spalten.
 * - **Je Spalte hoechstens einer.** `ORA-29879: cannot create multiple domain
 *   indexes on a column list using same indextype`.
 * - **`SYNC (ON COMMIT)` ist nicht optional.** Ohne die Klausel ist der Index
 *   nach `INSERT` + `COMMIT` leer (0 Treffer, mit der Klausel
 *   1) und bleibt es, bis jemand `CTX_DDL.SYNC_INDEX` ruft. Ein migriertes
 *   Schema haette damit einen Index, der nichts findet — die Klausel steht
 *   deshalb immer.
 */
internal object OracleFullTextDdl {

    /** Was Oracle statt eines Spaltentyps als Indexart traegt. */
    const val INDEXTYPE = "CTXSYS.CONTEXT"

    /** Der Parameterwert, ohne den der Index stumm leer bleibt. */
    const val SYNC_ON_COMMIT = "SYNC (ON COMMIT)"

    fun render(
        tableName: String,
        index: IndexDefinition,
        indexName: String,
        quoteIdentifier: (String) -> String,
    ): DdlStatement {
        if (index.columns.size != 1) {
            return DdlStatement(
                "",
                listOf(
                    ManualActionRequired(
                        code = "E057", objectType = "index", objectName = indexName,
                        reason = "Full-text index '$indexName' on table '$tableName' covers " +
                            "${index.columns.size} columns; an Oracle Text index covers exactly one " +
                            "(ORA-29851), and splitting it would change what a search matches.",
                        hint = "Index a single column, or create a MULTI_COLUMN_DATASTORE preference and the " +
                            "index manually on the target.",
                    ).toNote(),
                ),
            )
        }
        val column = quoteIdentifier(index.columns.single().name)
        val sql = "CREATE INDEX ${quoteIdentifier(indexName)} ON ${quoteIdentifier(tableName)} ($column) " +
            "INDEXTYPE IS $INDEXTYPE PARAMETERS ('$SYNC_ON_COMMIT');"
        return DdlStatement(sql, configDropNote(index, indexName, tableName) + uniqueDropNote(index, indexName))
    }

    /**
     * `unique` hat an einem Volltext-Index keine Bedeutung und keine
     * Entsprechung in Oracle Text. Verworfen wird es ohnehin — gemeldet,
     * weil es im Fingerabdruck steht und der Anwender es geschrieben hat.
     */
    private fun uniqueDropNote(index: IndexDefinition, indexName: String): List<TransformationNote> {
        if (!index.unique) return emptyList()
        return listOf(
            TransformationNote(
                type = NoteType.WARNING, code = "W102", objectName = indexName,
                message = "Full-text index '$indexName' is declared unique; an Oracle Text index has no " +
                    "such notion, so the declaration was dropped.",
                hint = "Remove `unique` from the full-text index, or add a separate unique index if the " +
                    "constraint is intended.",
            ),
        )
    }

    /**
     * Die Text-Search-Konfiguration des Modells (ADR 0025, z. B. `english`)
     * hat in Oracle keine Entsprechung in der Index-Anweisung: sie waere ein
     * `LEXER`, und der ist wie der Datastore eine benannte Preference.
     * Gemeldet statt verschwiegen — die Zerlegung in Woerter unterscheidet
     * sich sonst still von der Quelle.
     */
    private fun configDropNote(
        index: IndexDefinition,
        indexName: String,
        tableName: String,
    ): List<TransformationNote> {
        val config = index.textSearchConfig ?: return emptyList()
        return listOf(
            TransformationNote(
                type = NoteType.WARNING, code = "W154", objectName = indexName,
                message = "Text search configuration '$config' of index '$indexName' on table '$tableName' " +
                    "was dropped: Oracle selects the analyzer through a named CTX_DDL lexer preference, " +
                    "which the neutral model does not carry; the database default lexer applies.",
                hint = "Create a lexer preference and rebuild the index manually if the source " +
                    "configuration matters.",
            ),
        )
    }
}
