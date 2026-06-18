package dev.dmigrate.driver.postgresql

import dev.dmigrate.core.identity.ObjectKeyCodec
import dev.dmigrate.core.model.*
import dev.dmigrate.driver.metadata.JdbcOperations

internal fun readPostgresViews(
    session: JdbcOperations,
    schema: String,
): Map<String, ViewDefinition> {
    val rows = PostgresMetadataQueries.listViews(session, schema)
    val viewRelationDependencies = PostgresMetadataQueries.listViewRelationDependencies(session, schema)
    val viewFunctionDependencies = PostgresMetadataQueries.listViewFunctionDependencies(session, schema)
    val viewColumns = PostgresMetadataQueries.listViewColumns(session, schema)
    val result = LinkedHashMap<String, ViewDefinition>()
    for (row in rows) {
        val viewName = row["table_name"] as String
        val relationDependencies = viewRelationDependencies[viewName]
        val functionDependencies = viewFunctionDependencies[viewName] ?: emptyList()
        result[viewName] = ViewDefinition(
            query = row["view_definition"] as? String,
            materialized = (row["is_materialized"] as? Boolean) == true,
            columns = viewColumns[viewName]?.map { ViewColumnDefinition(name = it.name, type = it.type) },
            dependencies = if (
                relationDependencies != null ||
                functionDependencies.isNotEmpty()
            ) {
                DependencyInfo(
                    tables = relationDependencies?.tables ?: emptyList(),
                    views = relationDependencies?.views ?: emptyList(),
                    columns = relationDependencies?.columns ?: emptyMap(),
                    functions = functionDependencies,
                    projectionComplete = true,
                )
            } else {
                null
            },
            sourceDialect = "postgresql",
        )
    }
    return result
}

// E.1 Routine-Migration Slice E closed the Slice-A reverse-read
// carve-out: `security` / `definer` / `searchPath` are now sourced
// from `pg_proc.prosecdef`, `pg_proc.proowner` (joined via
// `pg_roles`), and `pg_proc.proconfig`. `sqlMode` is MySQL-only;
// the PG reader continues to leave it null. The carve-out's
// fingerprint caveat (file with explicit identity attrs differs
// from reverse-read schema with missing attrs) is now resolved
// when the underlying pg_proc projection is complete.

internal fun readPostgresFunctions(
    session: JdbcOperations,
    schema: String,
): Map<String, FunctionDefinition> {
    val rows = PostgresMetadataQueries.listFunctions(session, schema)
    val relationDeps = PostgresProgrammabilityMetadataQueries
        .listRoutineRelationDependencies(session, schema)
    val identityAttrs = PostgresProgrammabilityMetadataQueries
        .listRoutineIdentityAttributes(session, schema)
    val result = LinkedHashMap<String, FunctionDefinition>()
    for (row in rows) {
        val name = row["routine_name"] as String
        val specificName = row["specific_name"] as String
        val parameterDefinitions = readPostgresRoutineParameters(session, schema, specificName)
        val key = ObjectKeyCodec.routineKey(name, parameterDefinitions)
        val returnType = (row["data_type"] as? String)?.takeIf { it != "void" }?.let {
            ReturnType(type = PostgresTypeMapping.mapParamType(row["type_udt_name"] as? String ?: it))
        }
        val identity = routineIdentity(identityAttrs, name, specificName)
        result[key] = FunctionDefinition(
            parameters = parameterDefinitions,
            returns = returnType,
            language = row["external_language"] as? String,
            body = row["routine_definition"] as? String,
            deterministic = (row["is_deterministic"] as? String) == "YES",
            dependencies = routineDependencyInfo(relationDeps, name, specificName),
            sourceDialect = "postgresql",
            // Slice E: identity attrs from pg_proc.
            security = identity?.security,
            definer = identity?.definer,
            searchPath = identity?.searchPath,
            sqlMode = null,
        )
    }
    return result
}

internal fun readPostgresAggregates(
    session: JdbcOperations,
    schema: String,
): Map<String, AggregateDefinition> {
    val rows = PostgresMetadataQueries.listAggregates(session, schema)
    val result = LinkedHashMap<String, AggregateDefinition>()
    for (row in rows) {
        val name = row["name"] as String
        val inputTypes = (row["input_args"] as? String)
            .orEmpty()
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        // Keyed by bare name (overloaded aggregates are rare); the generator
        // re-derives the emitted name via ObjectKeyCodec.routineName.
        result[name] = AggregateDefinition(
            inputTypes = inputTypes,
            stateType = (row["state_type"] as? String).orEmpty(),
            transitionFunction = (row["transition_function"] as? String).orEmpty(),
            finalFunction = row["final_function"] as? String,
            initialCondition = row["initial_condition"] as? String,
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
    val relationDeps = PostgresProgrammabilityMetadataQueries
        .listRoutineRelationDependencies(session, schema)
    val identityAttrs = PostgresProgrammabilityMetadataQueries
        .listRoutineIdentityAttributes(session, schema)
    val result = LinkedHashMap<String, ProcedureDefinition>()
    for (row in rows) {
        val name = row["routine_name"] as String
        val specificName = row["specific_name"] as String
        val parameterDefinitions = readPostgresRoutineParameters(session, schema, specificName)
        val key = ObjectKeyCodec.routineKey(name, parameterDefinitions)
        val identity = routineIdentity(identityAttrs, name, specificName)
        result[key] = ProcedureDefinition(
            parameters = parameterDefinitions,
            language = row["external_language"] as? String,
            body = row["routine_definition"] as? String,
            dependencies = routineDependencyInfo(relationDeps, name, specificName),
            sourceDialect = "postgresql",
            // Slice E: identity attrs from pg_proc.
            security = identity?.security,
            definer = identity?.definer,
            searchPath = identity?.searchPath,
            sqlMode = null,
        )
    }
    return result
}

/**
 * E.1 Routine-Migration Slice E: look up routine identity
 * attributes by overload-specific key, mirroring the Slice D.2
 * dependency lookup. Returns null when the projection has no
 * matching row.
 */
private data class ResolvedRoutineIdentity(
    val security: RoutineSecurity?,
    val definer: String?,
    val searchPath: List<String>?,
)

private fun routineIdentity(
    projection: Map<RoutineKey, RoutineIdentityAttributes>,
    name: String,
    specificName: String,
): ResolvedRoutineIdentity? {
    val oid = specificName.substringAfterLast('_').toLongOrNull()
    val attrs = if (oid != null) {
        projection[RoutineKey(name = name, oid = oid)]
    } else {
        projection.entries.firstOrNull { it.key.name == name }?.value
    } ?: return null
    return ResolvedRoutineIdentity(
        security = if (attrs.securityDefiner) RoutineSecurity.DEFINER else RoutineSecurity.INVOKER,
        definer = attrs.definer.takeIf { attrs.securityDefiner },
        searchPath = attrs.searchPath,
    )
}

/**
 * E.1 Routine-Migration Slice D.2 follow-up: look up routine
 * dependencies by overload-specific key. PostgreSQL's
 * `information_schema.routines.specific_name` is conventionally
 * `<proname>_<oid>`; we extract the trailing OID to disambiguate
 * same-name overloads. Falls back to a name-only match when the
 * OID suffix is missing (defensive — `pg_get_function_identity_arguments`
 * could replace this in a future cleanup slice).
 */
private fun routineDependencyInfo(
    projection: Map<RoutineKey, RoutineRelationDependencies>,
    name: String,
    specificName: String,
): DependencyInfo? {
    val oid = specificName.substringAfterLast('_').toLongOrNull()
    val deps = if (oid != null) {
        projection[RoutineKey(name = name, oid = oid)]
    } else {
        // Name-only fallback when the specific_name format is
        // unrecognised. With overloads this can return wrong
        // edges, but the only realistic trigger is a non-PG
        // dialect masquerading through this reader.
        projection.entries.firstOrNull { it.key.name == name }?.value
    }
    if (deps == null) return null
    if (deps.tables.isEmpty() && deps.views.isEmpty() && deps.sequences.isEmpty()) return null
    return DependencyInfo(
        tables = deps.tables,
        views = deps.views,
        sequences = deps.sequences,
    )
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
    val triggerFunctions = PostgresProgrammabilityMetadataQueries
        .listTriggerFunctionDependencies(session, schema)
    val result = LinkedHashMap<String, TriggerDefinition>()
    for (row in rows) {
        val name = row["trigger_name"] as String
        val table = row["event_object_table"] as String
        val key = ObjectKeyCodec.triggerKey(table, name)
        val functionDeps = triggerFunctions[TriggerKey(table = table, name = name)].orEmpty()
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
            // E.1 Slice D.2: pg_trigger.tgfoid → pg_proc.oid edge
            // lands in DependencyInfo.functions so the second-phase
            // RoutineDependencyAnalyzer can chain DropTrigger →
            // DropFunction correctly.
            dependencies = if (functionDeps.isEmpty()) null
                else DependencyInfo(functions = functionDeps),
            sourceDialect = "postgresql",
        )
    }
    return result
}
