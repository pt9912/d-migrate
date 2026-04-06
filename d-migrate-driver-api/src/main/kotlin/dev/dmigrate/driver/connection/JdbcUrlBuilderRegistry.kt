package dev.dmigrate.driver.connection

import dev.dmigrate.driver.DatabaseDialect

/**
 * Registry für [JdbcUrlBuilder] pro [DatabaseDialect].
 *
 * **Bootstrap (Phase E)**: Es gibt KEINE automatische Self-Registration beim
 * Klassenladen. Treiber werden über ihre `*Driver.register()`-Bootstrap-
 * Objects in der globalen Registry eingetragen. Diese werden in Phase E
 * (CLI-Integration, [dev.dmigrate.cli.Main]) einmalig vor dem ersten
 * Command-Dispatch aufgerufen:
 *
 * ```kotlin
 * // d-migrate-cli/src/main/kotlin/dev/dmigrate/cli/Main.kt
 * fun main(args: Array<String>) {
 *     PostgresDriver.register()
 *     MysqlDriver.register()
 *     SqliteDriver.register()
 *     DMigrate().subcommands(...).main(args)
 * }
 * ```
 *
 * **Tests** in den Treiber-Modulen rufen `*Driver.register()` direkt in
 * ihrem `beforeSpec` auf.
 *
 * **Fallback** für Unit-Tests im `driver-api`-Modul ohne konkreten Treiber:
 * [HikariConnectionPoolFactory] benutzt automatisch einen internen
 * `FallbackJdbcUrlBuilder` mit denselben Defaults wie die produktiven
 * Builder, wenn die Registry leer ist.
 *
 * ServiceLoader-basierte Auto-Discovery kommt in 0.6.0, wenn externe
 * Treiber-JARs dazukommen — analog zur DDL-Registry-Diskussion im 0.2.0-Plan §6.2.
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
