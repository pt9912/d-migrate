package dev.dmigrate.driver.mysql

import dev.dmigrate.core.identity.ObjectKeyCodec
import dev.dmigrate.core.model.*
import dev.dmigrate.driver.*
import dev.dmigrate.driver.metadata.JdbcOperations

internal class MysqlRoutineReader {

    fun readViews(session: JdbcOperations, database: String): Map<String, ViewDefinition> {
        val rows = MysqlMetadataQueries.listViews(session, database)
        val viewTableDeps = MysqlMetadataQueries.listViewTableUsage(session, database)
        val viewFuncDeps = MysqlMetadataQueries.listViewRoutineUsage(session, database)
        val result = LinkedHashMap<String, ViewDefinition>()
        for (row in rows) {
            val viewName = row["table_name"] as String
            val tableDeps = viewTableDeps[viewName] ?: emptyList()
            val funcDeps = viewFuncDeps[viewName] ?: emptyList()
            // Phase G.2: empty VIEW_TABLE_USAGE projection for an existing
            // view means either "no table deps" (rare — most views select
            // FROM at least one table) or "user lacks SHOW VIEW privilege
            // on the referenced tables" (silent incomplete projection).
            // MySQL cannot distinguish; the planner sees the flag and
            // blocks view-replacing / column-altering ops with
            // VIEW_DEPENDENCY_PROJECTION_INCOMPLETE.
            val projectionComplete = tableDeps.isNotEmpty()
            result[viewName] = ViewDefinition(
                query = row["view_definition"] as? String,
                dependencies = DependencyInfo(
                    tables = tableDeps,
                    functions = funcDeps,
                    projectionComplete = projectionComplete,
                ),
                sourceDialect = "mysql",
            )
        }
        return result
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
