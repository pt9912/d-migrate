package dev.dmigrate.driver.oracle

import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.SqlIdentifiers
import dev.dmigrate.driver.data.AbstractJdbcDataReader
import oracle.sql.TIMESTAMPTZ
import java.sql.Blob
import java.sql.Clob
import java.sql.Connection

/**
 * Oracle [dev.dmigrate.driver.data.DataReader] (ADR 0052 Slice 3).
 *
 * Streaming: Oracle-JDBC unterstuetzt echtes Server-Cursor-Fetching ueber
 * `setFetchSize` ohne offene Transaktion -- wie MySQL/SQLite, anders als
 * PostgreSQL ([needsAutoCommitFalse] = `false`).
 *
 * **CLOB/BLOB-Materialisierung**: Oracle-JDBC liefert `CLOB`/`BLOB`-Spalten
 * ueber `getObject()` als live `java.sql.Clob`/`java.sql.Blob`-Locator, nicht
 * als materialisierten `String`/`ByteArray` (anders als die anderen vier
 * Dialekte, deren Treiber Text-/Binaerspalten direkt als Standardtyp
 * melden). Ein solcher Locator ueberlebt die Chunk-Grenze nicht sicher --
 * die Connection koennte laengst zurueckgegeben sein, wenn ein fremder
 * Ziel-Treiber ihn binden will. [mapValue] materialisiert deshalb sofort,
 * waehrend der Cursor noch auf der Zeile steht (Muster wie MSSQLs
 * `mapValue` fuer `DateTimeOffset`).
 *
 * **TIMESTAMP WITH TIME ZONE**: `getObject()` liefert (real gegen den
 * Testcontainer verifiziert, `test/integration-oracle`) das treibereigene
 * `oracle.sql.TIMESTAMPTZ`, nicht `OffsetDateTime`. Die Konvertierung
 * (`TIMESTAMPTZ.offsetDateTimeValue(conn)`) braucht zwingend die
 * Connection (benannte Zeitzonen-Aufloesung) -- deshalb traegt [mapValue]
 * hier (anders als bei MSSQL) einen Connection-Parameter.
 */
open class OracleDataReader(fetchSizeOverride: Int? = null) : AbstractJdbcDataReader() {

    override val dialect: DatabaseDialect = DatabaseDialect.ORACLE

    override fun quoteIdentifier(name: String): String =
        SqlIdentifiers.quoteIdentifier(name, dialect)

    /** LN-005: per `dataReader(fetchSize)` ueberschreibbar (`null` = dieser Default). */
    override val fetchSize: Int = fetchSizeOverride ?: 1_000

    /** Server-Cursor-Fetching genuegt; eine offene Transaktion waere hier nur Ballast. */
    override val needsAutoCommitFalse: Boolean = false

    override fun mapValue(value: Any?, conn: Connection): Any? = when (value) {
        is Clob -> value.materialize()
        is Blob -> value.materialize()
        is TIMESTAMPTZ -> value.offsetDateTimeValue(conn)
        else -> value
    }

    private fun Clob.materialize(): String =
        try {
            getSubString(1, length().toInt())
        } finally {
            runCatching { free() }
        }

    private fun Blob.materialize(): ByteArray =
        try {
            getBytes(1, length().toInt())
        } finally {
            runCatching { free() }
        }
}
