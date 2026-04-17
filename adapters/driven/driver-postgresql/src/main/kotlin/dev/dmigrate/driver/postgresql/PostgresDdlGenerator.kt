package dev.dmigrate.driver.postgresql

import dev.dmigrate.core.model.*
import dev.dmigrate.driver.*

class PostgresDdlGenerator : AbstractDdlGenerator(PostgresTypeMapper()) {

    override val dialect = DatabaseDialect.POSTGRESQL

    // ── Quoting ──────────────────────────────────

    override fun quoteIdentifier(name: String): String = "\"${name.replace("\"", "\"\"")}\""

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
            columnLines += generateColumnSql(colName, col, schema, name, notes)
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
        notes: MutableList<TransformationNote>
    ): String {
        val type = col.type

        // For Identifier type (SERIAL), skip NOT NULL since SERIAL implies it
        if (type is NeutralType.Identifier && type.autoIncrement) {
            val parts = mutableListOf<String>()
            parts += quoteIdentifier(colName)
            parts += typeMapper.toSql(type)
            if (col.default != null) parts += "DEFAULT ${typeMapper.toDefaultSql(col.default!!, type)}"
            if (col.unique) parts += "UNIQUE"
            return parts.joinToString(" ")
        }

        // For enum columns with ref_type, use the custom type name
        if (type is NeutralType.Enum) {
            val refType = type.refType
            if (refType != null) {
                val parts = mutableListOf<String>()
                parts += quoteIdentifier(colName)
                parts += quoteIdentifier(refType)
                if (col.required) parts += "NOT NULL"
                if (col.default != null) parts += "DEFAULT ${typeMapper.toDefaultSql(col.default!!, type)}"
                if (col.unique) parts += "UNIQUE"
                return parts.joinToString(" ")
            }
        }

        // For enum columns with inline values, use TEXT + CHECK constraint
        if (type is NeutralType.Enum) {
            val enumValues = type.values
            if (enumValues != null) {
                val parts = mutableListOf<String>()
                parts += quoteIdentifier(colName)
                parts += "TEXT"
                if (col.required) parts += "NOT NULL"
                if (col.default != null) parts += "DEFAULT ${typeMapper.toDefaultSql(col.default!!, type)}"
                if (col.unique) parts += "UNIQUE"
                val allowed = enumValues.joinToString(", ") { "'${it.replace("'", "''")}'" }
                parts += "CHECK (${quoteIdentifier(colName)} IN ($allowed))"
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
        val sql = buildString {
            append("CONSTRAINT ${quoteIdentifier(constraintName)} FOREIGN KEY ($fromCols) REFERENCES ${quoteIdentifier(toTable)} ($toCols)")
            if (onDelete != null) append(" ON DELETE ${referentialActionSql(onDelete)}")
            if (onUpdate != null) append(" ON UPDATE ${referentialActionSql(onUpdate)}")
        }
        return sql
    }

    private fun generateConstraintClause(constraint: ConstraintDefinition): String {
        return when (constraint.type) {
            ConstraintType.CHECK -> {
                "CONSTRAINT ${quoteIdentifier(constraint.name)} CHECK (${constraint.expression})"
            }
            ConstraintType.UNIQUE -> {
                val cols = constraint.columns?.joinToString(", ") { quoteIdentifier(it) } ?: ""
                "CONSTRAINT ${quoteIdentifier(constraint.name)} UNIQUE ($cols)"
            }
            ConstraintType.EXCLUDE -> {
                "CONSTRAINT ${quoteIdentifier(constraint.name)} EXCLUDE (${constraint.expression})"
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

    private fun generatePartitionStatement(
        parentTable: String,
        partition: PartitionDefinition,
        type: PartitionType
    ): DdlStatement {
        val sql = buildString {
            append("CREATE TABLE ${quoteIdentifier(partition.name)} PARTITION OF ${quoteIdentifier(parentTable)}")
            when (type) {
                PartitionType.RANGE -> {
                    append(" FOR VALUES FROM (${partition.from}) TO (${partition.to})")
                }
                PartitionType.LIST -> {
                    val vals = partition.values?.joinToString(", ") ?: ""
                    append(" FOR VALUES IN ($vals)")
                }
                PartitionType.HASH -> {
                    append(" FOR VALUES WITH (${partition.from})")
                }
            }
            append(";")
        }
        return DdlStatement(sql)
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

        val transformer = ViewQueryTransformer(DatabaseDialect.POSTGRESQL)
        val (transformedQuery, queryNotes) = transformer.transform(query, view.sourceDialect)

        return if (view.materialized) {
            DdlStatement("CREATE MATERIALIZED VIEW ${quoteIdentifier(name)} AS\n$transformedQuery;", queryNotes)
        } else {
            DdlStatement("CREATE OR REPLACE VIEW ${quoteIdentifier(name)} AS\n$transformedQuery;", queryNotes)
        }
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
                        code = "E053",
                        objectName = name,
                        message = "Function '$name' has no body and must be manually implemented.",
                        hint = "Provide a function body in the schema definition."
                    )
                )
            )
        }

        if (fn.sourceDialect != null && fn.sourceDialect != "postgresql") {
            skipped += SkippedObject("function", name, "Source dialect '${fn.sourceDialect}' is not compatible with PostgreSQL")
            return DdlStatement(
                "-- TODO: Rewrite function ${quoteIdentifier(name)} for PostgreSQL (source dialect: ${fn.sourceDialect})",
                listOf(
                    TransformationNote(
                        type = NoteType.ACTION_REQUIRED,
                        code = "E053",
                        objectName = name,
                        message = "Function '$name' was written for '${fn.sourceDialect}' and must be manually rewritten for PostgreSQL.",
                        hint = "Rewrite the function body using PostgreSQL-compatible syntax."
                    )
                )
            )
        }

        val params = fn.parameters.joinToString(", ") { param ->
            val direction = if (param.direction != ParameterDirection.IN) "${param.direction.name} " else ""
            "$direction${quoteIdentifier(param.name)} ${param.type.uppercase()}"
        }
        val returns = fn.returns?.let {
            val type = it.type.uppercase()
            val params = if (it.precision != null) "(${it.precision}${if (it.scale != null) ",${it.scale}" else ""})" else ""
            " RETURNS $type$params"
        } ?: ""
        val language = fn.language ?: "plpgsql"

        val sql = buildString {
            append("CREATE OR REPLACE FUNCTION ${quoteIdentifier(name)}($params)$returns AS \$\$\n")
            append(body)
            append("\n\$\$ LANGUAGE $language;")
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
                        code = "E053",
                        objectName = name,
                        message = "Procedure '$name' has no body and must be manually implemented.",
                        hint = "Provide a procedure body in the schema definition."
                    )
                )
            )
        }

        if (proc.sourceDialect != null && proc.sourceDialect != "postgresql") {
            skipped += SkippedObject("procedure", name, "Source dialect '${proc.sourceDialect}' is not compatible with PostgreSQL")
            return DdlStatement(
                "-- TODO: Rewrite procedure ${quoteIdentifier(name)} for PostgreSQL (source dialect: ${proc.sourceDialect})",
                listOf(
                    TransformationNote(
                        type = NoteType.ACTION_REQUIRED,
                        code = "E053",
                        objectName = name,
                        message = "Procedure '$name' was written for '${proc.sourceDialect}' and must be manually rewritten for PostgreSQL.",
                        hint = "Rewrite the procedure body using PostgreSQL-compatible syntax."
                    )
                )
            )
        }

        val params = proc.parameters.joinToString(", ") { param ->
            val direction = if (param.direction != ParameterDirection.IN) "${param.direction.name} " else ""
            "$direction${quoteIdentifier(param.name)} ${param.type.uppercase()}"
        }
        val language = proc.language ?: "plpgsql"

        val sql = buildString {
            append("CREATE OR REPLACE PROCEDURE ${quoteIdentifier(name)}($params) AS \$\$\n")
            append(body)
            append("\n\$\$ LANGUAGE $language;")
        }
        return DdlStatement(sql)
    }

    // ── Triggers ─────────────────────────────────

    override fun generateTriggers(
        triggers: Map<String, TriggerDefinition>,
        tables: Map<String, TableDefinition>,
        skipped: MutableList<SkippedObject>
    ): List<DdlStatement> {
        return triggers.flatMap { (name, trigger) -> generateTrigger(name, trigger, skipped) }
    }

    private fun generateTrigger(
        name: String,
        trigger: TriggerDefinition,
        skipped: MutableList<SkippedObject>
    ): List<DdlStatement> {
        val body = trigger.body
        if (body == null) {
            skipped += SkippedObject("trigger", name, "No body defined")
            return listOf(
                DdlStatement(
                    "-- TODO: Implement trigger ${quoteIdentifier(name)}",
                    listOf(
                        TransformationNote(
                            type = NoteType.ACTION_REQUIRED,
                            code = "E053",
                            objectName = name,
                            message = "Trigger '$name' has no body and must be manually implemented.",
                            hint = "Provide a trigger body in the schema definition."
                        )
                    )
                )
            )
        }

        if (trigger.sourceDialect != null && trigger.sourceDialect != "postgresql") {
            skipped += SkippedObject("trigger", name, "Source dialect '${trigger.sourceDialect}' is not compatible with PostgreSQL")
            return listOf(
                DdlStatement(
                    "-- TODO: Rewrite trigger ${quoteIdentifier(name)} for PostgreSQL (source dialect: ${trigger.sourceDialect})",
                    listOf(
                        TransformationNote(
                            type = NoteType.ACTION_REQUIRED,
                            code = "E053",
                            objectName = name,
                            message = "Trigger '$name' was written for '${trigger.sourceDialect}' and must be manually rewritten for PostgreSQL.",
                            hint = "Rewrite the trigger body using PostgreSQL-compatible syntax."
                        )
                    )
                )
            )
        }

        // PostgreSQL triggers require a separate trigger function
        val funcName = "trg_fn_${name}"
        val statements = mutableListOf<DdlStatement>()

        // 1. Create trigger function
        val funcSql = buildString {
            append("CREATE OR REPLACE FUNCTION ${quoteIdentifier(funcName)}() RETURNS TRIGGER AS \$\$\n")
            append(body)
            append("\n\$\$ LANGUAGE plpgsql;")
        }
        statements += DdlStatement(funcSql)

        // 2. Create trigger
        val timing = trigger.timing.name
        val event = trigger.event.name
        val forEach = trigger.forEach.name
        val triggerSql = buildString {
            append("CREATE TRIGGER ${quoteIdentifier(name)}\n")
            append("    $timing $event ON ${quoteIdentifier(trigger.table)}\n")
            append("    FOR EACH $forEach")
            if (trigger.condition != null) {
                append("\n    WHEN (${trigger.condition})")
            }
            append("\n    EXECUTE FUNCTION ${quoteIdentifier(funcName)}();")
        }
        statements += DdlStatement(triggerSql)

        return statements
    }
}
