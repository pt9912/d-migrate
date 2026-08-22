package dev.dmigrate.driver.mssql

import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.driver.connection.asJdbc
import dev.dmigrate.driver.data.DataWriter
import dev.dmigrate.driver.data.ImportOptions
import dev.dmigrate.driver.data.OnConflict
import dev.dmigrate.driver.data.TableImportSession
import dev.dmigrate.driver.data.TriggerMode
import dev.dmigrate.driver.data.loadTargetColumns
import dev.dmigrate.driver.data.runSuppressing
import dev.dmigrate.driver.metadata.JdbcMetadataSession
import dev.dmigrate.driver.metadata.JdbcOperations
import java.sql.Connection

/**
 * MSSQL [DataWriter] (Slice 3, [ADR 0047]).
 *
 * Eigenheiten gegenüber den anderen Dialekten:
 * - **SET-Optionen**: jede Import-Session setzt `ARITHABORT`, `QUOTED_IDENTIFIER`
 *   & Co. explizit. SQL Server verlangt sie für DML auf Tabellen mit gefiltertem
 *   Index; der JDBC-Treiber verbindet sich per Default mit `ARITHABORT OFF`
 *   (dieselbe Klasse Fehler wie beim Skript-Apply, siehe
 *   `DialectCapabilities.scriptPreamble`).
 * - **IDENTITY**: enthält der Chunk die IDENTITY-Spalte, schaltet die Session
 *   `SET IDENTITY_INSERT <table> ON` (sonst lehnt SQL Server explizite Werte ab)
 *   und am Ende wieder aus.
 * - **Konflikte**: T-SQL kennt kein `INSERT IGNORE`/`ON CONFLICT`; `skip` und
 *   `update` laufen über `MERGE … OUTPUT $action` (siehe [MssqlTableImportSession]).
 * - **FK-Checks** lassen sich nicht global abschalten (`supportsDisableFkChecks`
 *   = false, wie PostgreSQL); `truncateTables` setzt sie tabellenweise aus.
 */
class MssqlDataWriter(
    private val jdbcFactory: (Connection) -> JdbcOperations = ::JdbcMetadataSession,
) : DataWriter {

    override val dialect: DatabaseDialect = DatabaseDialect.MSSQL

    override fun schemaSync() = MssqlSchemaSync(jdbcFactory)

    /**
     * LN-013 Clean-Load-Kompensation: FK-Constraints tabellenweise aussetzen,
     * leeren, wieder scharf schalten. `TRUNCATE TABLE` scheidet aus — SQL Server
     * verbietet es für referenzierte Tabellen, auch mit ausgesetztem Constraint.
     */
    override fun truncateTables(pool: ConnectionPool, tables: List<String>) {
        if (tables.isEmpty()) return
        pool.borrow().use { handle ->
            val conn = handle.asJdbc()
            val jdbc = jdbcFactory(conn)
            val savedAutoCommit = conn.autoCommit
            val schema = MssqlQualifiedTableName.defaultSchema(jdbc)
            val qualified = tables.map { MssqlQualifiedTableName.parse(it, schema) }
            try {
                conn.autoCommit = true
                for (t in qualified) jdbc.execute("ALTER TABLE ${t.quotedPath()} NOCHECK CONSTRAINT ALL")
                deleteAndRecheck(jdbc, qualified)
            } finally {
                conn.autoCommit = savedAutoCommit
            }
        }
    }

    /**
     * Leert die Tabellen und schaltet die Constraints **einzeln** wieder scharf:
     * ein Fehler beim Re-Enable darf die restlichen Tabellen nicht dauerhaft mit
     * `NOCHECK` zurücklassen und den ursprünglichen Fehler nicht verdecken.
     */
    private fun deleteAndRecheck(jdbc: JdbcOperations, tables: List<MssqlQualifiedTableName>) {
        var failure: Throwable? = null
        try {
            for (t in tables) jdbc.execute("DELETE FROM ${t.quotedPath()}")
        } catch (t: Throwable) {
            failure = t
        }
        for (t in tables) {
            runCatching { jdbc.execute("ALTER TABLE ${t.quotedPath()} WITH CHECK CHECK CONSTRAINT ALL") }
                .onFailure { reenable ->
                    failure?.addSuppressed(reenable) ?: run { failure = reenable }
                }
        }
        failure?.let { throw it }
    }

    override fun openTable(
        pool: ConnectionPool,
        table: String,
        options: ImportOptions,
    ): TableImportSession {
        check(options.triggerMode == TriggerMode.FIRE) {
            "triggerMode=${options.triggerMode} is not supported for SQL Server — " +
                "the Runner should have validated this via DialectCapabilities"
        }
        check(!options.disableFkChecks) {
            "disableFkChecks is not supported for SQL Server — " +
                "the Runner should have validated this via DialectCapabilities"
        }

        val conn = pool.borrow().asJdbc()
        val jdbc = jdbcFactory(conn)
        var savedAutoCommit: Boolean? = null
        try {
            savedAutoCommit = conn.autoCommit
            jdbc.execute(SESSION_SET_OPTIONS)
            val qualified = MssqlQualifiedTableName.parse(table, MssqlQualifiedTableName.defaultSchema(jdbc))
            val targetColumns = loadTargetColumns(conn, qualified.quotedPath(), EMPTY_ROWS_CLAUSE)
            val identityColumns = MssqlMetadataQueries.identityColumns(jdbc, qualified.quotedPath())
            val computedColumns = MssqlMetadataQueries.computedColumns(jdbc, qualified.quotedPath())
            val primaryKeyColumns = if (options.onConflict == OnConflict.ABORT) {
                emptyList()
            } else {
                MssqlMetadataQueries.listPrimaryKeyColumns(jdbc, qualified.quotedPath()).also {
                    require(it.isNotEmpty()) {
                        "Target table '$table' has no primary key; " +
                            "onConflict=${options.onConflict.name.lowercase()} requires a primary key on SQL Server"
                    }
                }
            }

            // §6.14 nicht-atomares Truncate: vor der Import-Transaktion, damit die
            // Tabelle auch bei einem späteren Fehler leer bleibt.
            if (options.truncate) {
                if (!conn.autoCommit) conn.autoCommit = true
                jdbc.execute("DELETE FROM ${qualified.quotedPath()}")
            }

            conn.autoCommit = false

            val session = MssqlTableImportSession(
                conn = conn,
                savedAutoCommit = savedAutoCommit,
                table = table,
                qualifiedTable = qualified,
                targetColumns = targetColumns,
                primaryKeyColumns = primaryKeyColumns,
                identityColumns = identityColumns,
                computedColumns = computedColumns,
                options = options,
                jdbc = jdbc,
                schemaSync = MssqlSchemaSync(jdbcFactory),
            )
            if (options.truncate) session.markTruncatePerformed()
            return session
        } catch (t: Throwable) {
            t.runSuppressing { if (!conn.autoCommit) conn.rollback() }
            t.runSuppressing { if (savedAutoCommit != null) conn.autoCommit = savedAutoCommit }
            t.runSuppressing { conn.close() }
            throw t
        }
    }

    internal companion object {
        /** T-SQL kennt kein `LIMIT`; die Metadaten-Probe läuft über ein leeres Prädikat. */
        const val EMPTY_ROWS_CLAUSE = "WHERE 1 = 0"

        /**
         * SET-Optionen, die SQL Server für DML auf Tabellen mit gefiltertem Index
         * verlangt. mssql-jdbc setzt `ARITHABORT` per Default auf OFF; ohne diese
         * Zeilen scheitert ein INSERT auf so einer Tabelle mit Msg 1934.
         */
        val SESSION_SET_OPTIONS: String = listOf(
            "SET ANSI_NULLS ON",
            "SET ANSI_PADDING ON",
            "SET ANSI_WARNINGS ON",
            "SET ARITHABORT ON",
            "SET CONCAT_NULL_YIELDS_NULL ON",
            "SET NUMERIC_ROUNDABORT OFF",
            "SET QUOTED_IDENTIFIER ON",
        ).joinToString(";\n")

        fun setIdentityInsert(jdbc: JdbcOperations, table: MssqlQualifiedTableName, enabled: Boolean) {
            jdbc.execute("SET IDENTITY_INSERT ${table.quotedPath()} ${if (enabled) "ON" else "OFF"}")
        }
    }
}
