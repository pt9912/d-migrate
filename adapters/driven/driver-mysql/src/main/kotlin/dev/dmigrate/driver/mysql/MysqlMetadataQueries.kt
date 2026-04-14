package dev.dmigrate.driver.mysql

import dev.dmigrate.driver.metadata.*

/**
 * Shared JDBC metadata queries for MySQL.
 *
 * Operates on an already-borrowed connection via [JdbcMetadataSession].
 * Uses `information_schema` with `lower_case_table_names`-aware lookups.
 */
object MysqlMetadataQueries {

    fun listTableRefs(session: JdbcMetadataSession, database: String): List<TableRef> {
        return session.queryList(
            """
            SELECT table_name, table_schema, table_type
            FROM information_schema.tables
            WHERE table_schema = ?
              AND table_type = 'BASE TABLE'
            ORDER BY table_name
            """.trimIndent(), database,
        ).map { row ->
            TableRef(
                name = row["table_name"] as String,
                schema = row["table_schema"] as? String,
                type = row["table_type"] as? String ?: "BASE TABLE",
            )
        }
    }

    fun listTableEngine(session: JdbcMetadataSession, database: String, table: String): String? {
        val row = session.querySingle(
            "SELECT engine FROM information_schema.tables WHERE table_schema = ? AND table_name = ?",
            database, table,
        )
        return row?.get("engine") as? String
    }

    fun listColumns(session: JdbcMetadataSession, database: String, table: String): List<Map<String, Any?>> {
        return session.queryList(
            """
            SELECT column_name, data_type, column_type, is_nullable,
                   column_default, ordinal_position, extra,
                   character_maximum_length, numeric_precision, numeric_scale
            FROM information_schema.columns
            WHERE table_schema = ? AND table_name = ?
            ORDER BY ordinal_position
            """.trimIndent(), database, table,
        )
    }

    fun listPrimaryKeyColumns(session: JdbcMetadataSession, database: String, table: String): List<String> {
        return session.queryList(
            """
            SELECT column_name
            FROM information_schema.key_column_usage
            WHERE table_schema = ? AND table_name = ?
              AND constraint_name = 'PRIMARY'
            ORDER BY ordinal_position
            """.trimIndent(), database, table,
        ).map { it["column_name"] as String }
    }

    fun listForeignKeys(session: JdbcMetadataSession, database: String, table: String): List<ForeignKeyProjection> {
        val rows = session.queryList(
            """
            SELECT kcu.constraint_name, kcu.column_name,
                   kcu.referenced_table_name, kcu.referenced_column_name,
                   rc.delete_rule, rc.update_rule
            FROM information_schema.key_column_usage kcu
            JOIN information_schema.referential_constraints rc
              ON kcu.constraint_name = rc.constraint_name
              AND kcu.table_schema = rc.constraint_schema
            WHERE kcu.table_schema = ? AND kcu.table_name = ?
              AND kcu.referenced_table_name IS NOT NULL
            ORDER BY kcu.constraint_name, kcu.ordinal_position
            """.trimIndent(), database, table,
        )
        return rows.groupBy { it["constraint_name"] as String }.map { (name, fkRows) ->
            val first = fkRows.first()
            ForeignKeyProjection(
                name = name,
                columns = fkRows.map { it["column_name"] as String },
                referencedTable = first["referenced_table_name"] as String,
                referencedColumns = fkRows.map { it["referenced_column_name"] as String },
                onDelete = (first["delete_rule"] as? String)?.takeIf { it != "NO ACTION" && it != "RESTRICT" },
                onUpdate = (first["update_rule"] as? String)?.takeIf { it != "NO ACTION" && it != "RESTRICT" },
            )
        }
    }

    fun listCheckConstraints(session: JdbcMetadataSession, database: String, table: String): List<ConstraintProjection> {
        return session.queryList(
            """
            SELECT tc.constraint_name, cc.check_clause
            FROM information_schema.table_constraints tc
            JOIN information_schema.check_constraints cc
              ON tc.constraint_name = cc.constraint_name
              AND tc.constraint_schema = cc.constraint_schema
            WHERE tc.constraint_type = 'CHECK'
              AND tc.table_schema = ? AND tc.table_name = ?
              AND tc.constraint_name NOT LIKE '%_chk_%' OR tc.constraint_name LIKE '%_chk_%'
            ORDER BY tc.constraint_name
            """.trimIndent(), database, table,
        ).map { row ->
            ConstraintProjection(
                name = row["constraint_name"] as String,
                type = "CHECK",
                expression = row["check_clause"] as? String,
            )
        }
    }

    fun listIndices(session: JdbcMetadataSession, database: String, table: String): List<IndexProjection> {
        val rows = session.queryList(
            """
            SELECT index_name, column_name, non_unique, seq_in_index, index_type
            FROM information_schema.statistics
            WHERE table_schema = ? AND table_name = ?
              AND index_name != 'PRIMARY'
            ORDER BY index_name, seq_in_index
            """.trimIndent(), database, table,
        )
        return rows.groupBy { it["index_name"] as String }.map { (name, idxRows) ->
            IndexProjection(
                name = name,
                columns = idxRows.sortedBy { (it["seq_in_index"] as Number).toInt() }
                    .map { it["column_name"] as String },
                isUnique = (idxRows.first()["non_unique"] as Number).toInt() == 0,
                type = idxRows.first()["index_type"] as? String,
            )
        }
    }

    fun listViews(session: JdbcMetadataSession, database: String): List<Map<String, Any?>> {
        return session.queryList(
            """
            SELECT table_name, view_definition
            FROM information_schema.views
            WHERE table_schema = ?
            ORDER BY table_name
            """.trimIndent(), database,
        )
    }

    fun listFunctions(session: JdbcMetadataSession, database: String): List<Map<String, Any?>> {
        return session.queryList(
            """
            SELECT routine_name, routine_type, data_type,
                   dtd_identifier, routine_definition,
                   is_deterministic, routine_body
            FROM information_schema.routines
            WHERE routine_schema = ? AND routine_type = 'FUNCTION'
            ORDER BY routine_name
            """.trimIndent(), database,
        )
    }

    fun listProcedures(session: JdbcMetadataSession, database: String): List<Map<String, Any?>> {
        return session.queryList(
            """
            SELECT routine_name, routine_type,
                   routine_definition, routine_body
            FROM information_schema.routines
            WHERE routine_schema = ? AND routine_type = 'PROCEDURE'
            ORDER BY routine_name
            """.trimIndent(), database,
        )
    }

    fun listRoutineParameters(session: JdbcMetadataSession, database: String, routineName: String, routineType: String): List<Map<String, Any?>> {
        return session.queryList(
            """
            SELECT parameter_name, data_type, dtd_identifier,
                   parameter_mode, ordinal_position
            FROM information_schema.parameters
            WHERE specific_schema = ? AND specific_name = ?
              AND routine_type = ?
              AND ordinal_position > 0
            ORDER BY ordinal_position
            """.trimIndent(), database, routineName, routineType,
        )
    }

    fun listTriggers(session: JdbcMetadataSession, database: String): List<Map<String, Any?>> {
        return session.queryList(
            """
            SELECT trigger_name, event_object_table,
                   action_timing, event_manipulation,
                   action_orientation, action_statement
            FROM information_schema.triggers
            WHERE trigger_schema = ?
            ORDER BY event_object_table, trigger_name
            """.trimIndent(), database,
        )
    }
}
