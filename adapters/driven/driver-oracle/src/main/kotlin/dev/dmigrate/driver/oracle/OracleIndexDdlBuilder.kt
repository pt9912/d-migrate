package dev.dmigrate.driver.oracle

import dev.dmigrate.core.model.IndexColumn
import dev.dmigrate.core.model.IndexDefinition
import dev.dmigrate.core.model.IndexType
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.core.model.isSpatialGeometryIndex
import dev.dmigrate.driver.DdlStatement
import dev.dmigrate.driver.ManualActionRequired
import dev.dmigrate.driver.NoteType
import dev.dmigrate.driver.TransformationNote

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
        index.name ?: "idx_${tableName}_${index.columnNames.joinToString("_")}"

    fun render(tableName: String, table: TableDefinition, index: IndexDefinition, lobColumns: Set<String>): DdlStatement {
        val indexName = effectiveName(tableName, index)
        val columns = table.columns

        // Oracle Text (Slice 8) baut Volltext-Indizes noch nicht.
        if (index.type == IndexType.FULLTEXT) {
            return actionRequired(
                ManualActionRequired(
                    code = "E057", objectType = "index", objectName = indexName,
                    reason = "Full-text index '$indexName' on table '$tableName' is not rendered for Oracle: " +
                        "Oracle Text indexing is not carried by the neutral model yet.",
                    hint = "Create an Oracle Text index (CTXSYS.CONTEXT) manually on the target.",
                ),
            )
        }
        // Spatial ist nicht gescoped; eine Tabelle mit Geometry-Spalten ist
        // bereits vor generateIndices geblockt (canGenerateSpatial=false).
        if (index.isSpatialGeometryIndex { columns[it]?.type }) {
            return actionRequired(
                ManualActionRequired(
                    code = "E052", objectType = "index", objectName = indexName,
                    reason = "Spatial index '$indexName' on table '$tableName' is not rendered for Oracle: " +
                        "SDO_GEOMETRY indexing is not scoped yet.",
                    hint = "Create the spatial index manually once the column is migrated.",
                ),
            )
        }
        index.columns.firstOrNull { it.name in lobColumns }?.let { offending ->
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
        if (index.type != IndexType.BTREE) {
            notes += TransformationNote(
                type = NoteType.WARNING, code = "W102", objectName = indexName,
                message = "${index.type.name} index '$indexName' has no Oracle equivalent; created as a " +
                    "standard B-tree index.",
                hint = "Oracle B-tree indexes cover most access patterns; review whether the index is still useful.",
            )
        }
        val cols = index.columns.joinToString(", ") { renderIndexColumn(it) }
        val sql = buildString {
            append("CREATE ")
            if (index.unique) append("UNIQUE ")
            append("INDEX ${quoteIdentifier(indexName)} ON ${quoteIdentifier(tableName)} ($cols);")
        }
        return DdlStatement(sql, notes)
    }

    private fun renderIndexColumn(column: IndexColumn): String =
        buildString {
            append(quoteIdentifier(column.name))
            column.direction?.let { append(" ${it.name}") }
        }

    private fun actionRequired(action: ManualActionRequired): DdlStatement = DdlStatement("", listOf(action.toNote()))
}
