package dev.dmigrate.driver.postgresql

import dev.dmigrate.core.identity.ObjectKeyCodec
import dev.dmigrate.core.model.*
import dev.dmigrate.driver.metadata.JdbcOperations

internal fun readPostgresViews(
    session: JdbcOperations,
    schema: String,
): Map<String, ViewDefinition> {
    val rows = PostgresMetadataQueries.listViews(session, schema)
    val viewFunctionDependencies = PostgresMetadataQueries.listViewFunctionDependencies(session, schema)
    val result = LinkedHashMap<String, ViewDefinition>()
    for (row in rows) {
        val viewName = row["table_name"] as String
        val functionDependencies = viewFunctionDependencies[viewName] ?: emptyList()
        result[viewName] = ViewDefinition(
            query = row["view_definition"] as? String,
            dependencies = if (functionDependencies.isNotEmpty()) {
                DependencyInfo(functions = functionDependencies)
            } else {
                null
            },
            sourceDialect = "postgresql",
        )
    }
    return result
}

internal fun readPostgresFunctions(
    session: JdbcOperations,
    schema: String,
): Map<String, FunctionDefinition> {
    val rows = PostgresMetadataQueries.listFunctions(session, schema)
    val result = LinkedHashMap<String, FunctionDefinition>()
    for (row in rows) {
        val name = row["routine_name"] as String
        val specificName = row["specific_name"] as String
        val parameterDefinitions = readPostgresRoutineParameters(session, schema, specificName)
        val key = ObjectKeyCodec.routineKey(name, parameterDefinitions)
        val returnType = (row["data_type"] as? String)?.takeIf { it != "void" }?.let {
            ReturnType(type = PostgresTypeMapping.mapParamType(row["type_udt_name"] as? String ?: it))
        }
        result[key] = FunctionDefinition(
            parameters = parameterDefinitions,
            returns = returnType,
            language = row["external_language"] as? String,
            body = row["routine_definition"] as? String,
            deterministic = (row["is_deterministic"] as? String) == "YES",
            sourceDialect = "postgresql",
        )
    }
    return result
}

internal fun readPostgresProcedures(
    session: JdbcOperations,
    schema: String,
): Map<String, ProcedureDefinition> {
    val rows = PostgresMetadataQueries.listProcedures(session, schema)
    val result = LinkedHashMap<String, ProcedureDefinition>()
    for (row in rows) {
        val name = row["routine_name"] as String
        val specificName = row["specific_name"] as String
        val parameterDefinitions = readPostgresRoutineParameters(session, schema, specificName)
        val key = ObjectKeyCodec.routineKey(name, parameterDefinitions)
        result[key] = ProcedureDefinition(
            parameters = parameterDefinitions,
            language = row["external_language"] as? String,
            body = row["routine_definition"] as? String,
            sourceDialect = "postgresql",
        )
    }
    return result
}

private fun readPostgresRoutineParameters(
    session: JdbcOperations,
    schema: String,
    specificName: String,
): List<ParameterDefinition> =
    PostgresMetadataQueries.listRoutineParameters(session, schema, specificName).map { parameter ->
        ParameterDefinition(
            name = (parameter["parameter_name"] as? String) ?: "p${parameter["ordinal_position"]}",
            type = PostgresTypeMapping.mapParamType(
                parameter["udt_name"] as? String ?: parameter["data_type"] as? String ?: "text"
            ),
            direction = when ((parameter["parameter_mode"] as? String)?.uppercase()) {
                "OUT" -> ParameterDirection.OUT
                "INOUT" -> ParameterDirection.INOUT
                else -> ParameterDirection.IN
            },
        )
    }

internal fun readPostgresTriggers(
    session: JdbcOperations,
    schema: String,
): Map<String, TriggerDefinition> {
    val rows = PostgresMetadataQueries.listTriggers(session, schema)
    val result = LinkedHashMap<String, TriggerDefinition>()
    for (row in rows) {
        val name = row["trigger_name"] as String
        val table = row["event_object_table"] as String
        val key = ObjectKeyCodec.triggerKey(table, name)
        result[key] = TriggerDefinition(
            table = table,
            event = when ((row["event_manipulation"] as String).uppercase()) {
                "INSERT" -> TriggerEvent.INSERT
                "UPDATE" -> TriggerEvent.UPDATE
                "DELETE" -> TriggerEvent.DELETE
                else -> TriggerEvent.INSERT
            },
            timing = when ((row["action_timing"] as String).uppercase()) {
                "BEFORE" -> TriggerTiming.BEFORE
                "AFTER" -> TriggerTiming.AFTER
                "INSTEAD OF" -> TriggerTiming.INSTEAD_OF
                else -> TriggerTiming.BEFORE
            },
            forEach = when ((row["action_orientation"] as? String)?.uppercase()) {
                "STATEMENT" -> TriggerForEach.STATEMENT
                else -> TriggerForEach.ROW
            },
            condition = row["action_condition"] as? String,
            body = row["action_statement"] as? String,
            sourceDialect = "postgresql",
        )
    }
    return result
}
