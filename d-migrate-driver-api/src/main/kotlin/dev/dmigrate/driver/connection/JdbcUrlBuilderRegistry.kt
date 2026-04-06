package dev.dmigrate.driver.connection

import dev.dmigrate.driver.DatabaseDialect

/**
 * Registry für [JdbcUrlBuilder] pro [DatabaseDialect].
 *
 * Treiber registrieren ihren Builder beim Klassenladen über ihre statische
 * Initialisierung (z.B. ein `init { JdbcUrlBuilderRegistry.register(...) }`
 * im jeweiligen `*JdbcUrlBuilder`-Companion).
 *
 * Wenn ein Builder für einen Dialekt nicht registriert ist, wirft [get] eine
 * IllegalStateException — der CLI-Bootstrap muss sicherstellen, dass die
 * benötigten Treiber-Module auf dem Classpath sind und ihre Builder
 * registriert haben.
 *
 * Für Tests in `driver-api` ohne konkrete Treiber-Implementierung gibt es
 * [HikariConnectionPoolFactory.fallbackBuilder], der eine generische
 * Default-Logik bereitstellt.
 *
 * Analog zu [dev.dmigrate.driver.data.DataReaderRegistry], kein
 * ServiceLoader für 0.3.0 — kommt in 0.6.0 wenn externe Treiber-JARs
 * dazukommen.
 */
object JdbcUrlBuilderRegistry {

    private val builders = mutableMapOf<DatabaseDialect, JdbcUrlBuilder>()

    @Synchronized
    fun register(builder: JdbcUrlBuilder) {
        builders[builder.dialect] = builder
    }

    /**
     * @return den registrierten Builder, oder `null` wenn keiner registriert ist.
     *   Konsumenten (z.B. HikariConnectionPoolFactory) entscheiden selbst, ob
     *   sie auf einen Default zurückfallen oder eine Exception werfen.
     */
    fun find(dialect: DatabaseDialect): JdbcUrlBuilder? = builders[dialect]

    /** @throws IllegalStateException wenn kein Builder registriert ist */
    fun get(dialect: DatabaseDialect): JdbcUrlBuilder = builders[dialect]
        ?: throw IllegalStateException(
            "No JdbcUrlBuilder registered for dialect $dialect. " +
                "Make sure d-migrate-driver-${dialect.name.lowercase()} is on the classpath " +
                "and its *JdbcUrlBuilder has been initialized."
        )

    /**
     * Setzt die Registry zurück. Primär für Tests in den Treiber-Modulen
     * gedacht — `internal` würde wegen module-scoped visibility nicht
     * funktionieren, daher `public`.
     */
    @Synchronized
    fun clear() {
        builders.clear()
    }
}
