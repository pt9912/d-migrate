package dev.dmigrate.driver.mysql

import dev.dmigrate.core.model.*
import dev.dmigrate.driver.*

class MysqlDdlGenerator : AbstractDdlGenerator(MysqlTypeMapper()) {

    override val dialect = DatabaseDialect.MYSQL

    private val routineHelper = MysqlRoutineDdlHelper(::quoteIdentifier)
    private val sequenceSupport = MysqlSequenceDdlSupport(::quoteIdentifier)
    private val indexPartitionHelper = MysqlIndexPartitionDdlHelper(::quoteIdentifier)

    /**
     * Tables that end up **actually partitioned** in the emitted DDL — computed **once,
     * order-independent** from the schema (see [computePartitionedTables]) before any table is
     * emitted, then consumed read-only by the FK paths and [handleCircularReferences].
     * MySQL/InnoDB forbids foreign keys touching a partitioned table in **either** direction
     * (ADR 0020 §5), so such FKs are skipped + flagged (E065). "Actually partitioned" ≠
     * "partitioning configured": a config that is skipped (E055/E062) leaves a plain table whose
     * FKs stay valid. Previously this was a mutable set filled during emission — an order-dependent
     * side-channel (an FK referencing a not-yet-emitted partitioned table could be misread). Now
     * it is a full snapshot taken up front, so emission order no longer matters.
     */
    private var partitionedTables: Set<String> = emptySet()

    override fun generate(schema: SchemaDefinition, options: DdlGenerationOptions): DdlResult {
        partitionedTables = computePartitionedTables(schema)
        sequenceSupport.beginRun(schema, options)
        return sequenceSupport.finalizeResult(super.generate(schema, options))
    }

    /**
     * The set of tables whose partitioning is **actually emitted** (not skipped via E055/E062, not
     * filtered to an empty LIST). Mirrors [generateTable]'s emit decision exactly by calling the same
     * [MysqlIndexPartitionDdlHelper.generatePartitionClause] with a **throwaway** note sink — the
     * real notes are produced during emission, so this discards its diagnostic output and keeps only
     * the emit-or-not signal. Kept MySQL-local on purpose: only MySQL/InnoDB forbids FKs on
     * partitioned tables (PostgreSQL allows them; SQLite has no partitioning), so there is no
     * PG/SQLite consumer to share a generic abstraction with — hoisting it would be premature.
     */
    private fun computePartitionedTables(schema: SchemaDefinition): Set<String> =
        schema.tables.filterValues { table ->
            table.partitioning?.let {
                indexPartitionHelper.generatePartitionClause(it, table.columns, mutableListOf()).isNotBlank()
            } ?: false
        }.keys.toSet()

    // ── SequenceNextVal interception (§4.6) ──────

    override fun resolveSequenceDefault(
        tableName: String,
        colName: String,
        col: ColumnDefinition,
        seqDefault: DefaultValue.SequenceNextVal,
    ): String? = sequenceSupport.resolveSequenceDefault(tableName, colName, seqDefault)

    // ── Quoting ──────────────────────────────────

    override fun quoteIdentifier(name: String): String = SqlIdentifiers.quoteIdentifier(name, dialect)

    // ── Custom types (ENUM, COMPOSITE, DOMAIN) ──

    override fun generateCustomTypes(types: Map<String, CustomTypeDefinition>): List<DdlStatement> {
        // MySQL does not support standalone CREATE TYPE.
        // ENUMs are inlined at column level.
        // COMPOSITEs are not supported.
        // DOMAINs are handled inline as base type + CHECK.
        val statements = mutableListOf<DdlStatement>()
        for ((name, typeDef) in types) {
            if (typeDef.kind == CustomTypeKind.COMPOSITE) {
                val action = ManualActionRequired(
                    code = "E054", objectType = "composite_type", objectName = name,
                    reason = "Composite type '$name' is not supported in MySQL and was skipped.",
                    hint = "Consider restructuring the data model to avoid composite types.",
                )
                statements += DdlStatement("", listOf(action.toNote()))
            }
        }
        return statements
    }

    // ── Sequences ────────────────────────────────

    override fun generateSequences(
        schema: SchemaDefinition,
        skipped: MutableList<SkippedObject>
    ): List<DdlStatement> = sequenceSupport.generateSequences(schema.sequences, skipped)

    override fun canGenerateSpatial(profile: SpatialProfile): Boolean =
        profile == SpatialProfile.NATIVE

    // ── Tables (wrapper to attach pending sequence notes) ──

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

        // Compute the partition clause up front so the FK paths below can see whether the table
        // is actually partitioned (MySQL forbids FKs on partitioned tables, ADR 0020 §5). Its
        // diagnostics are merged back below (before the drain), preserving the original note order.
        val partitionNotes = mutableListOf<TransformationNote>()
        val partitionClause = table.partitioning
            ?.let { indexPartitionHelper.generatePartitionClause(it, table.columns, partitionNotes) }
            .orEmpty()
        // Order-independent: read the up-front snapshot (== partitionClause.isNotBlank() here, since
        // both come from the same generatePartitionClause logic) rather than mutating a side-channel.
        val isPartitioned = name in partitionedTables

        // Columns — physische Ordinalreihenfolge (siehe inOrdinalOrder).
        for ((colName, col) in table.columns.inOrdinalOrder()) {
            columnLines += generateColumnSql(colName, col, schema, name, notes)
            // C3: Warn when datetime with timezone is mapped to DATETIME (no TZ support in MySQL)
            if (col.type is NeutralType.DateTime && (col.type as NeutralType.DateTime).timezone) {
                notes += TransformationNote(
                    type = NoteType.WARNING,
                    code = "W100",
                    objectName = "$name.$colName",
                    message = "DATETIME with timezone on column '$colName' mapped to DATETIME in MySQL which does not support time zones.",
                    hint = "Store timezone information in a separate column or use UTC consistently."
                )
            }
        }

        // Inline foreign key constraints (non-circular, from column references)
        for ((colName, col) in table.columns.inOrdinalOrder()) {
            val ref = col.references ?: continue
            if ((name to colName) in deferredFks) continue
            val fkName = "fk_${name}_${colName}"
            if (isPartitioned || ref.table in partitionedTables) {
                notes += partitionedFkSkipNote(fkName, name)
                continue
            }
            columnLines += buildForeignKeyClause(fkName, listOf(colName), ref.table, listOf(ref.column), ref.onDelete, ref.onUpdate)
        }

        // Explicit constraints
        for (constraint in table.constraints) {
            if ((name to constraint.name) in deferredConstraints) continue
            if (constraint.type == ConstraintType.FOREIGN_KEY &&
                (isPartitioned || constraint.references?.table in partitionedTables)
            ) {
                notes += partitionedFkSkipNote(constraint.name, name)
                continue
            }
            generateConstraintClause(constraint, notes)?.let { columnLines += it }
        }

        // Primary key
        if (table.primaryKey.isNotEmpty()) {
            val pkCols = orderPkAutoIncrementFirst(name, table, notes)
                .joinToString(", ") { quoteIdentifier(it) }
            columnLines += "PRIMARY KEY ($pkCols)"
        }

        // Build CREATE TABLE
        val tableSql = buildString {
            append("CREATE TABLE ${quoteIdentifier(name)} (\n")
            append(columnLines.joinToString(",\n") { "    $it" })
            append("\n)")
            // Table options precede partition options per the MySQL grammar:
            //   CREATE TABLE ... (defs) [table_options] [partition_options]
            val tableOptions = options.mysqlContext?.tableOptions ?: MysqlTableOptions()
            append("\nENGINE=${tableOptions.engine}")
            append(" DEFAULT CHARSET=${tableOptions.charset}")
            append(" COLLATE=${tableOptions.collation}")
            if (partitionClause.isNotBlank()) {
                append("\n")
                append(partitionClause)
            }
            append(";")
        }
        notes += partitionNotes
        notes += sequenceSupport.drainPendingNotes()
        statements += DdlStatement(tableSql, notes)

        return statements
    }

    /**
     * MySQL requires an AUTO_INCREMENT column to be the leading column of a key
     * (ERROR 1075). When a composite PRIMARY KEY contains an AUTO_INCREMENT
     * column that is not first, reorder it to the front and flag the change.
     */
    private fun orderPkAutoIncrementFirst(
        tableName: String,
        table: TableDefinition,
        notes: MutableList<TransformationNote>,
    ): List<String> {
        val result = MysqlPrimaryKeyOrdering.autoIncrementFirst(table.primaryKey, table.columns)
        result.reordered?.let { moved ->
            notes += TransformationNote(
                type = NoteType.WARNING,
                code = "W118",
                objectName = "$tableName.$moved",
                message = "AUTO_INCREMENT column '$moved' was moved to the front of the composite " +
                    "PRIMARY KEY because MySQL requires it to be the leading key column (ERROR 1075).",
                hint = "Verify the primary key column order is acceptable for your access patterns.",
            )
        }
        return result.columns
    }

    private val columnConstraintHelper = MysqlColumnConstraintHelper(
        ::quoteIdentifier, typeMapper, ::columnSql, ::referentialActionSql,
    )

    private fun generateColumnSql(
        colName: String, col: ColumnDefinition, schema: SchemaDefinition,
        tableName: String, notes: MutableList<TransformationNote>,
    ): String = columnConstraintHelper.generateColumnSql(colName, col, schema, tableName, notes)

    private fun buildForeignKeyClause(
        constraintName: String, fromColumns: List<String>, toTable: String,
        toColumns: List<String>, onDelete: ReferentialAction?, onUpdate: ReferentialAction?,
    ): String = columnConstraintHelper.buildForeignKeyClause(constraintName, fromColumns, toTable, toColumns, onDelete, onUpdate)

    private fun generateConstraintClause(
        constraint: ConstraintDefinition, notes: MutableList<TransformationNote>,
    ): String? = columnConstraintHelper.generateConstraintClause(constraint, notes)

    override fun generateIndices(
        tableName: String,
        table: TableDefinition,
        options: DdlGenerationOptions,
    ): List<DdlStatement> =
        indexPartitionHelper.generateIndices(tableName, table)

    /**
     * §5 (ADR 0020): MySQL/InnoDB supports no foreign keys on partitioned tables in either
     * direction. A FK declared on — or referencing — a partitioned table is skipped + flagged.
     */
    private fun partitionedFkSkipNote(fkName: String, tableName: String): TransformationNote =
        TransformationNote(
            type = NoteType.ACTION_REQUIRED,
            code = "E065",
            objectName = fkName,
            message = "Foreign key '$fkName' on partitioned table '$tableName' was skipped: MySQL/InnoDB " +
                "does not support foreign keys on partitioned tables (in either direction).",
            hint = "Enforce referential integrity in the application, or do not partition the table.",
        )

    // ── Circular FK references ───────────────────

    override fun handleCircularReferences(
        edges: List<CircularFkEdge>,
        skipped: MutableList<SkippedObject>
    ): List<DdlStatement> {
        return edges.map { edge ->
            // ADR 0020 §5: drop a deferred/circular FK that touches a partitioned table (either end).
            if (edge.fromTable in partitionedTables || edge.toTable in partitionedTables) {
                return@map DdlStatement("", listOf(partitionedFkSkipNote(edge.constraintName, edge.fromTable)))
            }
            val sql = buildString {
                append("ALTER TABLE ${quoteIdentifier(edge.fromTable)} ADD CONSTRAINT ${quoteIdentifier(edge.constraintName)}")
                append(" FOREIGN KEY (${edge.fromColumns.joinToString(", ") { quoteIdentifier(it) }})")
                append(" REFERENCES ${quoteIdentifier(edge.toTable)} (${edge.toColumns.joinToString(", ") { quoteIdentifier(it) }})")
                val onDelete = edge.onDelete
                val onUpdate = edge.onUpdate
                if (onDelete != null) append(" ON DELETE ${referentialActionSql(onDelete)}")
                if (onUpdate != null) append(" ON UPDATE ${referentialActionSql(onUpdate)}")
                append(";")
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
        val statements = mutableListOf<DdlStatement>()
        statements += sequenceSupport.generateSupportFunctions(functions, skipped)
        statements += routineHelper.generateFunctions(functions, skipped)
        return statements
    }

    // ── Aggregates (N7) ──────────────────────────

    override fun generateAggregates(
        aggregates: Map<String, AggregateDefinition>,
        skipped: MutableList<SkippedObject>
    ): List<DdlStatement> = aggregates.map { (name, aggregate) ->
        if (aggregate.isLoadableUdf) {
            // MySQL loadable native UDF aggregate (compiled C in a shared library).
            val returns = (aggregate.returnType ?: "STRING").uppercase()
            DdlStatement(
                "CREATE AGGREGATE FUNCTION ${quoteIdentifier(name)} RETURNS $returns SONAME '${aggregate.library}';"
            )
        } else {
            // A SQL-defined (e.g. PostgreSQL) aggregate cannot be mechanically
            // translated to MySQL's loadable-UDF mechanism.
            val action = ManualActionRequired(
                code = "E053", objectType = "aggregate", objectName = name,
                reason = "Aggregate '$name' is SQL-defined and cannot be auto-translated to MySQL: MySQL user " +
                    "aggregates are loadable native UDFs (CREATE AGGREGATE FUNCTION … SONAME).",
                hint = "Re-implement '$name' as a MySQL loadable aggregate UDF, or express the aggregation " +
                    "in application code / built-in functions.",
            )
            skipped += action.toSkipped()
            DdlStatement("", notes = listOf(action.toNote()))
        }
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
        val statements = mutableListOf<DdlStatement>()
        statements += sequenceSupport.generateSupportTriggers(triggers, skipped)
        statements += routineHelper.generateTriggers(triggers, skipped)
        return statements
    }

    // ── Rollback overrides ──────────────────────

    override fun invertStatement(stmt: DdlStatement): DdlStatement? {
        val sql = stmt.sql.trim()

        // Handle DELIMITER-wrapped statements
        if (sql.startsWith("DELIMITER //", ignoreCase = true)) {
            val inner = sql.removePrefix("DELIMITER //").removeSuffix("DELIMITER ;").trim()
            // Strip leading block comments (/* ... */) used by support object markers
            val stripped = inner.replace(Regex("""/\*.*?\*/\s*""", RegexOption.DOT_MATCHES_ALL), "").trim()
            return when {
                stripped.startsWith("CREATE FUNCTION", ignoreCase = true) -> {
                    val name = extractNameAfterKeyword(stripped, "CREATE FUNCTION")
                    DdlStatement("DROP FUNCTION IF EXISTS $name;")
                }
                stripped.startsWith("CREATE PROCEDURE", ignoreCase = true) -> {
                    val name = extractNameAfterKeyword(stripped, "CREATE PROCEDURE")
                    DdlStatement("DROP PROCEDURE IF EXISTS $name;")
                }
                stripped.startsWith("CREATE TRIGGER", ignoreCase = true) -> {
                    val name = extractNameAfterKeyword(stripped, "CREATE TRIGGER")
                    DdlStatement("DROP TRIGGER IF EXISTS $name;")
                }
                else -> null
            }
        }

        return super.invertStatement(stmt)
    }

    private fun extractNameAfterKeyword(sql: String, keyword: String): String {
        val afterKeyword = sql.substring(keyword.length).trimStart()
        val cleaned = if (afterKeyword.uppercase().startsWith("IF NOT EXISTS"))
            afterKeyword.substring("IF NOT EXISTS".length).trimStart()
        else afterKeyword
        return cleaned.split(Regex("[\\s(]"), limit = 2).first()
    }
}
