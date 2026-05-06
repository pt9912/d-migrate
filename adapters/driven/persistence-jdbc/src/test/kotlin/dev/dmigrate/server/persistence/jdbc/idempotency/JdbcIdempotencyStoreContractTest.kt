package dev.dmigrate.server.persistence.jdbc.idempotency

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import dev.dmigrate.server.persistence.jdbc.internal.JdbcTransactionRunner
import dev.dmigrate.server.persistence.jdbc.migration.PhaseEMigrationRunner
import dev.dmigrate.server.ports.contract.IdempotencyStoreContractTests
import io.kotest.core.NamedTag
import org.testcontainers.postgresql.PostgreSQLContainer

private val IntegrationTag = NamedTag("integration")

private val testContainer = PostgreSQLContainer("postgres:16-alpine")
    .withDatabaseName("dmigrate_state")
    .withUsername("dmigrate")
    .withPassword("dmigrate")

private var testDataSource: HikariDataSource? = null

/**
 * Phase E2.3 — laesst die [IdempotencyStoreContractTests]-Suite gegen
 * eine Testcontainers-Postgres-Instanz laufen. Plan-Akzeptanz:
 * `IdempotencyStoreContractTests` gruen gegen Postgres inklusive
 * Recovery-Tests fuer expired PENDING/AWAITING_APPROVAL-Leases.
 *
 * Tagged `integration` — laeuft nur unter `-PintegrationTests`. Im
 * Default-Run skippt Kotest den ganzen Spec, deshalb darf der
 * `factory`-Lambda-Aufruf den Container *nicht* eager treffen.
 */
class JdbcIdempotencyStoreContractTest : IdempotencyStoreContractTests({
    val ds = testDataSource
        ?: error("DataSource not initialised — beforeSpec hook missed?")
    truncatePhaseETables(ds)
    JdbcIdempotencyStore(JdbcTransactionRunner(ds))
}) {
    init {
        tags(IntegrationTag)

        beforeSpec {
            testContainer.start()
            val cfg = HikariConfig().apply {
                jdbcUrl = testContainer.jdbcUrl
                username = testContainer.username
                password = testContainer.password
                maximumPoolSize = 4
                poolName = "phase-e-idempotency-contract"
            }
            testDataSource = HikariDataSource(cfg)
            PhaseEMigrationRunner(testDataSource!!).migrate()
        }

        afterSpec {
            testDataSource?.close()
            testDataSource = null
            testContainer.stop()
        }
    }
}

private fun truncatePhaseETables(ds: HikariDataSource) {
    ds.connection.use { conn ->
        conn.createStatement().use { stmt ->
            stmt.execute(
                """
                TRUNCATE
                  idempotency_reservations,
                  init_resume_reservations,
                  jobs,
                  quota_reservation_owners,
                  quota_counters
                """.trimIndent(),
            )
        }
    }
}
