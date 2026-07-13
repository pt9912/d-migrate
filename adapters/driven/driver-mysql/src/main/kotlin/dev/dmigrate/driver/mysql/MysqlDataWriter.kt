package dev.dmigrate.driver.mysql

import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.driver.connection.asJdbc
import dev.dmigrate.driver.data.DataWriter
import dev.dmigrate.driver.data.ImportOptions
import dev.dmigrate.driver.data.OnConflict
import dev.dmigrate.driver.data.TableImportSession
import dev.dmigrate.driver.data.TargetColumn
import dev.dmigrate.driver.data.TriggerMode
import dev.dmigrate.driver.data.runSuppressing
import dev.dmigrate.driver.metadata.JdbcMetadataSession
import dev.dmigrate.driver.metadata.JdbcOperations
import java.sql.Connection

class MysqlDataWriter(
    private val jdbcFactory: (Connection) -> JdbcOperations = ::JdbcMetadataSession,
) : DataWriter {

    override val dialect: DatabaseDialect = DatabaseDialect.MYSQL

    override fun schemaSync() = MysqlSchemaSync(jdbcFactory)

    // LN-013: Clean-Load-Kompensation für --atomic. MySQL kann FKs nicht per
    // CASCADE truncaten → FK-Checks aus, DELETE je Tabelle, FK-Checks wieder an.
    override fun truncateTables(pool: ConnectionPool, tables: List<String>) {
        if (tables.isEmpty()) return
        pool.borrow().use { handle ->
            val conn = handle.asJdbc()
            val jdbc = jdbcFactory(conn)
            val savedAutoCommit = conn.autoCommit
            try {
                conn.autoCommit = true
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0")
                try {
                    for (t in tables) jdbc.execute("DELETE FROM ${parseMysqlQualifiedTableName(t).quotedPath()}")
                } finally {
                    jdbc.execute("SET FOREIGN_KEY_CHECKS = 1")
                }
            } finally {
                conn.autoCommit = savedAutoCommit
            }
        }
    }

    override fun openTable(
        pool: ConnectionPool,
        table: String,
        options: ImportOptions,
    ): TableImportSession {
        check(options.triggerMode == TriggerMode.FIRE) {
            "triggerMode=${options.triggerMode} is not supported for MySQL — " +
                "the Runner should have validated this via DialectCapabilities"
        }

        val conn = pool.borrow().asJdbc()
        val jdbc = jdbcFactory(conn)
        val sync = schemaSync()
        val qualified = parseMysqlQualifiedTableName(table)
        var savedAutoCommit: Boolean? = null
        var fkChecksDisabled = false
        try {
            savedAutoCommit = conn.autoCommit
            val lowerCaseSetting = lowerCaseTableNames(conn)
            val targetColumns = enrichGeometrySrid(
                jdbc, conn, qualified, lowerCaseSetting, loadTargetColumns(conn, qualified),
            )
            val primaryKeyColumns = if (options.onConflict == OnConflict.UPDATE) {
                loadPrimaryKeyColumns(conn, qualified, lowerCaseSetting).also {
                    require(it.isNotEmpty()) {
                        "Target table '$table' has no primary key; onConflict=update requires a primary key"
                    }
                }
            } else {
                emptyList()
            }

            if (options.disableFkChecks) {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0")
                fkChecksDisabled = true
            }

            // §6.14 non-atomic truncate: DELETE FROM before the import
            // transaction so the table stays empty even on failure.
            if (options.truncate) {
                if (!conn.autoCommit) conn.autoCommit = true
                jdbc.execute("DELETE FROM ${qualified.quotedPath()}")
            }

            conn.autoCommit = false

            val session = MysqlTableImportSession(
                conn = conn,
                savedAutoCommit = savedAutoCommit,
                table = table,
                qualifiedTable = qualified,
                targetColumns = targetColumns,
                primaryKeyColumns = primaryKeyColumns,
                options = options,
                schemaSync = sync,
                fkChecksDisabled = fkChecksDisabled,
            )
            if (options.truncate) {
                session.markTruncatePerformed()
            }
            return session
        } catch (t: Throwable) {
            t.runSuppressing { if (!conn.autoCommit) conn.rollback() }
            t.runSuppressing { if (savedAutoCommit != null) conn.autoCommit = savedAutoCommit }
            t.runSuppressing { if (fkChecksDisabled) jdbc.execute("SET FOREIGN_KEY_CHECKS = 1") }
            t.runSuppressing { conn.close() }
            throw t
        }
    }

    private fun loadTargetColumns(
        conn: Connection,
        table: MysqlQualifiedTableName,
    ): List<TargetColumn> = dev.dmigrate.driver.data.loadTargetColumns(conn, table.quotedPath())

    /**
     * VA2 (Spatial): reichert Geometrie-Zielspalten mit ihrer SRID aus
     * `information_schema.columns.srs_id` an, damit der Import WKB als
     * `ST_GeomFromWKB(?, srid)` bindet statt SRID 0 zu erzwingen. NULL/0
     * (= keine SRID-Bindung) bleibt `null`.
     */
    private fun enrichGeometrySrid(
        jdbc: JdbcOperations,
        conn: Connection,
        table: MysqlQualifiedTableName,
        lowerCaseSetting: Int,
        columns: List<TargetColumn>,
    ): List<TargetColumn> {
        val schema = table.metadataSchema(conn, lowerCaseSetting)
        val metaTable = table.metadataTable(lowerCaseSetting)
        val sridByColumn = MysqlMetadataQueries.listColumns(jdbc, schema, metaTable)
            .mapNotNull { row ->
                val name = row["column_name"] as? String ?: return@mapNotNull null
                val srid = (row["srs_id"] as? Number)?.toInt()?.takeIf { it != 0 } ?: return@mapNotNull null
                name to srid
            }.toMap()
        if (sridByColumn.isEmpty()) return columns
        return columns.map { col -> sridByColumn[col.name]?.let { col.copy(srid = it) } ?: col }
    }

    private fun loadPrimaryKeyColumns(
        conn: Connection,
        table: MysqlQualifiedTableName,
        lowerCaseSetting: Int,
    ): List<String> {
        val rows = mutableListOf<Pair<Short, String>>()
        conn.metaData.getPrimaryKeys(
            conn.catalog,
            table.metadataSchema(conn, lowerCaseSetting),
            table.metadataTable(lowerCaseSetting),
        ).use { rs ->
            while (rs.next()) {
                rows += rs.getShort("KEY_SEQ") to rs.getString("COLUMN_NAME")
            }
        }
        return rows.sortedBy { it.first }.map { it.second }
    }

    internal companion object {
        internal fun setForeignKeyChecks(
            conn: Connection,
            enabled: Boolean,
        ) {
            conn.createStatement().use { stmt ->
                stmt.execute("SET FOREIGN_KEY_CHECKS = ${if (enabled) 1 else 0}")
            }
        }
    }
}
