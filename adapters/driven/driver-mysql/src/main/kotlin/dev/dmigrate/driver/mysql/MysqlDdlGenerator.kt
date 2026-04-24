package dev.dmigrate.driver.mysql

import dev.dmigrate.core.model.*
import dev.dmigrate.driver.*

class MysqlDdlGenerator : AbstractDdlGenerator(MysqlTypeMapper()) {

    override val dialect = DatabaseDialect.MYSQL

    private val routineHelper = MysqlRoutineDdlHelper(::quoteIdentifier)
    private val sequenceSupport = MysqlSequenceDdlSupport(::quoteIdentifier)
    private val indexPartitionHelper = MysqlIndexPartitionDdlHelper(::quoteIdentifier)

    override fun generate(schema: SchemaDefinition, options: DdlGenerationOptions): DdlResult {
        sequenceSupport.beginRun(schema, options)
        return sequenceSupport.finalizeResult(super.generate(schema, options))
    }

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
        sequences: Map<String, SequenceDefinition>,
        skipped: MutableList<SkippedObject>
    ): List<DdlStatement> = sequenceSupport.generateSequences(sequences, skipped)

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
                append(indexPartitionHelper.generatePartitionClause(partitioning, notes))
            }
            append("\nENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;")
        }
        notes += sequenceSupport.drainPendingNotes()
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

    override fun generateIndices(tableName: String, table: TableDefinition): List<DdlStatement> =
        indexPartitionHelper.generateIndices(tableName, table)

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
        val statements = mutableListOf<DdlStatement>()
        statements += sequenceSupport.generateSupportFunctions(functions, skipped)
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
