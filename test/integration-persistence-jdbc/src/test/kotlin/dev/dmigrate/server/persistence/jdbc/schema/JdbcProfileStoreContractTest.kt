package dev.dmigrate.server.persistence.jdbc.schema

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import dev.dmigrate.server.persistence.jdbc.internal.JdbcTransactionRunner
import dev.dmigrate.server.persistence.jdbc.migration.JdbcMigrationRunner
import dev.dmigrate.server.ports.contract.ProfileStoreContractTests
import dev.dmigrate.test.images.TestImages
import org.testcontainers.postgresql.PostgreSQLContainer

private val profileStoreTestContainer = PostgreSQLContainer(TestImages.POSTGRESQL)
    .withDatabaseName("dmigrate_state")
    .withUsername("dmigrate")
    .withPassword("dmigrate")

private var profileStoreTestDataSource: HikariDataSource? = null

/**
 * Die [ProfileStoreContractTests] gegen Testcontainers-Postgres (Issue #87).
 *
 * Tagged `integration` — laeuft nur unter `-PintegrationTests`.
 */
class JdbcProfileStoreContractTest : ProfileStoreContractTests({
    val ds = profileStoreTestDataSource
        ?: error("DataSource not initialised — beforeSpec hook missed?")
    ds.connection.use { conn -> conn.createStatement().use { it.execute("TRUNCATE profile_index_entries") } }
    JdbcProfileStore(JdbcTransactionRunner(ds))
}) {
    init {

        beforeSpec {
            profileStoreTestContainer.start()
            val cfg = HikariConfig().apply {
                jdbcUrl = profileStoreTestContainer.jdbcUrl
                username = profileStoreTestContainer.username
                password = profileStoreTestContainer.password
                maximumPoolSize = 4
                poolName = "profile-store-contract"
            }
            profileStoreTestDataSource = HikariDataSource(cfg)
            JdbcMigrationRunner(profileStoreTestDataSource!!).migrate()
        }

        afterSpec {
            profileStoreTestDataSource?.close()
            profileStoreTestDataSource = null
            profileStoreTestContainer.stop()
        }
    }
}
