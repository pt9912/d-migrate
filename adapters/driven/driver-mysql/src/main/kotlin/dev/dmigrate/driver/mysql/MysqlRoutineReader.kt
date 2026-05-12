package dev.dmigrate.driver.mysql

import dev.dmigrate.core.identity.ObjectKeyCodec
import dev.dmigrate.core.model.*
import dev.dmigrate.driver.*
import dev.dmigrate.driver.metadata.JdbcOperations

internal class MysqlRoutineReader {

    fun readFunctionNames(session: JdbcOperations, database: String): Set<String> =
        MysqlMetadataQueries.listFunctions(session, database)
            .mapNotNull { it["routine_name"] as? String }
            .toSet()

    fun readViews(
        session: JdbcOperations,
        database: String,
        visibleFunctionNames: Set<String> = emptySet(),
    ): Map<String, ViewDefinition> {
        val rows = MysqlMetadataQueries.listViews(session, database)
        val viewTableDeps = MysqlMetadataQueries.listViewTableUsage(session, database)
        val viewFuncDeps = MysqlMetadataQueries.listViewRoutineUsage(session, database)
        val result = LinkedHashMap<String, ViewDefinition>()
        for (row in rows) {
            val viewName = row["table_name"] as String
            val viewDefinition = row["view_definition"] as? String
            val tableDeps = viewTableDeps[viewName] ?: emptyList()
            val funcDeps = viewFuncDeps[viewName] ?: emptyList()
            // Phase G.2: empty VIEW_TABLE_USAGE projection for an existing
            // view typically means the introspecting user lacks SHOW VIEW
            // privilege on the referenced tables (silent incomplete
            // projection). But constant-only views (`SELECT 1 AS x`,
            // `SELECT NOW()`) genuinely have no table deps and would be
            // false-positive flagged as incomplete. A best-effort body
            // probe (`FROM`/`JOIN` token search in VIEW_DEFINITION)
            // discriminates the two cases. The probe is best-effort: a
            // string literal `SELECT 'FROM is a keyword'` produces a
            // false-positive incomplete flag, but conservative-on-failure
            // is the right default (operator sees a clear BLOCKER instead
            // of a silently-broken view).
            val tableProjectionStatus = when {
                tableDeps.isNotEmpty() -> DependencyProjectionStatus.COMPLETE
                viewBodyReferencesTables(viewDefinition) -> DependencyProjectionStatus.INCOMPLETE_PRIVILEGE
                else -> DependencyProjectionStatus.EMPTY_VERIFIED
            }
            val routineProjectionComplete =
                funcDeps.isNotEmpty() || !viewBodyReferencesVisibleRoutines(viewDefinition, visibleFunctionNames)
            val routineProjectionStatus = when {
                funcDeps.isNotEmpty() -> DependencyProjectionStatus.COMPLETE
                routineProjectionComplete -> DependencyProjectionStatus.EMPTY_VERIFIED
                else -> DependencyProjectionStatus.INCOMPLETE_PRIVILEGE
            }
            val columnProjectionStatus = when (tableProjectionStatus) {
                DependencyProjectionStatus.EMPTY_VERIFIED -> DependencyProjectionStatus.EMPTY_VERIFIED
                else -> DependencyProjectionStatus.UNKNOWN
            }
            result[viewName] = ViewDefinition(
                query = viewDefinition,
                dependencies = DependencyInfo(
                    tables = tableDeps,
                    functions = funcDeps,
                    projectionComplete = tableProjectionStatus.isUsable() && routineProjectionStatus.isUsable(),
                    tableProjectionStatus = tableProjectionStatus,
                    columnProjectionStatus = columnProjectionStatus,
                    routineProjectionStatus = routineProjectionStatus,
                    projectionSources = listOf(
                        "INFORMATION_SCHEMA.VIEW_TABLE_USAGE",
                        "INFORMATION_SCHEMA.VIEW_ROUTINE_USAGE",
                    ),
                ),
                sourceDialect = "mysql",
            )
        }
        return result
    }

    /**
     * Best-effort regex probe: returns `true` when the SQL body looks
     * like it references at least one table via `FROM <name>` or
     * `JOIN <name>` (case-insensitive). Used to discriminate
     * constant-only views from views with silently-incomplete
     * VIEW_TABLE_USAGE projections.
     *
     * False-positives possible:
     * - String literals containing FROM/JOIN words
     * - Comments containing FROM/JOIN words
     * Both cases default to "references tables" → projectionComplete=false,
     * which is the safer planning posture.
     */
    private fun viewBodyReferencesTables(body: String?): Boolean {
        if (body.isNullOrBlank()) return true // unknown body → assume tables (safe)
        return TABLE_REFERENCE_PATTERN.containsMatchIn(body)
    }

    private fun viewBodyReferencesVisibleRoutines(body: String?, visibleFunctionNames: Set<String>): Boolean {
        if (body.isNullOrBlank() || visibleFunctionNames.isEmpty()) return false
        val normalizedFunctions = visibleFunctionNames.map { normalizeRoutineName(it) }.toSet()
        return FUNCTION_CALL_PATTERN.findAll(body)
            .map { normalizeRoutineName(it.groupValues[1]) }
            .any { it in normalizedFunctions }
    }

    private fun normalizeRoutineName(name: String): String =
        name.substringAfterLast('.').trim('`').lowercase()

    private companion object {
        private val TABLE_REFERENCE_PATTERN = Regex("""\b(?:FROM|JOIN)\s+\w""", RegexOption.IGNORE_CASE)
        private val FUNCTION_CALL_PATTERN = Regex("""(?i)\b([`A-Za-z_][`A-Za-z0-9_$.]*)\s*\(""")
    }

    fun readFunctions(
        session: JdbcOperations,
        database: String,
    ): Map<String, FunctionDefinition> {
        val rows = MysqlMetadataQueries.listFunctions(session, database)
        val result = LinkedHashMap<String, FunctionDefinition>()
        for (row in rows) {
            val name = row["routine_name"] as String
            val params = MysqlMetadataQueries.listRoutineParameters(session, database, name, "FUNCTION")
            val paramDefs = params.map { p ->
                ParameterDefinition(
                    name = (p["parameter_name"] as? String) ?: "p${p["ordinal_position"]}",
                    type = MysqlTypeMapping.mapParamType(p["data_type"] as? String ?: "text"),
                    direction = when ((p["parameter_mode"] as? String)?.uppercase()) {
                        "OUT" -> ParameterDirection.OUT
                        "INOUT" -> ParameterDirection.INOUT
                        else -> ParameterDirection.IN
                    },
                )
            }
            val key = ObjectKeyCodec.routineKey(name, paramDefs)
            result[key] = FunctionDefinition(
                parameters = paramDefs,
                returns = (row["dtd_identifier"] as? String)?.let { ReturnType(type = MysqlTypeMapping.mapParamType(it)) },
                language = row["routine_body"] as? String,
                body = row["routine_definition"] as? String,
                deterministic = (row["is_deterministic"] as? String) == "YES",
                sourceDialect = "mysql",
            )
        }
        return result
    }

    fun readProcedures(
        session: JdbcOperations,
        database: String,
    ): Map<String, ProcedureDefinition> {
        val rows = MysqlMetadataQueries.listProcedures(session, database)
        val result = LinkedHashMap<String, ProcedureDefinition>()
        for (row in rows) {
            val name = row["routine_name"] as String
            val params = MysqlMetadataQueries.listRoutineParameters(session, database, name, "PROCEDURE")
            val paramDefs = params.map { p ->
                ParameterDefinition(
                    name = (p["parameter_name"] as? String) ?: "p${p["ordinal_position"]}",
                    type = MysqlTypeMapping.mapParamType(p["data_type"] as? String ?: "text"),
                    direction = when ((p["parameter_mode"] as? String)?.uppercase()) {
                        "OUT" -> ParameterDirection.OUT
                        "INOUT" -> ParameterDirection.INOUT
                        else -> ParameterDirection.IN
                    },
                )
            }
            val key = ObjectKeyCodec.routineKey(name, paramDefs)
            result[key] = ProcedureDefinition(
                parameters = paramDefs,
                language = row["routine_body"] as? String,
                body = row["routine_definition"] as? String,
                sourceDialect = "mysql",
            )
        }
        return result
    }

    fun readTriggers(session: JdbcOperations, database: String): Map<String, TriggerDefinition> {
        val rows = MysqlMetadataQueries.listTriggers(session, database)
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
                    else -> TriggerTiming.BEFORE
                },
                forEach = when ((row["action_orientation"] as? String)?.uppercase()) {
                    "STATEMENT" -> TriggerForEach.STATEMENT
                    else -> TriggerForEach.ROW
                },
                body = row["action_statement"] as? String,
                sourceDialect = "mysql",
            )
        }
        return result
    }
}
