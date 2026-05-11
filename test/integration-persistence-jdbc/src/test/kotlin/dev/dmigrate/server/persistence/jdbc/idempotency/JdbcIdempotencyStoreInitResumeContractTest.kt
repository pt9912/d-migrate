package dev.dmigrate.server.persistence.jdbc.idempotency

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import dev.dmigrate.server.persistence.jdbc.internal.JdbcTransactionRunner
import dev.dmigrate.server.persistence.jdbc.migration.JdbcMigrationRunner
import dev.dmigrate.server.ports.contract.ReadOnlyInitResumeContractTests
import org.testcontainers.postgresql.PostgreSQLContainer


private val initResumeTestContainer = PostgreSQLContainer("postgres:16-alpine")
    .withDatabaseName("dmigrate_state")
    .withUsername("dmigrate")
    .withPassword("dmigrate")

private var initResumeTestDataSource: HikariDataSource? = null

/**
 * LF-012 / LN-011 / LN-017 / LN-027 — laesst die [ReadOnlyInitResumeContractTests]-Suite gegen
 * Testcontainers-Postgres laufen. Plan-Akzeptanz:
 * `ReadOnlyInitResumeContractTests` gruen gegen Postgres
 * (Reserved/Existing/Conflict-Pfade plus Scope-Independence).
 *
 * Tagged `integration` — laeuft nur unter `-PintegrationTests`. Eigener
 * Container, weil Kotest Spec-Lifecycle-Sharing zwischen unabhaengigen
 * Spec-Klassen nicht trivial garantiert.
 */
class JdbcIdempotencyStoreInitResumeContractTest : ReadOnlyInitResumeContractTests({
    val ds = initResumeTestDataSource
        ?: error("DataSource not initialised — beforeSpec hook missed?")
    truncateInitResumeTable(ds)
    JdbcIdempotencyStore(JdbcTransactionRunner(ds))
}) {
    init {

        beforeSpec {
            initResumeTestContainer.start()
            val cfg = HikariConfig().apply {
                jdbcUrl = initResumeTestContainer.jdbcUrl
                username = initResumeTestContainer.username
                password = initResumeTestContainer.password
                maximumPoolSize = 4
                poolName = "phase-e-init-resume-contract"
            }
            initResumeTestDataSource = HikariDataSource(cfg)
            JdbcMigrationRunner(initResumeTestDataSource!!).migrate()
        }

        afterSpec {
            initResumeTestDataSource?.close()
            initResumeTestDataSource = null
            initResumeTestContainer.stop()
        }
    }
}

private fun truncateInitResumeTable(ds: HikariDataSource) {
    ds.connection.use { conn ->
        conn.createStatement().use { stmt ->
            stmt.execute("TRUNCATE init_resume_reservations")
        }
    }
}
