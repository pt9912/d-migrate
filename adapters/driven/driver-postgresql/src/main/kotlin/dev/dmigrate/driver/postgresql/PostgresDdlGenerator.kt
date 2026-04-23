package dev.dmigrate.driver.postgresql

import dev.dmigrate.core.model.*
import dev.dmigrate.driver.*
import dev.dmigrate.driver.SqlIdentifiers

class PostgresDdlGenerator : AbstractDdlGenerator(PostgresTypeMapper()) {

    override val dialect = DatabaseDialect.POSTGRESQL

    private val routineHelper = PostgresRoutineDdlHelper(::quoteIdentifier)
    private val columnConstraintHelper = PostgresColumnConstraintHelper(
        quoteIdentifier = ::quoteIdentifier,
        typeMapper = typeMapper,
        columnSql = ::columnSql,
        referentialActionSql = ::referentialActionSql,
    )

    // ── Quoting ──────────────────────────────────

    override fun quoteIdentifier(name: String): String = SqlIdentifiers.quoteIdentifier(name, dialect)

    // ── Custom types (ENUM, COMPOSITE, DOMAIN) ──

    override fun generateCustomTypes(types: Map<String, CustomTypeDefinition>): List<DdlStatement> {
        return types.flatMap { (name, typeDef) -> generateCustomType(name, typeDef) }
    }

    private fun generateCustomType(name: String, typeDef: CustomTypeDefinition): List<DdlStatement> {
        return when (typeDef.kind) {
            CustomTypeKind.ENUM -> {
                val values = typeDef.values ?: return emptyList()
                val enumValues = values.joinToString(", ") { "'${it.replace("'", "''")}'" }
                listOf(DdlStatement("CREATE TYPE ${quoteIdentifier(name)} AS ENUM ($enumValues);"))
            }
            CustomTypeKind.COMPOSITE -> {
                val fields = typeDef.fields ?: return emptyList()
                val fieldsSql = fields.entries.joinToString(",\n    ") { (fieldName, col) ->
                    "${quoteIdentifier(fieldName)} ${typeMapper.toSql(col.type)}"
                }
                listOf(DdlStatement("CREATE TYPE ${quoteIdentifier(name)} AS (\n    $fieldsSql\n);"))
            }
            CustomTypeKind.DOMAIN -> {
                val baseType = typeDef.baseType ?: return emptyList()
                val sqlType = buildString {
                    append(baseType.uppercase())
                    if (typeDef.precision != null) {
                        append("(${typeDef.precision}")
                        if (typeDef.scale != null) append(",${typeDef.scale}")
                        append(")")
                    }
                }
                val sql = buildString {
                    append("CREATE DOMAIN ${quoteIdentifier(name)} AS $sqlType")
                    if (typeDef.check != null) {
                        append(" CHECK (${typeDef.check})")
                    }
                    append(";")
                }
                listOf(DdlStatement(sql))
            }
        }
    }

    // ── Sequences ────────────────────────────────

    override fun generateSequences(
        sequences: Map<String, SequenceDefinition>,
        skipped: MutableList<SkippedObject>
    ): List<DdlStatement> {
        return sequences.map { (name, seq) -> generateSequence(name, seq) }
    }

    private fun generateSequence(name: String, seq: SequenceDefinition): DdlStatement {
        val sql = buildString {
            append("CREATE SEQUENCE ${quoteIdentifier(name)}")
            append(" START WITH ${seq.start}")
            append(" INCREMENT BY ${seq.increment}")
            if (seq.minValue != null) append(" MINVALUE ${seq.minValue}")
            if (seq.maxValue != null) append(" MAXVALUE ${seq.maxValue}")
            if (seq.cycle) append(" CYCLE") else append(" NO CYCLE")
            if (seq.cache != null) append(" CACHE ${seq.cache}")
            append(";")
        }
        return DdlStatement(sql)
    }

    override fun canGenerateSpatial(profile: SpatialProfile): Boolean =
        profile == SpatialProfile.POSTGIS

    // ── Tables ───────────────────────────────────

    override fun generateTable(
        name: String,
        table: TableDefinition,
        schema: SchemaDefinition,
        deferredFks: Set<Pair<String, String>>,
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
            if ((name to colName) in deferredFks) continue
            val fkName = "fk_${name}_${colName}"
            columnLines += buildForeignKeyClause(fkName, listOf(colName), ref.table, listOf(ref.column), ref.onDelete, ref.onUpdate)
        }

        // Explicit constraints
        for (constraint in table.constraints) {
            columnLines += generateConstraintClause(constraint)
        }

        // Primary key
        if (table.primaryKey.isNotEmpty()) {
            val pkCols = table.primaryKey.joinToString(", ") { quoteIdentifier(it) }
            columnLines += "PRIMARY KEY ($pkCols)"
        }

        // Build CREATE TABLE
        val tableSql = buildString {
            append("CREATE TABLE ${quoteIdentifier(name)} (\n")
            append(columnLines.joinToString(",\n") { "    $it" })
            append("\n)")
            // Partitioning
            val partitioning = table.partitioning
            if (partitioning != null) {
                val key = partitioning.key.joinToString(", ") { quoteIdentifier(it) }
                append(" PARTITION BY ${partitioning.type.name} ($key)")
            }
            append(";")
        }
        statements += DdlStatement(tableSql, notes)

        // Sub-partitions
        val partitioning = table.partitioning
        if (partitioning != null) {
            for (partition in partitioning.partitions) {
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

    override fun generateIndices(tableName: String, table: TableDefinition): List<DdlStatement> {
        return table.indices.map { index -> generateIndex(tableName, index) }
    }

    private fun generateIndex(tableName: String, index: IndexDefinition): DdlStatement {
        val indexName = index.name ?: "idx_${tableName}_${index.columns.joinToString("_")}"
        val cols = index.columns.joinToString(", ") { quoteIdentifier(it) }
        val sql = buildString {
            append("CREATE ")
            if (index.unique) append("UNIQUE ")
            append("INDEX ${quoteIdentifier(indexName)} ON ${quoteIdentifier(tableName)}")
            // Omit USING for BTREE since it's the default
            if (index.type != IndexType.BTREE) {
                append(" USING ${index.type.name}")
            }
            append(" ($cols);")
        }
        return DdlStatement(sql)
    }

    // ── Circular FK references ───────────────────

    override fun handleCircularReferences(
        edges: List<CircularFkEdge>,
        skipped: MutableList<SkippedObject>
    ): List<DdlStatement> {
        return edges.map { edge ->
            val constraintName = "fk_${edge.fromTable}_${edge.fromColumn}"
            val sql = buildString {
                append("ALTER TABLE ${quoteIdentifier(edge.fromTable)} ADD CONSTRAINT ${quoteIdentifier(constraintName)}")
                append(" FOREIGN KEY (${quoteIdentifier(edge.fromColumn)})")
                append(" REFERENCES ${quoteIdentifier(edge.toTable)} (${quoteIdentifier(edge.toColumn)});")
            }
            DdlStatement(sql)
        }
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
