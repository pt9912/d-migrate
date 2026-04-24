package dev.dmigrate.driver

/**
 * Central registry for [DatabaseDriver] instances. Replaces the previous
 * per-concern registries (DataReaderRegistry, JdbcUrlBuilderRegistry).
 *
 * Drivers are registered at startup (see `Main.kt`) and looked up by
 * [DatabaseDialect] at runtime.
 *
 * The registry is the single lookup point for all driver-facing ports:
 * DDL generation, data export, table listing, JDBC URL building, and
 * data import via [DatabaseDriver.dataWriter].
 */
object DatabaseDriverRegistry {

    private val drivers = mutableMapOf<DatabaseDialect, DatabaseDriver>()

    @Synchronized
    fun register(driver: DatabaseDriver) {
        drivers[driver.dialect] = driver
    }

    /**
     * Discovers and registers all [DatabaseDriver] implementations
     * available on the classpath via [java.util.ServiceLoader].
     */
    @Synchronized
    fun loadAll() {
        for (driver in java.util.ServiceLoader.load(DatabaseDriver::class.java)) {
            drivers[driver.dialect] = driver
        }
    }

    @Synchronized
    fun get(dialect: DatabaseDialect): DatabaseDriver =
        drivers[dialect]
            ?: throw IllegalArgumentException(
                "No DatabaseDriver registered for dialect $dialect. " +
                    "Registered: ${drivers.keys.joinToString()}"
            )

    @Synchronized
    fun clear() {
        drivers.clear()
    }
}
