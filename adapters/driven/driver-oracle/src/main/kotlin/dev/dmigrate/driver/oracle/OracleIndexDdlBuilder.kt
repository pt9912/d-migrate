package dev.dmigrate.driver.oracle

import dev.dmigrate.core.model.IndexColumn
import dev.dmigrate.core.model.IndexDefinition
import dev.dmigrate.core.model.IndexType
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.core.model.isSpatialGeometryIndex
import dev.dmigrate.driver.DdlStatement
import dev.dmigrate.driver.renderKey
import dev.dmigrate.driver.NoteType
import dev.dmigrate.driver.TransformationNote
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.RawSqlExpressionPortability

/**
 * Index-DDL fuer Oracle, aus [OracleDdlGenerator] ausgelagert (Slice 5a):
 * einzige Quelle fuer sowohl den Generate-Pfad (`generateIndices`) als auch
 * den Diff-Pfad (`OracleDiffTableOps.renderCreateTable`, `OracleDiffObjectOps`
 * ab Sub-Slice 5b) -- ein neu angelegter Index soll unabhaengig vom Aufrufer
 * dieselbe SQL bekommen, nicht eine zweite, moeglicherweise driftende Kopie.
 */
internal class OracleIndexDdlBuilder(
    private val quoteIdentifier: (String) -> String,
) {

    /**
     * Der Name, unter dem der Index tatsaechlich entsteht -- fuer einen
     * anonymen Index aus Tabellen- und Spaltennamen gebildet. Einzige Quelle
     * fuer Anlegen UND Loeschen (`OracleDiffObjectOps`): berechneten beide
     * Seiten ihn getrennt, koennte ein `DROP INDEX` einen anderen Namen
     * treffen als das `CREATE INDEX` vergeben hat.
     */
    fun effectiveName(tableName: String, index: IndexDefinition): String =
        index.name ?: "idx_${tableName}_${index.keyLabels.joinToString("_")}"

    fun render(tableName: String, table: TableDefinition, index: IndexDefinition, unkeyableColumns: Set<String>): DdlStatement {
        val indexName = effectiveName(tableName, index)
        RawSqlExpressionPortability.indexRefusal(index, indexName, DatabaseDialect.ORACLE)?.let { return it }
        val columns = table.columns

        // Volltext VOR dem LOB-Waechter unten: eine CLOB-Spalte ist fuer einen
        // gewoehnlichen Index kein zulaessiger Schluessel, fuer einen
        // Oracle-Text-Index dagegen der Normalfall.
        if (index.type == IndexType.FULLTEXT) {
            return OracleFullTextDdl.render(tableName, index, indexName, quoteIdentifier)
        }
        // Ebenfalls vor dem LOB-Waechter: eine Geometriespalte ist fuer einen
        // gewoehnlichen Index kein zulaessiger Schluessel, fuer einen
        // raeumlichen der Normalfall.
        if (index.isSpatialGeometryIndex { columns[it]?.type }) {
            return OracleSpatialIndexDdl.render(tableName, index, indexName, quoteIdentifier)
        }
        index.columns.firstOrNull { it.name in unkeyableColumns }?.let { offending ->
            return DdlStatement(
                "",
                listOf(
                    TransformationNote(
                        type = NoteType.WARNING, code = "W152", objectName = indexName,
                        message = "Index '$indexName' on column '${offending.name}' was skipped: the column is a " +
                            "large-object type (CLOB/BLOB) which Oracle does not allow as an index key.",
                        hint = "Index a bounded VARCHAR2(n) column instead.",
                    ),
                ),
            )
        }

        val notes = mutableListOf<TransformationNote>()
        // Bitmap ist der einzige Nicht-BTREE-Typ, den Oracle nativ rendert.
        // `UNIQUE BITMAP` gibt es aber nicht (`ORA-00968`) --
        // ein als eindeutig deklarierter Bitmap-Index wird deshalb ein
        // eindeutiger B-Tree, denn die Eindeutigkeit ist die staerkere Zusage.
        val bitmap = index.type == IndexType.BITMAP && !index.unique
        if (index.type == IndexType.BITMAP && index.unique) {
            notes += TransformationNote(
                type = NoteType.WARNING, code = "W102", objectName = indexName,
                message = "BITMAP index '$indexName' is declared unique, which Oracle does not allow " +
                    "(there is no UNIQUE BITMAP INDEX); created as a unique B-tree index instead.",
                hint = "Drop `unique` to get a bitmap index, or keep it and accept the B-tree.",
            )
        } else if (index.type != IndexType.BTREE && index.type != IndexType.BITMAP) {
            notes += TransformationNote(
                type = NoteType.WARNING, code = "W102", objectName = indexName,
                message = "${index.type.name} index '$indexName' has no Oracle equivalent; created as a " +
                    "standard B-tree index.",
                hint = "Oracle B-tree indexes cover most access patterns; review whether the index is still useful.",
            )
        }
        // Oracle kennt kein `WHERE` an einer Index-Anweisung. Der Index
        // entsteht trotzdem, deckt dann aber mehr Zeilen ab als verlangt --
        // und bei `unique` aendert sich die Zusicherung inhaltlich: aus
        // "hoechstens eine passende Zeile je Schluessel" wird "hoechstens
        // eine Zeile je Schluessel ueberhaupt".
        index.where?.let { predicate ->
            notes += TransformationNote(
                type = NoteType.WARNING, code = "W155", objectName = indexName,
                message = "Partial index '$indexName' was created as a full index: Oracle has no index " +
                    "predicate, so the condition '$predicate' is not part of it." +
                    if (index.unique) " The uniqueness now covers every row, not only the matching ones." else "",
                hint = "Model the condition as a function-based index (CASE WHEN … THEN … END) if the " +
                    "restriction matters.",
            )
        }
        val cols = index.columns.joinToString(", ") { renderIndexColumn(it) }
        val sql = buildString {
            append("CREATE ")
            if (index.unique) append("UNIQUE ")
            if (bitmap) append("BITMAP ")
            append("INDEX ${quoteIdentifier(indexName)} ON ${quoteIdentifier(tableName)} ($cols);")
        }
        return DdlStatement(sql, notes)
    }

    private fun renderIndexColumn(column: IndexColumn): String =
        buildString {
            append(column.renderKey(quoteIdentifier))
            column.direction?.let { append(" ${it.name}") }
        }

}
