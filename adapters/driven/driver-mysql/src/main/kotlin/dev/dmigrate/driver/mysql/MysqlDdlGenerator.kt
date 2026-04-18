package dev.dmigrate.driver.mysql

import dev.dmigrate.core.model.*
import dev.dmigrate.driver.*

class MysqlDdlGenerator : AbstractDdlGenerator(MysqlTypeMapper()) {

    override val dialect = DatabaseDialect.MYSQL

    private val routineHelper = MysqlRoutineDdlHelper(::quoteIdentifier)

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
                statements += DdlStatement(
                    "-- TODO: Composite type `$name` is not supported in MySQL",
                    listOf(
                        TransformationNote(
                            type = NoteType.ACTION_REQUIRED,
                            code = "E054",
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
                        code = "E056",
                        objectName = name,
                        message = "Sequence '$name' is not supported in MySQL and was skipped.",
                        hint = "Use AUTO_INCREMENT columns instead of sequences."
                    )
                )
            )
        }
        return statements
    }

    override fun canGenerateSpatial(profile: SpatialProfile): Boolean =
        profile == SpatialProfile.NATIVE

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

        // Columns
        for ((colName, col) in table.columns) {
            columnLines += generateColumnSql(colName, col, schema, notes)
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
                append(generatePartitionClause(partitioning, notes))
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
                val enumDef = enumValues.joinToString(", ") { "'${it.replace("'", "''")}'" }
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
            val enumDef = type.values!!.joinToString(", ") { "'${it.replace("'", "''")}'" }
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

        // Geometry: append SRID when present, emit W120 if best-effort
        if (type is NeutralType.Geometry) {
            val parts = mutableListOf<String>()
            parts += quoteIdentifier(colName)
            val baseType = typeMapper.toSql(type)
            val srid = type.srid
            if (srid != null) {
                parts += "$baseType /*!80003 SRID $srid */"
                notes += TransformationNote(
                    type = NoteType.WARNING, code = "W120",
                    objectName = "$colName", message = "SRID $srid emitted as MySQL comment hint; " +
                        "full SRID constraint support depends on MySQL 8.0+",
                )
            } else {
                parts += baseType
            }
            if (col.required) parts += "NOT NULL"
            return parts.joinToString(" ")
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
                    code = "E054",
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
        return routineHelper.generateTriggers(triggers, tables, skipped)
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
