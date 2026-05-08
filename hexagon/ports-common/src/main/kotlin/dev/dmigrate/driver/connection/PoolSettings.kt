package dev.dmigrate.driver.connection

/**
 * Connection-Pool-Einstellungen für [HikariConnectionPoolFactory].
 *
 * Defaults entsprechen `connection-config-spec.md` §2.2 (HikariCP-Defaults).
 * Die Einheit aller `*Ms`-Felder ist Millisekunden, konsistent mit den
 * Werten in `.d-migrate.yaml` (anders als die treiberspezifischen
 * `connectTimeout`/`socketTimeout`-Parameter in der URL, die je Treiber
 * unterschiedliche Einheiten verwenden).
 *
 * [statementTimeoutMs] und [networkTimeoutMs] sind die Cancel-Reaktions-
 * Schranken aus implementation-plan-0.9.6 §4.1: jede atomar-nicht-cancelbare
 * Driver-Operation bricht innerhalb dieses Budgets nach einem Cancel-Signal
 * ab. Default `30000ms` = obere Schranke aus LF-012 / LN-011 / LN-017 / LN-027 (pre-server-state-
 * Konfiguration). Wert `0` deaktiviert das jeweilige Timeout (Test-/
 * Bench-Szenarien); negative Werte sind Konstruktionsfehler.
 */
data class PoolSettings(
    val maximumPoolSize: Int = 10,
    val minimumIdle: Int = 2,
    val connectionTimeoutMs: Long = 10_000,
    val idleTimeoutMs: Long = 300_000,
    val maxLifetimeMs: Long = 600_000,
    val keepaliveTimeMs: Long = 60_000,
    val statementTimeoutMs: Int = 30_000,
    val networkTimeoutMs: Int = 30_000,
) {
    init {
        require(statementTimeoutMs >= 0) {
            "statementTimeoutMs must not be negative, was $statementTimeoutMs"
        }
        require(networkTimeoutMs >= 0) {
            "networkTimeoutMs must not be negative, was $networkTimeoutMs"
        }
    }
}
