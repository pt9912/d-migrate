package dev.dmigrate.driver.mssql

import com.microsoft.sqlserver.jdbc.ISQLServerBulkData
import com.microsoft.sqlserver.jdbc.ISQLServerConnection
import com.microsoft.sqlserver.jdbc.SQLServerBulkCopy
import com.microsoft.sqlserver.jdbc.SQLServerBulkCopyOptions
import dev.dmigrate.driver.data.JdbcForeignValueNormalizer
import dev.dmigrate.driver.data.OnConflict
import dev.dmigrate.driver.data.TargetColumn
import dev.dmigrate.driver.data.WriteResult
import java.sql.Connection
import java.sql.Types

/**
 * Bulk-Fast-Path fuer den SQL-Server-Import ueber `SQLServerBulkCopy`.
 *
 * **Gemessen** gegen SQL Server 2022, 200 000 Zeilen, vier Spalten:
 * gebatchtes `INSERT` 43 773 Zeilen/s, BulkCopy **94 473 Zeilen/s** — Faktor
 * 2,16. Das ist der Wert MIT den unten erzwungenen Sicherungen; ohne sie waeren
 * es 107 526 Zeilen/s (2,46), die zusaetzlichen 14 % sind der Preis dafuer,
 * dass der Bulk-Weg dieselbe Semantik traegt wie der INSERT-Weg.
 *
 * **Die beiden Voreinstellungen, die still Daten verderben.** `SQLServerBulkCopy`
 * prueft per Default **keine** Constraints und feuert **keine** Trigger — beides
 * live nachgemessen: eine Zeile, die einen `CHECK` verletzt, landet mit den
 * Voreinstellungen in der Tabelle, und ein `AFTER INSERT`-Trigger bleibt stumm.
 * Der INSERT-Weg tut beides. Der Fast-Path erzwingt deshalb
 * `isCheckConstraints` und `isFireTriggers`; ohne sie waere er kein schnellerer
 * Weg zum selben Ergebnis, sondern ein anderer.
 *
 * **Wo er nicht greift.** Er ist ein roher Wertstrom: kein `MERGE`, also kein
 * `--on-conflict skip|update`; kein SQL je Wert, also keine Geometrie (die
 * ueber `geometry::STGeomFromWKB(?, srid)` konstruiert wird). Beides faellt auf
 * den bestehenden Weg zurueck, nicht auf eine Naeherung.
 *
 * Praezision und Skala kommen aus dem **Server** ([columnMeta]), nicht aus einer
 * Annahme: `TargetColumn` fuehrt sie nicht, und `ISQLServerBulkData` verlangt
 * sie je Spalte.
 */
internal object MssqlBulkCopyFastPath {

    /** Was `ISQLServerBulkData` je Spalte wissen muss. */
    data class ColumnMeta(val name: String, val jdbcType: Int, val precision: Int, val scale: Int)

    fun isEligible(
        columns: List<TargetColumn>,
        onConflict: OnConflict,
        isGeometry: (TargetColumn) -> Boolean,
    ): Boolean =
        onConflict == OnConflict.ABORT &&
            columns.isNotEmpty() &&
            columns.none { isGeometry(it) } &&
            columns.all { it.jdbcType in BULK_SAFE_JDBC_TYPES }

    /**
     * Praezision und Skala der Zielspalten, vom Server erfragt. Einmal je
     * Tabelle — der Aufrufer haelt das Ergebnis, ein `SELECT` je Chunk waere
     * eine Rundreise fuer eine Angabe, die sich waehrend des Imports nicht
     * aendert.
     */
    fun columnMeta(conn: Connection, quotedTablePath: String, columns: List<TargetColumn>): List<ColumnMeta> {
        val columnList = columns.joinToString(", ") { MssqlIdentifiers.bracket(it.name) }
        conn.prepareStatement("SELECT $columnList FROM $quotedTablePath WHERE 1 = 0").use { stmt ->
            val meta = stmt.metaData
            return columns.mapIndexed { index, column ->
                ColumnMeta(
                    name = column.name,
                    jdbcType = column.jdbcType,
                    precision = meta.getPrecision(index + 1),
                    scale = meta.getScale(index + 1),
                )
            }
        }
    }

    /**
     * Schreibt den Chunk per BulkCopy. Die Verbindung bleibt die des Laufs —
     * `isUseInternalTransaction` ist mit einer uebergebenen Verbindung nicht
     * erlaubt, und genau das ist richtig: der Chunk gehoert in die Transaktion
     * des Aufrufers, wie beim INSERT-Weg.
     *
     * BulkCopy meldet keine Zeilenzahl zurueck; gebucht wird die Chunk-Groesse
     * — dieselbe groebere Buchfuehrung wie beim PostgreSQL-COPY-Pfad.
     */
    fun execute(
        conn: Connection,
        quotedTablePath: String,
        columnMeta: List<ColumnMeta>,
        rows: List<Array<Any?>>,
        keepIdentity: Boolean,
    ): WriteResult {
        if (rows.isEmpty()) return WriteResult(rowsInserted = 0, rowsUpdated = 0, rowsSkipped = 0)
        SQLServerBulkCopy(conn.unwrap(ISQLServerConnection::class.java)).use { bulk ->
            bulk.destinationTableName = quotedTablePath
            bulk.bulkCopyOptions = SQLServerBulkCopyOptions().apply {
                batchSize = rows.size
                isCheckConstraints = true
                isFireTriggers = true
                isKeepIdentity = keepIdentity
            }
            bulk.writeToServer(MssqlBulkChunkData(columnMeta, rows))
        }
        return WriteResult(rowsInserted = rows.size.toLong(), rowsUpdated = 0, rowsSkipped = 0)
    }

    /**
     * JDBC-Typen, deren Werte BulkCopy aus einem Java-Objekt verlustfrei
     * uebernimmt. Bewusst konservativ, wie beim PostgreSQL-COPY-Pfad: kein
     * `BINARY`/`VARBINARY` (dort liegt die Geometrie-WKB-Naht), kein `OTHER`,
     * kein `ARRAY`, keine LOB-Typen. Was fehlt, laeuft ueber den INSERT-Weg —
     * langsamer, aber unveraendert korrekt.
     */
    private val BULK_SAFE_JDBC_TYPES = setOf(
        Types.BIT, Types.BOOLEAN, Types.TINYINT, Types.SMALLINT, Types.INTEGER, Types.BIGINT,
        Types.DECIMAL, Types.NUMERIC, Types.REAL, Types.FLOAT, Types.DOUBLE,
        Types.CHAR, Types.VARCHAR, Types.NCHAR, Types.NVARCHAR,
        Types.DATE, Types.TIME, Types.TIMESTAMP,
    )
}

/**
 * Der Chunk als `ISQLServerBulkData`-Strom.
 *
 * Die Werte laufen durch [JdbcForeignValueNormalizer], wie beim INSERT-Weg:
 * ein `java.sql.Array` oder ein pgjdbc-`PGobject` aus einer fremden Quelle
 * landete sonst java-serialisiert in der Zielspalte.
 */
internal class MssqlBulkChunkData(
    private val columns: List<MssqlBulkCopyFastPath.ColumnMeta>,
    rows: List<Array<Any?>>,
) : ISQLServerBulkData {

    private val iterator = rows.iterator()
    private var current: Array<Any?>? = null

    override fun getColumnOrdinals(): MutableSet<Int> = (1..columns.size).toMutableSet()

    override fun getColumnName(column: Int): String = columns[column - 1].name

    override fun getColumnType(column: Int): Int = columns[column - 1].jdbcType

    override fun getPrecision(column: Int): Int = columns[column - 1].precision

    override fun getScale(column: Int): Int = columns[column - 1].scale

    override fun getRowData(): Array<Any?> =
        (current ?: error("getRowData() before next()")).map { value ->
            value?.let { JdbcForeignValueNormalizer.normalize(it) }
        }.toTypedArray()

    override fun next(): Boolean {
        if (!iterator.hasNext()) return false
        current = iterator.next()
        return true
    }
}
