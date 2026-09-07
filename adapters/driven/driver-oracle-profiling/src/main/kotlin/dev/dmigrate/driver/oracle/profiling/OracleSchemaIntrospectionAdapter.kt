package dev.dmigrate.driver.oracle.profiling

import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.driver.connection.asJdbc
import dev.dmigrate.driver.metadata.JdbcMetadataSession
import dev.dmigrate.driver.metadata.JdbcOperations
import dev.dmigrate.profiling.port.ColumnSchema
import dev.dmigrate.profiling.port.SchemaIntrospectionPort
import dev.dmigrate.profiling.port.TableSchema
import java.sql.Connection

/**
 * Tabellen- und Spalten-Metadaten fuer das Profiling.
 *
 * Ohne `schema`-Angabe gilt das aktuelle Schema der Verbindung, nicht die
 * ganze Datenbank — ein Oracle-Nutzer sieht ueber `ALL_*` auch fremde
 * Schemata, und ein Profiling ueber alle waere eine andere Frage als die
 * gestellte.
 *
 * Ausgeschlossen sind dieselben Objekte wie im Reverse-Pfad: Recycle-Bin,
 * Sekundaerobjekte eines Oracle-Text-Index sowie Materialized Views und ihre
 * Logs. Alle vier stehen als gewoehnliche Zeilen in `ALL_TABLES`; sie
 * mitzuprofilieren hiesse, Oracle-interne Ablagen als Nutzdaten auszuweisen.
 *
 * Die Bedingungen stehen ein zweites Mal in `OracleMetadataQueries
 * .listTableRefs` — die Profiling-Adapter sind von den Treibermodulen
 * unabhaengig, wie bei den vier anderen Dialekten auch. Wer dort eine
 * Ausnahme ergaenzt, ergaenzt sie hier mit; sonst profiliert d-migrate eine
 * Ablage, die sein Reverse zu Recht uebergeht.
 */
class OracleSchemaIntrospectionAdapter(
    private val jdbcFactory: (Connection) -> JdbcOperations = ::JdbcMetadataSession,
) : SchemaIntrospectionPort {

    private inline fun <T> withJdbc(pool: ConnectionPool, block: (JdbcOperations) -> T): T =
        pool.borrow().asJdbc().use { conn -> block(jdbcFactory(conn)) }

    /**
     * Ein `--schema`-Wert wird gross geschrieben, wie Oracle einen
     * unquotierten Bezeichner faltet.
     *
     * `ALL_TABLES.OWNER` traegt den gefalteten Namen; `--schema hr` fand
     * sonst nichts und lieferte einen **leeren Bericht ohne Meldung**. Wer
     * ein Schema meint, das wirklich klein geschrieben angelegt wurde,
     * quotiert es — dann bleibt es stehen.
     */
    private fun ownerOf(schema: String?): String? = when {
        schema == null -> null
        schema.startsWith("\"") && schema.endsWith("\"") && schema.length > 1 ->
            schema.substring(1, schema.length - 1)
        else -> schema.uppercase()
    }

    override fun listTables(pool: ConnectionPool, schema: String?): List<TableSchema> =
        withJdbc(pool) { jdbc ->
            jdbc.queryList(
                """
                SELECT t.owner AS table_schema, t.table_name
                FROM all_tables t
                WHERE t.owner = COALESCE(?, SYS_CONTEXT('USERENV', 'CURRENT_SCHEMA'))
                  AND t.table_name NOT LIKE 'BIN${'$'}%'
                  AND NOT EXISTS (
                    SELECT 1 FROM all_objects o
                    WHERE o.owner = t.owner AND o.object_name = t.table_name
                      AND o.object_type = 'TABLE' AND o.secondary = 'Y'
                  )
                  AND NOT EXISTS (
                    SELECT 1 FROM all_mviews m
                    WHERE m.owner = t.owner AND m.mview_name = t.table_name
                  )
                  AND NOT EXISTS (
                    SELECT 1 FROM all_mview_logs l
                    WHERE l.log_owner = t.owner AND l.log_table = t.table_name
                  )
                ORDER BY t.table_name
                """.trimIndent(),
                ownerOf(schema),
            ).map { row ->
                TableSchema(
                    name = row["table_name"] as String,
                    schema = row["table_schema"]?.toString(),
                )
            }
        }

    override fun listColumns(pool: ConnectionPool, table: String, schema: String?): List<ColumnSchema> =
        withJdbc(pool) { jdbc ->
            val owner = ownerOf(schema)
            val keys = keyColumns(jdbc, table, owner)
            val fkColumns = foreignKeyColumns(jdbc, table, owner)
            jdbc.queryList(
                """
                SELECT column_name, data_type, nullable
                FROM all_tab_columns
                WHERE owner = COALESCE(?, SYS_CONTEXT('USERENV', 'CURRENT_SCHEMA')) AND table_name = ?
                ORDER BY column_id
                """.trimIndent(),
                owner, table,
            ).map { row ->
                val name = row["column_name"] as String
                ColumnSchema(
                    name = name,
                    dbType = row["data_type"] as String,
                    nullable = row["nullable"] == "Y",
                    isPrimaryKey = name in keys.primary,
                    isForeignKey = name in fkColumns,
                    isUnique = name in keys.unique,
                )
            }
        }

    private data class KeyColumns(val primary: Set<String>, val unique: Set<String>)

    /**
     * Am Primaerschluessel haengt jede beteiligte Spalte, auch bei einem
     * zusammengesetzten. `isUnique` ist dagegen eine Aussage ueber die Spalte
     * allein: aus einer zusammengesetzten UNIQUE-Constraint folgt sie nicht,
     * und der Primaerschluessel zaehlt nicht mit — die beiden Eigenschaften
     * sind in allen Dialekten getrennt.
     */
    private fun keyColumns(jdbc: JdbcOperations, table: String, schema: String?): KeyColumns {
        val rows = jdbc.queryList(
            """
            SELECT cc.column_name, c.constraint_type,
                   COUNT(*) OVER (PARTITION BY cc.constraint_name) AS column_count
            FROM all_constraints c
            JOIN all_cons_columns cc
              ON cc.owner = c.owner AND cc.constraint_name = c.constraint_name
            WHERE c.owner = COALESCE(?, SYS_CONTEXT('USERENV', 'CURRENT_SCHEMA'))
              AND c.table_name = ? AND c.constraint_type IN ('P', 'U')
            """.trimIndent(),
            schema, table,
        )
        val primary = rows.filter { it["constraint_type"] == "P" }
            .mapTo(mutableSetOf()) { it["column_name"] as String }
        val unique = rows
            .filter { it["constraint_type"] == "U" && (it["column_count"] as Number).toInt() == 1 }
            .mapTo(mutableSetOf()) { it["column_name"] as String }
        return KeyColumns(primary, unique - primary)
    }

    private fun foreignKeyColumns(jdbc: JdbcOperations, table: String, schema: String?): Set<String> =
        jdbc.queryList(
            """
            SELECT cc.column_name
            FROM all_constraints c
            JOIN all_cons_columns cc
              ON cc.owner = c.owner AND cc.constraint_name = c.constraint_name
            WHERE c.owner = COALESCE(?, SYS_CONTEXT('USERENV', 'CURRENT_SCHEMA'))
              AND c.table_name = ? AND c.constraint_type = 'R'
            """.trimIndent(),
            schema, table,
        ).mapTo(mutableSetOf()) { it["column_name"] as String }
}
