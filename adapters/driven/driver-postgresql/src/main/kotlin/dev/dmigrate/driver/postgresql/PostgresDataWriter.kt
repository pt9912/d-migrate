package dev.dmigrate.driver.postgresql

import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.driver.data.DataWriter
import dev.dmigrate.driver.data.ImportOptions
import dev.dmigrate.driver.data.OnConflict
import dev.dmigrate.driver.data.SchemaSync
import dev.dmigrate.driver.data.TableImportSession
import dev.dmigrate.driver.data.TargetColumn
import dev.dmigrate.driver.data.TriggerMode
import dev.dmigrate.driver.data.runSuppressing
import dev.dmigrate.driver.metadata.JdbcMetadataSession
import dev.dmigrate.driver.metadata.JdbcOperations
import java.sql.Connection

class PostgresDataWriter(
    private val jdbcFactory: (Connection) -> JdbcOperations = ::JdbcMetadataSession,
) : DataWriter {

    override val dialect: DatabaseDialect = DatabaseDialect.POSTGRESQL

    override fun schemaSync(): SchemaSync = PostgresSchemaSync(jdbcFactory)

    override fun openTable(
        pool: ConnectionPool,
        table: String,
        options: ImportOptions,
    ): TableImportSession {
        if (options.disableFkChecks) {
            throw UnsupportedOperationException(
                "PostgreSQL does not support generic disableFkChecks in 0.4.0; " +
                    "use schema ordering or DEFERRABLE constraints instead"
            )
        }
        val conn = pool.borrow()
        val jdbc = jdbcFactory(conn)
        val sync = PostgresSchemaSync(jdbcFactory)
        val qualified = parseQualifiedTableName(table)
        var triggersDisabled = false
        var savedAutoCommit: Boolean? = null
        try {
            savedAutoCommit = conn.autoCommit
            val targetColumns = loadTargetColumns(conn, qualified)
            val generatedAlwaysColumns = loadGeneratedAlwaysColumns(jdbc, conn, qualified)
            val primaryKeyColumns = if (options.onConflict == OnConflict.UPDATE) {
                loadPrimaryKeyColumns(conn, qualified).also {
                    require(it.isNotEmpty()) {
                        "Target table '$table' has no primary key; onConflict=update requires a primary key"
                    }
                }
            } else {
                emptyList()
            }

            // §6.14 non-atomic truncate: runs before the import transaction
            // so the table stays empty even on failure. RESTART IDENTITY
            // resets attached sequences to their start values.
            if (options.truncate) {
                if (!conn.autoCommit) conn.autoCommit = true
                jdbc.execute("TRUNCATE TABLE ${qualified.quotedPath()} RESTART IDENTITY CASCADE")
            }

            conn.autoCommit = false
            when (options.triggerMode) {
                TriggerMode.FIRE -> Unit
                TriggerMode.STRICT -> {
                    sync.assertNoUserTriggers(conn, table)
                    conn.commit()
                }
                TriggerMode.DISABLE -> {
                    sync.disableTriggers(conn, table)
                    triggersDisabled = true
                }
            }

            val session = PostgresTableImportSession(
                conn = conn,
                savedAutoCommit = savedAutoCommit,
                table = table,
                qualifiedTable = qualified,
                targetColumns = targetColumns,
                generatedAlwaysColumns = generatedAlwaysColumns,
                primaryKeyColumns = primaryKeyColumns,
                options = options,
                schemaSync = sync,
                triggersDisabled = triggersDisabled,
            )
            if (options.truncate) {
                session.markTruncatePerformed()
            }
            return session
        } catch (t: Throwable) {
            t.runSuppressing { if (triggersDisabled) sync.enableTriggers(conn, table) }
            t.runSuppressing { conn.rollback() }
            t.runSuppressing { if (savedAutoCommit != null) conn.autoCommit = savedAutoCommit }
            t.runSuppressing { conn.close() }
            throw t
        }
    }

    private fun loadTargetColumns(
        conn: Connection,
        table: QualifiedTableName,
    ): List<TargetColumn> = dev.dmigrate.driver.data.loadTargetColumns(conn, table.quotedPath())

    private fun loadGeneratedAlwaysColumns(
        jdbc: JdbcOperations,
        conn: Connection,
        table: QualifiedTableName,
    ): Set<String> {
        val schema = table.schemaOrCurrent(conn)
        return jdbc.queryList(
            """
            SELECT column_name
            FROM information_schema.columns
            WHERE table_schema = ?
              AND table_name = ?
              AND is_identity = 'YES'
              AND identity_generation = 'ALWAYS'
            """.trimIndent(),
            schema, table.table,
        ).mapTo(mutableSetOf()) { it["column_name"] as String }
    }

    private fun loadPrimaryKeyColumns(
        conn: Connection,
        table: QualifiedTableName,
    ): List<String> {
        val schema = table.schemaOrCurrent(conn)
        val rows = mutableListOf<Pair<Short, String>>()
        conn.metaData.getPrimaryKeys(conn.catalog, schema, table.table).use { rs ->
            while (rs.next()) {
                rows += rs.getShort("KEY_SEQ") to rs.getString("COLUMN_NAME")
            }
        }
        return rows.sortedBy { it.first }.map { it.second }
    }
}
