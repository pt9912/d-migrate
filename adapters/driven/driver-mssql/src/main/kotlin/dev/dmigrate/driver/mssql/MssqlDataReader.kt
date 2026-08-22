package dev.dmigrate.driver.mssql

import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.SqlIdentifiers
import dev.dmigrate.driver.data.AbstractJdbcDataReader

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
 * **SRID-Grenze:** WKB trägt keine SRID, und in SQL Server ist sie
 * Eigenschaft des *Werts*, nicht der Spalte — es gibt also (anders als bei
 * PostGIS/MySQL) keine Spaltenmetadaten, aus denen der Import sie zurückholen
 * könnte. Übertragene Werte landen deshalb mit dem Spalten-Default des Ziels
 * (`geometry` → 0, `geography` → 4326); abweichende Wert-SRIDs gehen dabei
 * verloren. Dokumentiert in `spec/type-mapping.md` (Abschnitt Spatial);
 * eine SRID-treue Übertragung braucht eine eigene Projektion und ist als
 * Folgearbeit im Slice-Plan vermerkt.
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
}
