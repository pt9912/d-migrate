package dev.dmigrate.format

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import dev.dmigrate.core.model.*

internal fun buildProcedures(
    mapper: ObjectMapper,
    procedures: Map<String, ProcedureDefinition>,
): ObjectNode {
    val node = mapper.createObjectNode()
    for ((name, definition) in procedures.entries.sortedBy { it.key }) {
        val procedureNode = mapper.createObjectNode()
        if (definition.description != null) procedureNode.put("description", definition.description)
        if (definition.parameters.isNotEmpty()) {
            procedureNode.set<ArrayNode>("parameters", buildParameters(mapper, definition.parameters))
        }
        if (definition.language != null) procedureNode.put("language", definition.language)
        if (definition.body != null) procedureNode.put("body", definition.body)
        if (definition.dependencies != null) {
            procedureNode.set<ObjectNode>("dependencies", buildDependencies(mapper, definition.dependencies!!))
        }
        if (definition.sourceDialect != null) procedureNode.put("source_dialect", definition.sourceDialect)
        writeRoutineIdentityAttributes(mapper, procedureNode, definition.security, definition.definer,
            definition.searchPath, definition.sqlMode)
        node.set<ObjectNode>(name, procedureNode)
    }
    return node
}

internal fun buildFunctions(
    mapper: ObjectMapper,
    functions: Map<String, FunctionDefinition>,
): ObjectNode {
    val node = mapper.createObjectNode()
    for ((name, definition) in functions.entries.sortedBy { it.key }) {
        val functionNode = mapper.createObjectNode()
        if (definition.description != null) functionNode.put("description", definition.description)
        if (definition.parameters.isNotEmpty()) {
            functionNode.set<ArrayNode>("parameters", buildParameters(mapper, definition.parameters))
        }
        if (definition.returns != null) {
            functionNode.set<ObjectNode>("returns", buildReturnType(mapper, definition.returns!!))
        }
        if (definition.language != null) functionNode.put("language", definition.language)
        if (definition.deterministic != null) functionNode.put("deterministic", definition.deterministic!!)
        if (definition.body != null) functionNode.put("body", definition.body)
        if (definition.dependencies != null) {
            functionNode.set<ObjectNode>("dependencies", buildDependencies(mapper, definition.dependencies!!))
        }
        if (definition.sourceDialect != null) functionNode.put("source_dialect", definition.sourceDialect)
        writeRoutineIdentityAttributes(mapper, functionNode, definition.security, definition.definer,
            definition.searchPath, definition.sqlMode)
        node.set<ObjectNode>(name, functionNode)
    }
    return node
}

/**
 * E.1 Routine-Migration Slice A: persist the new routine identity
 * attributes only when set, so existing schema files without these
 * keys keep their byte-for-byte shape after a roundtrip.
 */
private fun writeRoutineIdentityAttributes(
    mapper: ObjectMapper,
    target: ObjectNode,
    security: RoutineSecurity?,
    definer: String?,
    searchPath: List<String>?,
    sqlMode: String?,
) {
    if (security != null) target.put("security", security.name.lowercase())
    if (definer != null) target.put("definer", definer)
    if (searchPath != null) target.set<ArrayNode>("search_path", stringArray(mapper, searchPath))
    if (sqlMode != null) target.put("sql_mode", sqlMode)
}

private fun buildParameters(
    mapper: ObjectMapper,
    parameters: List<ParameterDefinition>,
): ArrayNode {
    val arrayNode = mapper.createArrayNode()
    for (parameter in parameters) {
        val parameterNode = mapper.createObjectNode()
        parameterNode.put("name", parameter.name)
        parameterNode.put("type", parameter.type)
        if (parameter.direction != ParameterDirection.IN) {
            parameterNode.put("direction", parameter.direction.name.lowercase())
        }
        arrayNode.add(parameterNode)
    }
    return arrayNode
}

private fun buildReturnType(mapper: ObjectMapper, returnType: ReturnType): ObjectNode {
    val node = mapper.createObjectNode()
    node.put("type", returnType.type)
    if (returnType.precision != null) node.put("precision", returnType.precision!!)
    if (returnType.scale != null) node.put("scale", returnType.scale!!)
    return node
}

internal fun buildViews(
    mapper: ObjectMapper,
    views: Map<String, ViewDefinition>,
): ObjectNode {
    val node = mapper.createObjectNode()
    for ((name, definition) in views.entries.sortedBy { it.key }) {
        val viewNode = mapper.createObjectNode()
        if (definition.description != null) viewNode.put("description", definition.description)
        if (definition.materialized) viewNode.put("materialized", true)
        if (definition.refresh != null) viewNode.put("refresh", definition.refresh)
        if (definition.query != null) viewNode.put("query", definition.query)
        val columns = definition.columns
        if (columns != null) {
            viewNode.set<ArrayNode>("columns", buildViewColumns(mapper, columns))
        }
        if (definition.dependencies != null) {
            viewNode.set<ObjectNode>("dependencies", buildDependencies(mapper, definition.dependencies!!))
        }
        if (definition.sourceDialect != null) viewNode.put("source_dialect", definition.sourceDialect)
        node.set<ObjectNode>(name, viewNode)
    }
    return node
}

private fun buildViewColumns(mapper: ObjectMapper, columns: List<ViewColumnDefinition>): ArrayNode {
    val node = mapper.createArrayNode()
    for (column in columns) {
        val columnNode = mapper.createObjectNode()
        columnNode.put("name", column.name)
        if (column.type != null) columnNode.put("type", column.type)
        node.add(columnNode)
    }
    return node
}

internal fun buildTriggers(
    mapper: ObjectMapper,
    triggers: Map<String, TriggerDefinition>,
): ObjectNode {
    val node = mapper.createObjectNode()
    for ((name, definition) in triggers.entries.sortedBy { it.key }) {
        val triggerNode = mapper.createObjectNode()
        if (definition.description != null) triggerNode.put("description", definition.description)
        triggerNode.put("table", definition.table)
        // F4: a single-event trigger keeps the scalar `event:` form (the
        // dominant case, byte-identical to pre-F4 output); a multi-event
        // trigger (PostgreSQL `INSERT OR UPDATE …`) serialises as a
        // canonical-order list under the same `event` key.
        val canonicalEvents = definition.events.canonicalOrder()
        if (canonicalEvents.size == 1) {
            triggerNode.put("event", canonicalEvents.single().name.lowercase())
        } else {
            val events = mapper.createArrayNode()
            canonicalEvents.forEach { events.add(it.name.lowercase()) }
            triggerNode.set<ArrayNode>("event", events)
        }
        triggerNode.put("timing", definition.timing.name.lowercase())
        if (definition.forEach != TriggerForEach.ROW) {
            triggerNode.put("for_each", definition.forEach.name.lowercase())
        }
        if (definition.condition != null) triggerNode.put("condition", definition.condition)
        if (definition.body != null) triggerNode.put("body", definition.body)
        if (definition.dependencies != null) {
            triggerNode.set<ObjectNode>("dependencies", buildDependencies(mapper, definition.dependencies!!))
        }
        if (definition.sourceDialect != null) triggerNode.put("source_dialect", definition.sourceDialect)
        node.set<ObjectNode>(name, triggerNode)
    }
    return node
}

internal fun buildSequences(
    mapper: ObjectMapper,
    sequences: Map<String, SequenceDefinition>,
): ObjectNode {
    val node = mapper.createObjectNode()
    for ((name, definition) in sequences.entries.sortedBy { it.key }) {
        val sequenceNode = mapper.createObjectNode()
        if (definition.description != null) sequenceNode.put("description", definition.description)
        if (definition.start != 1L) sequenceNode.put("start", definition.start)
        if (definition.increment != 1L) sequenceNode.put("increment", definition.increment)
        if (definition.minValue != null) sequenceNode.put("min_value", definition.minValue!!)
        if (definition.maxValue != null) sequenceNode.put("max_value", definition.maxValue!!)
        if (definition.cycle) sequenceNode.put("cycle", true)
        if (definition.cache != null) sequenceNode.put("cache", definition.cache!!)
        if (definition.preserveCurrentValue) sequenceNode.put("preserve_current_value", true)
        node.set<ObjectNode>(name, sequenceNode)
    }
    return node
}

internal fun buildAggregates(
    mapper: ObjectMapper,
    aggregates: Map<String, AggregateDefinition>,
): ObjectNode {
    val node = mapper.createObjectNode()
    for ((name, definition) in aggregates.entries.sortedBy { it.key }) {
        val aggregateNode = mapper.createObjectNode()
        if (definition.inputTypes.isNotEmpty()) {
            val types = mapper.createArrayNode()
            definition.inputTypes.forEach { types.add(it) }
            aggregateNode.set<ArrayNode>("input_types", types)
        }
        if (definition.stateType != null) aggregateNode.put("state_type", definition.stateType)
        if (definition.transitionFunction != null) aggregateNode.put("transition_function", definition.transitionFunction)
        if (definition.finalFunction != null) aggregateNode.put("final_function", definition.finalFunction)
        if (definition.initialCondition != null) aggregateNode.put("initial_condition", definition.initialCondition)
        if (definition.sortOperator != null) aggregateNode.put("sort_operator", definition.sortOperator)
        if (definition.returnType != null) aggregateNode.put("return_type", definition.returnType)
        if (definition.library != null) aggregateNode.put("library", definition.library)
        if (definition.sourceDialect != null) aggregateNode.put("source_dialect", definition.sourceDialect)
        node.set<ObjectNode>(name, aggregateNode)
    }
    return node
}

private fun buildDependencies(
    mapper: ObjectMapper,
    dependencies: DependencyInfo,
): ObjectNode {
    val node = mapper.createObjectNode()
    if (dependencies.tables.isNotEmpty()) {
        node.set<ArrayNode>("tables", stringArray(mapper, dependencies.tables))
    }
    if (dependencies.views.isNotEmpty()) {
        node.set<ArrayNode>("views", stringArray(mapper, dependencies.views))
    }
    if (dependencies.columns.isNotEmpty()) {
        val columnsNode = mapper.createObjectNode()
        for ((tableName, columns) in dependencies.columns.entries.sortedBy { it.key }) {
            columnsNode.set<ArrayNode>(tableName, stringArray(mapper, columns))
        }
        node.set<ObjectNode>("columns", columnsNode)
    }
    if (dependencies.functions.isNotEmpty()) {
        node.set<ArrayNode>("functions", stringArray(mapper, dependencies.functions))
    }
    // E.1 Slice D.1: `sequences` is only emitted when non-empty so
    // Slice-A/B-era schema files (which never declared the field)
    // keep their byte-identical YAML roundtrip.
    if (dependencies.sequences.isNotEmpty()) {
        node.set<ArrayNode>("sequences", stringArray(mapper, dependencies.sequences))
    }
    // Phase G.2: only serialise `projection_complete` when it diverges
    // from the default `true`. MySQL-introspected views with stille
    // Unvollstaendigkeit (empty VIEW_TABLE_USAGE) get `false` written,
    // so a downstream `schema load` + `schema diff` re-honours the
    // planner's VIEW_DEPENDENCY_PROJECTION_INCOMPLETE block.
    if (!dependencies.projectionComplete) {
        node.put("projection_complete", false)
    }
    if (dependencies.tableProjectionStatus != DependencyProjectionStatus.COMPLETE) {
        node.put("table_projection_status", dependencies.tableProjectionStatus.name.lowercase())
    }
    if (dependencies.columnProjectionStatus != DependencyProjectionStatus.COMPLETE) {
        node.put("column_projection_status", dependencies.columnProjectionStatus.name.lowercase())
    }
    if (dependencies.routineProjectionStatus != DependencyProjectionStatus.COMPLETE) {
        node.put("routine_projection_status", dependencies.routineProjectionStatus.name.lowercase())
    }
    if (dependencies.projectionSources.isNotEmpty()) {
        node.set<ArrayNode>("projection_sources", stringArray(mapper, dependencies.projectionSources))
    }
    if (dependencies.projectionErrorClass != null) {
        node.put("projection_error_class", dependencies.projectionErrorClass)
    }
    return node
}
