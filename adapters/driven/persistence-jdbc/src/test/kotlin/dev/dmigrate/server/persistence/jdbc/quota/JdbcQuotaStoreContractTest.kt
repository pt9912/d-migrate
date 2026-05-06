package dev.dmigrate.server.persistence.jdbc.quota

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import dev.dmigrate.server.persistence.jdbc.internal.JdbcTransactionRunner
import dev.dmigrate.server.persistence.jdbc.migration.PhaseEMigrationRunner
import dev.dmigrate.server.ports.contract.QuotaStoreContractTests
import io.kotest.core.NamedTag
import org.testcontainers.postgresql.PostgreSQLContainer

private val IntegrationTag = NamedTag("integration")

private val quotaStoreTestContainer = PostgreSQLContainer("postgres:16-alpine")
    .withDatabaseName("dmigrate_state")
    .withUsername("dmigrate")
    .withPassword("dmigrate")

private var quotaStoreTestDataSource: HikariDataSource? = null

/**
 * Phase E2.7 — laesst die [QuotaStoreContractTests]-Suite gegen
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
        tags(IntegrationTag)

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
            PhaseEMigrationRunner(quotaStoreTestDataSource!!).migrate()
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
