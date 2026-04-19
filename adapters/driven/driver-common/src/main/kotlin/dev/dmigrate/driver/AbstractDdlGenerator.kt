package dev.dmigrate.driver

import dev.dmigrate.core.model.*

abstract class AbstractDdlGenerator(
    protected val typeMapper: TypeMapper
) : DdlGenerator {

    override fun generate(schema: SchemaDefinition, options: DdlGenerationOptions): DdlResult {
        val statements = mutableListOf<DdlStatement>()
        val skipped = mutableListOf<SkippedObject>()
        val blockedTables = mutableSetOf<String>()

        // ─── PRE_DATA (default phase) ────────────────────────────
        statements += generateHeader(schema)
        statements += generateCustomTypes(schema.customTypes)

        var preSkipCount = skipped.size
        statements += generateSequences(schema.sequences, skipped)
        tagNewSkips(skipped, preSkipCount, DdlPhase.PRE_DATA)

        val (sorted, circularEdges) = topologicalSort(schema.tables)
        val deferredFks = circularEdges.map { it.fromTable to it.fromColumn }.toSet()
        for ((name, table) in sorted) {
            val spatialBlockNote = spatialTableBlockNote(name, table, options)
            if (spatialBlockNote != null) {
                registerBlockedTable(name, spatialBlockNote, blockedTables, skipped)
                statements += DdlStatement("", notes = listOf(spatialBlockNote))
                continue
            }
            val tableStatements = generateTable(name, table, schema, deferredFks, options)
            val blockNote = tableStatements.asSequence()
                .flatMap { it.notes.asSequence() }
                .firstOrNull { it.blocksTable }
            if (blockNote != null) {
                registerBlockedTable(name, blockNote, blockedTables, skipped)
            }
            statements += tableStatements
        }
        for ((name, table) in sorted) {
            if (shouldBlockTable(name, table, options)) continue
            if (name in blockedTables) continue
            statements += generateIndices(name, table)
        }

        preSkipCount = skipped.size
        statements += handleCircularReferences(circularEdges, skipped)
        tagNewSkips(skipped, preSkipCount, DdlPhase.PRE_DATA)

        // ─── Views (PRE_DATA for now; Step C will split) ─────────
        val sortedViews = sortViewsByDependencies(schema.views)
        statements += sortedViews.notes.map { DdlStatement("", notes = listOf(it)) }

        preSkipCount = skipped.size
        statements += generateViews(sortedViews.sorted, skipped)
        tagNewSkips(skipped, preSkipCount, DdlPhase.PRE_DATA)

        // ─── POST_DATA ───────────────────────────────────────────
        preSkipCount = skipped.size
        statements += generateFunctions(schema.functions, skipped).withPhase(DdlPhase.POST_DATA)
        tagNewSkips(skipped, preSkipCount, DdlPhase.POST_DATA)

        preSkipCount = skipped.size
        statements += generateProcedures(schema.procedures, skipped).withPhase(DdlPhase.POST_DATA)
        tagNewSkips(skipped, preSkipCount, DdlPhase.POST_DATA)

        preSkipCount = skipped.size
        statements += generateTriggers(schema.triggers, schema.tables, skipped).withPhase(DdlPhase.POST_DATA)
        tagNewSkips(skipped, preSkipCount, DdlPhase.POST_DATA)

        return DdlResult(statements.filter { it.sql.isNotBlank() || it.notes.isNotEmpty() }, skipped)
    }

    override fun generateRollback(schema: SchemaDefinition, options: DdlGenerationOptions): DdlResult {
        val up = generate(schema, options)
        val downStatements = up.statements.reversed().mapNotNull { invertStatement(it) }
        return DdlResult(downStatements, emptyList())
    }

    // ── Abstract methods ────────────────────────

    abstract fun quoteIdentifier(name: String): String
    abstract fun generateTable(name: String, table: TableDefinition, schema: SchemaDefinition, deferredFks: Set<Pair<String, String>> = emptySet(), options: DdlGenerationOptions = DdlGenerationOptions()): List<DdlStatement>
    abstract fun generateCustomTypes(types: Map<String, CustomTypeDefinition>): List<DdlStatement>
    abstract fun generateSequences(sequences: Map<String, SequenceDefinition>, skipped: MutableList<SkippedObject>): List<DdlStatement>
    abstract fun generateIndices(tableName: String, table: TableDefinition): List<DdlStatement>
    abstract fun handleCircularReferences(edges: List<CircularFkEdge>, skipped: MutableList<SkippedObject>): List<DdlStatement>
    abstract fun generateViews(views: Map<String, ViewDefinition>, skipped: MutableList<SkippedObject>): List<DdlStatement>
    abstract fun generateFunctions(functions: Map<String, FunctionDefinition>, skipped: MutableList<SkippedObject>): List<DdlStatement>
    abstract fun generateProcedures(procedures: Map<String, ProcedureDefinition>, skipped: MutableList<SkippedObject>): List<DdlStatement>
    abstract fun generateTriggers(triggers: Map<String, TriggerDefinition>, tables: Map<String, TableDefinition>, skipped: MutableList<SkippedObject>): List<DdlStatement>

    // ── Spatial helpers ──────────────────────────

    protected fun hasGeometryColumns(table: TableDefinition): Boolean =
        table.columns.values.any { it.type is NeutralType.Geometry }

    protected open fun shouldBlockTable(name: String, table: TableDefinition, options: DdlGenerationOptions): Boolean =
        hasGeometryColumns(table) && !canGenerateSpatial(options.spatialProfile)

    protected open fun spatialTableBlockNote(
        name: String,
        table: TableDefinition,
        options: DdlGenerationOptions,
    ): TransformationNote? {
        if (!shouldBlockTable(name, table, options)) return null
        return TransformationNote(
            type = NoteType.ACTION_REQUIRED,
            code = "E052",
            objectName = name,
            message = "Table '$name' skipped: contains geometry columns incompatible with spatial profile '${options.spatialProfile.cliName}'",
            hint = "Use --spatial-profile to enable spatial DDL generation for this dialect",
            blocksTable = true,
        )
    }

    protected open fun canGenerateSpatial(profile: SpatialProfile): Boolean = when (profile) {
        SpatialProfile.POSTGIS, SpatialProfile.NATIVE, SpatialProfile.SPATIALITE -> true
        SpatialProfile.NONE -> false
    }

    private fun registerBlockedTable(
        name: String,
        blockNote: TransformationNote,
        blockedTables: MutableSet<String>,
        skipped: MutableList<SkippedObject>,
    ) {
        blockedTables += name
        skipped += SkippedObject(
            type = "table",
            name = name,
            reason = blockNote.message,
            code = blockNote.code,
            hint = blockNote.hint,
            phase = DdlPhase.PRE_DATA,
        )
    }

    private fun List<DdlStatement>.withPhase(phase: DdlPhase): List<DdlStatement> =
        map { it.copy(phase = phase) }

    private fun tagNewSkips(skipped: MutableList<SkippedObject>, fromIndex: Int, phase: DdlPhase) {
        for (i in fromIndex until skipped.size) {
            skipped[i] = skipped[i].copy(phase = phase)
        }
    }

    // ── Shared logic ────────────────────────────

    protected open fun getVersion(): String = "0.9.1"

    protected fun generateHeader(schema: SchemaDefinition): List<DdlStatement> {
        val header = buildString {
            appendLine("-- Generated by d-migrate ${getVersion()}")
            appendLine("-- Source: neutral schema v${schema.version} \"${schema.name}\"")
            append("-- Target: ${dialect.name.lowercase()} | Generated: ${java.time.Instant.now()}")
        }
        return listOf(DdlStatement(header))
    }

    protected fun columnSql(colName: String, col: ColumnDefinition, schema: SchemaDefinition): String {
        val parts = mutableListOf<String>()
        parts += quoteIdentifier(colName)
        parts += typeMapper.toSql(col.type)
        if (col.required) parts += "NOT NULL"
        if (col.default != null) parts += "DEFAULT ${typeMapper.toDefaultSql(col.default!!, col.type)}"
        if (col.unique) parts += "UNIQUE"
        return parts.joinToString(" ")
    }

    protected fun referentialActionSql(action: ReferentialAction): String = when (action) {
        ReferentialAction.RESTRICT -> "RESTRICT"
        ReferentialAction.CASCADE -> "CASCADE"
        ReferentialAction.SET_NULL -> "SET NULL"
        ReferentialAction.SET_DEFAULT -> "SET DEFAULT"
        ReferentialAction.NO_ACTION -> "NO ACTION"
    }

    protected open fun invertStatement(stmt: DdlStatement): DdlStatement? {
        val sql = stmt.sql.trim()
        return when {
            sql.startsWith("CREATE TABLE", ignoreCase = true) -> {
                val name = extractNameAfter(sql, "CREATE TABLE")
                DdlStatement("DROP TABLE IF EXISTS $name;")
            }
            sql.startsWith("CREATE INDEX", ignoreCase = true) || sql.startsWith("CREATE UNIQUE INDEX", ignoreCase = true) -> {
                val name = extractNameAfter(sql, if ("UNIQUE" in sql.uppercase()) "CREATE UNIQUE INDEX" else "CREATE INDEX")
                DdlStatement("DROP INDEX IF EXISTS $name;")
            }
            sql.startsWith("CREATE TYPE", ignoreCase = true) -> {
                val name = extractNameAfter(sql, "CREATE TYPE")
                DdlStatement("DROP TYPE IF EXISTS $name;")
            }
            sql.startsWith("CREATE VIEW", ignoreCase = true) || sql.startsWith("CREATE OR REPLACE VIEW", ignoreCase = true) -> {
                val keyword = if ("OR REPLACE" in sql.uppercase()) "CREATE OR REPLACE VIEW" else "CREATE VIEW"
                val name = extractNameAfter(sql, keyword)
                DdlStatement("DROP VIEW IF EXISTS $name;")
            }
            sql.startsWith("CREATE MATERIALIZED VIEW", ignoreCase = true) -> {
                val name = extractNameAfter(sql, "CREATE MATERIALIZED VIEW")
                DdlStatement("DROP MATERIALIZED VIEW IF EXISTS $name;")
            }
            sql.startsWith("CREATE FUNCTION", ignoreCase = true) || sql.startsWith("CREATE OR REPLACE FUNCTION", ignoreCase = true) -> {
                val keyword = if ("OR REPLACE" in sql.uppercase()) "CREATE OR REPLACE FUNCTION" else "CREATE FUNCTION"
                val name = extractNameAfter(sql, keyword)
                DdlStatement("DROP FUNCTION IF EXISTS $name;")
            }
            sql.startsWith("CREATE PROCEDURE", ignoreCase = true) -> {
                val name = extractNameAfter(sql, "CREATE PROCEDURE")
                DdlStatement("DROP PROCEDURE IF EXISTS $name;")
            }
            sql.startsWith("CREATE TRIGGER", ignoreCase = true) -> {
                val name = extractNameAfter(sql, "CREATE TRIGGER")
                DdlStatement("DROP TRIGGER IF EXISTS $name;")
            }
            sql.startsWith("CREATE SEQUENCE", ignoreCase = true) -> {
                val name = extractNameAfter(sql, "CREATE SEQUENCE")
                DdlStatement("DROP SEQUENCE IF EXISTS $name;")
            }
            sql.startsWith("ALTER TABLE", ignoreCase = true) && sql.contains("ADD CONSTRAINT", ignoreCase = true) -> {
                val tableName = extractNameAfter(sql, "ALTER TABLE")
                val addConstraintIdx = sql.uppercase().indexOf("ADD CONSTRAINT")
                val constraintPart = sql.substring(addConstraintIdx + "ADD CONSTRAINT".length).trimStart()
                val constraintName = constraintPart.split(Regex("[\\s(]"), limit = 2).first()
                DdlStatement("ALTER TABLE $tableName DROP CONSTRAINT IF EXISTS $constraintName;")
            }
            sql.startsWith("--") -> null // Skip comments
            else -> null
        }
    }

    private fun extractNameAfter(sql: String, keyword: String): String {
        val afterKeyword = sql.substring(keyword.length).trimStart()
        // Handle IF NOT EXISTS
        val cleaned = if (afterKeyword.uppercase().startsWith("IF NOT EXISTS"))
            afterKeyword.substring("IF NOT EXISTS".length).trimStart()
        else afterKeyword
        // Take the first token (quoted or unquoted identifier)
        return cleaned.split(Regex("[\\s(]"), limit = 2).first()
    }

    // ── Topological sort ────────────────────────

    data class TopologicalSortResult(
        val sorted: List<Pair<String, TableDefinition>>,
        val circularEdges: List<CircularFkEdge>
    )

    data class ViewSortResult(
        val sorted: Map<String, ViewDefinition>,
        val notes: List<TransformationNote> = emptyList(),
    )

    data class CircularFkEdge(
        val fromTable: String,
        val fromColumn: String,
        val toTable: String,
        val toColumn: String
    )

    protected fun topologicalSort(tables: Map<String, TableDefinition>): TopologicalSortResult {
        val edges = tables.flatMap { (tableName, table) ->
            table.columns.mapNotNull { (colName, col) ->
                col.references?.let { ref ->
                    dev.dmigrate.core.dependency.FkEdge(tableName, colName, ref.table, ref.column)
                }
            }
        }
        val result = dev.dmigrate.core.dependency.sortTablesByDependency(tables.keys, edges)
        return TopologicalSortResult(
            sorted = result.sorted.map { it to tables[it]!! },
            circularEdges = result.circularEdges.map {
                CircularFkEdge(it.fromTable, it.fromColumn ?: "", it.toTable, it.toColumn ?: "")
            },
        )
    }

    protected fun sortViewsByDependencies(views: Map<String, ViewDefinition>): ViewSortResult {
        if (views.isEmpty()) return ViewSortResult(views)

        val viewNames = views.keys
        val originalOrder = views.keys.toList()
        val deps = views.mapValuesTo(linkedMapOf()) { (name, view) ->
            (
                declaredViewDependencies(name, view, viewNames) +
                    inferViewDependenciesFromQuery(name, view.query, viewNames)
                ).toMutableSet()
        }
        val inDegree = deps.mapValuesTo(mutableMapOf()) { (_, depSet) -> depSet.size }
        val queue = ArrayDeque(originalOrder.filter { inDegree[it] == 0 })
        val sorted = mutableListOf<String>()

        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            sorted += current
            for ((name, depSet) in deps) {
                if (depSet.remove(current)) {
                    inDegree[name] = (inDegree[name] ?: 1) - 1
                    if (inDegree[name] == 0) queue.add(name)
                }
            }
        }

        val remaining = originalOrder.filter { it !in sorted }
        val ordered = linkedMapOf<String, ViewDefinition>()
        for (name in sorted + remaining) {
            ordered[name] = views.getValue(name)
        }
        val notes = if (remaining.isEmpty()) emptyList() else listOf(
            TransformationNote(
                type = NoteType.WARNING,
                code = "W113",
                objectName = "views",
                message = "Views contain unresolved or circular dependencies: ${remaining.joinToString(", ")}. Original order is preserved for the remaining views.",
                hint = "Declare consistent view dependencies or adjust view definitions to break the cycle."
            )
        )
        return ViewSortResult(ordered, notes)
    }

    private fun declaredViewDependencies(
        name: String,
        view: ViewDefinition,
        viewNames: Set<String>,
    ): Set<String> = view.dependencies?.views
        ?.asSequence()
        ?.filter { it != name && it in viewNames }
        ?.toSet()
        ?: emptySet()

    private fun inferViewDependenciesFromQuery(
        name: String,
        query: String?,
        viewNames: Set<String>,
    ): Set<String> {
        if (query.isNullOrBlank()) return emptySet()

        val regex = Regex(
            """(?i)\b(?:from|join)\s+([`"]?[A-Za-z_][A-Za-z0-9_]*[`"]?(?:\.[`"]?[A-Za-z_][A-Za-z0-9_]*[`"]?)?)"""
        )
        return regex.findAll(query)
            .map { it.groupValues[1] }
            .map { ref -> ref.substringAfterLast('.') }
            .map { ref -> ref.trim('`', '"') }
            .filter { it != name && it in viewNames }
            .toSet()
    }
}
