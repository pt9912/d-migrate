package dev.dmigrate.server.persistence.jdbc.schema

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import dev.dmigrate.server.persistence.jdbc.internal.JdbcTransactionRunner
import dev.dmigrate.server.persistence.jdbc.migration.JdbcMigrationRunner
import dev.dmigrate.server.ports.contract.DiffStoreContractTests
import dev.dmigrate.test.images.TestImages
import org.testcontainers.postgresql.PostgreSQLContainer

private val diffStoreTestContainer = PostgreSQLContainer(TestImages.POSTGRESQL)
    .withDatabaseName("dmigrate_state")
    .withUsername("dmigrate")
    .withPassword("dmigrate")

private var diffStoreTestDataSource: HikariDataSource? = null

/**
 * Die [DiffStoreContractTests] gegen Testcontainers-Postgres (Issue #87).
 *
 * Tagged `integration` — laeuft nur unter `-PintegrationTests`.
 */
class JdbcDiffStoreContractTest : DiffStoreContractTests({
    val ds = diffStoreTestDataSource
        ?: error("DataSource not initialised — beforeSpec hook missed?")
    ds.connection.use { conn -> conn.createStatement().use { it.execute("TRUNCATE diff_index_entries") } }
    JdbcDiffStore(JdbcTransactionRunner(ds))
}) {
    init {

        beforeSpec {
            diffStoreTestContainer.start()
            val cfg = HikariConfig().apply {
                jdbcUrl = diffStoreTestContainer.jdbcUrl
                username = diffStoreTestContainer.username
                password = diffStoreTestContainer.password
                maximumPoolSize = 4
                poolName = "diff-store-contract"
            }
            diffStoreTestDataSource = HikariDataSource(cfg)
            JdbcMigrationRunner(diffStoreTestDataSource!!).migrate()
        }

        afterSpec {
            diffStoreTestDataSource?.close()
            diffStoreTestDataSource = null
            diffStoreTestContainer.stop()
        }
    }
}
