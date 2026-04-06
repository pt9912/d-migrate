package dev.dmigrate.driver.sqlite

import dev.dmigrate.driver.connection.JdbcUrlBuilderRegistry
import dev.dmigrate.driver.data.DataReaderRegistry

/**
 * Bootstrap für den SQLite-Treiber.
 *
 * Siehe [dev.dmigrate.driver.postgresql.PostgresDriver] für die ausführliche
 * Erklärung des Bootstrap-Modells.
 */
object SqliteDriver {

    /** Registriert alle SQLite-Komponenten. Idempotent. */
    fun register() {
        JdbcUrlBuilderRegistry.register(SqliteJdbcUrlBuilder())
        DataReaderRegistry.registerDataReader(SqliteDataReader())
        DataReaderRegistry.registerTableLister(SqliteTableLister())
    }
}
