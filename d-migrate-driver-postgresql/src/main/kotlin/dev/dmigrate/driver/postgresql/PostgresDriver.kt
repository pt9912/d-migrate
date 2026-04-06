package dev.dmigrate.driver.postgresql

import dev.dmigrate.driver.connection.JdbcUrlBuilderRegistry
import dev.dmigrate.driver.data.DataReaderRegistry

/**
 * Bootstrap für den PostgreSQL-Treiber.
 *
 * Registriert in einem Aufruf alle drei Phase-B-Komponenten in den globalen
 * Registries:
 * - [PostgresJdbcUrlBuilder] in [JdbcUrlBuilderRegistry]
 * - [PostgresDataReader] in [DataReaderRegistry]
 * - [PostgresTableLister] in [DataReaderRegistry]
 *
 * **Nutzung**:
 * - **Phase B (heute)**: Tests rufen [register] explizit auf, um die
 *   Komponenten für die Test-JVM bereitzustellen.
 * - **Phase E (CLI-Integration)**: Der CLI-Bootstrap (Main.kt) ruft
 *   `PostgresDriver.register()`, `MysqlDriver.register()`,
 *   `SqliteDriver.register()` einmalig vor dem ersten Command-Dispatch auf.
 *
 * Es gibt **keine automatische Self-Registration** beim Klassenladen — Kotlin
 * lädt Klassen erst, wenn sie referenziert werden, und der HikariConnection-
 * PoolFactory referenziert die Treiber-Klassen aus Architektur-Gründen nicht
 * direkt. ServiceLoader-basierte Auto-Discovery folgt in 0.6.0 mit den
 * externen Treiber-JARs.
 */
object PostgresDriver {

    /** Registriert alle PostgreSQL-Komponenten. Idempotent. */
    fun register() {
        JdbcUrlBuilderRegistry.register(PostgresJdbcUrlBuilder())
        DataReaderRegistry.registerDataReader(PostgresDataReader())
        DataReaderRegistry.registerTableLister(PostgresTableLister())
    }
}
