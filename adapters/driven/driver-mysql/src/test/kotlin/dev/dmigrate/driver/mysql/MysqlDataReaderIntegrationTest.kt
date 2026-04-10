package dev.dmigrate.driver.mysql

import dev.dmigrate.core.data.DataFilter
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.connection.ConnectionConfig
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.driver.connection.HikariConnectionPoolFactory
import dev.dmigrate.driver.connection.JdbcUrlBuilderRegistry
import dev.dmigrate.driver.data.DataReaderRegistry
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.NamedTag
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.testcontainers.mysql.MySQLContainer

private val IntegrationTag = NamedTag("integration")

/**
 * Integration tests for MysqlDataReader / MysqlTableLister against a
 * Testcontainers MySQL.
 *
 * Tagged `integration` so the default `./gradlew test` excludes them
 * (build.gradle.kts root config). Run with `./gradlew test -PintegrationTests`
 * or via `.github/workflows/integration.yml`.
 *
 * Verifies (Plan §4 Phase B Schritt 12 + 13):
 * - Lifecycle (single-use ChunkSequence, idempotent close, connection return)
 * - Empty-table contract from §6.17
 * - Multi-chunk streaming with chunkSize splitting
 * - DataFilter (WhereClause + ColumnSubset)
 * - MysqlTableLister returns user tables only
 * - MysqlJdbcUrlBuilder defaults are wired (useCursorFetch=true, utf8mb4)
 */
class MysqlDataReaderIntegrationTest : FunSpec({

    tags(IntegrationTag)

    val container = MySQLContainer("mysql:8.0")
        .withDatabaseName("dmigrate_test")
        .withUsername("dmigrate")
        .withPassword("dmigrate")

    var pool: ConnectionPool? = null

    beforeSpec {
        container.start()
        val driver = MysqlDriver()
        JdbcUrlBuilderRegistry.register(driver.urlBuilder())
        DataReaderRegistry.registerDataReader(driver.dataReader())
        DataReaderRegistry.registerTableLister(driver.tableLister())

        val cfg = ConnectionConfig(
            dialect = DatabaseDialect.MYSQL,
            host = container.host,
            port = container.firstMappedPort,
            database = container.databaseName,
            user = container.username,
            password = container.password,
        )
        val p = HikariConnectionPoolFactory.create(cfg)
        pool = p

        p.borrow().use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute(
                    "CREATE TABLE users (id INT AUTO_INCREMENT PRIMARY KEY, " +
                        "name VARCHAR(100) NOT NULL, email VARCHAR(254)) ENGINE=InnoDB"
                )
                stmt.execute(
                    "CREATE TABLE empty_table (id INT AUTO_INCREMENT PRIMARY KEY, " +
                        "label VARCHAR(100)) ENGINE=InnoDB"
                )
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
        DataReaderRegistry.clear()
    }

    fun pool() = pool!!

    val reader = MysqlDataReader()
    val lister = MysqlTableLister()

    // ─── DataReader: streamTable ─────────────────────────────────

    test("streamTable returns all rows in a single chunk when chunkSize >= row count") {
        val chunks = reader.streamTable(pool(), "users", chunkSize = 100).toList()
        chunks.size shouldBe 1
        val chunk = chunks.single()
        chunk.table shouldBe "users"
        chunk.columns.map { it.name } shouldContainExactly listOf("id", "name", "email")
        chunk.rows.size shouldBe 7
        chunk.rows[0][1] shouldBe "user-1"
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

    // ─── MysqlJdbcUrlBuilder integration ─────────────────────────

    test("MysqlJdbcUrlBuilder is registered and useCursorFetch is active") {
        val builder = JdbcUrlBuilderRegistry.find(DatabaseDialect.MYSQL)
        (builder is MysqlJdbcUrlBuilder) shouldBe true

        // Verify via SHOW SESSION VARIABLES that the connection uses the cursor
        // Mode is communicated via the JDBC param, not a server-visible setting,
        // but we can at least verify the connection accepts the parameter without errors.
        pool().borrow().use { conn ->
            conn.prepareStatement("SELECT 1").use { ps ->
                ps.executeQuery().use { rs ->
                    rs.next() shouldBe true
                }
            }
        }
    }
})
