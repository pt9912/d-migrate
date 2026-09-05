package dev.dmigrate.server.persistence.jdbc.schema

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import dev.dmigrate.server.core.principal.TenantId
import dev.dmigrate.server.core.resource.ResourceKind
import dev.dmigrate.server.core.resource.ServerResourceUri
import dev.dmigrate.server.persistence.jdbc.internal.JdbcTransactionRunner
import dev.dmigrate.server.persistence.jdbc.migration.JdbcMigrationRunner
import dev.dmigrate.server.ports.SchemaIndexEntry
import dev.dmigrate.server.ports.SchemaRegisterOutcome
import dev.dmigrate.server.ports.contract.SchemaStoreContractTests
import io.kotest.matchers.collections.shouldHaveSize
import org.testcontainers.postgresql.PostgreSQLContainer
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

private val schemaStoreTestContainer = PostgreSQLContainer("postgres:16-alpine")
    .withDatabaseName("dmigrate_state")
    .withUsername("dmigrate")
    .withPassword("dmigrate")

private var schemaStoreTestDataSource: HikariDataSource? = null

/**
 * ImpPlan-1.2.0-mcp-server-state-schema-artifact-persistence.md AP2 —
 * laesst die [SchemaStoreContractTests]-Suite gegen Testcontainers-Postgres
 * laufen (inkl. `register()`s drei Ausgaenge, AE-2).
 *
 * Tagged `integration` — laeuft nur unter `-PintegrationTests`.
 */
class JdbcSchemaStoreContractTest : SchemaStoreContractTests({
    val ds = schemaStoreTestDataSource
        ?: error("DataSource not initialised — beforeSpec hook missed?")
    truncateSchemaIndexEntriesTable(ds)
    JdbcSchemaStore(JdbcTransactionRunner(ds))
}) {
    init {

        beforeSpec {
            schemaStoreTestContainer.start()
            val cfg = HikariConfig().apply {
                jdbcUrl = schemaStoreTestContainer.jdbcUrl
                username = schemaStoreTestContainer.username
                password = schemaStoreTestContainer.password
                maximumPoolSize = 4
                poolName = "schema-store-contract"
            }
            schemaStoreTestDataSource = HikariDataSource(cfg)
            JdbcMigrationRunner(schemaStoreTestDataSource!!).migrate()
        }

        afterSpec {
            schemaStoreTestDataSource?.close()
            schemaStoreTestDataSource = null
            schemaStoreTestContainer.stop()
        }

        // AE-2-Review-Korrektur (ImpPlan-1.2.0-mcp-server-state-schema-artifact-persistence.md):
        // Replay-Wettlauf -- der eigentliche Grund, warum SELECT...FOR UPDATE allein die
        // register()-Semantik NICHT nachbilden kann. JDBC-spezifisch (echte, gleichzeitige
        // Connections gegen dieselbe Zeile) -- gehoert nicht in die geteilte
        // SchemaStoreContractTests-Suite, die auch InMemorySchemaStore deckt.
        test("concurrent register() with the same schemaId never throws — exactly one Registered") {
            val ds = schemaStoreTestDataSource ?: error("DataSource not initialised")
            truncateSchemaIndexEntriesTable(ds)
            val store = JdbcSchemaStore(JdbcTransactionRunner(ds))
            val entry = SchemaIndexEntry(
                schemaId = "race_1",
                tenantId = TenantId("acme"),
                resourceUri = ServerResourceUri(TenantId("acme"), ResourceKind.SCHEMAS, "race_1"),
                artifactRef = "artifact-race_1",
                displayName = "race schema",
                createdAt = Instant.parse("2026-05-06T10:00:00Z"),
                expiresAt = Instant.parse("2026-05-13T10:00:00Z"),
            )

            val concurrency = 10
            val startLatch = CountDownLatch(1)
            val doneLatch = CountDownLatch(concurrency)
            val executor = Executors.newFixedThreadPool(concurrency)
            val outcomes = java.util.Collections.synchronizedList(mutableListOf<SchemaRegisterOutcome>())
            val failures = java.util.Collections.synchronizedList(mutableListOf<Throwable>())

            repeat(concurrency) {
                executor.submit {
                    try {
                        startLatch.await()
                        outcomes += store.register(entry)
                    } catch (t: Throwable) {
                        failures += t
                    } finally {
                        doneLatch.countDown()
                    }
                }
            }
            startLatch.countDown()
            doneLatch.await(30, TimeUnit.SECONDS)
            executor.shutdown()

            failures shouldHaveSize 0
            outcomes.filterIsInstance<SchemaRegisterOutcome.Registered>() shouldHaveSize 1
            outcomes.filterIsInstance<SchemaRegisterOutcome.AlreadyRegistered>() shouldHaveSize (concurrency - 1)
        }
    }
}

private fun truncateSchemaIndexEntriesTable(ds: HikariDataSource) {
    ds.connection.use { conn ->
        conn.createStatement().use { stmt ->
            stmt.execute("TRUNCATE schema_index_entries")
        }
    }
}
