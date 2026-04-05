package dev.dmigrate.driver.mysql

import dev.dmigrate.core.model.*
import dev.dmigrate.driver.*

class MysqlDdlGenerator : AbstractDdlGenerator(MysqlTypeMapper()) {

    override val dialect = DatabaseDialect.MYSQL

    // ── Quoting ──────────────────────────────────

    override fun quoteIdentifier(name: String): String = "`$name`"

    // ── Custom types (ENUM, COMPOSITE, DOMAIN) ──

    override fun generateCustomTypes(types: Map<String, CustomTypeDefinition>): List<DdlStatement> {
        // MySQL does not support standalone CREATE TYPE.
        // ENUMs are inlined at column level.
        // COMPOSITEs are not supported.
        // DOMAINs are handled inline as base type + CHECK.
        val statements = mutableListOf<DdlStatement>()
        for ((name, typeDef) in types) {
            if (typeDef.kind == CustomTypeKind.COMPOSITE) {
                statements += DdlStatement(
                    "-- TODO: Composite type `$name` is not supported in MySQL",
                    listOf(
                        TransformationNote(
                            type = NoteType.ACTION_REQUIRED,
                            code = "E052",
                            objectName = name,
                            message = "Composite type '$name' is not supported in MySQL and was skipped.",
                            hint = "Consider restructuring the data model to avoid composite types."
                        )
                    )
                )
            }
        }
        return statements
    }

    // ── Sequences ────────────────────────────────

    override fun generateSequences(
        sequences: Map<String, SequenceDefinition>,
        skipped: MutableList<SkippedObject>
    ): List<DdlStatement> {
        val statements = mutableListOf<DdlStatement>()
        for ((name, _) in sequences) {
            skipped += SkippedObject("sequence", name, "Sequences are not supported in MySQL")
            statements += DdlStatement(
                "-- TODO: Sequence `$name` is not supported in MySQL",
                listOf(
                    TransformationNote(
                        type = NoteType.ACTION_REQUIRED,
                        code = "E052",
                        objectName = name,
                        message = "Sequence '$name' is not supported in MySQL and was skipped.",
                        hint = "Use AUTO_INCREMENT columns instead of sequences."
                    )
                )
            )
        }
        return statements
    }

    // ── Tables ───────────────────────────────────

    override fun generateTable(
        name: String,
        table: TableDefinition,
        schema: SchemaDefinition
    ): List<DdlStatement> {
        val statements = mutableListOf<DdlStatement>()
        val notes = mutableListOf<TransformationNote>()
        val columnLines = mutableListOf<String>()

        // Columns
        for ((colName, col) in table.columns) {
            columnLines += generateColumnSql(colName, col, schema, notes)
        }

        // Inline foreign key constraints (non-circular, from column references)
        for ((colName, col) in table.columns) {
            val ref = col.references ?: continue
            val fkName = "fk_${name}_${colName}"
            columnLines += buildForeignKeyClause(fkName, listOf(colName), ref.table, listOf(ref.column), ref.onDelete, ref.onUpdate)
        }

        // Explicit constraints
        for (constraint in table.constraints) {
            columnLines += generateConstraintClause(constraint, notes)
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
                append(generatePartitionClause(partitioning))
            }
            append("\nENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;")
        }
        statements += DdlStatement(tableSql, notes)

        return statements
    }

    private fun generateColumnSql(
        colName: String,
        col: ColumnDefinition,
        schema: SchemaDefinition,
        notes: MutableList<TransformationNote>
    ): String {
        val type = col.type

        // For Identifier type (AUTO_INCREMENT), TypeMapper already returns "INT NOT NULL AUTO_INCREMENT"
        if (type is NeutralType.Identifier && type.autoIncrement) {
            val parts = mutableListOf<String>()
            parts += quoteIdentifier(colName)
            parts += typeMapper.toSql(type)
            if (col.default != null) parts += "DEFAULT ${typeMapper.toDefaultSql(col.default!!, type)}"
            if (col.unique) parts += "UNIQUE"
            return parts.joinToString(" ")
        }

        // For enum columns with ref_type, resolve from schema.customTypes and inline ENUM(...)
        if (type is NeutralType.Enum && type.refType != null) {
            val customType = schema.customTypes[type.refType]
            val enumValues = customType?.values
            if (enumValues != null) {
                val enumDef = enumValues.joinToString(", ") { "'$it'" }
                val parts = mutableListOf<String>()
                parts += quoteIdentifier(colName)
                parts += "ENUM($enumDef)"
                if (col.required) parts += "NOT NULL"
                if (col.default != null) parts += "DEFAULT ${typeMapper.toDefaultSql(col.default!!, type)}"
                if (col.unique) parts += "UNIQUE"
                return parts.joinToString(" ")
            }
        }

        // For enum columns with inline values, inline ENUM(...)
        if (type is NeutralType.Enum && type.values != null) {
            val enumDef = type.values!!.joinToString(", ") { "'$it'" }
            val parts = mutableListOf<String>()
            parts += quoteIdentifier(colName)
            parts += "ENUM($enumDef)"
            if (col.required) parts += "NOT NULL"
            if (col.default != null) parts += "DEFAULT ${typeMapper.toDefaultSql(col.default!!, type)}"
            if (col.unique) parts += "UNIQUE"
            return parts.joinToString(" ")
        }

        // For domain ref_type, use base type + inline CHECK
        if (type is NeutralType.Enum && type.refType != null) {
            val customType = schema.customTypes[type.refType]
            if (customType != null && customType.kind == CustomTypeKind.DOMAIN) {
                val parts = mutableListOf<String>()
                parts += quoteIdentifier(colName)
                parts += customType.baseType ?: "TEXT"
                if (col.required) parts += "NOT NULL"
                if (col.default != null) parts += "DEFAULT ${typeMapper.toDefaultSql(col.default!!, type)}"
                if (col.unique) parts += "UNIQUE"
                if (customType.check != null) {
                    parts += "CHECK (${customType.check})"
                }
                return parts.joinToString(" ")
            }
        }

        // Default: delegate to base class columnSql
        return columnSql(colName, col, schema)
    }

    private fun buildForeignKeyClause(
        constraintName: String,
        fromColumns: List<String>,
        toTable: String,
        toColumns: List<String>,
        onDelete: ReferentialAction?,
        onUpdate: ReferentialAction?
    ): String {
        val fromCols = fromColumns.joinToString(", ") { quoteIdentifier(it) }
        val toCols = toColumns.joinToString(", ") { quoteIdentifier(it) }
        return buildString {
            append("CONSTRAINT ${quoteIdentifier(constraintName)} FOREIGN KEY ($fromCols) REFERENCES ${quoteIdentifier(toTable)} ($toCols)")
            if (onDelete != null) append(" ON DELETE ${referentialActionSql(onDelete)}")
            if (onUpdate != null) append(" ON UPDATE ${referentialActionSql(onUpdate)}")
        }
    }

    private fun generateConstraintClause(
        constraint: ConstraintDefinition,
        notes: MutableList<TransformationNote>
    ): String {
        return when (constraint.type) {
            ConstraintType.CHECK -> {
                "CONSTRAINT ${quoteIdentifier(constraint.name)} CHECK (${constraint.expression})"
            }
            ConstraintType.UNIQUE -> {
                val cols = constraint.columns?.joinToString(", ") { quoteIdentifier(it) } ?: ""
                "CONSTRAINT ${quoteIdentifier(constraint.name)} UNIQUE ($cols)"
            }
            ConstraintType.EXCLUDE -> {
                // EXCLUDE constraints are not supported in MySQL
                notes += TransformationNote(
                    type = NoteType.ACTION_REQUIRED,
                    code = "E052",
                    objectName = constraint.name,
                    message = "EXCLUDE constraint '${constraint.name}' is not supported in MySQL.",
                    hint = "Consider using CHECK constraints or application-level validation instead."
                )
                "-- TODO: EXCLUDE constraint ${quoteIdentifier(constraint.name)} is not supported in MySQL"
            }
            ConstraintType.FOREIGN_KEY -> {
                val ref = constraint.references!!
                buildForeignKeyClause(
                    constraint.name,
                    constraint.columns ?: emptyList(),
                    ref.table,
                    ref.columns,
                    ref.onDelete,
                    ref.onUpdate
                )
            }
        }
    }

    private fun generatePartitionClause(partitioning: PartitionConfig): String {
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
                    "-- TODO: ${index.type.name} index `$indexName` is not supported in MySQL",
                    listOf(
                        TransformationNote(
                            type = NoteType.WARNING,
                            code = "W102",
                            objectName = indexName,
                            message = "${index.type.name} index '$indexName' is not supported in MySQL and was skipped.",
                            hint = "Consider using a BTREE index or FULLTEXT index instead."
                        )
                    )
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
        return views.mapNotNull { (name, view) -> generateView(name, view, skipped) }
    }

    private fun generateView(
        name: String,
        view: ViewDefinition,
        skipped: MutableList<SkippedObject>
    ): DdlStatement? {
        val query = view.query
        if (query == null) {
            skipped += SkippedObject("view", name, "No query defined")
            return null
        }

        if (view.sourceDialect != null && view.sourceDialect != "mysql") {
            skipped += SkippedObject("view", name, "Source dialect '${view.sourceDialect}' is not compatible with MySQL")
            val note = TransformationNote(
                type = NoteType.ACTION_REQUIRED,
                code = "E052",
                objectName = name,
                message = "View '$name' was written for '${view.sourceDialect}' and must be manually rewritten for MySQL.",
                hint = "Rewrite the query using MySQL-compatible SQL syntax."
            )
            val sql = "-- TODO: Rewrite view ${quoteIdentifier(name)} for MySQL (source dialect: ${view.sourceDialect})"
            return DdlStatement(sql, listOf(note))
        }

        val notes = mutableListOf<TransformationNote>()
        if (view.materialized) {
            notes += TransformationNote(
                type = NoteType.WARNING,
                code = "W103",
                objectName = name,
                message = "Materialized views are not supported in MySQL. Created as a regular view instead.",
                hint = "Consider using a table with a scheduled refresh procedure to emulate materialized views."
            )
        }

        return DdlStatement("CREATE OR REPLACE VIEW ${quoteIdentifier(name)} AS\n$query;", notes)
    }

    // ── Functions ────────────────────────────────

    override fun generateFunctions(
        functions: Map<String, FunctionDefinition>,
        skipped: MutableList<SkippedObject>
    ): List<DdlStatement> {
        return functions.mapNotNull { (name, fn) -> generateFunction(name, fn, skipped) }
    }

    private fun generateFunction(
        name: String,
        fn: FunctionDefinition,
        skipped: MutableList<SkippedObject>
    ): DdlStatement? {
        val body = fn.body
        if (body == null) {
            skipped += SkippedObject("function", name, "No body defined")
            return DdlStatement(
                "-- TODO: Implement function ${quoteIdentifier(name)}",
                listOf(
                    TransformationNote(
                        type = NoteType.ACTION_REQUIRED,
                        code = "E052",
                        objectName = name,
                        message = "Function '$name' has no body and must be manually implemented.",
                        hint = "Provide a function body in the schema definition."
                    )
                )
            )
        }

        if (fn.sourceDialect != null && fn.sourceDialect != "mysql") {
            skipped += SkippedObject("function", name, "Source dialect '${fn.sourceDialect}' is not compatible with MySQL")
            return DdlStatement(
                "-- TODO: Rewrite function ${quoteIdentifier(name)} for MySQL (source dialect: ${fn.sourceDialect})",
                listOf(
                    TransformationNote(
                        type = NoteType.ACTION_REQUIRED,
                        code = "E052",
                        objectName = name,
                        message = "Function '$name' was written for '${fn.sourceDialect}' and must be manually rewritten for MySQL.",
                        hint = "Rewrite the function body using MySQL-compatible syntax."
                    )
                )
            )
        }

        val params = fn.parameters.joinToString(", ") { param ->
            val direction = if (param.direction != ParameterDirection.IN) "${param.direction.name} " else ""
            "$direction${quoteIdentifier(param.name)} ${param.type}"
        }
        val returns = fn.returns?.let { "\nRETURNS ${it.type}" } ?: ""
        val deterministic = when (fn.deterministic) {
            true -> "\nDETERMINISTIC"
            false -> "\nNOT DETERMINISTIC"
            null -> ""
        }

        val sql = buildString {
            append("DELIMITER //\n")
            append("CREATE FUNCTION ${quoteIdentifier(name)}($params)$returns$deterministic\n")
            append("BEGIN\n")
            append(body)
            append("\nEND //\n")
            append("DELIMITER ;")
        }
        return DdlStatement(sql)
    }

    // ── Procedures ───────────────────────────────

    override fun generateProcedures(
        procedures: Map<String, ProcedureDefinition>,
        skipped: MutableList<SkippedObject>
    ): List<DdlStatement> {
        return procedures.mapNotNull { (name, proc) -> generateProcedure(name, proc, skipped) }
    }

    private fun generateProcedure(
        name: String,
        proc: ProcedureDefinition,
        skipped: MutableList<SkippedObject>
    ): DdlStatement? {
        val body = proc.body
        if (body == null) {
            skipped += SkippedObject("procedure", name, "No body defined")
            return DdlStatement(
                "-- TODO: Implement procedure ${quoteIdentifier(name)}",
                listOf(
                    TransformationNote(
                        type = NoteType.ACTION_REQUIRED,
                        code = "E052",
                        objectName = name,
                        message = "Procedure '$name' has no body and must be manually implemented.",
                        hint = "Provide a procedure body in the schema definition."
                    )
                )
            )
        }

        if (proc.sourceDialect != null && proc.sourceDialect != "mysql") {
            skipped += SkippedObject("procedure", name, "Source dialect '${proc.sourceDialect}' is not compatible with MySQL")
            return DdlStatement(
                "-- TODO: Rewrite procedure ${quoteIdentifier(name)} for MySQL (source dialect: ${proc.sourceDialect})",
                listOf(
                    TransformationNote(
                        type = NoteType.ACTION_REQUIRED,
                        code = "E052",
                        objectName = name,
                        message = "Procedure '$name' was written for '${proc.sourceDialect}' and must be manually rewritten for MySQL.",
                        hint = "Rewrite the procedure body using MySQL-compatible syntax."
                    )
                )
            )
        }

        val params = proc.parameters.joinToString(", ") { param ->
            val direction = if (param.direction != ParameterDirection.IN) "${param.direction.name} " else ""
            "$direction${quoteIdentifier(param.name)} ${param.type}"
        }

        val sql = buildString {
            append("DELIMITER //\n")
            append("CREATE PROCEDURE ${quoteIdentifier(name)}($params)\n")
            append("BEGIN\n")
            append(body)
            append("\nEND //\n")
            append("DELIMITER ;")
        }
        return DdlStatement(sql)
    }

    // ── Triggers ─────────────────────────────────

    override fun generateTriggers(
        triggers: Map<String, TriggerDefinition>,
        tables: Map<String, TableDefinition>,
        skipped: MutableList<SkippedObject>
    ): List<DdlStatement> {
        return triggers.mapNotNull { (name, trigger) -> generateTrigger(name, trigger, skipped) }
    }

    private fun generateTrigger(
        name: String,
        trigger: TriggerDefinition,
        skipped: MutableList<SkippedObject>
    ): DdlStatement? {
        val body = trigger.body
        if (body == null) {
            skipped += SkippedObject("trigger", name, "No body defined")
            return DdlStatement(
                "-- TODO: Implement trigger ${quoteIdentifier(name)}",
                listOf(
                    TransformationNote(
                        type = NoteType.ACTION_REQUIRED,
                        code = "E052",
                        objectName = name,
                        message = "Trigger '$name' has no body and must be manually implemented.",
                        hint = "Provide a trigger body in the schema definition."
                    )
                )
            )
        }

        if (trigger.sourceDialect != null && trigger.sourceDialect != "mysql") {
            skipped += SkippedObject("trigger", name, "Source dialect '${trigger.sourceDialect}' is not compatible with MySQL")
            return DdlStatement(
                "-- TODO: Rewrite trigger ${quoteIdentifier(name)} for MySQL (source dialect: ${trigger.sourceDialect})",
                listOf(
                    TransformationNote(
                        type = NoteType.ACTION_REQUIRED,
                        code = "E052",
                        objectName = name,
                        message = "Trigger '$name' was written for '${trigger.sourceDialect}' and must be manually rewritten for MySQL.",
                        hint = "Rewrite the trigger body using MySQL-compatible syntax."
                    )
                )
            )
        }

        val timing = trigger.timing.name
        val event = trigger.event.name
        val forEach = trigger.forEach.name

        val sql = buildString {
            append("DELIMITER //\n")
            append("CREATE TRIGGER ${quoteIdentifier(name)}\n")
            append("    $timing $event ON ${quoteIdentifier(trigger.table)}\n")
            append("    FOR EACH $forEach\n")
            append("BEGIN\n")
            append(body)
            append("\nEND //\n")
            append("DELIMITER ;")
        }
        return DdlStatement(sql)
    }

    // ── Rollback overrides ──────────────────────

    override fun invertStatement(stmt: DdlStatement): DdlStatement? {
        val sql = stmt.sql.trim()

        // Handle DELIMITER-wrapped statements
        if (sql.startsWith("DELIMITER //", ignoreCase = true)) {
            val inner = sql.removePrefix("DELIMITER //").removeSuffix("DELIMITER ;").trim()
            return when {
                inner.startsWith("CREATE FUNCTION", ignoreCase = true) -> {
                    val name = extractNameAfterKeyword(inner, "CREATE FUNCTION")
                    DdlStatement("DROP FUNCTION IF EXISTS $name;")
                }
                inner.startsWith("CREATE PROCEDURE", ignoreCase = true) -> {
                    val name = extractNameAfterKeyword(inner, "CREATE PROCEDURE")
                    DdlStatement("DROP PROCEDURE IF EXISTS $name;")
                }
                inner.startsWith("CREATE TRIGGER", ignoreCase = true) -> {
                    val name = extractNameAfterKeyword(inner, "CREATE TRIGGER")
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
