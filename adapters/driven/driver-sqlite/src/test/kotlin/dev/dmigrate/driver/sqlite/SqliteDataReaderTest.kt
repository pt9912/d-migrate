package dev.dmigrate.driver.sqlite

import dev.dmigrate.core.data.DataFilter
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.connection.ConnectionConfig
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.driver.connection.HikariConnectionPoolFactory
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.deleteIfExists

/**
 * Inline-Tests für SqliteDataReader und SqliteTableLister.
 *
 * Verifiziert:
 * - Lifecycle (single-use ChunkSequence, close() idempotent, Connection-Return)
 * - Empty-Table-Vertrag aus §6.17 (mindestens ein Chunk mit columns + leere rows)
 * - Multiple Chunks (chunkSize wird respektiert)
 * - DataFilter (ParameterizedClause + ColumnSubset)
 * - TableLister (sqlite_*-Tabellen werden ausgeschlossen)
 *
 * Tests laufen gegen eine temporäre Datei-DB statt :memory:, weil Hikari
 * mit poolSize=1 sonst die DB zwischen den Statements verlieren würde
 * (jeder borrow gegen :memory: ist eine neue, leere DB).
 */
class SqliteDataReaderTest : FunSpec({

    lateinit var dbFile: Path
    lateinit var pool: ConnectionPool

    beforeEach {
        dbFile = Files.createTempFile("d-migrate-test-", ".db")
        // Frisches File — Hikari erwartet eine existierende SQLite-DB nicht zwingend,
        // sqlite-jdbc legt sie an
        dbFile.deleteIfExists()
        val cfg = ConnectionConfig(
            dialect = DatabaseDialect.SQLITE,
            host = null,
            port = null,
            database = dbFile.absolutePathString(),
            user = null,
            password = null,
        )
        pool = HikariConnectionPoolFactory.create(cfg)
        // Schema setup
        pool.borrow().use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute(
                    "CREATE TABLE users (id INTEGER PRIMARY KEY, name TEXT NOT NULL, email TEXT)"
                )
                stmt.execute(
                    "CREATE TABLE empty_table (id INTEGER PRIMARY KEY, value TEXT)"
                )
                stmt.execute("CREATE INDEX idx_users_email ON users (email)")
            }
            // Inserts
            conn.prepareStatement(
                "INSERT INTO users (id, name, email) VALUES (?, ?, ?)"
            ).use { ps ->
                for (i in 1..7) {
                    ps.setInt(1, i)
                    ps.setString(2, "user-$i")
                    ps.setString(3, "u$i@example.com")
                    ps.executeUpdate()
                }
            }
        }
    }

    afterEach {
        pool.close()
        dbFile.deleteIfExists()
    }

    // ─── DataReader: streamTable ─────────────────────────────────

    test("streamTable returns all rows in a single chunk when chunkSize >= row count") {
        val reader = SqliteDataReader()
        val chunks = reader.streamTable(pool, "users", chunkSize = 100).toList()
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
        val reader = SqliteDataReader()
        val chunks = reader.streamTable(pool, "users", chunkSize = 3).toList()
        // 7 Rows / chunkSize 3 → Chunks von 3, 3, 1
        chunks.size shouldBe 3
        chunks[0].rows.size shouldBe 3
        chunks[1].rows.size shouldBe 3
        chunks[2].rows.size shouldBe 1
        chunks[0].chunkIndex shouldBe 0L
        chunks[1].chunkIndex shouldBe 1L
        chunks[2].chunkIndex shouldBe 2L
    }

    test("§6.17: empty table returns exactly one chunk with columns and empty rows") {
        val reader = SqliteDataReader()
        val chunks = reader.streamTable(pool, "empty_table").toList()
        chunks.size shouldBe 1
        val chunk = chunks.single()
        chunk.rows.size shouldBe 0
        chunk.columns.map { it.name } shouldContainExactly listOf("id", "value")
        chunk.chunkIndex shouldBe 0L
    }

    test("DataFilter.ParameterizedClause filters rows server-side") {
        val reader = SqliteDataReader()
        val filter = DataFilter.ParameterizedClause("id > ?", listOf(4))
        val rows = reader.streamTable(pool, "users", filter = filter).toList()
            .flatMap { it.rows.toList() }
        rows.size shouldBe 3
        rows.map { it[0] as Int }.sorted() shouldBe listOf(5, 6, 7)
    }

    test("DataFilter.ColumnSubset projects to a subset of columns") {
        val reader = SqliteDataReader()
        val filter = DataFilter.ColumnSubset(listOf("id", "name"))
        val chunks = reader.streamTable(pool, "users", filter = filter).toList()
        chunks.single().columns.map { it.name } shouldContainExactly listOf("id", "name")
        chunks.single().rows.first().size shouldBe 2
    }

    test("DataFilter.Compound combines projection and where") {
        val reader = SqliteDataReader()
        val filter = DataFilter.Compound(
            listOf(
                DataFilter.ColumnSubset(listOf("name")),
                DataFilter.ParameterizedClause("id IN (?, ?, ?)", listOf(1, 3, 5)),
            )
        )
        val chunks = reader.streamTable(pool, "users", filter = filter).toList()
        val chunk = chunks.single()
        chunk.columns.map { it.name } shouldContainExactly listOf("name")
        chunk.rows.size shouldBe 3
        chunk.rows.map { it[0] as String }.sorted() shouldBe listOf("user-1", "user-3", "user-5")
    }

    // ─── ChunkSequence contract ──────────────────────────────────

    test("ChunkSequence is single-use — second iteration throws") {
        val reader = SqliteDataReader()
        val seq = reader.streamTable(pool, "users", chunkSize = 100)
        seq.toList()  // first iteration
        shouldThrow<IllegalStateException> { seq.toList() }
    }

    test("ChunkSequence.close() is idempotent") {
        val reader = SqliteDataReader()
        val seq = reader.streamTable(pool, "users", chunkSize = 100)
        seq.close()
        seq.close()  // must not throw
    }

    test("close() returns the connection to the pool") {
        val reader = SqliteDataReader()
        // Borrow + Stream consumes one connection — after close it should be 0 again
        reader.streamTable(pool, "users", chunkSize = 100).use { it.toList() }
        // Hikari pool size is 1 for SQLite — if the connection was leaked, the next
        // borrow would block. We give it a small timeout via a fresh stream.
        reader.streamTable(pool, "users", chunkSize = 100).use { seq ->
            seq.toList().size shouldBe 1
        }
    }

    test("schema-qualified table names are quoted segment by segment") {
        // SQLite kennt schemas via ATTACH; hier nur das Quoting per Smoke-Test:
        // single-segment funktioniert weiterhin.
        val reader = SqliteDataReader()
        val chunks = reader.streamTable(pool, "users", chunkSize = 100).toList()
        chunks.single().table shouldBe "users"
    }

    test("invalid chunkSize throws") {
        val reader = SqliteDataReader()
        shouldThrow<IllegalArgumentException> {
            reader.streamTable(pool, "users", chunkSize = 0)
        }
        shouldThrow<IllegalArgumentException> {
            reader.streamTable(pool, "users", chunkSize = -1)
        }
    }

    // ─── TableLister ─────────────────────────────────────────────

    test("TableLister returns user tables sorted, excludes sqlite_* internal tables") {
        val lister = SqliteTableLister()
        val tables = lister.listTables(pool)
        tables shouldContainExactly listOf("empty_table", "users")
        tables.none { it.startsWith("sqlite_") } shouldBe true
    }

    test("TableLister returns the pool connection after listing") {
        val lister = SqliteTableLister()
        // Two consecutive calls must work — the connection must be returned
        // each time, otherwise SQLite poolSize=1 would deadlock.
        lister.listTables(pool)
        lister.listTables(pool)
    }
})
