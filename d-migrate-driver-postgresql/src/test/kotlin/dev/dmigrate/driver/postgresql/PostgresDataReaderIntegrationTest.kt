package dev.dmigrate.driver.postgresql

import dev.dmigrate.core.data.DataFilter
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.connection.ConnectionConfig
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.driver.connection.HikariConnectionPoolFactory
import dev.dmigrate.driver.connection.JdbcUrlBuilderRegistry
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.NamedTag
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.testcontainers.containers.PostgreSQLContainer

private val IntegrationTag = NamedTag("integration")

/**
 * Integration tests for PostgresDataReader / PostgresTableLister against a
 * Testcontainers PostgreSQL.
 *
 * Tagged `integration` so the default `./gradlew test` excludes them
 * (build.gradle.kts root config). Run with `./gradlew test -PintegrationTests`
 * or via `.github/workflows/integration.yml`.
 *
 * Verifies (Plan §4 Phase B Schritt 12 + 13):
 * - Lifecycle (single-use ChunkSequence, idempotent close, connection return)
 * - Empty-table contract from §6.17 (one chunk with columns + emptyList rows)
 * - Multi-chunk streaming with chunkSize splitting
 * - DataFilter (WhereClause + ColumnSubset)
 * - PostgresTableLister returns user tables only
 * - PostgresJdbcUrlBuilder defaults are wired (ApplicationName=d-migrate)
 */
class PostgresDataReaderIntegrationTest : FunSpec({

    tags(IntegrationTag)

    val container = PostgreSQLContainer("postgres:16-alpine")
        .withDatabaseName("dmigrate_test")
        .withUsername("dmigrate")
        .withPassword("dmigrate")

    // nullable statt lateinit — `lateinit` Locals haben kein .isInitialized,
    // und der Default-Build ohne -PintegrationTests skipt diese Spec, aber
    // Kotest läuft afterSpec trotzdem (mit nicht initialisiertem pool).
    var pool: ConnectionPool? = null

    beforeSpec {
        container.start()
        // Treiber-Builder für die Test-JVM registrieren — der CLI-Bootstrap macht das
        // in Produktion zentral; hier brauchen wir ihn explizit.
        PostgresJdbcUrlBuilder.register()

        val cfg = ConnectionConfig(
            dialect = DatabaseDialect.POSTGRESQL,
            host = container.host,
            port = container.firstMappedPort,
            database = container.databaseName,
            user = container.username,
            password = container.password,
        )
        val p = HikariConnectionPoolFactory.create(cfg)
        pool = p

        // Schema setup
        p.borrow().use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("CREATE TABLE users (id SERIAL PRIMARY KEY, name TEXT NOT NULL, email TEXT)")
                stmt.execute("CREATE TABLE empty_table (id SERIAL PRIMARY KEY, label TEXT)")
                stmt.execute("CREATE INDEX idx_users_email ON users (email)")
            }
            conn.prepareStatement("INSERT INTO users (name, email) VALUES (?, ?)").use { ps ->
                for (i in 1..7) {
                    ps.setString(1, "user-$i")
                    ps.setString(2, "u$i@example.com")
                    ps.executeUpdate()
                }
            }
        }
    }

    afterSpec {
        pool?.close()
        if (container.isRunning) container.stop()
        JdbcUrlBuilderRegistry.clear()
    }

    /** Convenience: liefert den initialisierten Pool. Wirft NPE wenn beforeSpec nicht lief. */
    fun pool() = pool!!

    val reader = PostgresDataReader()
    val lister = PostgresTableLister()

    // ─── DataReader: streamTable ─────────────────────────────────

    test("streamTable returns all rows in a single chunk when chunkSize >= row count") {
        val chunks = reader.streamTable(pool(), "users", chunkSize = 100).toList()
        chunks.size shouldBe 1
        val chunk = chunks.single()
        chunk.table shouldBe "users"
        chunk.columns.map { it.name } shouldContainExactly listOf("id", "name", "email")
        chunk.rows.size shouldBe 7
        chunk.rows[0][1] shouldBe "user-1"
        chunk.rows[6][2] shouldBe "u7@example.com"
        chunk.chunkIndex shouldBe 0L
    }

    test("streamTable splits rows across multiple chunks") {
        val chunks = reader.streamTable(pool(), "users", chunkSize = 3).toList()
        chunks.size shouldBe 3
        chunks[0].rows.size shouldBe 3
        chunks[1].rows.size shouldBe 3
        chunks[2].rows.size shouldBe 1
    }

    test("§6.17: empty table emits exactly one chunk with columns and empty rows") {
        val chunks = reader.streamTable(pool(), "empty_table").toList()
        chunks.size shouldBe 1
        val chunk = chunks.single()
        chunk.rows.size shouldBe 0
        chunk.columns.map { it.name } shouldContainExactly listOf("id", "label")
        chunk.chunkIndex shouldBe 0L
    }

    test("DataFilter.WhereClause filters rows server-side") {
        val rows = reader
            .streamTable(pool(), "users", filter = DataFilter.WhereClause("id > 4"))
            .toList()
            .flatMap { it.rows.toList() }
        rows.size shouldBe 3
    }

    test("DataFilter.ColumnSubset projects to a subset of columns") {
        val chunks = reader
            .streamTable(pool(), "users", filter = DataFilter.ColumnSubset(listOf("id", "name")))
            .toList()
        chunks.single().columns.map { it.name } shouldContainExactly listOf("id", "name")
        chunks.single().rows.first().size shouldBe 2
    }

    // ─── ChunkSequence contract ──────────────────────────────────

    test("ChunkSequence is single-use") {
        val seq = reader.streamTable(pool(), "users", chunkSize = 100)
        seq.toList()
        shouldThrow<IllegalStateException> { seq.toList() }
    }

    test("ChunkSequence.close() is idempotent") {
        val seq = reader.streamTable(pool(), "users", chunkSize = 100)
        seq.close()
        seq.close()
    }

    test("close() returns the connection to the pool — repeated borrows succeed") {
        repeat(5) {
            reader.streamTable(pool(), "users", chunkSize = 100).use { it.toList() }
        }
        pool().activeConnections() shouldBe 0
    }

    // ─── TableLister ─────────────────────────────────────────────

    test("TableLister returns user tables sorted") {
        val tables = lister.listTables(pool())
        tables shouldContainExactly listOf("empty_table", "users")
    }

    test("TableLister returns the connection after listing — repeated calls succeed") {
        repeat(5) { lister.listTables(pool()) }
        pool().activeConnections() shouldBe 0
    }

    // ─── PostgresJdbcUrlBuilder integration ──────────────────────

    test("PostgresJdbcUrlBuilder is registered and produces ApplicationName=d-migrate") {
        val builder = JdbcUrlBuilderRegistry.find(DatabaseDialect.POSTGRESQL)
        (builder is PostgresJdbcUrlBuilder) shouldBe true

        // Verify via PostgreSQL system view that the application_name made it through
        pool().borrow().use { conn ->
            conn.prepareStatement(
                "SELECT application_name FROM pg_stat_activity WHERE pid = pg_backend_pid()"
            ).use { ps ->
                ps.executeQuery().use { rs ->
                    rs.next() shouldBe true
                    rs.getString(1) shouldBe "d-migrate"
                }
            }
        }
    }
})
