package dev.dmigrate.driver.sqlite

import dev.dmigrate.core.model.GeometryType
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.SqlIdentifiers
import dev.dmigrate.driver.data.AbstractJdbcDataReader
import java.sql.Connection

/**
 * SQLite [dev.dmigrate.driver.data.DataReader].
 *
 * LF-008 / LN-009 / LN-010: SQLite-Spezifika:
 * - Kein echtes Cursor-Streaming nötig — SQLite hält die DB ohnehin im
 *   Prozess; ein einfacher ResultSet-Iterator reicht.
 * - `setAutoCommit(false)` ist nicht zwingend notwendig, schadet aber auch
 *   nicht — wir lassen den AbstractJdbcDataReader-Default greifen.
 * - Quoting: doppelte Anführungszeichen, identisch zu PostgreSQL.
 */
class SqliteDataReader(fetchSizeOverride: Int? = null) : AbstractJdbcDataReader() {

    override val dialect: DatabaseDialect = DatabaseDialect.SQLITE

    override fun quoteIdentifier(name: String): String =
        SqlIdentifiers.quoteIdentifier(name, dialect)

    /**
     * SQLite hat keinen serverseitigen Cursor — ResultSet wird ohnehin lazy
     * aus der Datei gelesen. Default-fetchSize ist hier nur ein Hint.
     * LN-005: per `dataReader(fetchSize)` überschreibbar (`null` = dieser Default).
     */
    override val fetchSize: Int = fetchSizeOverride ?: 1_000

    override val needsAutoCommitFalse: Boolean = false

    /**
     * SpatiaLite legt Geometrie in einem **eigenen** Binaerformat ab, nicht als
     * WKB — der rohe BLOB einer 2D-Punktspalte misst 60 Bytes, ihr WKB 21. Roh
     * gelesen und in eine PostGIS-Spalte geschrieben ergaebe das keinen Punkt.
     * Der Lesepfad wickelt Geometriespalten deshalb in `ST_AsBinary`.
     *
     * Das geht nur mit geladener Extension, und die haengt an der Verbindung
     * (`?spatialite=true`). Eine gewoehnliche SQLite-Datei mit einer als
     * `POINT` deklarierten Spalte scheiterte sonst an der unbekannten Funktion.
     */
    override fun supportsGeometryRead(conn: Connection): Boolean =
        runCatching {
            conn.createStatement().use { stmt ->
                stmt.executeQuery("SELECT spatialite_version()").use { it.next() }
            }
        }.getOrDefault(false)

    override fun geometryReadExpression(quotedColumn: String): String = "ST_AsBinary($quotedColumn)"

    /**
     * SQLite fuehrt keine Typen, sondern Affinitaeten; der JDBC-Treiber meldet
     * den **deklarierten** Namen aus dem `CREATE TABLE` — bei einer von
     * `AddGeometryColumn` angelegten Spalte also `POINT`, `LINESTRING`, …
     */
    override fun isGeometryTypeName(typeNameLower: String): Boolean =
        typeNameLower in GeometryType.KNOWN_VALUES
}
