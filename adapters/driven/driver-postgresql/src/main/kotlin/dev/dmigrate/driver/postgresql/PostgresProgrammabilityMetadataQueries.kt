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
     * `relkind` to discriminate tables / views / sequences.
     *
     * The projection groups by `(proname, oid)` so two
     * same-name overloads with distinct signatures get distinct
     * dependency bags. The reader joins by the same
     * `(name, specific_name)` pair to avoid the overload-
     * collision bug where `fn(int)` and `fn(text)` would share
     * the union of each other's deps.
     */
    fun listRoutineRelationDependencies(
        session: JdbcOperations,
        schemaName: String,
    ): Map<RoutineKey, RoutineRelationDependencies> {
        val rows = session.queryList(
            """
            SELECT DISTINCT
                   p.proname AS routine_name,
                   p.oid::text || '_' || p.proname AS routine_key,
                   p.oid AS routine_oid,
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
            ORDER BY routine_name, routine_oid, relation_name
            """.trimIndent(), schemaName, schemaName,
        )

        val grouped = linkedMapOf<RoutineKey, MutableRoutineRelationDependencies>()
        for (row in rows) {
            val routineName = row["routine_name"] as String
            val routineOid = (row["routine_oid"] as Number).toLong()
            val relationName = row["relation_name"] as String
            val relationKind = row["relation_kind"] as String
            val key = RoutineKey(name = routineName, oid = routineOid)
            val deps = grouped.getOrPut(key) { MutableRoutineRelationDependencies() }
            when (relationKind) {
                "v", "m" -> deps.views += relationName
                "S" -> deps.sequences += relationName
                else -> deps.tables += relationName
            }
        }
        return grouped.mapValues { (_, deps) -> deps.toImmutable() }
    }

    /**
     * E.1 Routine-Migration Slice E: routine identity attributes
     * (security, definer, search_path) projected from `pg_proc`.
     * Slice A landed `FunctionDefinition`/`ProcedureDefinition`
     * with these fields but left the reverse reader at `null` as
     * a documented carve-out — Slice E closes it.
     *
     * The query is keyed by `RoutineKey(name, oid)` so same-name
     * overloads stay distinct (mirrors the D.2 follow-up pattern
     * for `listRoutineRelationDependencies`).
     *
     * `proconfig` is a `text[]` of `key=value` strings; the parser
     * extracts a `search_path=...` entry and splits it on commas.
     * `pg_roles` (not `pg_authid`) is joined for the owner name
     * because `pg_authid` requires superuser privileges in many
     * managed-database environments — `pg_roles` exposes the same
     * `rolname` minus the credential columns.
     */
    fun listRoutineIdentityAttributes(
        session: JdbcOperations,
        schemaName: String,
    ): Map<RoutineKey, RoutineIdentityAttributes> {
        val rows = session.queryList(
            """
            SELECT p.proname AS routine_name,
                   p.oid AS routine_oid,
                   p.prosecdef AS security_definer,
                   r.rolname AS definer,
                   p.proconfig AS config
            FROM pg_proc p
            JOIN pg_namespace n ON n.oid = p.pronamespace AND n.nspname = ?
            LEFT JOIN pg_roles r ON r.oid = p.proowner
            WHERE p.proname NOT LIKE 'pg_%'
            ORDER BY routine_name, routine_oid
            """.trimIndent(), schemaName,
        )

        val grouped = linkedMapOf<RoutineKey, RoutineIdentityAttributes>()
        for (row in rows) {
            val routineName = row["routine_name"] as String
            val routineOid = (row["routine_oid"] as Number).toLong()
            val securityDefiner = (row["security_definer"] as? Boolean) == true
            val definer = row["definer"] as? String
            val searchPath = parseSearchPath(row["config"])
            grouped[RoutineKey(name = routineName, oid = routineOid)] = RoutineIdentityAttributes(
                securityDefiner = securityDefiner,
                definer = definer,
                searchPath = searchPath,
            )
        }
        return grouped
    }

    /**
     * Extracts the `search_path` segment from a `proconfig` array
     * row. JDBC returns `text[]` as `java.sql.Array` (driver-
     * specific) or a `Array<String>` / `List<String>`; the parser
     * accepts both via the [toStringList] helper.
     *
     * PostgreSQL quotes a `proconfig` value with surrounding
     * double quotes whenever it contains commas or other special
     * characters — so a `search_path="weird,schema",public` value
     * must be split on **un-quoted** commas only, and the
     * resulting tokens must have their wrapping quotes stripped
     * (and any escaped `""` collapsed back to `"`) so the
     * comparator round-trips byte-identically against the
     * file-authored form (`["weird,schema", "public"]`).
     */
    private fun parseSearchPath(config: Any?): List<String>? {
        val entries = toStringList(config) ?: return null
        for (entry in entries) {
            if (entry.startsWith("search_path=")) {
                val value = entry.substringAfter("search_path=")
                return splitQuotedSegments(value).map { unquoteSearchPathSegment(it) }.filter { it.isNotEmpty() }
            }
        }
        return null
    }

    /**
     * Splits a `search_path` value on commas while preserving
     * comma sequences inside double-quoted segments. A PG
     * `proconfig` value `"a,b",c` becomes `["\"a,b\"", "c"]`.
     */
    private fun splitQuotedSegments(value: String): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < value.length) {
            val ch = value[i]
            when {
                ch == '"' -> {
                    current.append(ch)
                    // Treat `""` inside a quoted segment as an
                    // escaped quote, not as the end of the segment.
                    val isEscapedQuote = inQuotes && i + 1 < value.length && value[i + 1] == '"'
                    if (isEscapedQuote) {
                        current.append('"')
                        i++
                    } else {
                        inQuotes = !inQuotes
                    }
                }
                ch == ',' && !inQuotes -> {
                    result += current.toString()
                    current.clear()
                }
                else -> current.append(ch)
            }
            i++
        }
        if (current.isNotEmpty()) result += current.toString()
        return result
    }

    /**
     * Strips a single layer of double quotes from a `search_path`
     * segment and collapses `""` back to `"`. Leaves un-quoted
     * segments untouched.
     */
    private fun unquoteSearchPathSegment(segment: String): String {
        val trimmed = segment.trim()
        return if (trimmed.length >= 2 && trimmed.startsWith('"') && trimmed.endsWith('"')) {
            trimmed.substring(1, trimmed.length - 1).replace("\"\"", "\"")
        } else {
            trimmed
        }
    }

    private fun toStringList(value: Any?): List<String>? = when (value) {
        null -> null
        is List<*> -> value.filterIsInstance<String>()
        is Array<*> -> value.filterIsInstance<String>()
        is java.sql.Array -> {
            val raw = value.array
            if (raw is Array<*>) raw.filterIsInstance<String>() else null
        }
        else -> null
    }

    /**
     * E.1 Routine-Migration Slice D.2: trigger ↔ function edges via
     * `pg_trigger.tgfoid → pg_proc.oid`. The projection is keyed by
     * `(table, trigger_name)` because `pg_trigger.tgname` is only
     * unique per relation — two triggers with the same name on
     * different tables are legal and must not collapse into a
     * single bag.
     */
    fun listTriggerFunctionDependencies(
        session: JdbcOperations,
        schemaName: String,
    ): Map<TriggerKey, List<String>> {
        val rows = session.queryList(
            """
            SELECT c.relname AS table_name,
                   t.tgname AS trigger_name,
                   p.proname AS function_name
            FROM pg_trigger t
            JOIN pg_class c ON c.oid = t.tgrelid
            JOIN pg_namespace n ON n.oid = c.relnamespace AND n.nspname = ?
            JOIN pg_proc p ON p.oid = t.tgfoid
            WHERE NOT t.tgisinternal
            ORDER BY table_name, trigger_name, function_name
            """.trimIndent(), schemaName,
        )
        return rows.groupBy(
            { TriggerKey(table = it["table_name"] as String, name = it["trigger_name"] as String) },
            { it["function_name"] as String },
        )
    }
}

internal data class ViewRelationDependencies(
    val tables: List<String> = emptyList(),
    val views: List<String> = emptyList(),
    val columns: Map<String, List<String>> = emptyMap(),
)

/**
 * E.1 Slice D.2 + follow-up: identifies a specific routine
 * overload — the `oid` distinguishes same-name overloads with
 * different parameter signatures so each overload gets its own
 * `RoutineRelationDependencies` bag rather than sharing the
 * union.
 */
internal data class RoutineKey(val name: String, val oid: Long)

/**
 * E.1 Slice D.2 + follow-up: `pg_trigger.tgname` is only unique
 * per relation, so the trigger-function projection keys by the
 * `(table, name)` pair.
 */
internal data class TriggerKey(val table: String, val name: String)

/** E.1 Slice D.2: routine dependency projection from `pg_depend`. */
internal data class RoutineRelationDependencies(
    val tables: List<String> = emptyList(),
    val views: List<String> = emptyList(),
    val sequences: List<String> = emptyList(),
)

/**
 * E.1 Slice E: routine identity attribute projection from
 * `pg_proc` — fills the Slice A carve-out where the reverse
 * reader left `security` / `definer` / `searchPath` at null.
 */
internal data class RoutineIdentityAttributes(
    val securityDefiner: Boolean,
    val definer: String?,
    val searchPath: List<String>?,
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
