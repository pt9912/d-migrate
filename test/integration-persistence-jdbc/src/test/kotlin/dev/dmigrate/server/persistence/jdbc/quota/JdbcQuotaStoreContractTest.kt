package dev.dmigrate.server.persistence.jdbc.quota

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import dev.dmigrate.server.persistence.jdbc.internal.JdbcTransactionRunner
import dev.dmigrate.server.persistence.jdbc.migration.JdbcMigrationRunner
import dev.dmigrate.server.ports.contract.QuotaStoreContractTests
import org.testcontainers.postgresql.PostgreSQLContainer


private val quotaStoreTestContainer = PostgreSQLContainer("postgres:16-alpine")
    .withDatabaseName("dmigrate_state")
    .withUsername("dmigrate")
    .withPassword("dmigrate")

private var quotaStoreTestDataSource: HikariDataSource? = null

/**
 * LF-012 / LN-011 / LN-017 / LN-027 — laesst die [QuotaStoreContractTests]-Suite gegen
 * Testcontainers-Postgres laufen. Plan-Akzeptanz (a):
 * `QuotaStoreContractTests` gruen gegen Postgres inklusive
 * parallele-reserves-never-exceed-limit-Test.
 */
class JdbcQuotaStoreContractTest : QuotaStoreContractTests({
    val ds = quotaStoreTestDataSource
        ?: error("DataSource not initialised — beforeSpec hook missed?")
    truncateQuotaCounters(ds)
    JdbcQuotaStore(JdbcTransactionRunner(ds))
}) {
    init {

        beforeSpec {
            quotaStoreTestContainer.start()
            val cfg = HikariConfig().apply {
                jdbcUrl = quotaStoreTestContainer.jdbcUrl
                username = quotaStoreTestContainer.username
                password = quotaStoreTestContainer.password
                maximumPoolSize = 16
                poolName = "phase-e-quotastore-contract"
            }
            quotaStoreTestDataSource = HikariDataSource(cfg)
            JdbcMigrationRunner(quotaStoreTestDataSource!!).migrate()
        }

        afterSpec {
            quotaStoreTestDataSource?.close()
            quotaStoreTestDataSource = null
            quotaStoreTestContainer.stop()
        }
    }
}

private fun truncateQuotaCounters(ds: HikariDataSource) {
    ds.connection.use { conn ->
        conn.createStatement().use { stmt ->
            stmt.execute("TRUNCATE quota_counters")
        }
    }
}
