package dev.dmigrate.driver.postgresql

import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.SqlIdentifiers
import dev.dmigrate.driver.data.AbstractJdbcDataReader

/**
 * PostgreSQL [dev.dmigrate.driver.data.DataReader].
 *
 * LF-008 / LN-009 / LN-010: PostgreSQL-Spezifika:
 * - Cursor-basiertes Streaming via `Statement#setFetchSize(N)` +
 *   `setAutoCommit(false)` — beides setzt der [AbstractJdbcDataReader]
 *   automatisch, weil [needsAutoCommitFalse] hier `true` bleibt.
 * - Quoting: doppelte Anführungszeichen, Escape eingebetteter Quotes.
 * - `application_name=d-migrate` wird zentral in
 *   [dev.dmigrate.driver.connection.HikariConnectionPoolFactory] über die
 *   `ApplicationName`-URL-Property gesetzt.
 *
 * Tests fuer den Live-DB-Pfad leben in `:test:integration-postgresql`
 * und laufen unter `-PintegrationTests` gegen einen Testcontainers-
 * PostgreSQL — siehe `.github/workflows/integration.yml`.
 */
class PostgresDataReader : AbstractJdbcDataReader() {

    override val dialect: DatabaseDialect = DatabaseDialect.POSTGRESQL

    override fun quoteIdentifier(name: String): String =
        SqlIdentifiers.quoteIdentifier(name, dialect)

    /** Standard-Cursor-fetchSize. Empirisch guter Wert für PostgreSQL JDBC. */
    override val fetchSize: Int = 1_000

    /** PostgreSQL braucht zwingend `setAutoCommit(false)` für Cursor-Streaming. */
    override val needsAutoCommitFalse: Boolean = true

    /**
     * VA1b (Spatial-Slice): PostGIS-Geometriespalten auf dem Read-Pfad als
     * kanonisches **WKB** projizieren (`ST_AsBinary`, OGC-Standard). Bewusst NICHT
     * EWKB: EWKB trägt zwar die SRID, ist aber **nicht cross-dialect-tauglich**
     * (MySQL `ST_GeomFromWKB` versteht das EWKB-SRID-Flag nicht). Das Transfer-
     * Format ist damit einheitlich WKB für PG **und** MySQL; SRID-Erhalt läuft
     * separat über den Reverse-Pfad (VA2) und das Ziel-Binding (VA1c).
     */
    override val supportsGeometryRead: Boolean = true

    override fun geometryReadExpression(quotedColumn: String): String = "ST_AsBinary($quotedColumn)"
}
