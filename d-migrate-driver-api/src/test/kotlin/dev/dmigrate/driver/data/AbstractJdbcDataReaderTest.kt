package dev.dmigrate.driver.data

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
 * Direct unit tests for [AbstractJdbcDataReader] / [JdbcChunkSequence].
 *
 * Uses sqlite-jdbc (already a testImplementation of this module for the
 * HikariConnectionPoolFactory tests) and a small concrete subclass to drive
 * the abstract contract end-to-end:
 *
 * - Lifecycle (single-use ChunkSequence, idempotent close, connection return)
 * - Empty-table contract (Plan §6.17): one chunk with columns + emptyList rows
 * - Multi-chunk streaming with chunkSize splitting
 * - DataFilter (WhereClause, ColumnSubset, Compound)
 * - Setup-error cleanup (invalid SQL must release the borrowed connection)
 *
 * The concrete SqliteDataReader in d-migrate-driver-sqlite has its own
 * end-to-end suite — this test focuses on the abstract base class itself
 * so coverage stays in the right module.
 */
class AbstractJdbcDataReaderTest : FunSpec({

    lateinit var dbFile: Path
    lateinit var pool: ConnectionPool

    beforeEach {
        dbFile = Files.createTempFile("d-migrate-jdbcreader-", ".db")
        dbFile.deleteIfExists()
        pool = HikariConnectionPoolFactory.create(
            ConnectionConfig(
                dialect = DatabaseDialect.SQLITE,
                host = null,
                port = null,
                database = dbFile.absolutePathString(),
                user = null,
                password = null,
            )
        )
        pool.borrow().use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("CREATE TABLE items (id INTEGER PRIMARY KEY, name TEXT, qty INTEGER)")
                stmt.execute("CREATE TABLE empty (id INTEGER PRIMARY KEY, label TEXT)")
            }
            conn.prepareStatement("INSERT INTO items (id, name, qty) VALUES (?, ?, ?)").use { ps ->
                for (i in 1..5) {
                    ps.setInt(1, i)
                    ps.setString(2, "item-$i")
                    ps.setInt(3, i * 10)
                    ps.executeUpdate()
                }
            }
        }
    }

    afterEach {
        pool.close()
        dbFile.deleteIfExists()
    }

    val reader = TestJdbcReader()

    test("streams all rows in one chunk when chunkSize covers everything") {
        val chunks = reader.streamTable(pool, "items", chunkSize = 100).toList()
        chunks.size shouldBe 1
        val c = chunks.single()
        c.table shouldBe "items"
        c.columns.map { it.name } shouldContainExactly listOf("id", "name", "qty")
        c.rows.size shouldBe 5
        c.chunkIndex shouldBe 0L
    }

    test("splits rows across multiple chunks") {
        val chunks = reader.streamTable(pool, "items", chunkSize = 2).toList()
        chunks.size shouldBe 3
        chunks[0].rows.size shouldBe 2
        chunks[1].rows.size shouldBe 2
        chunks[2].rows.size shouldBe 1
        chunks.map { it.chunkIndex } shouldContainExactly listOf(0L, 1L, 2L)
    }

    test("§6.17: empty table emits exactly one chunk with columns and emptyList rows") {
        val chunks = reader.streamTable(pool, "empty").toList()
        chunks.size shouldBe 1
        chunks.single().rows.size shouldBe 0
        chunks.single().columns.map { it.name } shouldContainExactly listOf("id", "label")
    }

    test("ChunkSequence is single-use — second iteration throws") {
        val seq = reader.streamTable(pool, "items", chunkSize = 100)
        seq.toList()
        shouldThrow<IllegalStateException> { seq.toList() }
    }

    test("ChunkSequence.close() is idempotent") {
        val seq = reader.streamTable(pool, "items", chunkSize = 100)
        seq.close()
        seq.close()
    }

    test("explicit close() before iteration throws on iterator() call") {
        val seq = reader.streamTable(pool, "items", chunkSize = 100)
        seq.close()
        shouldThrow<IllegalStateException> { seq.iterator() }
    }

    test("DataFilter.WhereClause is applied server-side") {
        val seq = reader.streamTable(pool, "items", filter = DataFilter.WhereClause("qty >= 30"))
        val rows = seq.toList().flatMap { it.rows.toList() }
        rows.size shouldBe 3
        rows.map { it[0] as Int }.sorted() shouldBe listOf(3, 4, 5)
    }

    test("DataFilter.ColumnSubset projects to a subset of columns") {
        val seq = reader.streamTable(pool, "items", filter = DataFilter.ColumnSubset(listOf("id", "name")))
        val chunk = seq.toList().single()
        chunk.columns.map { it.name } shouldContainExactly listOf("id", "name")
        chunk.rows.first().size shouldBe 2
    }

    test("DataFilter.Compound combines projection + where with AND") {
        val filter = DataFilter.Compound(
            listOf(
                DataFilter.ColumnSubset(listOf("name", "qty")),
                DataFilter.WhereClause("qty > 20"),
                DataFilter.WhereClause("qty < 50"),
            )
        )
        val chunk = reader.streamTable(pool, "items", filter = filter).toList().single()
        chunk.columns.map { it.name } shouldContainExactly listOf("name", "qty")
        chunk.rows.size shouldBe 2 // qty 30 and 40
    }

    test("invalid chunkSize throws IllegalArgumentException") {
        shouldThrow<IllegalArgumentException> { reader.streamTable(pool, "items", chunkSize = 0) }
        shouldThrow<IllegalArgumentException> { reader.streamTable(pool, "items", chunkSize = -5) }
    }

    test("setup error releases the borrowed connection") {
        // Unknown table → SQL error during prepareStatement/executeQuery
        shouldThrow<Exception> {
            reader.streamTable(pool, "definitely_does_not_exist", chunkSize = 100)
        }
        // Pool ist nur 1 connection (SQLite poolSize=1) — wenn die nicht zurück
        // gegeben wird, blockiert der nächste borrow. Verifizieren mit echtem Stream.
        reader.streamTable(pool, "items", chunkSize = 100).use { it.toList() }
    }

    test("after full iteration the connection is auto-returned to the pool") {
        // Konsumiere die Sequence ohne use{} — die letzte next()-Iteration sollte
        // close() automatisch aufrufen.
        val seq = reader.streamTable(pool, "items", chunkSize = 100)
        seq.toList()
        // Nächster borrow muss klappen
        reader.streamTable(pool, "items", chunkSize = 100).use { it.toList() }
    }

    test("schema-qualified table names are quoted segment by segment") {
        // SQLite alias "main" → primäre DB; eine schema-qualifizierte Form
        // "main.items" wird zu `SELECT * FROM "main"."items"` und muss daher
        // genau die items-Tabelle treffen.
        val chunks = reader.streamTable(pool, "main.items", chunkSize = 100).toList()
        chunks.single().rows.size shouldBe 5
    }

    // ───────────────────────────────────────────────────────────────────
    // M-R6: DataFilter.ParameterizedClause + bindParams integration
    // ───────────────────────────────────────────────────────────────────

    test("M-R6: pure WhereClause — no regression against 0.3.0 (no params bound)") {
        // This is the classic path: raw SQL, no ? placeholders, no binding.
        val filter = DataFilter.WhereClause("qty >= 30")
        val chunks = reader.streamTable(pool, "items", filter = filter).toList()
        val rows = chunks.flatMap { it.rows.toList() }
        rows.size shouldBe 3
        rows.map { it[0] as Int }.sorted() shouldBe listOf(3, 4, 5)
    }

    test("M-R6: pure ParameterizedClause — single ? is bound via setObject") {
        val filter = DataFilter.ParameterizedClause(
            sql = "qty >= ?",
            params = listOf(30),
        )
        val rows = reader.streamTable(pool, "items", filter = filter).toList()
            .flatMap { it.rows.toList() }
        rows.size shouldBe 3
        rows.map { it[0] as Int }.sorted() shouldBe listOf(3, 4, 5)
    }

    test("M-R6: Compound([WhereClause, ParameterizedClause]) — SQL composed with AND, params bound in order") {
        val filter = DataFilter.Compound(
            listOf(
                DataFilter.WhereClause("name LIKE 'item-%'"),
                DataFilter.ParameterizedClause("qty BETWEEN ? AND ?", listOf(20, 40)),
            )
        )
        val rows = reader.streamTable(pool, "items", filter = filter).toList()
            .flatMap { it.rows.toList() }
        // Rows with qty 20, 30, 40
        rows.size shouldBe 3
        rows.map { it[0] as Int }.sorted() shouldBe listOf(2, 3, 4)
    }

    test("M-R6: Compound with ColumnSubset + ParameterizedClause projects AND binds") {
        val filter = DataFilter.Compound(
            listOf(
                DataFilter.ColumnSubset(listOf("id", "qty")),
                DataFilter.ParameterizedClause("qty >= ?", listOf(40)),
            )
        )
        val chunk = reader.streamTable(pool, "items", filter = filter).toList().single()
        chunk.columns.map { it.name } shouldContainExactly listOf("id", "qty")
        chunk.rows.size shouldBe 2
        chunk.rows.map { it[0] as Int }.sorted() shouldBe listOf(4, 5)
    }

    test("M-R6: multiple ParameterizedClauses — positional params remain in Compound order") {
        // Two separate parameterized clauses combined with AND. The bind
        // positions are flattened left-to-right and must match the ? order
        // in the composed SQL.
        val filter = DataFilter.Compound(
            listOf(
                DataFilter.ParameterizedClause("qty > ?", listOf(10)),
                DataFilter.ParameterizedClause("qty < ?", listOf(50)),
            )
        )
        val rows = reader.streamTable(pool, "items", filter = filter).toList()
            .flatMap { it.rows.toList() }
        // 20, 30, 40 match; 10 and 50 are excluded (strict >, <)
        rows.size shouldBe 3
        rows.map { it[0] as Int }.sorted() shouldBe listOf(2, 3, 4)
    }

    test("M-R6: ParameterizedClause null param is bound as SQL NULL") {
        // Sanity check that setObject(idx, null) path works. SQLite treats
        // `column >= NULL` as always-false, so the filter matches nothing.
        val filter = DataFilter.ParameterizedClause("qty >= ?", listOf(null))
        val rows = reader.streamTable(pool, "items", filter = filter).toList()
            .flatMap { it.rows.toList() }
        rows.size shouldBe 0
    }

    test("M-R5: literal ? in WhereClause + ParameterizedClause is rejected early") {
        val filter = DataFilter.Compound(
            listOf(
                DataFilter.WhereClause("name = 'x?'"),
                DataFilter.ParameterizedClause("qty >= ?", listOf(20)),
            )
        )

        val ex = shouldThrow<IllegalArgumentException> {
            reader.streamTable(pool, "items", filter = filter).toList()
        }
        ex.message!!.contains("WhereClause") shouldBe true
        ex.message!!.contains("ParameterizedClause") shouldBe true
    }
})

/** Concrete subclass that mirrors SqliteDataReader's quoting/settings without depending on the SQLite driver module. */
private class TestJdbcReader : AbstractJdbcDataReader() {
    override val dialect: DatabaseDialect = DatabaseDialect.SQLITE
    override fun quoteIdentifier(name: String): String = "\"${name.replace("\"", "\"\"")}\""
    override val needsAutoCommitFalse: Boolean = false
}
