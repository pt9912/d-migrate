package dev.dmigrate.server.persistence.jdbc.artifact

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import dev.dmigrate.server.persistence.jdbc.internal.JdbcTransactionRunner
import dev.dmigrate.server.persistence.jdbc.migration.JdbcMigrationRunner
import dev.dmigrate.server.ports.contract.ArtifactStoreContractTests
import org.testcontainers.postgresql.PostgreSQLContainer

private val artifactStoreTestContainer = PostgreSQLContainer("postgres:16-alpine")
    .withDatabaseName("dmigrate_state")
    .withUsername("dmigrate")
    .withPassword("dmigrate")

private var artifactStoreTestDataSource: HikariDataSource? = null

/**
 * ImpPlan-1.2.0-mcp-server-state-schema-artifact-persistence.md AP3 —
 * laesst die [ArtifactStoreContractTests]-Suite gegen Testcontainers-
 * Postgres laufen, inkl. `deleteExpiredRecords()` (AE-4).
 *
 * Tagged `integration` — laeuft nur unter `-PintegrationTests`.
 */
class JdbcArtifactStoreContractTest : ArtifactStoreContractTests({
    val ds = artifactStoreTestDataSource
        ?: error("DataSource not initialised — beforeSpec hook missed?")
    truncateArtifactRecordsTable(ds)
    JdbcArtifactStore(JdbcTransactionRunner(ds))
}) {
    init {

        beforeSpec {
            artifactStoreTestContainer.start()
            val cfg = HikariConfig().apply {
                jdbcUrl = artifactStoreTestContainer.jdbcUrl
                username = artifactStoreTestContainer.username
                password = artifactStoreTestContainer.password
                maximumPoolSize = 4
                poolName = "artifact-store-contract"
            }
            artifactStoreTestDataSource = HikariDataSource(cfg)
            JdbcMigrationRunner(artifactStoreTestDataSource!!).migrate()
        }

        afterSpec {
            artifactStoreTestDataSource?.close()
            artifactStoreTestDataSource = null
            artifactStoreTestContainer.stop()
        }
    }
}

private fun truncateArtifactRecordsTable(ds: HikariDataSource) {
    ds.connection.use { conn ->
        conn.createStatement().use { stmt ->
            stmt.execute("TRUNCATE artifact_records")
        }
    }
}
