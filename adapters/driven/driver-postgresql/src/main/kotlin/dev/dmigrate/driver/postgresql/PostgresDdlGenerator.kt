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

        // Columns — physische Ordinalreihenfolge (siehe inOrdinalOrder).
        for ((colName, col) in table.columns.inOrdinalOrder()) {
            columnLines += generateColumnSql(colName, col, schema, name)
        }

        // Inline foreign key constraints (non-circular, from column references)
        for ((colName, col) in table.columns.inOrdinalOrder()) {
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
            if (partition.isDefault) {
                append(" DEFAULT")
            } else when (type) {
                PartitionType.RANGE -> {
                    val from = renderRangeBounds(partition.from, "FROM", partition.name)
                    val to = renderRangeBounds(partition.to, "TO", partition.name)
                    append(" FOR VALUES FROM ($from) TO ($to)")
                }
                PartitionType.LIST -> {
                    val vals = partition.values.orEmpty()
                        .joinToString(", ") { PartitionLiteralGuard.ensureSafe(it, partition.name) }
                    append(" FOR VALUES IN ($vals)")
                }
                PartitionType.HASH -> {
                    val modulus = requireNotNull(partition.modulus) {
                        "HASH partition '${partition.name}' must have a modulus"
                    }
                    val remainder = requireNotNull(partition.remainder) {
                        "HASH partition '${partition.name}' must have a remainder"
                    }
                    append(" FOR VALUES WITH (MODULUS $modulus, REMAINDER $remainder)")
                }
            }
            append(";")
        }
        return DdlStatement(sql)
    }

    private fun renderRangeBounds(
        bounds: List<PartitionBound>?,
        clause: String,
        partitionName: String
    ): String {
        requireNotNull(bounds) { "Partition '$partitionName' $clause bound must not be null" }
        require(bounds.isNotEmpty()) { "Partition '$partitionName' $clause bound must not be empty" }
        return bounds.joinToString(", ") { bound ->
            when (bound) {
                PartitionBound.MinValue -> "MINVALUE"
                PartitionBound.MaxValue -> "MAXVALUE"
                is PartitionBound.Value -> PartitionLiteralGuard.ensureSafe(bound.literal, partitionName)
            }
        }
    }

    // ── Indices ──────────────────────────────────

    override fun generateIndices(
        tableName: String,
        table: TableDefinition,
        options: DdlGenerationOptions,
    ): List<DdlStatement> {
        val generatedNames = indexNameAllocator.namesFor(tableName, table.indices)
        val parentIndices = table.indices.mapIndexed { position, index ->
            generateIndex(tableName, index, generatedNames[position], table.columns)
        }
        // AP2a: kind-lokale Indizes je Partition — auf der Partitionstabelle
        // erzeugt (Partitionen erben die Spalten des Parents). Im selben
        // Index-Phasenlauf wie die Top-Level-Indizes, damit die Tabellen schon stehen.
        val partitionIndices = table.partitioning?.partitions.orEmpty().flatMap { partition ->
            val names = indexNameAllocator.namesFor(partition.name, partition.indices)
            partition.indices.mapIndexed { position, index ->
                generateIndex(partition.name, index, names[position], table.columns)
            }
        }
        return parentIndices + partitionIndices
    }

    private fun generateIndex(
        tableName: String,
        index: IndexDefinition,
        indexName: String,
        columns: Map<String, ColumnDefinition>,
    ): DdlStatement {
        // P2 (ADR 0025): the neutral FULLTEXT index over the source text columns is the
        // cross-dialect abstraction of PostgreSQL's tsvector machinery. On PostgreSQL it
        // expands back to a GiST index over the (parameterless) tsvector column — the
        // tsvector column itself and its populating trigger come from the column/trigger
        // model, so only the GiST index is re-derived here. This keeps the PG→PG
        // round-trip at 0 diffs (the reverse reader replaced this very GiST index with a
        // FULLTEXT one in PostgresFullTextIndexSynthesis).
        if (index.type == IndexType.FULLTEXT) {
            return expandFullTextIndex(tableName, index, indexName, columns)
        }
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

    /**
     * P2 (ADR 0025): expand a neutral [IndexType.FULLTEXT] index into PostgreSQL's
     * native form — a GiST index over the `tsvector` ([NeutralType.FullText]) column the
     * index is built on. The FULLTEXT index lists the *human* source text columns
     * (MySQL/SQLite index those directly); PostgreSQL indexes the precomputed vector
     * column instead. The vector column is read from [IndexDefinition.fullTextVectorColumn]
     * (set on PG reverse — unambiguous even with several tsvector columns per table); for
     * a hand-authored index without it we fall back to the table's sole tsvector column.
     * We delegate to the regular index path, which keeps the `tsvector_ops`
     * default-operator-class handling (so no W123). When no tsvector column can be
     * determined, the full expansion (synthesising the tsvector column + populating
     * trigger) is out of scope — W133 records the manual step.
     */
    private fun expandFullTextIndex(
        tableName: String,
        index: IndexDefinition,
        indexName: String,
        columns: Map<String, ColumnDefinition>,
    ): DdlStatement {
        val tsvectorColumn = index.fullTextVectorColumn?.takeIf { columns[it]?.type is NeutralType.FullText }
            ?: columns.entries.singleOrNull { it.value.type is NeutralType.FullText }?.key
            ?: return DdlStatement(
                "",
                listOf(
                    TransformationNote(
                        type = NoteType.WARNING,
                        code = "W133",
                        objectName = indexName,
                        message = "FULLTEXT index '$indexName' on table '$tableName' could not be " +
                            "expanded for PostgreSQL: no backing tsvector column is recorded and the " +
                            "table has no single unambiguous tsvector column.",
                        hint = "Set the backing tsvector column on the index (PG reverse does this), " +
                            "or target MySQL/SQLite where FULLTEXT maps to a native fulltext index.",
                    ),
                ),
            )
        return generateIndex(
            tableName,
            index.copy(
                // ADR 0025: restore the recorded access method (GIN/GiST); GiST when absent.
                type = index.fullTextAccessMethod ?: IndexType.GIST,
                columns = listOf(IndexColumn(tsvectorColumn)),
                textSearchConfig = null,
                fullTextVectorColumn = null,
                fullTextAccessMethod = null,
            ),
            indexName,
            columns,
        )
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
