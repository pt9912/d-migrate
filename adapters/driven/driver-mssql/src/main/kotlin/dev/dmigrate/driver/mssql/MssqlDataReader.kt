package dev.dmigrate.driver.mssql

import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.SqlIdentifiers
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.driver.connection.asJdbc
import dev.dmigrate.driver.data.AbstractJdbcDataReader
import java.sql.Connection

/**
 * MSSQL [dev.dmigrate.driver.data.DataReader] (Slice 3, [ADR 0047]).
 *
 * Streaming: mssql-jdbc puffert Ergebnisse per Default **adaptiv**
 * (`responseBuffering=adaptive`) und liest sie zeilenweise vom TDS-Stream —
 * ein serverseitiger Cursor ist dafür nicht nötig, `setFetchSize` bleibt ein
 * Hinweis. Deshalb auch **keine** offene Transaktion
 * ([needsAutoCommitFalse] = `false`): SQL Server hielte unter
 * READ COMMITTED sonst Shared Locks über den gesamten Export.
 *
 * Geometrie: `geometry`/`geography` liefern über JDBC das SQL-Server-interne
 * UDT-Format. Der Reader projiziert sie deshalb als kanonisches **WKB**
 * (`.STAsBinary()`, OGC-Reihenfolge long-lat — dieselbe wie PostGIS/MySQL mit
 * `axis-order=long-lat`).
 *
 * **SRID:** WKB trägt keine SRID, und in SQL Server ist sie Eigenschaft des
 * *Werts*, nicht der Spalte — es gibt also (anders als bei PostGIS/MySQL)
 * keine Spaltenmetadaten, aus denen der Import sie zurückholen könnte. Sie
 * kommt deshalb aus den Werten selbst ([geometrySrids]).
 */
open class MssqlDataReader(fetchSizeOverride: Int? = null) : AbstractJdbcDataReader() {

    override val dialect: DatabaseDialect = DatabaseDialect.MSSQL

    override fun quoteIdentifier(name: String): String =
        SqlIdentifiers.quoteIdentifier(name, dialect)

    /** LN-005: per `dataReader(fetchSize)` überschreibbar (`null` = dieser Default). */
    override val fetchSize: Int = fetchSizeOverride ?: 1_000

    /** Adaptive Pufferung genügt; eine offene Transaktion würde nur Locks halten. */
    override val needsAutoCommitFalse: Boolean = false

    override val supportsGeometryRead: Boolean = true

    override fun geometryReadExpression(quotedColumn: String): String = "$quotedColumn.STAsBinary()"

    /**
     * Nur die beiden echten Spatial-Typen — SQL Server hat keine gleichnamigen
     * Nicht-Spatial-Typen wie PostgreSQL (`point`/`polygon`/…).
     */
    override fun isGeometryTypeName(typeNameLower: String): Boolean =
        typeNameLower == "geometry" || typeNameLower == "geography"

    /**
     * mssql-jdbc liefert `DATETIMEOFFSET` als treibereigenes
     * `microsoft.sql.DateTimeOffset`. Der neutrale Chunk-Strom soll nur
     * Standardtypen tragen: sonst ist der Wert weder in der `--verify`-
     * Kanonisierung (LN-009) noch beim Export sauber behandelbar — belegt vom
     * Pagila-PG→MSSQL-Smoke, wo jede Tabelle mit `timestamptz` als
     * „inconclusive" endete.
     */
    override fun mapValue(value: Any?, conn: java.sql.Connection): Any? = when (value) {
        is microsoft.sql.DateTimeOffset -> value.offsetDateTime
        else -> value
    }

    /**
     * Liest je Spalte die vorkommenden Bezugssysteme aus den Werten.
     *
     * Ein voller `DISTINCT`-Durchlauf, kein `TOP (1)`: die erste Zeile
     * verriete nur, dass es *eine* SRID gibt, nicht dass es *nur* diese gibt.
     * Eine Spalte mit gemischten Bezugssystemen sähe damit einheitlich aus
     * und käme am Ziel geschlossen im falschen System an — genau der stumme
     * Schaden, den die Übertragung vermeiden soll. Der Durchlauf liest eine
     * `int`-Projektion einer Spalte und wiegt gegen den Transfer selbst,
     * der gleich alle Spalten aller Zeilen liest, wenig.
     */
    override fun geometrySrids(
        pool: ConnectionPool,
        table: String,
        columns: List<String>,
    ): Map<String, List<Int>> {
        if (columns.isEmpty()) return emptyMap()
        val path = quoteTablePath(table)
        return pool.borrow().asJdbc().use { conn ->
            columns.associateWith { column -> distinctSrids(conn, path, column) }
                .filterValues { it.isNotEmpty() }
        }
    }

    private fun distinctSrids(conn: Connection, quotedTablePath: String, column: String): List<Int> {
        val quoted = quoteIdentifier(column)
        val sql = "SELECT DISTINCT $quoted.STSrid AS srid FROM $quotedTablePath WHERE $quoted IS NOT NULL"
        return conn.createStatement().use { stmt ->
            stmt.executeQuery(sql).use { rs ->
                buildList {
                    while (rs.next()) {
                        val srid = rs.getInt("srid")
                        if (!rs.wasNull()) add(srid)
                    }
                }
            }
        }.sorted()
    }
}
