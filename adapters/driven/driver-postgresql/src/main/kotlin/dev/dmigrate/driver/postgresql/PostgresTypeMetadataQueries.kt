package dev.dmigrate.driver.postgresql

import dev.dmigrate.driver.metadata.JdbcOperations

internal object PostgresTypeMetadataQueries {

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
            .mapValues { (_, value) -> value.map { it["enumlabel"] as String } }
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
}
