package dev.dmigrate.driver.postgresql

import dev.dmigrate.driver.metadata.JdbcOperations

internal object PostgresProgrammabilityMetadataQueries {

    fun listViews(session: JdbcOperations, schemaName: String): List<Map<String, Any?>> {
        return session.queryList(
            """
            SELECT c.relname AS table_name,
                   pg_get_viewdef(c.oid, true) AS view_definition,
                   c.relkind = 'm' AS is_materialized
            FROM pg_class c
            JOIN pg_namespace n ON n.oid = c.relnamespace
            WHERE n.nspname = ?
              AND c.relkind IN ('v', 'm')
            ORDER BY c.relname
            """.trimIndent(), schemaName,
        )
    }

    fun listViewRelationDependencies(session: JdbcOperations, schemaName: String): Map<String, ViewRelationDependencies> {
        val rows = session.queryList(
            """
            SELECT DISTINCT
                   v.relname AS view_name,
                   r.relname AS relation_name,
                   r.relkind AS relation_kind,
                   a.attname AS column_name
            FROM pg_depend d
            JOIN pg_rewrite rw ON rw.oid = d.objid
            JOIN pg_class v ON v.oid = rw.ev_class AND v.relkind IN ('v', 'm')
            JOIN pg_namespace vn ON vn.oid = v.relnamespace AND vn.nspname = ?
            JOIN pg_class r ON r.oid = d.refobjid
            JOIN pg_namespace rn ON rn.oid = r.relnamespace AND rn.nspname = ?
            LEFT JOIN pg_attribute a ON a.attrelid = r.oid
                                      AND a.attnum = d.refobjsubid
                                      AND NOT a.attisdropped
            WHERE d.classid = 'pg_rewrite'::regclass
              AND d.refclassid = 'pg_class'::regclass
              AND d.deptype IN ('n', 'a')
              AND r.relkind IN ('r', 'p', 'v', 'm', 'f')
            ORDER BY view_name, relation_name, column_name
            """.trimIndent(), schemaName, schemaName,
        )

        val grouped = linkedMapOf<String, MutableViewRelationDependencies>()
        for (row in rows) {
            val viewName = row["view_name"] as String
            val relationName = row["relation_name"] as String
            val relationKind = row["relation_kind"] as String
            val columnName = row["column_name"] as? String
            val deps = grouped.getOrPut(viewName) { MutableViewRelationDependencies() }
            when (relationKind) {
                "v", "m" -> {
                    if (relationName != viewName) deps.views += relationName
                }
                else -> {
                    deps.tables += relationName
                    if (columnName != null) deps.columns.getOrPut(relationName) { linkedSetOf() } += columnName
                }
            }
        }
        return grouped.mapValues { (_, deps) -> deps.toImmutable() }
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

    fun listViewColumns(session: JdbcOperations, schemaName: String): Map<String, List<ViewColumnProjection>> {
        val rows = session.queryList(
            """
            SELECT c.relname AS view_name,
                   a.attname AS column_name,
                   pg_catalog.format_type(a.atttypid, a.atttypmod) AS column_type,
                   a.attnum AS ordinal_position
            FROM pg_class c
            JOIN pg_namespace n ON n.oid = c.relnamespace
            JOIN pg_attribute a ON a.attrelid = c.oid
            WHERE n.nspname = ?
              AND c.relkind IN ('v', 'm')
              AND a.attnum > 0
              AND NOT a.attisdropped
            ORDER BY c.relname, a.attnum
            """.trimIndent(), schemaName,
        )
        return rows.groupBy(
            { it["view_name"] as String },
            {
                ViewColumnProjection(
                    name = it["column_name"] as String,
                    type = it["column_type"] as? String,
                )
            },
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

    /**
     * E.1 Routine-Migration Slice D.2: routine ↔ table / view /
     * sequence edges via `pg_depend`. `pg_proc.oid` is the
     * referencing side (`classid = 'pg_proc'::regclass`);
     * `pg_class.oid` is the referenced relation
     * (`refclassid = 'pg_class'::regclass`), filtered by
     * `relkind` to discriminate tables / views / sequences. Returns
     * a per-routine-name bag of dependency names keyed by relkind.
     */
    fun listRoutineRelationDependencies(
        session: JdbcOperations,
        schemaName: String,
    ): Map<String, RoutineRelationDependencies> {
        val rows = session.queryList(
            """
            SELECT DISTINCT
                   p.proname AS routine_name,
                   r.relname AS relation_name,
                   r.relkind AS relation_kind
            FROM pg_depend d
            JOIN pg_proc p ON p.oid = d.objid
            JOIN pg_namespace pn ON pn.oid = p.pronamespace AND pn.nspname = ?
            JOIN pg_class r ON r.oid = d.refobjid
            JOIN pg_namespace rn ON rn.oid = r.relnamespace AND rn.nspname = ?
            WHERE d.classid = 'pg_proc'::regclass
              AND d.refclassid = 'pg_class'::regclass
              AND d.deptype IN ('n', 'a')
              AND r.relkind IN ('r', 'p', 'v', 'm', 'S', 'f')
            ORDER BY routine_name, relation_name
            """.trimIndent(), schemaName, schemaName,
        )

        val grouped = linkedMapOf<String, MutableRoutineRelationDependencies>()
        for (row in rows) {
            val routineName = row["routine_name"] as String
            val relationName = row["relation_name"] as String
            val relationKind = row["relation_kind"] as String
            val deps = grouped.getOrPut(routineName) { MutableRoutineRelationDependencies() }
            when (relationKind) {
                "v", "m" -> deps.views += relationName
                "S" -> deps.sequences += relationName
                else -> deps.tables += relationName
            }
        }
        return grouped.mapValues { (_, deps) -> deps.toImmutable() }
    }

    /**
     * E.1 Routine-Migration Slice D.2: trigger ↔ function edges via
     * `pg_trigger.tgfoid → pg_proc.oid`. Each row pairs a trigger
     * with the procedural function it invokes; the reader writes
     * the result into the trigger's `DependencyInfo.functions`.
     */
    fun listTriggerFunctionDependencies(
        session: JdbcOperations,
        schemaName: String,
    ): Map<String, List<String>> {
        val rows = session.queryList(
            """
            SELECT t.tgname AS trigger_name, p.proname AS function_name
            FROM pg_trigger t
            JOIN pg_class c ON c.oid = t.tgrelid
            JOIN pg_namespace n ON n.oid = c.relnamespace AND n.nspname = ?
            JOIN pg_proc p ON p.oid = t.tgfoid
            WHERE NOT t.tgisinternal
            ORDER BY trigger_name, function_name
            """.trimIndent(), schemaName,
        )
        return rows.groupBy(
            { it["trigger_name"] as String },
            { it["function_name"] as String },
        )
    }
}

internal data class ViewRelationDependencies(
    val tables: List<String> = emptyList(),
    val views: List<String> = emptyList(),
    val columns: Map<String, List<String>> = emptyMap(),
)

/** E.1 Slice D.2: routine dependency projection from `pg_depend`. */
internal data class RoutineRelationDependencies(
    val tables: List<String> = emptyList(),
    val views: List<String> = emptyList(),
    val sequences: List<String> = emptyList(),
)

private class MutableRoutineRelationDependencies {
    val tables = linkedSetOf<String>()
    val views = linkedSetOf<String>()
    val sequences = linkedSetOf<String>()

    fun toImmutable(): RoutineRelationDependencies = RoutineRelationDependencies(
        tables = tables.toList(),
        views = views.toList(),
        sequences = sequences.toList(),
    )
}

internal data class ViewColumnProjection(
    val name: String,
    val type: String?,
)

private class MutableViewRelationDependencies {
    val tables = linkedSetOf<String>()
    val views = linkedSetOf<String>()
    val columns = linkedMapOf<String, LinkedHashSet<String>>()

    fun toImmutable(): ViewRelationDependencies =
        ViewRelationDependencies(
            tables = tables.toList(),
            views = views.toList(),
            columns = columns.mapValues { (_, names) -> names.toList() },
        )
}
