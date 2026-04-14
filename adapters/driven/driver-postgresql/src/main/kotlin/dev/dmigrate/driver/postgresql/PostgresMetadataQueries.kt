package dev.dmigrate.driver.postgresql

import dev.dmigrate.driver.metadata.*

/**
 * Shared JDBC metadata queries for PostgreSQL.
 *
 * Operates on an already-borrowed connection via [JdbcMetadataSession].
 * Uses both `information_schema` and `pg_catalog` for comprehensive
 * metadata extraction.
 */
object PostgresMetadataQueries {

    fun listTableRefs(session: JdbcMetadataSession, schema: String): List<TableRef> {
        return session.queryList(
            """
            SELECT table_name, table_schema, table_type
            FROM information_schema.tables
            WHERE table_schema = ?
              AND table_type = 'BASE TABLE'
            ORDER BY table_name
            """.trimIndent(), schema,
        ).map { row ->
            TableRef(
                name = row["table_name"] as String,
                schema = row["table_schema"] as? String,
                type = row["table_type"] as? String ?: "BASE TABLE",
            )
        }
    }

    fun listColumns(session: JdbcMetadataSession, schema: String, table: String): List<Map<String, Any?>> {
        return session.queryList(
            """
            SELECT column_name, data_type, udt_name, is_nullable,
                   column_default, ordinal_position,
                   character_maximum_length, numeric_precision, numeric_scale,
                   is_identity, identity_generation
            FROM information_schema.columns
            WHERE table_schema = ? AND table_name = ?
            ORDER BY ordinal_position
            """.trimIndent(), schema, table,
        )
    }

    fun listPrimaryKeyColumns(session: JdbcMetadataSession, schema: String, table: String): List<String> {
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
            """.trimIndent(), schema, table,
        ).map { it["column_name"] as String }
    }

    fun listForeignKeys(session: JdbcMetadataSession, schema: String, table: String): List<ForeignKeyProjection> {
        val rows = session.queryList(
            """
            SELECT tc.constraint_name,
                   kcu.column_name,
                   ccu.table_name AS referenced_table,
                   ccu.column_name AS referenced_column,
                   rc.delete_rule, rc.update_rule
            FROM information_schema.table_constraints tc
            JOIN information_schema.key_column_usage kcu
              ON tc.constraint_name = kcu.constraint_name
              AND tc.table_schema = kcu.table_schema
            JOIN information_schema.constraint_column_usage ccu
              ON tc.constraint_name = ccu.constraint_name
              AND tc.table_schema = ccu.table_schema
            JOIN information_schema.referential_constraints rc
              ON tc.constraint_name = rc.constraint_name
              AND tc.table_schema = rc.constraint_schema
            WHERE tc.constraint_type = 'FOREIGN KEY'
              AND tc.table_schema = ? AND tc.table_name = ?
            ORDER BY tc.constraint_name, kcu.ordinal_position
            """.trimIndent(), schema, table,
        )
        return rows.groupBy { it["constraint_name"] as String }.map { (name, fkRows) ->
            val first = fkRows.first()
            ForeignKeyProjection(
                name = name,
                columns = fkRows.map { it["column_name"] as String },
                referencedTable = first["referenced_table"] as String,
                referencedColumns = fkRows.map { it["referenced_column"] as String },
                onDelete = (first["delete_rule"] as? String)?.takeIf { it != "NO ACTION" },
                onUpdate = (first["update_rule"] as? String)?.takeIf { it != "NO ACTION" },
            )
        }
    }

    fun listUniqueConstraintColumns(session: JdbcMetadataSession, schema: String, table: String): Map<String, List<String>> {
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
            """.trimIndent(), schema, table,
        )
        return rows.groupBy { it["constraint_name"] as String }
            .mapValues { (_, v) -> v.map { it["column_name"] as String } }
    }

    fun listCheckConstraints(session: JdbcMetadataSession, schema: String, table: String): List<ConstraintProjection> {
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
            """.trimIndent(), schema, table,
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
    fun listIndices(session: JdbcMetadataSession, schema: String, table: String): List<IndexProjection> {
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
            """.trimIndent(), schema, table,
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

    fun listSequences(session: JdbcMetadataSession, schema: String): List<Map<String, Any?>> {
        return session.queryList(
            """
            SELECT sequence_name, start_value, increment, minimum_value,
                   maximum_value, cycle_option, cache_size
            FROM information_schema.sequences
            WHERE sequence_schema = ?
            ORDER BY sequence_name
            """.trimIndent(), schema,
        )
    }

    fun getPartitionInfo(session: JdbcMetadataSession, schema: String, table: String): Map<String, Any?>? {
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
            """.trimIndent(), schema, table,
        )
    }

    fun listInstalledExtensions(session: JdbcMetadataSession): List<String> {
        return session.queryList(
            "SELECT extname FROM pg_extension WHERE extname != 'plpgsql' ORDER BY extname"
        ).map { it["extname"] as String }
    }

    fun listEnumTypes(session: JdbcMetadataSession, schema: String): Map<String, List<String>> {
        val rows = session.queryList(
            """
            SELECT t.typname, e.enumlabel
            FROM pg_type t
            JOIN pg_enum e ON e.enumtypid = t.oid
            JOIN pg_namespace n ON n.oid = t.typnamespace
            WHERE n.nspname = ?
            ORDER BY t.typname, e.enumsortorder
            """.trimIndent(), schema,
        )
        return rows.groupBy { it["typname"] as String }
            .mapValues { (_, v) -> v.map { it["enumlabel"] as String } }
    }

    fun listDomainTypes(session: JdbcMetadataSession, schema: String): List<Map<String, Any?>> {
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
            """.trimIndent(), schema,
        )
    }

    fun listCompositeTypes(session: JdbcMetadataSession, schema: String): List<Map<String, Any?>> {
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
            """.trimIndent(), schema,
        )
    }

    fun listViews(session: JdbcMetadataSession, schema: String): List<Map<String, Any?>> {
        return session.queryList(
            """
            SELECT table_name, view_definition
            FROM information_schema.views
            WHERE table_schema = ?
            ORDER BY table_name
            """.trimIndent(), schema,
        )
    }

    fun listFunctions(session: JdbcMetadataSession, schema: String): List<Map<String, Any?>> {
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
            """.trimIndent(), schema,
        )
    }

    fun listProcedures(session: JdbcMetadataSession, schema: String): List<Map<String, Any?>> {
        return session.queryList(
            """
            SELECT r.routine_name, r.specific_name, r.routine_type,
                   r.external_language, r.routine_definition
            FROM information_schema.routines r
            WHERE r.routine_schema = ?
              AND r.routine_type = 'PROCEDURE'
            ORDER BY r.specific_name
            """.trimIndent(), schema,
        )
    }

    /** List parameters for a specific routine identified by its `specific_name`. */
    fun listRoutineParameters(session: JdbcMetadataSession, schema: String, specificName: String): List<Map<String, Any?>> {
        return session.queryList(
            """
            SELECT parameter_name, data_type, udt_name, parameter_mode,
                   ordinal_position
            FROM information_schema.parameters
            WHERE specific_schema = ?
              AND specific_name = ?
              AND ordinal_position > 0
            ORDER BY ordinal_position
            """.trimIndent(), schema, specificName,
        )
    }

    fun listTriggers(session: JdbcMetadataSession, schema: String): List<Map<String, Any?>> {
        return session.queryList(
            """
            SELECT trigger_name, event_object_table,
                   action_timing, event_manipulation,
                   action_orientation, action_condition,
                   action_statement
            FROM information_schema.triggers
            WHERE trigger_schema = ?
            ORDER BY event_object_table, trigger_name
            """.trimIndent(), schema,
        )
    }
}
