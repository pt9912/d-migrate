package dev.dmigrate.format

import com.fasterxml.jackson.databind.JsonNode
import dev.dmigrate.core.model.*

internal fun parseProcedures(node: JsonNode?): Map<String, ProcedureDefinition> =
    parseNamedObjectMap(node) { childNode ->
        ProcedureDefinition(
            description = childNode.optionalText("description"),
            parameters = parseParameters(childNode["parameters"]),
            language = childNode.optionalText("language"),
            body = childNode.optionalText("body"),
            dependencies = parseDependencies(childNode["dependencies"]),
            sourceDialect = childNode.optionalText("source_dialect"),
        )
    }

internal fun parseFunctions(node: JsonNode?): Map<String, FunctionDefinition> =
    parseNamedObjectMap(node) { childNode ->
        FunctionDefinition(
            description = childNode.optionalText("description"),
            parameters = parseParameters(childNode["parameters"]),
            returns = parseReturnType(childNode["returns"]),
            language = childNode.optionalText("language"),
            deterministic = childNode.optionalBool("deterministic"),
            body = childNode.optionalText("body"),
            dependencies = parseDependencies(childNode["dependencies"]),
            sourceDialect = childNode.optionalText("source_dialect"),
        )
    }

private fun parseParameters(node: JsonNode?): List<ParameterDefinition> {
    if (node == null || !node.isArray) return emptyList()
    return node.map { childNode ->
        ParameterDefinition(
            name = childNode.requiredText("name"),
            type = childNode.requiredText("type"),
            direction = childNode.optionalText("direction")?.toParameterDirection() ?: ParameterDirection.IN,
        )
    }
}

private fun parseReturnType(node: JsonNode?): ReturnType? {
    if (node == null || !node.isObject) return null
    return ReturnType(
        type = node.requiredText("type"),
        precision = node.optionalInt("precision"),
        scale = node.optionalInt("scale"),
    )
}

internal fun parseViews(node: JsonNode?): Map<String, ViewDefinition> =
    parseNamedObjectMap(node) { childNode ->
        ViewDefinition(
            description = childNode.optionalText("description"),
            materialized = childNode.boolOrDefault("materialized", false),
            refresh = childNode.optionalText("refresh"),
            query = childNode.optionalText("query"),
            dependencies = parseDependencies(childNode["dependencies"]),
            sourceDialect = childNode.optionalText("source_dialect"),
        )
    }

internal fun parseTriggers(node: JsonNode?): Map<String, TriggerDefinition> =
    parseNamedObjectMap(node) { childNode ->
        TriggerDefinition(
            description = childNode.optionalText("description"),
            table = childNode.requiredText("table"),
            event = childNode.requiredText("event").toTriggerEvent(),
            timing = childNode.requiredText("timing").toTriggerTiming(),
            forEach = childNode.optionalText("for_each")?.toTriggerForEach() ?: TriggerForEach.ROW,
            condition = childNode.optionalText("condition"),
            body = childNode.optionalText("body"),
            dependencies = parseDependencies(childNode["dependencies"]),
            sourceDialect = childNode.optionalText("source_dialect"),
        )
    }

internal fun parseSequences(node: JsonNode?): Map<String, SequenceDefinition> =
    parseNamedObjectMap(node) { childNode ->
        SequenceDefinition(
            description = childNode.optionalText("description"),
            start = childNode.optionalLong("start") ?: 1,
            increment = childNode.optionalLong("increment") ?: 1,
            minValue = childNode.optionalLong("min_value"),
            maxValue = childNode.optionalLong("max_value"),
            cycle = childNode.boolOrDefault("cycle", false),
            cache = childNode.optionalInt("cache"),
        )
    }

private fun parseDependencies(node: JsonNode?): DependencyInfo? {
    if (node == null || !node.isObject) return null
    val columns = mutableMapOf<String, List<String>>()
    node["columns"]?.objectEntries()?.forEach { (tableName, columnsNode) ->
        columns[tableName] = columnsNode.toStringList()
    }
    return DependencyInfo(
        tables = node["tables"]?.toStringList() ?: emptyList(),
        views = node["views"]?.toStringList() ?: emptyList(),
        columns = columns,
        functions = node["functions"]?.toStringList() ?: emptyList(),
    )
}
