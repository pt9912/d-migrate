package dev.dmigrate.driver.connection

/**
 * Connection-Pool-Einstellungen für [HikariConnectionPoolFactory].
 *
 * Defaults entsprechen `connection-config-spec.md` §2.2 (HikariCP-Defaults).
 * Die Einheit aller `*Ms`-Felder ist Millisekunden, konsistent mit den
 * Werten in `.d-migrate.yaml` (anders als die treiberspezifischen
 * `connectTimeout`/`socketTimeout`-Parameter in der URL, die je Treiber
 * unterschiedliche Einheiten verwenden).
 */
data class PoolSettings(
    val maximumPoolSize: Int = 10,
    val minimumIdle: Int = 2,
    val connectionTimeoutMs: Long = 10_000,
    val idleTimeoutMs: Long = 300_000,
    val maxLifetimeMs: Long = 600_000,
    val keepaliveTimeMs: Long = 60_000,
)
