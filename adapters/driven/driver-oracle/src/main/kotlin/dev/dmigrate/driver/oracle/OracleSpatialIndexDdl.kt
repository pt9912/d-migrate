package dev.dmigrate.driver.oracle

import dev.dmigrate.core.model.IndexDefinition
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.DdlPhase
import dev.dmigrate.driver.DdlStatement
import dev.dmigrate.driver.ManualActionRequired
import dev.dmigrate.driver.NoteType
import dev.dmigrate.driver.SqlIdentifiers
import dev.dmigrate.driver.TransformationNote

/**
 * Raeumliche Indizes fuer Oracle Spatial.
 *
 * Der Index entsteht nicht als blankes `CREATE INDEX`, sondern in einem
 * PL/SQL-Block, der sich im Fehlerfall selbst aufraeumt:
 *
 * ```sql
 * BEGIN
 *   EXECUTE IMMEDIATE 'CREATE INDEX "ix_places_geom" ON "places" ("geom")
 *       INDEXTYPE IS MDSYS.SPATIAL_INDEX_V2';
 * EXCEPTION WHEN OTHERS THEN
 *   BEGIN EXECUTE IMMEDIATE 'DROP INDEX "ix_places_geom" FORCE';
 *   EXCEPTION WHEN OTHERS THEN NULL; END;
 *   RAISE;
 * END;
 * /
 * ```
 *
 * Drei Eigenschaften von Oracle Spatial bestimmen diese Form:
 *
 * - **Ein gescheiterter Spatial-Index sperrt die Tabelle.** Er bleibt mit
 *   `DOMIDX_OPSTATUS = FAILED` stehen, und danach scheitert *jedes* `INSERT`
 *   mit `ORA-29861`, bis jemand `DROP INDEX … FORCE` ruft. Ein blankes
 *   `CREATE INDEX` hinterliesse also im Fehlerfall eine unbeschreibbare
 *   Tabelle. Der Block darueber laesst nichts stehen -- gemessen: null
 *   Index-Reste, Tabelle weiter beschreibbar -- und meldet den Fehler
 *   trotzdem weiter (`RAISE`).
 * - **Der Index braucht die SRID, nicht die Metadatenzeile.**
 *   `MDSYS.SPATIAL_INDEX_V2` gelingt entweder mit einer Zeile in
 *   `USER_SDO_GEOM_METADATA` oder mit mindestens einer Geometriezeile, aus
 *   der er die SRID ableitet. Fehlt beides, kommt `ORA-13199`.
 * - **Die Metadatenzeile ist fuer d-migrate unerreichbar.**
 *   `MDSYS.SDO_GEOM_TRIG_INS1` hebt Tabellen- und Spaltennamen beim Einfuegen
 *   bedingungslos hoch; d-migrate quotiert dagegen wortgetreu und erzeugt
 *   kleingeschriebene Tabellen. Eine Zeile fuer `places` landete als `PLACES`
 *   und benennt damit eine andere Tabelle.
 *
 * Zusammen ergeben sie [DdlPhase.POST_DATA] als Ort des Index: erst nach den
 * Daten steht die SRID zur Verfuegung, aus der Oracle ihn bauen kann. Die
 * Phasenordnung `pre-data → Daten → post-data` sichert das Anwenderhandbuch
 * bereits zu.
 */
internal object OracleSpatialIndexDdl {

    /** Was Oracle statt eines Spaltentyps als Indexart traegt. */
    const val INDEXTYPE = "MDSYS.SPATIAL_INDEX_V2"

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
                        code = "E052", objectType = "index", objectName = indexName,
                        reason = "Spatial index '$indexName' on table '$tableName' covers " +
                            "${index.columns.size} columns; an Oracle spatial index covers exactly one " +
                            "(ORA-29851), and splitting it would change what a query matches.",
                        hint = "Index a single geometry column, or create the index manually on the target.",
                    ).toNote(DdlPhase.POST_DATA),
                ),
                phase = DdlPhase.POST_DATA,
            )
        }
        val column = quoteIdentifier(index.columns.single().name)
        val create = "CREATE INDEX ${quoteIdentifier(indexName)} ON ${quoteIdentifier(tableName)} " +
            "($column) INDEXTYPE IS $INDEXTYPE"
        val drop = "DROP INDEX ${quoteIdentifier(indexName)} FORCE"
        return DdlStatement(
            selfCleaningBlock(create, drop),
            uniqueDropNote(index, indexName),
            phase = DdlPhase.POST_DATA,
            // Der Block endet auf `END;`; ein Datei-Konsument erkennt sein
            // Ende erst am `/` in eigener Zeile.
            scriptTerminator = OracleRoutineDdl.PLSQL_SCRIPT_TERMINATOR,
        )
    }

    /**
     * Die Ruecknahme des von [render] erzeugten Blocks, oder `null`, wenn
     * [sql] keiner ist.
     *
     * Der Block traegt sein eigenes `DROP` bereits im Aufraeum-Zweig. Es von
     * dort zu holen, statt den Indexnamen ein zweites Mal aus dem Text zu
     * fischen, haelt Anlegen und Ruecknahme aneinander: was der Block im
     * Fehlerfall wegraeumt, raeumt auch das Rollback weg.
     *
     * `FORCE` faellt dabei weg -- es deckt den halb gebauten Index ab, den es
     * nach einem erfolgreichen `CREATE` nicht gibt.
     */
    fun invertedDrop(sql: String): String? {
        if (!sql.trimStart().startsWith("BEGIN") || !sql.contains(INDEXTYPE)) return null
        val drop = executeImmediateLiterals(sql).lastOrNull { it.startsWith("DROP INDEX ") } ?: return null
        return "${drop.removeSuffix(" FORCE")};"
    }

    /** Die einfach gequoteten Argumente aller `EXECUTE IMMEDIATE` in [sql]. */
    private fun executeImmediateLiterals(sql: String): List<String> =
        Regex("EXECUTE IMMEDIATE\\s+'((?:[^']|'')*)'")
            .findAll(sql)
            .map { it.groupValues[1].replace("''", "'") }
            .toList()

    /**
     * [create] ausfuehren und im Fehlerfall jeden Rest beseitigen, bevor der
     * Fehler weitergereicht wird.
     *
     * Das `EXCEPTION WHEN OTHERS THEN NULL` um das `DROP` herum ist
     * beabsichtigt und verschluckt nichts: es deckt nur den Fall ab, dass gar
     * kein Rest entstanden ist. Der eigentliche Fehler kommt gleich darauf
     * ueber [RAISE] heraus.
     */
    private fun selfCleaningBlock(create: String, drop: String): String = """
        BEGIN
          EXECUTE IMMEDIATE ${literal(create)};
        EXCEPTION WHEN OTHERS THEN
          BEGIN
            EXECUTE IMMEDIATE ${literal(drop)};
          EXCEPTION WHEN OTHERS THEN NULL;
          END;
          RAISE;
        END;
    """.trimIndent()

    private fun literal(sql: String): String = SqlIdentifiers.quoteStringLiteral(sql, DatabaseDialect.ORACLE)

    /**
     * `unique` hat an einem raeumlichen Index keine Bedeutung und keine
     * Entsprechung in Oracle Spatial. Verworfen wird es ohnehin -- gemeldet,
     * weil es im Fingerabdruck steht und der Anwender es geschrieben hat.
     */
    private fun uniqueDropNote(index: IndexDefinition, indexName: String): List<TransformationNote> {
        if (!index.unique) return emptyList()
        return listOf(
            TransformationNote(
                type = NoteType.WARNING, code = "W102", objectName = indexName,
                message = "Spatial index '$indexName' is declared unique; an Oracle spatial index has no " +
                    "such notion, so the declaration was dropped.",
                hint = "Remove `unique` from the spatial index, or add a separate unique index if the " +
                    "constraint is intended.",
                phase = DdlPhase.POST_DATA,
            ),
        )
    }
}
