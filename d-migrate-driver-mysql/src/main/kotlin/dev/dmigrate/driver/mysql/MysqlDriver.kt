package dev.dmigrate.driver.mysql

import dev.dmigrate.driver.connection.JdbcUrlBuilderRegistry
import dev.dmigrate.driver.data.DataReaderRegistry

/**
 * Bootstrap für den MySQL-Treiber.
 *
 * Siehe [dev.dmigrate.driver.postgresql.PostgresDriver] für die ausführliche
 * Erklärung des Bootstrap-Modells und warum es keine automatische
 * Self-Registration beim Klassenladen gibt.
 */
object MysqlDriver {

    /** Registriert alle MySQL-Komponenten. Idempotent. */
    fun register() {
        JdbcUrlBuilderRegistry.register(MysqlJdbcUrlBuilder())
        DataReaderRegistry.registerDataReader(MysqlDataReader())
        DataReaderRegistry.registerTableLister(MysqlTableLister())
    }
}
