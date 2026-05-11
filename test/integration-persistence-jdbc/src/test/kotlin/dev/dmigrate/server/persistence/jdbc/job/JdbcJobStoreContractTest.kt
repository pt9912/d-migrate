package dev.dmigrate.server.persistence.jdbc.job

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import dev.dmigrate.server.persistence.jdbc.internal.JdbcTransactionRunner
import dev.dmigrate.server.persistence.jdbc.migration.JdbcMigrationRunner
import dev.dmigrate.server.ports.contract.JobStoreContractTests
import org.testcontainers.postgresql.PostgreSQLContainer


private val jobStoreTestContainer = PostgreSQLContainer("postgres:16-alpine")
    .withDatabaseName("dmigrate_state")
    .withUsername("dmigrate")
    .withPassword("dmigrate")

private var jobStoreTestDataSource: HikariDataSource? = null

/**
 * LF-012 / LN-011 / LN-017 / LN-027 — laesst die [JobStoreContractTests]-Suite gegen
 * Testcontainers-Postgres laufen. Plan-Akzeptanz:
 * `JobStoreContractTests` gruen gegen Postgres inklusive
 * `IllegalTransition.currentStatus`-Diskriminierung und
 * `markCancelRequested`-Idempotenz.
 *
 * Tagged `integration` — laeuft nur unter `-PintegrationTests`.
 */
class JdbcJobStoreContractTest : JobStoreContractTests({
    val ds = jobStoreTestDataSource
        ?: error("DataSource not initialised — beforeSpec hook missed?")
    truncateJobsTable(ds)
    JdbcJobStore(JdbcTransactionRunner(ds))
}) {
    init {

        beforeSpec {
            jobStoreTestContainer.start()
            val cfg = HikariConfig().apply {
                jdbcUrl = jobStoreTestContainer.jdbcUrl
                username = jobStoreTestContainer.username
                password = jobStoreTestContainer.password
                maximumPoolSize = 4
                poolName = "phase-e-jobstore-contract"
            }
            jobStoreTestDataSource = HikariDataSource(cfg)
            JdbcMigrationRunner(jobStoreTestDataSource!!).migrate()
        }

        afterSpec {
            jobStoreTestDataSource?.close()
            jobStoreTestDataSource = null
            jobStoreTestContainer.stop()
        }
    }
}

private fun truncateJobsTable(ds: HikariDataSource) {
    ds.connection.use { conn ->
        conn.createStatement().use { stmt ->
            stmt.execute("TRUNCATE jobs")
        }
    }
}
