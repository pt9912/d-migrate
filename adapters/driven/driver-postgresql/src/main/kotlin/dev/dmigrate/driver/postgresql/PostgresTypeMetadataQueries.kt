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
              AND NOT EXISTS (SELECT 1 FROM pg_depend d WHERE d.deptype = 'e' AND d.objid = t.oid)
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
                   bn.nspname AS base_schema,
                   -- Laenge, Genauigkeit und Array stehen nur hier, und ohne Recht an der Domain
                   -- (anders als in den Sichten des SQL-Standards) auch fuer jede Rolle.
                   pg_catalog.format_type(t.typbasetype, t.typtypmod) AS base_type_sql,
                   pg_catalog.pg_get_constraintdef(c.oid) AS check_clause
            FROM pg_type t
            JOIN pg_namespace n ON n.oid = t.typnamespace
            JOIN pg_type bt ON bt.oid = t.typbasetype
            JOIN pg_namespace bn ON bn.oid = bt.typnamespace
            LEFT JOIN pg_constraint c ON c.contypid = t.oid AND c.contype = 'c'
            WHERE t.typtype = 'd' AND n.nspname = ?
              AND NOT EXISTS (SELECT 1 FROM pg_depend d WHERE d.deptype = 'e' AND d.objid = t.oid)
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
              -- 5a: Extension-eigene Composite-Typen ausschließen (z. B. PostGIS
              -- `geometry_dump`/`valid_detail`), sonst CREATE TYPE-Kollision im Ziel.
              AND NOT EXISTS (SELECT 1 FROM pg_depend d WHERE d.deptype = 'e' AND d.objid = t.oid)
            ORDER BY t.typname, a.attnum
            """.trimIndent(), schemaName,
        )
    }
}
