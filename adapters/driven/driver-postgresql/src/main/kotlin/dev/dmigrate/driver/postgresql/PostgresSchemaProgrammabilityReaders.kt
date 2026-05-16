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

// E.1 Routine-Migration Slice A carve-out:
// `security` / `definer` / `searchPath` / `sqlMode` are NOT yet
// populated from the live database. Sourcing them requires reading
// `pg_proc.prosecdef`, `pg_proc.proowner` (joined to `pg_authid`)
// and `pg_proc.proconfig` (joined to `pg_db_role_setting`) which is
// non-trivial because of permission scoping and out-of-information-
// schema queries. Until Slice E lands the reverse path leaves the
// fields null, with two consequences:
//   1. File-to-DB diffs against a schema file that declares
//      `security: definer` always emit `ReplaceFunction`; this is
//      logged as the documented caveat in the slice plan §3.
//   2. The fingerprint computed from a reverse-read schema differs
//      from the fingerprint computed from the same schema authored
//      in a file with explicit identity attributes — by design,
//      since the file is more specific.
// A later slice will widen `listFunctions`/`listProcedures` to
// project these attributes.

internal fun readPostgresFunctions(
    session: JdbcOperations,
    schema: String,
): Map<String, FunctionDefinition> {
    val rows = PostgresMetadataQueries.listFunctions(session, schema)
    val relationDeps = PostgresProgrammabilityMetadataQueries
        .listRoutineRelationDependencies(session, schema)
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
            dependencies = routineDependencyInfo(relationDeps, name, specificName),
            sourceDialect = "postgresql",
            // Reverse-read carve-out (see file-level comment).
            security = null,
            definer = null,
            searchPath = null,
            sqlMode = null,
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
            dependencies = routineDependencyInfo(relationDeps, name, specificName),
            sourceDialect = "postgresql",
            // Reverse-read carve-out (see file-level comment).
            security = null,
            definer = null,
            searchPath = null,
            sqlMode = null,
        )
    }
    return result
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
