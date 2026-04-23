package dev.dmigrate.driver.postgresql

import dev.dmigrate.driver.metadata.*

/**
 * Shared JDBC metadata queries for PostgreSQL.
 *
 * Operates on an already-borrowed connection via [JdbcOperations].
 * Uses both `information_schema` and `pg_catalog` for comprehensive
 * metadata extraction.
 */
object PostgresMetadataQueries {

    fun listTableRefs(session: JdbcOperations, schemaName: String): List<TableRef> {
        return session.queryList(
            """
            SELECT table_name, table_schema, table_type
            FROM information_schema.tables
            WHERE table_schema = ?
              AND table_type = 'BASE TABLE'
            ORDER BY table_name
            """.trimIndent(), schemaName,
        ).map { row ->
            TableRef(
                name = row["table_name"] as String,
                schema = row["table_schema"] as? String,
                type = row["table_type"] as? String ?: "BASE TABLE",
            )
        }
    }

    fun listColumns(session: JdbcOperations, schemaName: String, table: String): List<Map<String, Any?>> {
        return session.queryList(
            """
            SELECT column_name, data_type, udt_name, is_nullable,
                   column_default, ordinal_position,
                   character_maximum_length, numeric_precision, numeric_scale,
                   is_identity, identity_generation
            FROM information_schema.columns
            WHERE table_schema = ? AND table_name = ?
            ORDER BY ordinal_position
            """.trimIndent(), schemaName, table,
        )
    }

    fun listPrimaryKeyColumns(session: JdbcOperations, schemaName: String, table: String): List<String> {
        return session.queryList(
            """
            SELECT kcu.column_name
            FROM information_schema.table_constraints tc
            JOIN information_schema.key_column_usage kcu
              ON tc.constraint_name = kcu.constraint_name
              AND tc.table_schema = kcu.table_schema
            WHERE tc.constraint_type = 'PRIMARY KEY'
              AND tc.table_schema = ? AND tc.table_name = ?
            ORDER BY kcu.ordinal_position
            """.trimIndent(), schemaName, table,
        ).map { it["column_name"] as String }
    }

    fun listForeignKeys(session: JdbcOperations, schemaName: String, table: String): List<ForeignKeyProjection> {
        // Uses pg_constraint directly to avoid the cartesian product from
        // information_schema.constraint_column_usage on composite FKs.
        val rows = session.queryList(
            """
            SELECT c.conname AS constraint_name,
                   array_agg(sa.attname ORDER BY pos.n) AS columns,
                   tf.relname AS referenced_table,
                   array_agg(ta.attname ORDER BY pos.n) AS referenced_columns,
                   c.confdeltype, c.confupdtype
            FROM pg_constraint c
            JOIN pg_class sf ON sf.oid = c.conrelid
            JOIN pg_class tf ON tf.oid = c.confrelid
            JOIN pg_namespace n ON n.oid = sf.relnamespace
            CROSS JOIN LATERAL unnest(c.conkey, c.confkey) WITH ORDINALITY AS pos(src_attnum, tgt_attnum, n)
            JOIN pg_attribute sa ON sa.attrelid = sf.oid AND sa.attnum = pos.src_attnum
            JOIN pg_attribute ta ON ta.attrelid = tf.oid AND ta.attnum = pos.tgt_attnum
            WHERE c.contype = 'f'
              AND n.nspname = ? AND sf.relname = ?
            GROUP BY c.conname, tf.relname, c.confdeltype, c.confupdtype
            ORDER BY c.conname
            """.trimIndent(), schemaName, table,
        )
        return rows.map { row ->
            val cols = parseArrayColumn(row["columns"])
            val refCols = parseArrayColumn(row["referenced_columns"])
            ForeignKeyProjection(
                name = row["constraint_name"] as String,
                columns = cols,
                referencedTable = row["referenced_table"] as String,
                referencedColumns = refCols,
                onDelete = mapPgAction(row["confdeltype"] as? String),
                onUpdate = mapPgAction(row["confupdtype"] as? String),
            )
        }
    }

    private fun parseArrayColumn(value: Any?): List<String> = when (value) {
        is java.sql.Array -> (value.array as Array<*>).map { it.toString() }
        is String -> value.removeSurrounding("{", "}").split(",").map { it.trim() }
        else -> emptyList()
    }

    private fun mapPgAction(code: String?): String? = when (code) {
        "c" -> "CASCADE"
        "n" -> "SET NULL"
        "d" -> "SET DEFAULT"
        "r" -> "RESTRICT"
        "a", null -> null // NO ACTION
        else -> null
    }

    fun listUniqueConstraintColumns(session: JdbcOperations, schemaName: String, table: String): Map<String, List<String>> {
        val rows = session.queryList(
            """
            SELECT tc.constraint_name, kcu.column_name
            FROM information_schema.table_constraints tc
            JOIN information_schema.key_column_usage kcu
              ON tc.constraint_name = kcu.constraint_name
              AND tc.table_schema = kcu.table_schema
            WHERE tc.constraint_type = 'UNIQUE'
              AND tc.table_schema = ? AND tc.table_name = ?
            ORDER BY tc.constraint_name, kcu.ordinal_position
            """.trimIndent(), schemaName, table,
        )
        return rows.groupBy { it["constraint_name"] as String }
            .mapValues { (_, v) -> v.map { it["column_name"] as String } }
    }

    fun listCheckConstraints(session: JdbcOperations, schemaName: String, table: String): List<ConstraintProjection> {
        return session.queryList(
            """
            SELECT tc.constraint_name, cc.check_clause
            FROM information_schema.table_constraints tc
            JOIN information_schema.check_constraints cc
              ON tc.constraint_name = cc.constraint_name
              AND tc.constraint_schema = cc.constraint_schema
            WHERE tc.constraint_type = 'CHECK'
              AND tc.table_schema = ? AND tc.table_name = ?
              AND tc.constraint_name NOT LIKE '%_not_null'
            ORDER BY tc.constraint_name
            """.trimIndent(), schemaName, table,
        ).map { row ->
            ConstraintProjection(
                name = row["constraint_name"] as String,
                type = "CHECK",
                expression = row["check_clause"] as? String,
            )
        }
    }

    /**
     * Lists non-backing indices. Uses pg_index to exclude primary key
     * and unique constraint backing indices.
     */
    fun listIndices(session: JdbcOperations, schemaName: String, table: String): List<IndexProjection> {
        return session.queryList(
            """
            SELECT i.relname AS index_name,
                   array_agg(a.attname ORDER BY k.n) AS columns,
                   ix.indisunique AS is_unique,
                   am.amname AS index_type
            FROM pg_index ix
            JOIN pg_class t ON t.oid = ix.indrelid
            JOIN pg_class i ON i.oid = ix.indexrelid
            JOIN pg_namespace n ON n.oid = t.relnamespace
            JOIN pg_am am ON am.oid = i.relam
            CROSS JOIN LATERAL unnest(ix.indkey) WITH ORDINALITY AS k(attnum, n)
            JOIN pg_attribute a ON a.attrelid = t.oid AND a.attnum = k.attnum
            WHERE n.nspname = ? AND t.relname = ?
              AND NOT ix.indisprimary
              AND NOT EXISTS (
                  SELECT 1 FROM pg_constraint c
                  WHERE c.conindid = ix.indexrelid
                    AND c.contype IN ('u', 'x')
              )
            GROUP BY i.relname, ix.indisunique, am.amname
            ORDER BY i.relname
            """.trimIndent(), schemaName, table,
        ).map { row ->
            val cols = row["columns"]
            val columnList = when (cols) {
                is java.sql.Array -> (cols.array as Array<*>).map { it.toString() }
                is String -> cols.removeSurrounding("{", "}").split(",")
                else -> emptyList()
            }
            IndexProjection(
                name = row["index_name"] as String,
                columns = columnList,
                isUnique = row["is_unique"] as Boolean,
                type = row["index_type"] as? String,
            )
        }
    }

    fun listSequences(session: JdbcOperations, schemaName: String): List<Map<String, Any?>> {
        // information_schema.sequences does NOT have a cache_size column;
        // pg_sequences (system view) does — LEFT JOIN to get it.
        return session.queryList(
            """
            SELECT s.sequence_name, s.start_value, s.increment, s.minimum_value,
                   s.maximum_value, s.cycle_option,
                   ps.cache_size
            FROM information_schema.sequences s
            LEFT JOIN pg_sequences ps
              ON ps.schemaname = s.sequence_schema AND ps.sequencename = s.sequence_name
            WHERE s.sequence_schema = ?
            ORDER BY s.sequence_name
            """.trimIndent(), schemaName,
        )
    }

    fun getPartitionInfo(session: JdbcOperations, schemaName: String, table: String): Map<String, Any?>? {
        return session.querySingle(
            """
            SELECT pt.partstrat, array_agg(a.attname ORDER BY pos.n) AS key_columns
            FROM pg_partitioned_table pt
            JOIN pg_class c ON c.oid = pt.partrelid
            JOIN pg_namespace n ON n.oid = c.relnamespace
            CROSS JOIN LATERAL unnest(pt.partattrs) WITH ORDINALITY AS pos(attnum, n)
            JOIN pg_attribute a ON a.attrelid = c.oid AND a.attnum = pos.attnum
            WHERE n.nspname = ? AND c.relname = ?
            GROUP BY pt.partstrat
            """.trimIndent(), schemaName, table,
        )
    }

    fun listInstalledExtensions(session: JdbcOperations): List<String> {
        return session.queryList(
            "SELECT extname FROM pg_extension WHERE extname != 'plpgsql' ORDER BY extname"
        ).map { it["extname"] as String }
    }

    fun listEnumTypes(session: JdbcOperations, schemaName: String): Map<String, List<String>> {
        val rows = session.queryList(
            """
            SELECT t.typname, e.enumlabel
            FROM pg_type t
            JOIN pg_enum e ON e.enumtypid = t.oid
            JOIN pg_namespace n ON n.oid = t.typnamespace
            WHERE n.nspname = ?
            ORDER BY t.typname, e.enumsortorder
            """.trimIndent(), schemaName,
        )
        return rows.groupBy { it["typname"] as String }
            .mapValues { (_, v) -> v.map { it["enumlabel"] as String } }
    }

    fun listDomainTypes(session: JdbcOperations, schemaName: String): List<Map<String, Any?>> {
        return session.queryList(
            """
            SELECT t.typname,
                   bt.typname AS base_type,
                   information_schema.domains.numeric_precision,
                   information_schema.domains.numeric_scale,
                   information_schema.domains.domain_default,
                   pg_catalog.pg_get_constraintdef(c.oid) AS check_clause
            FROM pg_type t
            JOIN pg_namespace n ON n.oid = t.typnamespace
            JOIN pg_type bt ON bt.oid = t.typbasetype
            LEFT JOIN information_schema.domains
              ON information_schema.domains.domain_schema = n.nspname
              AND information_schema.domains.domain_name = t.typname
            LEFT JOIN pg_constraint c ON c.contypid = t.oid AND c.contype = 'c'
            WHERE t.typtype = 'd' AND n.nspname = ?
            ORDER BY t.typname
            """.trimIndent(), schemaName,
        )
    }

    fun listCompositeTypes(session: JdbcOperations, schemaName: String): List<Map<String, Any?>> {
        return session.queryList(
            """
            SELECT t.typname, a.attname, a.attnum,
                   format_type(a.atttypid, a.atttypmod) AS column_type
            FROM pg_type t
            JOIN pg_namespace n ON n.oid = t.typnamespace
            JOIN pg_class c ON c.oid = t.typrelid
            JOIN pg_attribute a ON a.attrelid = c.oid AND a.attnum > 0 AND NOT a.attisdropped
            WHERE t.typtype = 'c' AND n.nspname = ?
              AND NOT EXISTS (SELECT 1 FROM pg_class r WHERE r.oid = t.typrelid AND r.relkind != 'c')
            ORDER BY t.typname, a.attnum
            """.trimIndent(), schemaName,
        )
    }

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

    /** List parameters for a specific routine identified by its `specific_name`. */
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
