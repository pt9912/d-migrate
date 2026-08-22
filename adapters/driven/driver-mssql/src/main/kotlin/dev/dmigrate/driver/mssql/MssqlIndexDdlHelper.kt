package dev.dmigrate.driver.mssql

import dev.dmigrate.core.model.IndexColumn
import dev.dmigrate.core.model.IndexDefinition
import dev.dmigrate.core.model.IndexSortDirection
import dev.dmigrate.core.model.IndexType
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.core.model.isSpatialGeometryIndex
import dev.dmigrate.driver.DdlStatement
import dev.dmigrate.driver.IndexPrefixDropNote
import dev.dmigrate.driver.ManualActionRequired
import dev.dmigrate.driver.NoteType
import dev.dmigrate.driver.TransformationNote

/**
 * `CREATE INDEX` für T-SQL. Indexnamen sind in SQL Server tabellenlokal
 * (wie MySQL), darum braucht es keinen schema-globalen Allokator; ohne
 * Modellname gilt `idx_<table>_<cols>`. Clustered/nonclustered wird nicht
 * gesteuert (SQL-Server-Default: nonclustered; Regeln in
 * `spec/ddl-generation-rules.md`).
 */
internal class MssqlIndexDdlHelper(
    private val quoteIdentifier: (String) -> String,
    private val typeMapper: MssqlTypeMapper,
) {

    /** [lobColumns]: Spalten, die als LOB gerendert werden (siehe [MssqlColumnConstraintHelper.lobColumns]). */
    fun generateIndices(tableName: String, table: TableDefinition, lobColumns: Set<String>): List<DdlStatement> =
        table.indices.map { generateIndex(tableName, table, it, lobColumns) }

    /**
     * Ein einzelner Index — der Eintrittspunkt fuer den Diff-Pfad, der Indizes
     * operationsweise statt tabellenweise rendert. Bewusst dieselbe Funktion
     * wie [generateIndices]: `generate` und `migrate` muessen denselben Index
     * schreiben, sonst meldet der Postcompare Drift.
     */
    fun generateIndex(
        tableName: String,
        table: TableDefinition,
        index: IndexDefinition,
        lobColumns: Set<String>,
    ): DdlStatement {
        val indexName = index.name ?: "idx_${tableName}_${index.columnNames.joinToString("_")}"
        val columns = table.columns

        // ADR 0025: Volltext braucht in SQL Server einen Full-Text-Katalog und
        // einen eindeutigen Schlüsselindex — nichts davon trägt das Modell.
        if (index.type == IndexType.FULLTEXT) {
            return actionRequired(
                ManualActionRequired(
                    code = "E057", objectType = "index", objectName = indexName,
                    reason = "Full-text index '$indexName' on table '$tableName' is not rendered for SQL Server: " +
                        "it requires a full-text catalog and a unique key index that the neutral model does not carry.",
                    hint = "Create the full-text catalog and CREATE FULLTEXT INDEX manually on the target.",
                ),
            )
        }
        if (index.isSpatialGeometryIndex { columns[it]?.type }) {
            return spatialIndex(tableName, table, index, indexName, lobColumns)
        }
        index.columns.firstOrNull { it.name in lobColumns }?.let { offending ->
            return DdlStatement(
                "",
                listOf(
                    TransformationNote(
                        type = NoteType.WARNING, code = "W141", objectName = indexName,
                        message = "Index '$indexName' on column '${offending.name}' was skipped: the column is a " +
                            "large-object type (NVARCHAR(MAX)/VARBINARY(MAX)/XML) which SQL Server does not " +
                            "allow as an index key.",
                        hint = "Index a bounded NVARCHAR(n) column instead, or use INCLUDE columns manually.",
                    ),
                ),
            )
        }

        val notes = IndexPrefixDropNote
            .forDialect(index, indexName, "SQL Server", "a computed column over LEFT(col, n)")
            .toMutableList()
        if (index.type != IndexType.BTREE) {
            notes += TransformationNote(
                type = NoteType.WARNING, code = "W102", objectName = indexName,
                message = "${index.type.name} index '$indexName' has no SQL Server equivalent; created as a " +
                    "nonclustered index.",
                hint = "SQL Server rowstore indexes are B-tree based; review whether the index is still useful.",
            )
        }
        val cols = index.columns.joinToString(", ") { renderIndexColumn(it) }
        val sql = buildString {
            append("CREATE ")
            if (index.unique) append("UNIQUE ")
            append("INDEX ${quoteIdentifier(indexName)} ON ${quoteIdentifier(tableName)} ($cols)")
            if (index.where != null) append(" WHERE ${index.where}")
            append(";")
        }
        return DdlStatement(sql, notes)
    }

    /**
     * Räumlicher Index: SQL Server verlangt einen clustered Primary Key und genau
     * eine Spalte. Auf `geography` (geodätischer SRID) ist er dann direkt
     * renderbar (GEOGRAPHY_AUTO_GRID); planares `geometry` verlangt zusätzlich
     * BOUNDING_BOX-Tessellation, die das Modell nicht kennt.
     */
    private fun spatialIndex(
        tableName: String,
        table: TableDefinition,
        index: IndexDefinition,
        indexName: String,
        lobColumns: Set<String>,
    ): DdlStatement {
        val column = index.columns.singleOrNull()
        val geodetic = column != null && isGeodeticColumn(table.columns[column.name]?.type)
        // Der PK gilt nur als vorhanden, wenn er auch gerendert wird (kein LOB-Schlüssel, E057).
        val renderedPk = table.primaryKey.isNotEmpty() && table.primaryKey.none { it in lobColumns }
        val blocker = when {
            !renderedPk -> "the table has no primary key (SQL Server requires a clustered primary key)"
            column == null -> "SQL Server spatial indexes cover exactly one column"
            !geodetic -> "a geometry spatial index needs BOUNDING_BOX tessellation parameters that the neutral " +
                "model does not carry"
            else -> null
        }
        if (blocker != null) {
            return actionRequired(
                ManualActionRequired(
                    code = "E057", objectType = "index", objectName = indexName,
                    reason = "Spatial index '$indexName' on table '$tableName' is not rendered for SQL Server: $blocker.",
                    hint = "Run CREATE SPATIAL INDEX manually on the target once the prerequisites are met.",
                ),
            )
        }
        return DdlStatement(
            "CREATE SPATIAL INDEX ${quoteIdentifier(indexName)} ON ${quoteIdentifier(tableName)} " +
                "(${quoteIdentifier(column!!.name)});",
        )
    }

    private fun isGeodeticColumn(type: NeutralType?): Boolean =
        type is NeutralType.Geometry && typeMapper.isGeodeticSrid(type.srid)

    private fun renderIndexColumn(column: IndexColumn): String = buildString {
        append(quoteIdentifier(column.name))
        if (column.direction == IndexSortDirection.DESC) append(" DESC")
    }

    private fun actionRequired(action: ManualActionRequired): DdlStatement =
        DdlStatement("", listOf(action.toNote()))
}
