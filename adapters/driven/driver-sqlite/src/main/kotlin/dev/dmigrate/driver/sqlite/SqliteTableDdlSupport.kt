package dev.dmigrate.driver.sqlite

import dev.dmigrate.core.model.*
import dev.dmigrate.driver.*

internal class SqliteTableDdlSupport(
    private val quoteIdentifier: (String) -> String,
    private val columnConstraintHelper: SqliteColumnConstraintHelper,
    private val sequenceSupport: SqliteSequenceDdlSupport,
) {

    fun generateTable(
        name: String,
        table: TableDefinition,
        schema: SchemaDefinition,
        deferredFks: Set<Pair<String, String>>,
        deferredConstraints: Set<Pair<String, String>>,
        options: DdlGenerationOptions,
    ): List<DdlStatement> {
        // Defensive: drop any sequence-support notes that survived the
        // previous `generateTable` call (e.g. through a future early
        // return that bypasses the end-of-method drain). Pre-existing
        // notes belong to the previous table and would be wrong to
        // attach here; the contract is "every `generateTable` start
        // begins from a clean note slate".
        sequenceSupport.drainPendingNotes()

        val geometryColumns = table.columns.filter { it.value.type is NeutralType.Geometry }
        val isSpatiaLite = geometryColumns.isNotEmpty() && options.spatialProfile == SpatialProfile.SPATIALITE

        if (isSpatiaLite) {
            val blocked = checkSpatialMetadataBlocks(name, table, geometryColumns)
            if (blocked != null) return blocked
        }

        val notes = mutableListOf<TransformationNote>()
        val columnLines = buildColumnLines(name, table, schema, deferredFks, deferredConstraints, isSpatiaLite, notes)

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
        notes += sequenceSupport.drainPendingNotes()
        val statements = mutableListOf(DdlStatement(tableSql, notes))
        if (isSpatiaLite) statements += generateSpatiaLiteColumns(name, geometryColumns)
        return statements
    }

    fun generateIndices(
        tableName: String,
        table: TableDefinition,
        options: DdlGenerationOptions,
    ): List<DdlStatement> =
        generatedIndexNames(tableName, table.indices).mapIndexedNotNull { position, indexName ->
            generateIndex(tableName, table.indices[position], indexName, table.columns, options)
        }

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
        // VA4: ein Index auf einer Geometriespalte blockt die Tabelle NICHT mehr —
        // er wird separat als SpatiaLite `CreateSpatialIndex` emittiert (generateIndices).
        return null
    }

    private fun buildColumnLines(
        name: String,
        table: TableDefinition,
        schema: SchemaDefinition,
        deferredFks: Set<Pair<String, String>>,
        deferredConstraints: Set<Pair<String, String>>,
        isSpatiaLite: Boolean,
        notes: MutableList<TransformationNote>,
    ): List<String> {
        val lines = mutableListOf<String>()
        val normalColumns = table.columns.filter { it.value.type !is NeutralType.Geometry }
        val effectiveColumns = if (isSpatiaLite) normalColumns else table.columns
        for ((columnName, column) in effectiveColumns.inOrdinalOrder()) {
            lines += columnConstraintHelper.generateColumnSql(columnName, column, schema, name, notes, deferredFks)
        }
        for (constraint in table.constraints) {
            if ((name to constraint.name) in deferredConstraints) continue
            val clause = columnConstraintHelper.generateConstraintClause(constraint, notes, name)
            if (clause != null) lines += clause
        }
        val skipPrimaryKey = table.primaryKey.size == 1 && table.primaryKey.all { primaryKey ->
            val column = table.columns[primaryKey]
            column?.type is NeutralType.Identifier || column?.generation is ColumnGeneration.Identity
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

    private fun generateIndex(
        tableName: String,
        index: IndexDefinition,
        indexName: String,
        columns: Map<String, ColumnDefinition>,
        options: DdlGenerationOptions,
    ): DdlStatement? {
        // VA4: ein Index auf einer Geometriespalte → SpatiaLite `CreateSpatialIndex`
        // (R*Tree). Nur unter `--spatial-profile spatialite`; sonst geskippt mit Note.
        val geometryColumn = index.columnNames.firstOrNull { columns[it]?.type is NeutralType.Geometry }
        if (geometryColumn != null) {
            return spatialIndexStatement(tableName, geometryColumn, indexName, options)
        }
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

        val columns = index.columns.joinToString(", ") { renderIndexColumn(it) }
        val sql = buildString {
            append("CREATE ")
            if (index.unique) append("UNIQUE ")
            append("INDEX ${quoteIdentifier(indexName)} ON ${quoteIdentifier(tableName)} ($columns)")
            if (index.where != null) append(" WHERE ${index.where}")
            append(";")
        }
        return DdlStatement(sql, IndexPrefixDropNote.forDialect(index, indexName, "SQLite", "substr(col, 1, n)"))
    }

    /**
     * VA4: SpatiaLite `CreateSpatialIndex` für einen Geometrie-Index. Ohne
     * `--spatial-profile spatialite` wird er übersprungen (Note), da der Spatial-
     * Index die SpatiaLite-Extension + Geometrie-Metadaten voraussetzt.
     */
    private fun spatialIndexStatement(
        tableName: String,
        geometryColumn: String,
        indexName: String,
        options: DdlGenerationOptions,
    ): DdlStatement {
        if (options.spatialProfile != SpatialProfile.SPATIALITE) {
            return DdlStatement(
                "-- Index ${quoteIdentifier(indexName)} skipped: geometry index requires --spatial-profile spatialite",
                listOf(
                    TransformationNote(
                        type = NoteType.WARNING,
                        code = "SPATIAL_PROFILE_REQUIRED",
                        objectName = indexName,
                        message = "Spatial index '$indexName' on table '$tableName' requires the SpatiaLite profile.",
                        hint = "Re-run schema generate with --spatial-profile spatialite to emit CreateSpatialIndex.",
                    )
                ),
            )
        }
        val escapedTable = tableName.replace("'", "''")
        val escapedColumn = geometryColumn.replace("'", "''")
        return DdlStatement("SELECT CreateSpatialIndex('$escapedTable', '$escapedColumn');")
    }

    private fun renderIndexColumn(column: IndexColumn): String =
        buildString {
            val direction = column.direction
            append(quoteIdentifier(column.name))
            if (direction != null) append(" ${direction.name}")
        }

    private fun generatedIndexNames(tableName: String, indices: List<IndexDefinition>): List<String> {
        val baseNames = indices.map { index ->
            index.name ?: "idx_${tableName}_${index.columnNames.joinToString("_")}"
        }
        val baseCounts = baseNames.groupingBy { it }.eachCount()
        val used = indices.mapNotNull { it.name }.groupingBy { it }.eachCount().toMutableMap()
        return indices.mapIndexed { position, index ->
            index.name ?: disambiguateGeneratedIndexName(baseNames[position], index, baseCounts.getValue(baseNames[position]), used)
        }
    }

    private fun disambiguateGeneratedIndexName(
        baseName: String,
        index: IndexDefinition,
        baseCount: Int,
        used: MutableMap<String, Int>,
    ): String {
        val candidate = if (baseCount == 1) baseName else "${baseName}_${indexDisambiguationSuffix(index)}"
        val seen = used.getOrDefault(candidate, 0)
        used[candidate] = seen + 1
        return if (seen == 0) candidate else "${candidate}_${seen + 1}"
    }

    private fun indexDisambiguationSuffix(index: IndexDefinition): String {
        val directionPart = index.columns.joinToString("_") { it.direction?.name?.lowercase() ?: "default" }
        val wherePart = index.where?.let { "_where_${Integer.toUnsignedString(it.hashCode(), 36)}" }.orEmpty()
        return "$directionPart$wherePart"
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
