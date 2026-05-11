package dev.dmigrate.server.persistence.jdbc.idempotency

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import dev.dmigrate.server.persistence.jdbc.internal.JdbcTransactionRunner
import dev.dmigrate.server.persistence.jdbc.migration.JdbcMigrationRunner
import dev.dmigrate.server.ports.contract.IdempotencyStoreContractTests
import org.testcontainers.postgresql.PostgreSQLContainer


private val testContainer = PostgreSQLContainer("postgres:16-alpine")
    .withDatabaseName("dmigrate_state")
    .withUsername("dmigrate")
    .withPassword("dmigrate")

private var testDataSource: HikariDataSource? = null

/**
 * LF-012 / LN-011 / LN-017 / LN-027 — laesst die [IdempotencyStoreContractTests]-Suite gegen
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
    truncateServerStateTables(ds)
    JdbcIdempotencyStore(JdbcTransactionRunner(ds))
}) {
    init {

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
            JdbcMigrationRunner(testDataSource!!).migrate()
        }

        afterSpec {
            testDataSource?.close()
            testDataSource = null
            testContainer.stop()
        }
    }
}

private fun truncateServerStateTables(ds: HikariDataSource) {
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
