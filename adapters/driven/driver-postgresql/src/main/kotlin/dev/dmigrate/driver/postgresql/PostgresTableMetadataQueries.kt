package dev.dmigrate.driver.postgresql

import dev.dmigrate.core.model.IndexSortDirection
import dev.dmigrate.driver.metadata.JdbcOperations
import dev.dmigrate.driver.metadata.ConstraintProjection
import dev.dmigrate.driver.metadata.ForeignKeyProjection
import dev.dmigrate.driver.metadata.IndexProjection
import dev.dmigrate.driver.metadata.TableRef

internal object PostgresTableMetadataQueries {

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
                   is_identity, identity_generation,
                   pg_get_serial_sequence(format('%I.%I', table_schema, table_name), column_name)
                       AS generated_sequence_name
            FROM information_schema.columns
            WHERE table_schema = ? AND table_name = ?
            ORDER BY ordinal_position
            """.trimIndent(), schemaName, table,
        )
    }

    fun listPrimaryKeyColumns(session: JdbcOperations, schemaName: String, table: String): List<String> {
        return session.queryList(
            """
            SELECT a.attname AS column_name
            FROM pg_constraint c
            JOIN pg_class t ON t.oid = c.conrelid
            JOIN pg_namespace n ON n.oid = t.relnamespace
            CROSS JOIN LATERAL unnest(c.conkey) WITH ORDINALITY AS pos(attnum, n)
            JOIN pg_attribute a ON a.attrelid = t.oid AND a.attnum = pos.attnum
            WHERE c.contype = 'p'
              AND n.nspname = ? AND t.relname = ?
            ORDER BY pos.n
            """.trimIndent(), schemaName, table,
        ).map { it["column_name"] as String }
    }

    fun listForeignKeys(session: JdbcOperations, schemaName: String, table: String): List<ForeignKeyProjection> {
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
            ForeignKeyProjection(
                name = row["constraint_name"] as String,
                columns = parseArrayColumn(row["columns"]),
                referencedTable = row["referenced_table"] as String,
                referencedColumns = parseArrayColumn(row["referenced_columns"]),
                onDelete = mapPgAction(row["confdeltype"] as? String),
                onUpdate = mapPgAction(row["confupdtype"] as? String),
            )
        }
    }

    fun listUniqueConstraintColumns(
        session: JdbcOperations,
        schemaName: String,
        table: String,
    ): Map<String, List<String>> {
        val rows = session.queryList(
            """
            SELECT c.conname AS constraint_name,
                   a.attname AS column_name
            FROM pg_constraint c
            JOIN pg_class t ON t.oid = c.conrelid
            JOIN pg_namespace n ON n.oid = t.relnamespace
            CROSS JOIN LATERAL unnest(c.conkey) WITH ORDINALITY AS pos(attnum, n)
            JOIN pg_attribute a ON a.attrelid = t.oid AND a.attnum = pos.attnum
            WHERE c.contype = 'u'
              AND n.nspname = ? AND t.relname = ?
            ORDER BY c.conname, pos.n
            """.trimIndent(), schemaName, table,
        )
        return rows.groupBy { it["constraint_name"] as String }
            .mapValues { (_, value) -> value.map { it["column_name"] as String } }
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

    fun listIndices(session: JdbcOperations, schemaName: String, table: String): List<IndexProjection> {
        return session.queryList(
            """
            SELECT i.relname AS index_name,
                   array_agg(a.attname ORDER BY k.n) AS columns,
                   array_agg(
                       CASE WHEN (ix.indoption[k.n - 1] & 1) = 1 THEN 'DESC' ELSE NULL END
                       ORDER BY k.n
                   ) AS directions,
                   ix.indisunique AS is_unique,
                   am.amname AS index_type,
                   pg_get_expr(ix.indpred, ix.indrelid) AS predicate
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
            GROUP BY i.relname, ix.indisunique, am.amname, ix.indpred, ix.indrelid
            ORDER BY i.relname
            """.trimIndent(), schemaName, table,
        ).map { row ->
            IndexProjection(
                name = row["index_name"] as String,
                columns = parseArrayColumn(row["columns"]),
                isUnique = row["is_unique"] as Boolean,
                type = row["index_type"] as? String,
                directions = parseDirectionArrayColumn(row["directions"]),
                where = row["predicate"] as? String,
            )
        }
    }

    fun listSequences(session: JdbcOperations, schemaName: String): List<Map<String, Any?>> {
        return session.queryList(
            """
            SELECT s.sequence_name, s.start_value, s.increment, s.minimum_value,
                   s.maximum_value, s.cycle_option,
                   ps.cache_size
            FROM information_schema.sequences s
            LEFT JOIN pg_sequences ps
              ON ps.schemaname = s.sequence_schema AND ps.sequencename = s.sequence_name
            WHERE s.sequence_schema = ?
              AND NOT EXISTS (
                  SELECT 1
                  FROM pg_class seq
                  JOIN pg_namespace seq_ns ON seq_ns.oid = seq.relnamespace
                  JOIN pg_depend dep ON dep.objid = seq.oid
                  WHERE seq.relkind = 'S'
                    AND seq_ns.nspname = s.sequence_schema
                    AND seq.relname = s.sequence_name
                    AND dep.deptype IN ('a', 'i')
              )
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

    private fun parseArrayColumn(value: Any?): List<String> = when (value) {
        is java.sql.Array -> (value.array as Array<*>).map { it.toString() }
        is String -> value.removeSurrounding("{", "}").split(",").map { it.trim() }
        else -> emptyList()
    }

    private fun parseDirectionArrayColumn(value: Any?): List<IndexSortDirection?> = when (value) {
        is java.sql.Array -> (value.array as Array<*>).map { parseIndexSortDirection(it?.toString()) }
        is String -> value.removeSurrounding("{", "}").split(",").map { parseIndexSortDirection(it.trim()) }
        else -> emptyList()
    }

    private fun parseIndexSortDirection(value: String?): IndexSortDirection? =
        if (value.equals("DESC", ignoreCase = true)) IndexSortDirection.DESC else null

    private fun mapPgAction(code: String?): String? = when (code) {
        "c" -> "CASCADE"
        "n" -> "SET NULL"
        "d" -> "SET DEFAULT"
        "r" -> "RESTRICT"
        "a", null -> null
        else -> null
    }
}
