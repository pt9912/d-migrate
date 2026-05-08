package dev.dmigrate.server.persistence.jdbc.job

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import dev.dmigrate.server.persistence.jdbc.idempotency.JdbcIdempotencyStore
import dev.dmigrate.server.persistence.jdbc.internal.JdbcTransactionRunner
import dev.dmigrate.server.persistence.jdbc.migration.JdbcMigrationRunner
import dev.dmigrate.server.ports.contract.JobStartTransactionContractTests
import dev.dmigrate.server.ports.contract.JobStartTransactionFixture
import io.kotest.core.NamedTag
import org.testcontainers.postgresql.PostgreSQLContainer

private val IntegrationTag = NamedTag("integration")

private val txTestContainer = PostgreSQLContainer("postgres:16-alpine")
    .withDatabaseName("dmigrate_state")
    .withUsername("dmigrate")
    .withPassword("dmigrate")

private var txTestDataSource: HikariDataSource? = null

/**
 * Phase E2.6 — laesst die [JobStartTransactionContractTests]-Suite gegen
 * Testcontainers-Postgres laufen. Plan-Akzeptanz:
 * `JobStartTransactionContractTests` gruen gegen Postgres inklusive
 * parallel-commit-Test; Atomicity-Vertrag aus
 * `spec/phase-e-port-atomicity.md` § 3 ausfuehrbar verifiziert.
 *
 * Pro Test-Aufruf liefert das Fixture-Lambda eine frisch truncated DB
 * mit konsistent verkabelten JdbcIdempotencyStore + JdbcJobStore +
 * JdbcJobStartTransaction (alle ueber denselben JdbcTransactionRunner,
 * alle gegen denselben DataSource). So greift Plan §7.2 atomicity:
 * `commitOnConnection` und `saveOnConnection` teilen sich die TX.
 */
class JdbcJobStartTransactionContractTest : JobStartTransactionContractTests({
    val ds = txTestDataSource
        ?: error("DataSource not initialised — beforeSpec hook missed?")
    truncateServerStateTables(ds)
    val runner = JdbcTransactionRunner(ds)
    val idempotencyStore = JdbcIdempotencyStore(runner)
    val jobStore = JdbcJobStore(runner)
    JobStartTransactionFixture(
        idempotencyStore = idempotencyStore,
        jobStore = jobStore,
        transaction = JdbcJobStartTransaction(runner, idempotencyStore, jobStore),
    )
}) {
    init {
        tags(IntegrationTag)

        beforeSpec {
            txTestContainer.start()
            val cfg = HikariConfig().apply {
                jdbcUrl = txTestContainer.jdbcUrl
                username = txTestContainer.username
                password = txTestContainer.password
                maximumPoolSize = 16
                poolName = "phase-e-jobstart-tx-contract"
            }
            txTestDataSource = HikariDataSource(cfg)
            JdbcMigrationRunner(txTestDataSource!!).migrate()
        }

        afterSpec {
            txTestDataSource?.close()
            txTestDataSource = null
            txTestContainer.stop()
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
