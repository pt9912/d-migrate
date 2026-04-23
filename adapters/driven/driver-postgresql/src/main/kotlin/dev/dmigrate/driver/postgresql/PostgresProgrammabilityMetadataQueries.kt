package dev.dmigrate.driver.postgresql

import dev.dmigrate.driver.metadata.JdbcOperations

internal object PostgresProgrammabilityMetadataQueries {

    fun listViews(session: JdbcOperations, schemaName: String): List<Map<String, Any?>> {
        return session.queryList(
            """
            SELECT table_name, view_definition
            FROM information_schema.views
            WHERE table_schema = ?
            ORDER BY table_name
            """.trimIndent(), schemaName,
        )
    }

    fun listViewFunctionDependencies(session: JdbcOperations, schemaName: String): Map<String, List<String>> {
        val rows = session.queryList(
            """
            SELECT DISTINCT v.relname AS view_name, p.proname AS function_name
            FROM pg_depend d
            JOIN pg_rewrite rw ON rw.oid = d.objid
            JOIN pg_class v ON v.oid = rw.ev_class AND v.relkind IN ('v', 'm')
            JOIN pg_namespace n ON n.oid = v.relnamespace AND n.nspname = ?
            JOIN pg_proc p ON p.oid = d.refobjid
            JOIN pg_namespace fn ON fn.oid = p.pronamespace AND fn.nspname = ?
            WHERE d.classid = 'pg_rewrite'::regclass
              AND d.refclassid = 'pg_proc'::regclass
              AND d.deptype IN ('n', 'a')
            ORDER BY view_name, function_name
            """.trimIndent(), schemaName, schemaName,
        )
        return rows.groupBy(
            { it["view_name"] as String },
            { it["function_name"] as String },
        )
    }

    fun listFunctions(session: JdbcOperations, schemaName: String): List<Map<String, Any?>> {
        return session.queryList(
            """
            SELECT r.routine_name, r.specific_name, r.routine_type, r.data_type,
                   r.type_udt_name, r.external_language,
                   r.routine_definition, r.is_deterministic
            FROM information_schema.routines r
            WHERE r.routine_schema = ?
              AND r.routine_type = 'FUNCTION'
              AND r.routine_name NOT LIKE 'pg_%'
            ORDER BY r.specific_name
            """.trimIndent(), schemaName,
        )
    }

    fun listProcedures(session: JdbcOperations, schemaName: String): List<Map<String, Any?>> {
        return session.queryList(
            """
            SELECT r.routine_name, r.specific_name, r.routine_type,
                   r.external_language, r.routine_definition
            FROM information_schema.routines r
            WHERE r.routine_schema = ?
              AND r.routine_type = 'PROCEDURE'
            ORDER BY r.specific_name
            """.trimIndent(), schemaName,
        )
    }

    fun listRoutineParameters(session: JdbcOperations, schemaName: String, specificName: String): List<Map<String, Any?>> {
        return session.queryList(
            """
            SELECT parameter_name, data_type, udt_name, parameter_mode,
                   ordinal_position
            FROM information_schema.parameters
            WHERE specific_schema = ?
              AND specific_name = ?
              AND ordinal_position > 0
            ORDER BY ordinal_position
            """.trimIndent(), schemaName, specificName,
        )
    }

    fun listTriggers(session: JdbcOperations, schemaName: String): List<Map<String, Any?>> {
        return session.queryList(
            """
            SELECT trigger_name, event_object_table,
                   action_timing, event_manipulation,
                   action_orientation, action_condition,
                   action_statement
            FROM information_schema.triggers
            WHERE trigger_schema = ?
            ORDER BY event_object_table, trigger_name
            """.trimIndent(), schemaName,
        )
    }
}
