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
            security = childNode.optionalText("security")?.toRoutineSecurity(),
            definer = childNode.optionalText("definer"),
            searchPath = childNode["search_path"]?.toStringListOrNull(),
            sqlMode = childNode.optionalText("sql_mode"),
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
            security = childNode.optionalText("security")?.toRoutineSecurity(),
            definer = childNode.optionalText("definer"),
            searchPath = childNode["search_path"]?.toStringListOrNull(),
            sqlMode = childNode.optionalText("sql_mode"),
        )
    }

private fun JsonNode.toStringListOrNull(): List<String>? =
    if (isArray) map { it.asText() } else null

private fun String.toRoutineSecurity(): RoutineSecurity =
    RoutineSecurity.valueOf(uppercase())

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
            columns = parseViewColumns(childNode["columns"]),
            dependencies = parseDependencies(childNode["dependencies"]),
            sourceDialect = childNode.optionalText("source_dialect"),
        )
    }

private fun parseViewColumns(node: JsonNode?): List<ViewColumnDefinition>? {
    if (node == null || !node.isArray) return null
    return node.map { childNode ->
        if (childNode.isObject) {
            ViewColumnDefinition(
                name = childNode.requiredText("name"),
                type = childNode.optionalText("type"),
            )
        } else {
            ViewColumnDefinition(name = childNode.asText())
        }
    }
}

internal fun parseTriggers(node: JsonNode?): Map<String, TriggerDefinition> =
    parseNamedObjectMap(node) { childNode ->
        TriggerDefinition(
            description = childNode.optionalText("description"),
            table = childNode.requiredText("table"),
            events = parseTriggerEvents(childNode),
            timing = childNode.requiredText("timing").toTriggerTiming(),
            forEach = childNode.optionalText("for_each")?.toTriggerForEach() ?: TriggerForEach.ROW,
            condition = childNode.optionalText("condition"),
            body = childNode.optionalText("body"),
            dependencies = parseDependencies(childNode["dependencies"]),
            sourceDialect = childNode.optionalText("source_dialect"),
        )
    }

/**
 * F4: the `event` field is scalar-or-array. A scalar (`event: insert`) is a
 * single-event trigger; an array (`event: [insert, update]`) is a multi-event
 * trigger (PostgreSQL `INSERT OR UPDATE …`). The scalar form keeps existing
 * single-event schema files reading unchanged.
 */
private fun parseTriggerEvents(node: JsonNode): Set<TriggerEvent> {
    val eventNode = node["event"] ?: throw IllegalArgumentException("Missing required field: event")
    val events = if (eventNode.isArray) {
        eventNode.map { it.asText().toTriggerEvent() }
    } else {
        listOf(eventNode.asText().toTriggerEvent())
    }
    require(events.isNotEmpty()) { "Trigger field 'event' must list at least one event" }
    return events.toSet()
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
            preserveCurrentValue = childNode.boolOrDefault("preserve_current_value", false),
        )
    }

internal fun parseAggregates(node: JsonNode?): Map<String, AggregateDefinition> =
    parseNamedObjectMap(node) { childNode ->
        AggregateDefinition(
            inputTypes = childNode["input_types"]?.toStringList() ?: emptyList(),
            stateType = childNode.optionalText("state_type"),
            transitionFunction = childNode.optionalText("transition_function"),
            finalFunction = childNode.optionalText("final_function"),
            initialCondition = childNode.optionalText("initial_condition"),
            sortOperator = childNode.optionalText("sort_operator"),
            returnType = childNode.optionalText("return_type"),
            library = childNode.optionalText("library"),
            sourceDialect = childNode.optionalText("source_dialect"),
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
        sequences = node["sequences"]?.toStringList() ?: emptyList(),
        // Phase G.2: `projection_complete` is omitted when `true`
        // (the default) so hand-written schema files stay terse. An
        // explicit `false` survives a dump→load roundtrip and keeps
        // the planner's `VIEW_DEPENDENCY_PROJECTION_INCOMPLETE`
        // block effective.
        projectionComplete = node["projection_complete"]?.asBoolean(true) ?: true,
        tableProjectionStatus = node["table_projection_status"]?.asText()
            ?.toDependencyProjectionStatus() ?: DependencyProjectionStatus.COMPLETE,
        columnProjectionStatus = node["column_projection_status"]?.asText()
            ?.toDependencyProjectionStatus() ?: DependencyProjectionStatus.COMPLETE,
        routineProjectionStatus = node["routine_projection_status"]?.asText()
            ?.toDependencyProjectionStatus() ?: DependencyProjectionStatus.COMPLETE,
        projectionSources = node["projection_sources"]?.toStringList() ?: emptyList(),
        projectionErrorClass = node.optionalText("projection_error_class"),
    )
}

private fun String.toDependencyProjectionStatus(): DependencyProjectionStatus =
    DependencyProjectionStatus.valueOf(uppercase())
