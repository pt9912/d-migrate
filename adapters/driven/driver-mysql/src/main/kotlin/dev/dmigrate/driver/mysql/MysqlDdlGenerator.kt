package dev.dmigrate.driver.mysql

import dev.dmigrate.core.model.*
import dev.dmigrate.driver.*

class MysqlDdlGenerator : AbstractDdlGenerator(MysqlTypeMapper()) {

    override val dialect = DatabaseDialect.MYSQL

    private val routineHelper = MysqlRoutineDdlHelper(::quoteIdentifier)

    // ── Sequence emulation state (§4.4, §4.7) ──

    private var currentOptions: DdlGenerationOptions = DdlGenerationOptions()
    private var currentSchema: SchemaDefinition? = null
    private var supportObjectsBlocked = false
    private val pendingSupportTriggers = mutableListOf<SupportTriggerSpec>()
    private val pendingSequenceNotes = mutableListOf<TransformationNote>()

    private data class SupportTriggerSpec(
        val tableName: String,
        val columnName: String,
        val sequenceName: String,
    )

    private val isHelperTable: Boolean
        get() = currentOptions.mysqlNamedSequenceMode == MysqlNamedSequenceMode.HELPER_TABLE

    override fun generate(schema: SchemaDefinition, options: DdlGenerationOptions): DdlResult {
        currentOptions = options
        currentSchema = schema
        supportObjectsBlocked = false
        pendingSupportTriggers.clear()
        pendingSequenceNotes.clear()
        val result = super.generate(schema, options)
        // W117: global warning for transaction-bound sequence increments (once per run in helper_table)
        if (isHelperTable && schema.sequences?.isNotEmpty() == true) {
            val w117 = TransformationNote(
                type = NoteType.WARNING, code = "W117",
                objectName = MysqlSequenceNaming.SUPPORT_TABLE,
                message = "Sequence values in MySQL helper-table mode are transaction-bound; " +
                    "rollback retracts increments (unlike native PostgreSQL sequences).",
            )
            return DdlResult(result.statements, result.skippedObjects, result.globalNotes + w117)
        }
        return result
    }

    // ── SequenceNextVal interception (§4.6) ──────

    override fun resolveSequenceDefault(
        tableName: String,
        colName: String,
        col: ColumnDefinition,
        seqDefault: DefaultValue.SequenceNextVal,
    ): String? {
        if (isHelperTable) {
            // Collect trigger metadata; actual trigger DDL generated in generateTriggers()
            pendingSupportTriggers += SupportTriggerSpec(tableName, colName, seqDefault.sequenceName)
            pendingSequenceNotes += TransformationNote(
                type = NoteType.WARNING, code = "W115",
                objectName = "$tableName.$colName",
                message = "SequenceNextVal on '$colName' uses lossy MySQL trigger semantics; " +
                    "explicit NULL is treated like an omitted value.",
            )
            return null // no DEFAULT clause — trigger handles it
        }
        // ACTION_REQUIRED: emit E056 for this column
        pendingSequenceNotes += TransformationNote(
            type = NoteType.ACTION_REQUIRED, code = "E056",
            objectName = "$tableName.$colName",
            message = "Sequence-based default on '$colName' requires " +
                "--mysql-named-sequences helper_table to generate support objects.",
            hint = "Add --mysql-named-sequences helper_table to enable sequence emulation.",
        )
        return null
    }

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
        sequences: Map<String, SequenceDefinition>,
        skipped: MutableList<SkippedObject>
    ): List<DdlStatement> {
        if (!isHelperTable) {
            // ACTION_REQUIRED mode: skip all sequences with E056
            val statements = mutableListOf<DdlStatement>()
            for ((name, _) in sequences) {
                val action = ManualActionRequired(
                    code = "E056", objectType = "sequence", objectName = name,
                    reason = "Sequence '$name' is not supported in MySQL without helper_table mode.",
                    hint = "Add --mysql-named-sequences helper_table to enable sequence emulation.",
                )
                skipped += action.toSkipped()
                statements += DdlStatement("", listOf(action.toNote()))
            }
            return statements
        }

        // HELPER_TABLE mode: emit dmg_sequences table + seed statements
        val statements = mutableListOf<DdlStatement>()
        val notes = mutableListOf<TransformationNote>()

        // E124: collision check for dmg_sequences table name
        val schema = currentSchema
        if (schema != null && MysqlSequenceNaming.SUPPORT_TABLE in (schema.tables?.keys ?: emptySet())) {
            val action = ManualActionRequired(
                code = "E124", objectType = "table", objectName = MysqlSequenceNaming.SUPPORT_TABLE,
                reason = "Support object name collision: '${MysqlSequenceNaming.SUPPORT_TABLE}' already exists in the neutral schema.",
                hint = "Rename the existing table or use --mysql-named-sequences action_required.",
            )
            skipped += action.toSkipped()
            statements += DdlStatement("", listOf(action.toNote()))
            supportObjectsBlocked = true
            return statements
        }

        val createTable = buildString {
            appendLine("CREATE TABLE `${MysqlSequenceNaming.SUPPORT_TABLE}` (")
            appendLine("    `managed_by` VARCHAR(32) NOT NULL,")
            appendLine("    `format_version` VARCHAR(32) NOT NULL,")
            appendLine("    `name` VARCHAR(255) NOT NULL,")
            appendLine("    `next_value` BIGINT NOT NULL,")
            appendLine("    `increment_by` BIGINT NOT NULL,")
            appendLine("    `min_value` BIGINT NULL,")
            appendLine("    `max_value` BIGINT NULL,")
            appendLine("    `cycle_enabled` TINYINT(1) NOT NULL,")
            appendLine("    `cache_size` INT NULL,")
            appendLine("    PRIMARY KEY (`name`)")
            append(") ENGINE=InnoDB;")
        }
        statements += DdlStatement(createTable)

        // Seed one row per sequence
        for ((name, seq) in sequences) {
            val start = seq.start ?: 1L
            val increment = seq.increment ?: 1L
            val minVal = seq.minValue?.toString() ?: "NULL"
            val maxVal = seq.maxValue?.toString() ?: "NULL"
            val cycle = if (seq.cycle == true) 1 else 0
            val cache = seq.cache?.toString() ?: "NULL"
            val seedSql = "INSERT INTO `${MysqlSequenceNaming.SUPPORT_TABLE}` " +
                "(`managed_by`, `format_version`, `name`, `next_value`, `increment_by`, " +
                "`min_value`, `max_value`, `cycle_enabled`, `cache_size`) VALUES " +
                "('d-migrate', 'mysql-sequence-v1', '$name', $start, $increment, " +
                "$minVal, $maxVal, $cycle, $cache);"
            statements += DdlStatement(seedSql)

            // W114: cache stored but not preallocation-emulated
            if (seq.cache != null) {
                notes += TransformationNote(
                    type = NoteType.WARNING, code = "W114",
                    objectName = name,
                    message = "Sequence '$name' has cache=${seq.cache} but MySQL helper-table mode " +
                        "does not emulate preallocation; cache value is stored as metadata only.",
                )
            }
        }

        if (notes.isNotEmpty()) {
            statements += DdlStatement("", notes)
        }
        return statements
    }

    override fun canGenerateSpatial(profile: SpatialProfile): Boolean =
        profile == SpatialProfile.NATIVE

    // ── Tables (wrapper to attach pending sequence notes) ──

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

        // Columns
        for ((colName, col) in table.columns) {
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
        for ((colName, col) in table.columns) {
            val ref = col.references ?: continue
            if ((name to colName) in deferredFks) continue
            val fkName = "fk_${name}_${colName}"
            columnLines += buildForeignKeyClause(fkName, listOf(colName), ref.table, listOf(ref.column), ref.onDelete, ref.onUpdate)
        }

        // Explicit constraints
        for (constraint in table.constraints) {
            generateConstraintClause(constraint, notes)?.let { columnLines += it }
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
            // Partitioning (inline in CREATE TABLE for MySQL)
            val partitioning = table.partitioning
            if (partitioning != null) {
                append("\n")
                append(generatePartitionClause(partitioning, notes))
            }
            append("\nENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;")
        }
        // Attach pending sequence notes (W115 / E056) collected during columnSql()
        notes += pendingSequenceNotes
        pendingSequenceNotes.clear()
        statements += DdlStatement(tableSql, notes)

        return statements
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

    private fun generatePartitionClause(partitioning: PartitionConfig, notes: MutableList<TransformationNote>): String {
        if (partitioning.type == PartitionType.RANGE) {
            notes += TransformationNote(
                type = NoteType.WARNING,
                code = "W112",
                objectName = partitioning.key.joinToString(","),
                message = "RANGE partition expressions may need manual adjustment for MySQL (e.g., wrapping date columns with YEAR()).",
                hint = "Review the partition key expressions and adjust for MySQL-specific syntax if needed."
            )
        }
        val key = partitioning.key.joinToString(", ") { quoteIdentifier(it) }
        return buildString {
            append("PARTITION BY ${partitioning.type.name} ($key)")
            if (partitioning.partitions.isNotEmpty()) {
                append(" (\n")
                val partitionDefs = partitioning.partitions.map { partition ->
                    buildString {
                        append("    PARTITION ${quoteIdentifier(partition.name)}")
                        when (partitioning.type) {
                            PartitionType.RANGE -> {
                                append(" VALUES LESS THAN (${partition.to})")
                            }
                            PartitionType.LIST -> {
                                val vals = partition.values?.joinToString(", ") ?: ""
                                append(" VALUES IN ($vals)")
                            }
                            PartitionType.HASH -> {
                                // HASH partitions don't have explicit values in MySQL
                            }
                        }
                    }
                }
                append(partitionDefs.joinToString(",\n"))
                append("\n)")
            }
        }
    }

    // ── Indices ──────────────────────────────────

    override fun generateIndices(tableName: String, table: TableDefinition): List<DdlStatement> {
        return table.indices.mapNotNull { index -> generateIndex(tableName, index) }
    }

    private fun generateIndex(tableName: String, index: IndexDefinition): DdlStatement? {
        val indexName = index.name ?: "idx_${tableName}_${index.columns.joinToString("_")}"
        val cols = index.columns.joinToString(", ") { quoteIdentifier(it) }

        // Unsupported index types on InnoDB
        return when (index.type) {
            IndexType.GIN, IndexType.GIST, IndexType.BRIN -> {
                DdlStatement(
                    "",
                    listOf(TransformationNote(
                        type = NoteType.WARNING, code = "W102", objectName = indexName,
                        message = "${index.type.name} index '$indexName' is not supported in MySQL and was skipped.",
                        hint = "Consider using a BTREE index or FULLTEXT index instead.",
                    ))
                )
            }
            IndexType.HASH -> {
                // HASH index is not supported on InnoDB; use BTREE with a warning
                val sql = buildString {
                    append("CREATE ")
                    if (index.unique) append("UNIQUE ")
                    append("INDEX ${quoteIdentifier(indexName)} ON ${quoteIdentifier(tableName)}")
                    append(" USING BTREE")
                    append(" ($cols);")
                }
                DdlStatement(
                    sql,
                    listOf(
                        TransformationNote(
                            type = NoteType.WARNING,
                            code = "W102",
                            objectName = indexName,
                            message = "HASH index '$indexName' is not supported on InnoDB; converted to BTREE.",
                            hint = "InnoDB only supports BTREE indexes. The HASH index has been automatically converted."
                        )
                    )
                )
            }
            IndexType.BTREE -> {
                val sql = buildString {
                    append("CREATE ")
                    if (index.unique) append("UNIQUE ")
                    append("INDEX ${quoteIdentifier(indexName)} ON ${quoteIdentifier(tableName)}")
                    append(" ($cols);")
                }
                DdlStatement(sql)
            }
        }
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
        if (!isHelperTable || supportObjectsBlocked) return routineHelper.generateFunctions(functions, skipped)

        val statements = mutableListOf<DdlStatement>()

        // E124: collision check for support routine names
        for (routineName in listOf(MysqlSequenceNaming.NEXTVAL_ROUTINE, MysqlSequenceNaming.SETVAL_ROUTINE)) {
            if (routineName in (functions.keys)) {
                val action = ManualActionRequired(
                    code = "E124", objectType = "function", objectName = routineName,
                    reason = "Support object name collision: '$routineName' already exists in the neutral schema.",
                    hint = "Rename the existing function or use --mysql-named-sequences action_required.",
                )
                skipped += action.toSkipped()
                statements += DdlStatement("", listOf(action.toNote()))
                supportObjectsBlocked = true
                // Skip support routines but still emit user functions
                statements += routineHelper.generateFunctions(functions, skipped)
                return statements
            }
        }

        // dmg_nextval routine
        val nextvalSql = buildString {
            appendLine("DELIMITER //")
            appendLine("/* d-migrate:mysql-sequence-v1 object=nextval */")
            appendLine("CREATE FUNCTION `${MysqlSequenceNaming.NEXTVAL_ROUTINE}`(seq_name VARCHAR(255))")
            appendLine("RETURNS BIGINT")
            appendLine("DETERMINISTIC")
            appendLine("MODIFIES SQL DATA")
            appendLine("BEGIN")
            appendLine("    DECLARE val BIGINT;")
            appendLine("    UPDATE `${MysqlSequenceNaming.SUPPORT_TABLE}` SET `next_value` = `next_value` + `increment_by` WHERE `name` = seq_name;")
            appendLine("    SELECT `next_value` - `increment_by` INTO val FROM `${MysqlSequenceNaming.SUPPORT_TABLE}` WHERE `name` = seq_name;")
            appendLine("    RETURN val;")
            appendLine("END //")
            append("DELIMITER ;")
        }
        statements += DdlStatement(nextvalSql)

        // dmg_setval routine
        val setvalSql = buildString {
            appendLine("DELIMITER //")
            appendLine("/* d-migrate:mysql-sequence-v1 object=setval */")
            appendLine("CREATE FUNCTION `${MysqlSequenceNaming.SETVAL_ROUTINE}`(seq_name VARCHAR(255), new_value BIGINT)")
            appendLine("RETURNS BIGINT")
            appendLine("DETERMINISTIC")
            appendLine("MODIFIES SQL DATA")
            appendLine("BEGIN")
            appendLine("    UPDATE `${MysqlSequenceNaming.SUPPORT_TABLE}` SET `next_value` = new_value WHERE `name` = seq_name;")
            appendLine("    RETURN new_value;")
            appendLine("END //")
            append("DELIMITER ;")
        }
        statements += DdlStatement(setvalSql)

        // Then user functions
        statements += routineHelper.generateFunctions(functions, skipped)
        return statements
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
        if (!isHelperTable || supportObjectsBlocked) return routineHelper.generateTriggers(triggers, tables, skipped)

        val statements = mutableListOf<DdlStatement>()
        // E124: collision check for support trigger names
        for (spec in pendingSupportTriggers) {
            val trigName = MysqlSequenceNaming.triggerName(spec.tableName, spec.columnName)
            if (trigName in triggers) {
                val action = ManualActionRequired(
                    code = "E124", objectType = "trigger", objectName = trigName,
                    reason = "Support object name collision: '$trigName' already exists in the neutral schema.",
                    hint = "Rename the existing trigger or use --mysql-named-sequences action_required.",
                )
                skipped += action.toSkipped()
                statements += DdlStatement("", listOf(action.toNote()))
            }
        }
        // Generate support triggers for SequenceNextVal columns (skip colliding ones)
        for (spec in pendingSupportTriggers) {
            val trigName = MysqlSequenceNaming.triggerName(spec.tableName, spec.columnName)
            if (trigName in triggers) continue // already reported as E124
            val triggerSql = buildString {
                appendLine("DELIMITER //")
                appendLine("/* d-migrate:mysql-sequence-v1 object=sequence-trigger sequence=${spec.sequenceName} table=${spec.tableName} column=${spec.columnName} */")
                appendLine("CREATE TRIGGER `$trigName`")
                appendLine("    BEFORE INSERT ON `${spec.tableName}`")
                appendLine("    FOR EACH ROW")
                appendLine("BEGIN")
                appendLine("    IF NEW.`${spec.columnName}` IS NULL THEN")
                appendLine("        SET NEW.`${spec.columnName}` = `${MysqlSequenceNaming.NEXTVAL_ROUTINE}`('${spec.sequenceName}');")
                appendLine("    END IF;")
                appendLine("END //")
                append("DELIMITER ;")
            }
            statements += DdlStatement(triggerSql)
        }
        // Then user triggers
        statements += routineHelper.generateTriggers(triggers, tables, skipped)
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
