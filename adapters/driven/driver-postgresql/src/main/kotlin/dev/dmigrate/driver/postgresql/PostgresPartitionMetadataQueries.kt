package dev.dmigrate.driver.postgresql

import dev.dmigrate.driver.metadata.JdbcOperations

/**
 * Partitions-bezogene JDBC-Metadaten-Abfragen für PostgreSQL (ADR 0019).
 * Eigenes Objekt analog zu [PostgresTypeMetadataQueries] /
 * [PostgresProgrammabilityMetadataQueries] — hält Strategie/Schlüssel, die
 * Kind-Partitionen und die Index-Vererbungs-Klassifikation an einem Ort.
 */
internal object PostgresPartitionMetadataQueries {

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

    /**
     * AP1 (ADR 0019): die Kind-Partitionen eines partitionierten Parents über
     * `pg_inherits` finden und je Kind die rohe `FOR VALUES`-Klausel
     * (`pg_get_expr(relpartbound, …)`) liefern. Das Parsen/Normalisieren der
     * Klausel ins strukturierte Modell übernimmt [PostgresPartitionBoundParser].
     * `relispartition` grenzt deklarative Partitionen gegen Legacy-Inheritance ab.
     */
    fun listPartitionChildren(session: JdbcOperations, schemaName: String, table: String): List<Map<String, Any?>> {
        return session.queryList(
            """
            SELECT c.relname AS partition_name,
                   pg_get_expr(c.relpartbound, c.oid) AS bound_expr
            FROM pg_inherits i
            JOIN pg_class c ON c.oid = i.inhrelid
            JOIN pg_class p ON p.oid = i.inhparent
            JOIN pg_namespace n ON n.oid = p.relnamespace
            WHERE n.nspname = ? AND p.relname = ?
              AND c.relispartition
            ORDER BY c.relname
            """.trimIndent(), schemaName, table,
        )
    }

    /**
     * AP2a (ADR 0019): die Namen der Indizes von [table], die **vom Parent
     * propagiert** sind (Index-Vererbung). PG legt für einen am partitionierten
     * Parent definierten Index je Kind ein attached Backing an — dieses trägt eine
     * `pg_inherits`-Zeile (`inhrelid` = Kind-Index-OID). Solche Indizes dürfen NICHT
     * als kind-lokal erfasst werden (sie entstehen beim Apply des Parent-Index neu);
     * der Aufrufer zieht sie von `listIndices` ab, sodass nur kind-lokale bleiben.
     */
    fun listInheritedIndexNames(session: JdbcOperations, schemaName: String, table: String): List<String> {
        return session.queryList(
            """
            SELECT ci.relname AS index_name
            FROM pg_inherits inh
            JOIN pg_class ci ON ci.oid = inh.inhrelid
            JOIN pg_index cix ON cix.indexrelid = ci.oid
            JOIN pg_class ct ON ct.oid = cix.indrelid
            JOIN pg_namespace cn ON cn.oid = ct.relnamespace
            WHERE cn.nspname = ? AND ct.relname = ?
            """.trimIndent(), schemaName, table,
        ).map { it["index_name"] as String }
    }
}
