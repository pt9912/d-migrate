package dev.dmigrate.driver.postgresql

import dev.dmigrate.core.model.*
import dev.dmigrate.driver.*
import dev.dmigrate.driver.SqlIdentifiers

class PostgresDdlGenerator : AbstractDdlGenerator(PostgresTypeMapper()), DeferredForeignKeyDdlSupport {

    override val dialect = DatabaseDialect.POSTGRESQL

    private val routineHelper = PostgresRoutineDdlHelper(::quoteIdentifier)
    private val typeSequenceSupport = PostgresTypeSequenceDdlSupport(
        quoteIdentifier = ::quoteIdentifier,
        typeMapper = typeMapper,
    )
    private val columnConstraintHelper = PostgresColumnConstraintHelper(
        quoteIdentifier = ::quoteIdentifier,
        typeMapper = typeMapper,
        columnSql = ::columnSql,
        referentialActionSql = ::referentialActionSql,
    )

    // N8: index names are schema-global in PostgreSQL; the allocator
    // disambiguates cross-table collisions and is reset per generate() run.
    private val indexNameAllocator = PostgresIndexNameAllocator()

    override fun generate(
        schema: SchemaDefinition,
        options: DdlGenerationOptions,
    ): DdlResult {
        indexNameAllocator.reset()
        return super.generate(schema, options)
    }

    // ── Quoting ──────────────────────────────────

    override fun quoteIdentifier(name: String): String = SqlIdentifiers.quoteIdentifier(name, dialect)

    // ── Custom types (ENUM, COMPOSITE, DOMAIN) ──

    override fun generateCustomTypes(types: Map<String, CustomTypeDefinition>): List<DdlStatement> =
        typeSequenceSupport.generateCustomTypes(types)

    // ── Sequences ────────────────────────────────

    override fun generateSequences(
        schema: SchemaDefinition,
        skipped: MutableList<SkippedObject>
    ): List<DdlStatement> = typeSequenceSupport.generateSequences(sequencesForGeneration(schema))

    private fun sequencesForGeneration(schema: SchemaDefinition): Map<String, SequenceDefinition> {
        val ownedSequenceKeys = schema.tables.values
            .asSequence()
            .flatMap { it.columns.values.asSequence() }
            .mapNotNull { (it.generation as? ColumnGeneration.Identity)?.sequenceName }
            .flatMap { sequenceName ->
                sequence {
                    yield(sequenceName)
                    yield(sequenceName.substringAfterLast("."))
                }
            }
            .toSet()
        if (ownedSequenceKeys.isEmpty()) return schema.sequences
        return schema.sequences.filterKeys { it !in ownedSequenceKeys }
    }

    override fun canGenerateSpatial(profile: SpatialProfile): Boolean =
        profile == SpatialProfile.POSTGIS

    // ── Tables ───────────────────────────────────

    override fun generateTable(
        name: String,
        table: TableDefinition,
        schema: SchemaDefinition,
        deferredFks: Set<Pair<String, String>>,
        deferredConstraints: Set<Pair<String, String>>,
        options: DdlGenerationOptions
    ): List<DdlStatement> {
        val statements = mutableListOf<DdlStatement>()
        val notes = mutableListOf<TransformationNote>()
        val columnLines = mutableListOf<String>()

        // PostGIS dependency info-note
        if (options.spatialProfile == SpatialProfile.POSTGIS && hasGeometryColumns(table)) {
            notes += TransformationNote(
                type = NoteType.INFO, code = "I001", objectName = name,
                message = "Table '$name' uses PostGIS geometry types. Ensure PostGIS extension is installed on the target database.",
            )
        }

        // Columns
        for ((colName, col) in table.columns) {
            columnLines += generateColumnSql(colName, col, schema, name)
        }

        // Inline foreign key constraints (non-circular, from column references)
        for ((colName, col) in table.columns) {
            val ref = col.references ?: continue
            if (options.deferForeignKeys) continue
            if ((name to colName) in deferredFks) continue
            val fkName = "fk_${name}_${colName}"
            columnLines += buildForeignKeyClause(fkName, listOf(colName), ref.table, listOf(ref.column), ref.onDelete, ref.onUpdate)
        }

        // Explicit constraints
        for (constraint in table.constraints) {
            if (options.deferForeignKeys && constraint.type == ConstraintType.FOREIGN_KEY) continue
            if ((name to constraint.name) in deferredConstraints) continue
            columnLines += generateConstraintClause(constraint)
        }

        // Primary key
        if (table.primaryKey.isNotEmpty()) {
            val pkCols = table.primaryKey.joinToString(", ") { quoteIdentifier(it) }
            columnLines += "PRIMARY KEY ($pkCols)"
        }

        // N2: PostgreSQL has no default partition — a `PARTITION BY` parent
        // without child partitions accepts no rows ("no partition of relation
        // found for row"). Emit partitioning only when child partitions exist;
        // otherwise fall back to a plain table and flag it (like MySQL's E055).
        val partitioning = table.partitioning
        val emitPartitioning = partitioning != null && partitioning.partitions.isNotEmpty()
        if (partitioning != null && !emitPartitioning) {
            notes += TransformationNote(
                type = NoteType.ACTION_REQUIRED,
                code = "E055",
                objectName = name,
                message = "${partitioning.type.name} partitioning of table '$name' has no child partitions; " +
                    "PostgreSQL would reject every insert. Created as a plain (non-partitioned) table.",
                hint = "Define the partition boundaries (PARTITION OF …) or remove the partitioning configuration.",
            )
        }

        // Build CREATE TABLE
        val tableSql = buildString {
            append("CREATE TABLE ${quoteIdentifier(name)} (\n")
            append(columnLines.joinToString(",\n") { "    $it" })
            append("\n)")
            if (emitPartitioning) {
                val key = partitioning!!.key.joinToString(", ") { quoteIdentifier(it) }
                append(" PARTITION BY ${partitioning.type.name} ($key)")
            }
            append(";")
        }
        statements += DdlStatement(tableSql, notes)

        // Sub-partitions
        if (emitPartitioning) {
            for (partition in partitioning!!.partitions) {
                statements += generatePartitionStatement(name, partition, partitioning.type)
            }
        }

        return statements
    }

    private fun generateColumnSql(
        colName: String,
        col: ColumnDefinition,
        schema: SchemaDefinition,
        tableName: String,
    ): String = columnConstraintHelper.generateColumnSql(colName, col, schema, tableName)

    private fun buildForeignKeyClause(
        constraintName: String,
        fromColumns: List<String>,
        toTable: String,
        toColumns: List<String>,
        onDelete: ReferentialAction?,
        onUpdate: ReferentialAction?
    ): String = columnConstraintHelper.buildForeignKeyClause(constraintName, fromColumns, toTable, toColumns, onDelete, onUpdate)

    private fun generateConstraintClause(constraint: ConstraintDefinition): String =
        columnConstraintHelper.generateConstraintClause(constraint)

    private fun generatePartitionStatement(
        parentTable: String,
        partition: PartitionDefinition,
        type: PartitionType
    ): DdlStatement {
        val sql = buildString {
            append("CREATE TABLE ${quoteIdentifier(partition.name)} PARTITION OF ${quoteIdentifier(parentTable)}")
            when (type) {
                PartitionType.RANGE -> {
                    val from = validatePartitionBound(partition.from, "FROM", partition.name)
                    val to = validatePartitionBound(partition.to, "TO", partition.name)
                    append(" FOR VALUES FROM ($from) TO ($to)")
                }
                PartitionType.LIST -> {
                    val vals = partition.values?.onEach {
                        validatePartitionBound(it, "IN", partition.name)
                    }?.joinToString(", ") ?: ""
                    append(" FOR VALUES IN ($vals)")
                }
                PartitionType.HASH -> {
                    val from = validatePartitionBound(partition.from, "WITH", partition.name)
                    append(" FOR VALUES WITH ($from)")
                }
            }
            append(";")
        }
        return DdlStatement(sql)
    }

    private fun validatePartitionBound(value: String?, clause: String, partitionName: String): String {
        requireNotNull(value) {
            "Partition '$partitionName' $clause bound must not be null"
        }
        require(!value.contains(';') && !value.contains("--") && !value.contains("/*")) {
            "Partition '$partitionName' $clause bound contains unsafe characters: $value"
        }
        return value
    }

    // ── Indices ──────────────────────────────────

    override fun generateIndices(
        tableName: String,
        table: TableDefinition,
        options: DdlGenerationOptions,
    ): List<DdlStatement> {
        val generatedNames = indexNameAllocator.namesFor(tableName, table.indices)
        return table.indices.mapIndexed { position, index ->
            generateIndex(tableName, index, generatedNames[position], table.columns)
        }
    }

    private fun generateIndex(
        tableName: String,
        index: IndexDefinition,
        indexName: String,
        columns: Map<String, ColumnDefinition>,
    ): DdlStatement {
        PostgresIndexOpClass.missingOpClassColumn(index) { columns[it]?.type }?.let { offending ->
            return DdlStatement(
                "",
                listOf(
                    TransformationNote(
                        type = NoteType.WARNING,
                        code = "W123",
                        objectName = indexName,
                        message = "${index.type.name} index '$indexName' on column '$offending' was skipped: " +
                            "the column type has no default ${index.type.name} operator class in PostgreSQL " +
                            "(e.g. a tsvector column degraded to text on reverse).",
                        hint = "Restore the original column type, or add the required operator class " +
                            "(e.g. via the pg_trgm extension) and create the index manually.",
                    )
                )
            )
        }
        val cols = index.columns.joinToString(", ") { renderIndexColumn(it) }
        val sql = buildString {
            append("CREATE ")
            if (index.unique) append("UNIQUE ")
            append("INDEX ${quoteIdentifier(indexName)} ON ${quoteIdentifier(tableName)}")
            // Omit USING for BTREE since it's the default. VA3: SPATIAL → GIST.
            if (index.type != IndexType.BTREE) {
                append(" USING ${pgAccessMethod(index.type)}")
            }
            append(" ($cols)")
            if (index.where != null) append(" WHERE ${index.where}")
            append(";")
        }
        val notes = IndexPrefixDropNote.forDialect(index, indexName, "PostgreSQL", "left(col, n)").toMutableList()
        if (index.name != null && index.name != indexName) {
            notes += TransformationNote(
                type = NoteType.WARNING,
                code = "W127",
                objectName = indexName,
                message = "Index '${index.name}' on table '$tableName' was renamed to '$indexName' to keep " +
                    "index names unique within the PostgreSQL schema (MySQL allows the same index name on " +
                    "several tables; PostgreSQL index names are schema-global).",
                hint = "Rename the source index if a specific PostgreSQL name is required.",
            )
        }
        return DdlStatement(sql, notes)
    }

    private fun renderIndexColumn(column: IndexColumn): String =
        buildString {
            val direction = column.direction
            append(quoteIdentifier(column.name))
            if (direction != null) append(" ${direction.name}")
        }


    // ── Circular FK references ───────────────────

    override fun handleCircularReferences(
        edges: List<CircularFkEdge>,
        skipped: MutableList<SkippedObject>
    ): List<DdlStatement> {
        return edges.map { edge ->
            val sql = buildString {
                append("ALTER TABLE ${quoteIdentifier(edge.fromTable)} ADD ")
                append(buildForeignKeyClause(
                    edge.constraintName,
                    edge.fromColumns,
                    edge.toTable,
                    edge.toColumns,
                    edge.onDelete,
                    edge.onUpdate,
                ))
                append(";")
            }
            DdlStatement(sql)
        }
    }

    override fun generateDeferredForeignKeys(
        foreignKeys: List<DeferredForeignKey>,
        skipped: MutableList<SkippedObject>,
    ): List<DdlStatement> =
        foreignKeys.map { fk ->
            val sql = buildString {
                append("ALTER TABLE ${quoteIdentifier(fk.fromTable)} ADD ")
                append(buildForeignKeyClause(
                    fk.constraintName,
                    fk.fromColumns,
                    fk.toTable,
                    fk.toColumns,
                    fk.onDelete,
                    fk.onUpdate,
                ))
                append(";")
            }
            DdlStatement(sql)
        }

    // ── Views ────────────────────────────────────

    override fun generateViews(
        views: Map<String, ViewDefinition>,
        skipped: MutableList<SkippedObject>
    ): List<DdlStatement> {
        return routineHelper.generateViews(views, skipped)
    }

    // ── Functions ────────────────────────────────

    override fun generateFunctions(
        functions: Map<String, FunctionDefinition>,
        skipped: MutableList<SkippedObject>
    ): List<DdlStatement> {
        return routineHelper.generateFunctions(functions, skipped)
    }

    // ── Aggregates (N7) ──────────────────────────

    override fun generateAggregates(
        aggregates: Map<String, AggregateDefinition>,
        skipped: MutableList<SkippedObject>
    ): List<DdlStatement> {
        return routineHelper.generateAggregates(aggregates, skipped)
    }

    // ── Procedures ───────────────────────────────

    override fun generateProcedures(
        procedures: Map<String, ProcedureDefinition>,
        skipped: MutableList<SkippedObject>
    ): List<DdlStatement> {
        return routineHelper.generateProcedures(procedures, skipped)
    }

    // ── Triggers ─────────────────────────────────

    override fun generateTriggers(
        triggers: Map<String, TriggerDefinition>,
        tables: Map<String, TableDefinition>,
        skipped: MutableList<SkippedObject>
    ): List<DdlStatement> {
        return routineHelper.generateTriggers(triggers, skipped)
    }

    override fun resolveSequenceDefault(
        tableName: String,
        colName: String,
        col: dev.dmigrate.core.model.ColumnDefinition,
        seqDefault: dev.dmigrate.core.model.DefaultValue.SequenceNextVal,
    ): String = "DEFAULT nextval(${SqlIdentifiers.quoteStringLiteral(seqDefault.sequenceName)})"
}
