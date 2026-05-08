package dev.dmigrate.server.persistence.jdbc.quota

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import dev.dmigrate.server.application.quota.QuotaReservationOwnerStoreContractTests
import dev.dmigrate.server.persistence.jdbc.internal.JdbcTransactionRunner
import dev.dmigrate.server.persistence.jdbc.migration.JdbcMigrationRunner
import io.kotest.core.NamedTag
import org.testcontainers.postgresql.PostgreSQLContainer

private val IntegrationTag = NamedTag("integration")

private val ownerStoreTestContainer = PostgreSQLContainer("postgres:16-alpine")
    .withDatabaseName("dmigrate_state")
    .withUsername("dmigrate")
    .withPassword("dmigrate")

private var ownerStoreTestDataSource: HikariDataSource? = null

/**
 * LF-012 / LN-011 / LN-017 / LN-027 — laesst die [QuotaReservationOwnerStoreContractTests]-
 * Suite gegen Testcontainers-Postgres laufen. Plan-Akzeptanz (b):
 * `QuotaReservationOwnerStoreContractTests` gruen gegen Postgres
 * inklusive parallel-markX-CAS und parallel-register-PK-Verletzung.
 */
class JdbcQuotaReservationOwnerStoreContractTest : QuotaReservationOwnerStoreContractTests({
    val ds = ownerStoreTestDataSource
        ?: error("DataSource not initialised — beforeSpec hook missed?")
    truncateOwnerTable(ds)
    JdbcQuotaReservationOwnerStore(JdbcTransactionRunner(ds))
}) {
    init {
        tags(IntegrationTag)

        beforeSpec {
            ownerStoreTestContainer.start()
            val cfg = HikariConfig().apply {
                jdbcUrl = ownerStoreTestContainer.jdbcUrl
                username = ownerStoreTestContainer.username
                password = ownerStoreTestContainer.password
                maximumPoolSize = 16
                poolName = "phase-e-ownerstore-contract"
            }
            ownerStoreTestDataSource = HikariDataSource(cfg)
            JdbcMigrationRunner(ownerStoreTestDataSource!!).migrate()
        }

        afterSpec {
            ownerStoreTestDataSource?.close()
            ownerStoreTestDataSource = null
            ownerStoreTestContainer.stop()
        }
    }
}

private fun truncateOwnerTable(ds: HikariDataSource) {
    ds.connection.use { conn ->
        conn.createStatement().use { stmt ->
            stmt.execute("TRUNCATE quota_reservation_owners")
        }
    }
}
