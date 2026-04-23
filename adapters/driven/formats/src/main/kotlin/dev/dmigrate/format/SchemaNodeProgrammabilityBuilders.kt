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
        node.set<ObjectNode>(name, functionNode)
    }
    return node
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
        if (definition.dependencies != null) {
            viewNode.set<ObjectNode>("dependencies", buildDependencies(mapper, definition.dependencies!!))
        }
        if (definition.sourceDialect != null) viewNode.put("source_dialect", definition.sourceDialect)
        node.set<ObjectNode>(name, viewNode)
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
        triggerNode.put("event", definition.event.name.lowercase())
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
        node.set<ObjectNode>(name, sequenceNode)
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
    return node
}
