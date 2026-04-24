package dev.dmigrate.driver.sqlite

import dev.dmigrate.core.model.*
import dev.dmigrate.driver.*

internal class SqliteTableDdlSupport(
    private val quoteIdentifier: (String) -> String,
    private val columnConstraintHelper: SqliteColumnConstraintHelper,
) {

    fun generateTable(
        name: String,
        table: TableDefinition,
        schema: SchemaDefinition,
        deferredFks: Set<Pair<String, String>>,
        options: DdlGenerationOptions,
    ): List<DdlStatement> {
        val geometryColumns = table.columns.filter { it.value.type is NeutralType.Geometry }
        val isSpatiaLite = geometryColumns.isNotEmpty() && options.spatialProfile == SpatialProfile.SPATIALITE

        if (isSpatiaLite) {
            val blocked = checkSpatialMetadataBlocks(name, table, geometryColumns)
            if (blocked != null) return blocked
        }

        val notes = mutableListOf<TransformationNote>()
        val columnLines = buildColumnLines(name, table, schema, deferredFks, isSpatiaLite, notes)

        if (table.partitioning != null) {
            notes += TransformationNote(
                type = NoteType.ACTION_REQUIRED,
                code = "E055",
                objectName = name,
                message = "Table partitioning is not supported in SQLite for table '$name'.",
                hint = "Partition data at the application level or use separate tables."
            )
        }

        val tableSql = "CREATE TABLE ${quoteIdentifier(name)} (\n${columnLines.joinToString(",\n") { "    $it" }}\n);"
        val statements = mutableListOf(DdlStatement(tableSql, notes))
        if (isSpatiaLite) statements += generateSpatiaLiteColumns(name, geometryColumns)
        return statements
    }

    fun generateIndices(tableName: String, table: TableDefinition): List<DdlStatement> =
        table.indices.mapNotNull { index -> generateIndex(tableName, index) }

    private fun checkSpatialMetadataBlocks(
        name: String,
        table: TableDefinition,
        geometryColumns: Map<String, ColumnDefinition>,
    ): List<DdlStatement>? {
        val geometryColumnNames = geometryColumns.keys
        for ((columnName, column) in geometryColumns) {
            if (hasSpatialMetadataConflict(table, columnName, column)) {
                return blockTableForSpatialMetadata(name, columnName, "required/unique/default/references/PK")
            }
        }
        for (constraint in table.constraints) {
            val blockingColumn = constraint.columns.orEmpty().firstOrNull { it in geometryColumnNames }
            if (blockingColumn != null) {
                return blockTableForSpatialMetadata(
                    name,
                    blockingColumn,
                    "table-level constraint '${constraint.name}'",
                )
            }
        }
        for (index in table.indices) {
            val blockingColumn = index.columns.firstOrNull { it in geometryColumnNames }
            if (blockingColumn != null) {
                return blockTableForSpatialMetadata(name, blockingColumn, "index on geometry column")
            }
        }
        return null
    }

    private fun buildColumnLines(
        name: String,
        table: TableDefinition,
        schema: SchemaDefinition,
        deferredFks: Set<Pair<String, String>>,
        isSpatiaLite: Boolean,
        notes: MutableList<TransformationNote>,
    ): List<String> {
        val lines = mutableListOf<String>()
        val normalColumns = table.columns.filter { it.value.type !is NeutralType.Geometry }
        val effectiveColumns = if (isSpatiaLite) normalColumns else table.columns
        for ((columnName, column) in effectiveColumns) {
            lines += columnConstraintHelper.generateColumnSql(columnName, column, schema, name, notes, deferredFks)
        }
        for (constraint in table.constraints) {
            lines += columnConstraintHelper.generateConstraintClause(constraint, notes)
        }
        val skipPrimaryKey = table.primaryKey.size == 1 && table.primaryKey.all { primaryKey ->
            table.columns[primaryKey]?.type is NeutralType.Identifier
        }
        if (table.primaryKey.isNotEmpty() && !skipPrimaryKey) {
            lines += "PRIMARY KEY (${table.primaryKey.joinToString(", ") { quoteIdentifier(it) }})"
        }
        return lines
    }

    private fun generateSpatiaLiteColumns(
        name: String,
        geometryColumns: Map<String, ColumnDefinition>,
    ): List<DdlStatement> =
        geometryColumns.map { (columnName, column) ->
            val geometry = column.type as NeutralType.Geometry
            val geometryType = geometry.geometryType.schemaName.uppercase()
            val srid = geometry.srid ?: 0
            DdlStatement(
                buildString {
                    append("SELECT AddGeometryColumn('")
                    append(name.replace("'", "''"))
                    append("', '")
                    append(columnName.replace("'", "''"))
                    append("', ")
                    append(srid)
                    append(", '")
                    append(geometryType)
                    append("', 'XY');")
                }
            )
        }

    private fun generateIndex(tableName: String, index: IndexDefinition): DdlStatement? {
        val indexName = index.name ?: "idx_${tableName}_${index.columns.joinToString("_")}"
        if (index.type != IndexType.BTREE) {
            return DdlStatement(
                "-- Index ${quoteIdentifier(indexName)} skipped: ${index.type.name} index type is not supported in SQLite",
                listOf(
                    TransformationNote(
                        type = NoteType.WARNING,
                        code = "W102",
                        objectName = indexName,
                        message = buildString {
                            append(index.type.name)
                            append(" index '")
                            append(indexName)
                            append("' on table '")
                            append(tableName)
                            append("' is not supported in SQLite. Only BTREE is available.")
                        },
                        hint = "The index has been skipped. If needed, create a standard BTREE index instead."
                    )
                )
            )
        }

        val columns = index.columns.joinToString(", ") { quoteIdentifier(it) }
        val sql = buildString {
            append("CREATE ")
            if (index.unique) append("UNIQUE ")
            append("INDEX ${quoteIdentifier(indexName)} ON ${quoteIdentifier(tableName)} ($columns);")
        }
        return DdlStatement(sql)
    }

    private fun blockTableForSpatialMetadata(
        table: String,
        column: String,
        reason: String,
    ): List<DdlStatement> =
        listOf(
            DdlStatement(
                "",
                notes = listOf(
                    TransformationNote(
                        type = NoteType.ACTION_REQUIRED,
                        code = "E052",
                        objectName = table,
                        message = "Geometry column '$column' has unsupported metadata ($reason) for SpatiaLite",
                        hint = "Remove metadata from geometry column or use a different dialect",
                        blocksTable = true,
                    )
                )
            )
        )

    private fun hasSpatialMetadataConflict(
        table: TableDefinition,
        columnName: String,
        column: ColumnDefinition,
    ): Boolean =
        column.required ||
            column.unique ||
            column.default != null ||
            column.references != null ||
            columnName in table.primaryKey
}
