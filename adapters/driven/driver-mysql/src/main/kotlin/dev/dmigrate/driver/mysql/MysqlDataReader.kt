package dev.dmigrate.driver.mysql

import dev.dmigrate.core.model.GeometryType
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.SqlIdentifiers
import dev.dmigrate.driver.data.AbstractJdbcDataReader

/**
 * MySQL [dev.dmigrate.driver.data.DataReader].
 *
 * LF-008 / LN-009 / LN-010: MySQL-Spezifika fuer verlustfreies,
 * konsistentes Export-Streaming:
 * - **Streaming-Strategie**: serverseitiger Cursor via `useCursorFetch=true`
 *   (gesetzt in [dev.dmigrate.driver.connection.HikariConnectionPoolFactory]
 *   als Default in der JDBC-URL) + realer `fetchSize`. Bewusste Wahl gegen
 *   das alte `Statement.setFetchSize(Integer.MIN_VALUE)`-Idiom:
 *   - row-by-row Protokoll-Overhead bei MIN_VALUE
 *   - inkompatibel mit langlaufenden HikariCP-Connections
 *   - in Connector/J 9.x ist `useCursorFetch` der dokumentierte Default-Pfad
 * - Quoting: Backticks, mit Backtick-Escape.
 * - `setAutoCommit(false)` ist mit `useCursorFetch` nicht zwingend nötig —
 *   der serverseitige Cursor steht für sich. Wir lassen es auf `false`,
 *   damit ein evtl. konsistenter Snapshot über den Stream hinweg möglich ist.
 *
 * Tests fuer den Live-DB-Pfad leben in `:test:integration-mysql`
 * und laufen unter `-PintegrationTests` gegen einen Testcontainers-
 * MySQL — siehe `.github/workflows/integration.yml`.
 */
class MysqlDataReader(fetchSizeOverride: Int? = null) : AbstractJdbcDataReader() {

    override val dialect: DatabaseDialect = DatabaseDialect.MYSQL

    override fun quoteIdentifier(name: String): String =
        SqlIdentifiers.quoteIdentifier(name, dialect)

    /**
     * LF-008 / LN-010: Tuning fuer serverseitigen Cursor.
     * LN-005: per `dataReader(fetchSize)` überschreibbar (`null` = dieser Default).
     */
    override val fetchSize: Int = fetchSizeOverride ?: 1_000

    /** Konsistenter Snapshot über den Stream hinweg. */
    override val needsAutoCommitFalse: Boolean = true

    /**
     * VA1b (Spatial-Slice): MySQL-native Geometriespalten auf dem Read-Pfad als
     * kanonisches **WKB** projizieren (`ST_AsBinary`, OGC-Standard). MySQL kennt
     * kein EWKB — die SRID wird hier nicht mitkodiert; SRID-Erhalt ist Sache des
     * Reverse-Pfads (VA2) bzw. des Ziel-Bindings (VA1c).
     *
     * VA2-X1 (Cross-Dialect-Achsenreihenfolge): `axis-order=long-lat` erzwingt OGC-
     * X/Y-Reihenfolge im WKB. Ohne diese Option nutzt MySQL für geografische SRS
     * (z. B. 4326) die SRS-definierte **lat-long**-Reihenfolge — PostGIS schreibt/
     * liest aber **long-lat**. Ein Cross-Dialect-Transfer vertauschte sonst die
     * Achsen (datenbelegt: München → vertauscht), bei gleicher `ST_AsText`-Ausgabe
     * (False-Green). Bei SRID 0 (kartesisch) ist die Option unschädlich (no-op).
     */
    override val supportsGeometryRead: Boolean = true

    override fun geometryReadExpression(quotedColumn: String): String =
        "ST_AsBinary($quotedColumn, 'axis-order=long-lat')"

    /**
     * MySQL hat keine nativen Nicht-Spatial-Typen namens point/polygon/…: alle
     * OGC-Geometrie-Typnamen sind echte, WKB-fähige Spatial-Typen.
     */
    override fun isGeometryTypeName(typeNameLower: String): Boolean =
        typeNameLower in GeometryType.KNOWN_VALUES
}
