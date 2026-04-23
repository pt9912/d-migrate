package dev.dmigrate.driver.sqlite

import dev.dmigrate.core.model.*
import dev.dmigrate.driver.*

class SqliteDdlGenerator : AbstractDdlGenerator(SqliteTypeMapper()) {

    override val dialect = DatabaseDialect.SQLITE

    private val routineHelper = SqliteRoutineDdlHelper(::quoteIdentifier)
    private val columnConstraintHelper = SqliteColumnConstraintHelper(
        ::quoteIdentifier, typeMapper, ::columnSql, ::referentialActionSql
    )

    // -- Quoting -----------------------------------------------

    override fun quoteIdentifier(name: String): String = SqlIdentifiers.quoteIdentifier(name, dialect)

    // -- Custom types (ENUM, COMPOSITE, DOMAIN) ----------------

    override fun generateCustomTypes(types: Map<String, CustomTypeDefinition>): List<DdlStatement> {
        // SQLite has no CREATE TYPE. ENUMs are handled inline with CHECK constraints.
        // Composite types are not supported. Domain types use base type + inline CHECK.
        val statements = mutableListOf<DdlStatement>()
        for ((name, typeDef) in types) {
            when (typeDef.kind) {
                CustomTypeKind.ENUM -> {
                    // No standalone CREATE TYPE for ENUM in SQLite; handled inline at column level.
                    // Emit a comment so the user knows it was intentionally skipped.
                    statements += DdlStatement(
                        "-- Enum type ${quoteIdentifier(name)} is handled inline via CHECK constraints",
                        listOf(
                            TransformationNote(
                                type = NoteType.INFO,
                                code = "I001",
                                objectName = name,
                                message = "Enum type '$name' mapped to inline TEXT + CHECK constraint in SQLite."
                            )
                        )
                    )
                }
                CustomTypeKind.COMPOSITE -> {
                    // NOT SUPPORTED
                    statements += DdlStatement(
                        "-- Composite type ${quoteIdentifier(name)} is not supported in SQLite",
                        listOf(
                            TransformationNote(
                                type = NoteType.ACTION_REQUIRED,
                                code = "E054",
                                objectName = name,
                                message = "Composite type '$name' is not supported in SQLite.",
                                hint = "Flatten composite fields into individual table columns or use JSON."
                            )
                        )
                    )
                }
                CustomTypeKind.DOMAIN -> {
                    // Domain types are not natively supported, but we map base type + inline CHECK
                    // at the column level. Emit an informational comment here.
                    statements += DdlStatement(
                        "-- Domain type ${quoteIdentifier(name)} is mapped to its base type with inline CHECK in SQLite",
                        listOf(
                            TransformationNote(
                                type = NoteType.INFO,
                                code = "I001",
                                objectName = name,
                                message = "Domain type '$name' mapped to base type with inline CHECK constraint in SQLite."
                            )
                        )
                    )
                }
            }
        }
        return statements
    }

    // -- Sequences ---------------------------------------------

    override fun generateSequences(
        sequences: Map<String, SequenceDefinition>,
        skipped: MutableList<SkippedObject>
    ): List<DdlStatement> {
        // SQLite does not support sequences. Skip each one with E056.
        val statements = mutableListOf<DdlStatement>()
        for ((name, _) in sequences) {
            skipped += SkippedObject("sequence", name, "Sequences are not supported in SQLite")
            statements += DdlStatement(
                "-- Sequence ${quoteIdentifier(name)} is not supported in SQLite",
                listOf(
                    TransformationNote(
                        type = NoteType.ACTION_REQUIRED,
                        code = "E056",
                        objectName = name,
                        message = "Sequence '$name' is not supported in SQLite.",
                        hint = "Use INTEGER PRIMARY KEY AUTOINCREMENT or application-level sequencing."
                    )
                )
            )
        }
        return statements
    }

    override fun canGenerateSpatial(profile: SpatialProfile): Boolean =
        profile == SpatialProfile.SPATIALITE

    // -- Tables ------------------------------------------------

    override fun generateTable(
        name: String,
        table: TableDefinition,
        schema: SchemaDefinition,
        deferredFks: Set<Pair<String, String>>,
        options: DdlGenerationOptions,
    ): List<DdlStatement> {
        val geometryCols = table.columns.filter { it.value.type is NeutralType.Geometry }
        val isSpatiaLite = geometryCols.isNotEmpty() && options.spatialProfile == SpatialProfile.SPATIALITE

        if (isSpatiaLite) {
            val blocked = checkSpatialMetadataBlocks(name, table, geometryCols)
            if (blocked != null) return blocked
        }

        val notes = mutableListOf<TransformationNote>()
        val columnLines = buildColumnLines(name, table, schema, deferredFks, isSpatiaLite, notes)

        if (table.partitioning != null) {
            notes += TransformationNote(
                type = NoteType.ACTION_REQUIRED, code = "E055", objectName = name,
                message = "Table partitioning is not supported in SQLite for table '$name'.",
                hint = "Partition data at the application level or use separate tables."
            )
        }

        val tableSql = "CREATE TABLE ${quoteIdentifier(name)} (\n${columnLines.joinToString(",\n") { "    $it" }}\n);"
        val statements = mutableListOf(DdlStatement(tableSql, notes))
        if (isSpatiaLite) statements += generateSpatiaLiteColumns(name, geometryCols)
        return statements
    }

    private fun checkSpatialMetadataBlocks(
        name: String, table: TableDefinition, geometryCols: Map<String, ColumnDefinition>,
    ): List<DdlStatement>? {
        val geoColNames = geometryCols.keys
        for ((colName, col) in geometryCols) {
            if (hasSpatialMetadataConflict(table, colName, col)) {
                return blockTableForSpatialMetadata(name, colName, "required/unique/default/references/PK")
            }
        }
        for (constraint in table.constraints) {
            val cols = constraint.columns.orEmpty()
            val blockingColumn = cols.firstOrNull { it in geoColNames }
            if (blockingColumn != null) {
                return blockTableForSpatialMetadata(
                    name,
                    blockingColumn,
                    "table-level constraint '${constraint.name}'",
                )
            }
        }
        for (idx in table.indices) {
            val blockingColumn = idx.columns.firstOrNull { it in geoColNames }
            if (blockingColumn != null) {
                return blockTableForSpatialMetadata(name, blockingColumn, "index on geometry column")
            }
        }
        return null
    }

    private fun buildColumnLines(
        name: String, table: TableDefinition, schema: SchemaDefinition,
        deferredFks: Set<Pair<String, String>>, isSpatiaLite: Boolean,
        notes: MutableList<TransformationNote>,
    ): List<String> {
        val lines = mutableListOf<String>()
        val normalCols = table.columns.filter { it.value.type !is NeutralType.Geometry }
        val effectiveCols = if (isSpatiaLite) normalCols else table.columns
        for ((colName, col) in effectiveCols) lines += generateColumnSql(colName, col, schema, name, notes, deferredFks)
        for (constraint in table.constraints) lines += generateConstraintClause(constraint, notes)
        val skipPk = table.primaryKey.size == 1 && table.primaryKey.all { pk ->
            table.columns[pk]?.type is NeutralType.Identifier
        }
        if (table.primaryKey.isNotEmpty() && !skipPk) {
            lines += "PRIMARY KEY (${table.primaryKey.joinToString(", ") { quoteIdentifier(it) }})"
        }
        return lines
    }

    private fun generateSpatiaLiteColumns(name: String, geometryCols: Map<String, ColumnDefinition>): List<DdlStatement> {
        return geometryCols.map { (colName, col) ->
            val geo = col.type as NeutralType.Geometry
            val geoType = geo.geometryType.schemaName.uppercase()
            val srid = geo.srid ?: 0
            DdlStatement(
                buildString {
                    append("SELECT AddGeometryColumn('")
                    append(name.replace("'", "''"))
                    append("', '")
                    append(colName.replace("'", "''"))
                    append("', ")
                    append(srid)
                    append(", '")
                    append(geoType)
                    append("', 'XY');")
                }
            )
        }
    }

    private fun generateColumnSql(
        colName: String,
        col: ColumnDefinition,
        schema: SchemaDefinition,
        tableName: String,
        notes: MutableList<TransformationNote>,
        deferredFks: Set<Pair<String, String>> = emptySet()
    ): String = columnConstraintHelper.generateColumnSql(colName, col, schema, tableName, notes, deferredFks)

    private fun generateConstraintClause(
        constraint: ConstraintDefinition,
        notes: MutableList<TransformationNote>
    ): String = columnConstraintHelper.generateConstraintClause(constraint, notes)

    // -- Indices -----------------------------------------------

    override fun generateIndices(tableName: String, table: TableDefinition): List<DdlStatement> {
        return table.indices.mapNotNull { index -> generateIndex(tableName, index) }
    }

    private fun generateIndex(tableName: String, index: IndexDefinition): DdlStatement? {
        val indexName = index.name ?: "idx_${tableName}_${index.columns.joinToString("_")}"

        // Only BTREE is supported in SQLite (it is the default and only index type)
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

        val cols = index.columns.joinToString(", ") { quoteIdentifier(it) }
        val sql = buildString {
            append("CREATE ")
            if (index.unique) append("UNIQUE ")
            append("INDEX ${quoteIdentifier(indexName)} ON ${quoteIdentifier(tableName)} ($cols);")
        }
        return DdlStatement(sql)
    }

    // -- Circular FK references --------------------------------

    override fun handleCircularReferences(
        edges: List<CircularFkEdge>,
        skipped: MutableList<SkippedObject>
    ): List<DdlStatement> {
        // SQLite cannot do ALTER TABLE ADD CONSTRAINT, so circular FKs are not possible.
        // Add each circular edge as a SkippedObject and return empty statements.
        val statements = mutableListOf<DdlStatement>()
        for (edge in edges) {
            val constraintName = "fk_${edge.fromTable}_${edge.fromColumn}"
            skipped += SkippedObject(
                "foreign_key",
                constraintName,
                circularForeignKeySkipReason(edge),
            )
            statements += DdlStatement(
                "-- Circular FK ${quoteIdentifier(constraintName)} skipped: SQLite cannot ALTER TABLE ADD CONSTRAINT",
                listOf(
                    TransformationNote(
                        type = NoteType.ACTION_REQUIRED,
                        code = "E019",
                        objectName = constraintName,
                        message = circularForeignKeyMessage(edge),
                        hint = "SQLite does not support ALTER TABLE ADD CONSTRAINT. Enforce referential integrity at the application level."
                    )
                )
            )
        }
        return statements
    }

    // -- Views -------------------------------------------------

    override fun generateViews(
        views: Map<String, ViewDefinition>,
        skipped: MutableList<SkippedObject>
    ): List<DdlStatement> = routineHelper.generateViews(views, skipped)

    // -- Functions ---------------------------------------------

    override fun generateFunctions(
        functions: Map<String, FunctionDefinition>,
        skipped: MutableList<SkippedObject>
    ): List<DdlStatement> = routineHelper.generateFunctions(functions, skipped)

    // -- Procedures --------------------------------------------

    override fun generateProcedures(
        procedures: Map<String, ProcedureDefinition>,
        skipped: MutableList<SkippedObject>
    ): List<DdlStatement> = routineHelper.generateProcedures(procedures, skipped)

    // -- Triggers ----------------------------------------------

    override fun generateTriggers(
        triggers: Map<String, TriggerDefinition>,
        tables: Map<String, TableDefinition>,
        skipped: MutableList<SkippedObject>
    ): List<DdlStatement> = routineHelper.generateTriggers(triggers, skipped)

    // -- Rollback inversion overrides --------------------------

    override fun invertStatement(stmt: DdlStatement): DdlStatement? {
        val sql = stmt.sql.trim()
        // Handle "CREATE VIEW IF NOT EXISTS" which the base class does not cover
        if (sql.startsWith("CREATE VIEW IF NOT EXISTS", ignoreCase = true)) {
            val afterKeyword = sql.substring("CREATE VIEW IF NOT EXISTS".length).trimStart()
            val name = afterKeyword.split(Regex("[\\s(]"), limit = 2).first()
            return DdlStatement("DROP VIEW IF EXISTS $name;")
        }
        // Handle SpatiaLite AddGeometryColumn → DiscardGeometryColumn
        if (sql.startsWith("SELECT AddGeometryColumn(", ignoreCase = true)) {
            val argsStart = sql.indexOf('(') + 1
            val argsEnd = sql.lastIndexOf(')')
            if (argsStart > 0 && argsEnd > argsStart) {
                val args = sql.substring(argsStart, argsEnd).split(',').map { it.trim() }
                if (args.size >= 2) {
                    return DdlStatement("SELECT DiscardGeometryColumn(${args[0]}, ${args[1]});")
                }
            }
        }
        return super.invertStatement(stmt)
    }

    private fun blockTableForSpatialMetadata(table: String, column: String, reason: String): List<DdlStatement> =
        listOf(DdlStatement("", notes = listOf(TransformationNote(
            type = NoteType.ACTION_REQUIRED, code = "E052", objectName = table,
            message = "Geometry column '$column' has unsupported metadata ($reason) for SpatiaLite",
            hint = "Remove metadata from geometry column or use a different dialect",
            blocksTable = true,
        ))))

    private fun hasSpatialMetadataConflict(
        table: TableDefinition,
        colName: String,
        col: ColumnDefinition,
    ): Boolean =
        col.required ||
            col.unique ||
            col.default != null ||
            col.references != null ||
            colName in table.primaryKey

    private fun circularForeignKeySkipReason(edge: CircularFkEdge): String =
        buildString {
            append("Circular foreign key from '")
            append(edge.fromTable)
            append('.')
            append(edge.fromColumn)
            append("' to '")
            append(edge.toTable)
            append('.')
            append(edge.toColumn)
            append("' cannot be added in SQLite (no ALTER TABLE ADD CONSTRAINT)")
        }

    private fun circularForeignKeyMessage(edge: CircularFkEdge): String =
        buildString {
            append("Circular foreign key from '")
            append(edge.fromTable)
            append('.')
            append(edge.fromColumn)
            append("' to '")
            append(edge.toTable)
            append('.')
            append(edge.toColumn)
            append("' cannot be created in SQLite.")
        }
}
