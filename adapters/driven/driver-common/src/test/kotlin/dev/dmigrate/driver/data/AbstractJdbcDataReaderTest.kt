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
 * - DataFilter (ParameterizedClause, ColumnSubset, Compound)
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

    test("DataFilter.ParameterizedClause is applied server-side") {
        val seq = reader.streamTable(pool, "items", filter = DataFilter.ParameterizedClause("qty >= ?", listOf(30)))
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
                DataFilter.ParameterizedClause("qty > ?", listOf(20)),
                DataFilter.ParameterizedClause("qty < ?", listOf(50)),
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

    test("M-R6: pure ParameterizedClause — no regression against 0.3.0") {
        // ParameterizedClause with bind params replaces the old WhereClause path.
        val filter = DataFilter.ParameterizedClause("qty >= ?", listOf(30))
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

    test("M-R6: Compound([ParameterizedClause, ParameterizedClause]) — SQL composed with AND, params bound in order") {
        val filter = DataFilter.Compound(
            listOf(
                DataFilter.ParameterizedClause("name LIKE ?", listOf("item-%")),
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

    test("M-R5: two ParameterizedClauses combined in Compound bind params in order") {
        val filter = DataFilter.Compound(
            listOf(
                DataFilter.ParameterizedClause("name = ?", listOf("item-3")),
                DataFilter.ParameterizedClause("qty >= ?", listOf(20)),
            )
        )

        val rows = reader.streamTable(pool, "items", filter = filter).toList()
            .flatMap { it.rows.toList() }
        rows.size shouldBe 1
        rows.single()[0] as Int shouldBe 3
    }

    // ───────────────────────────────────────────────────────────────────
    // 0.9.0 Phase C.2: ResumeMarker — Fresh-Track + Resume-Position
    // (`docs/ImpPlan-0.9.0-C2.md` §4.1 / §5.1)
    // ───────────────────────────────────────────────────────────────────

    test("C.2 ResumeMarker fresh-track: ORDER BY is enforced, no WHERE cascade") {
        val marker = ResumeMarker(
            markerColumn = "qty",
            tieBreakerColumns = listOf("id"),
            position = null,
        )
        val chunks = reader.streamTable(
            pool = pool,
            table = "items",
            filter = null,
            chunkSize = 100,
            resumeMarker = marker,
        ).toList()
        val rows = chunks.flatMap { it.rows.toList() }
        rows.size shouldBe 5
        rows.map { it[0] as Int } shouldContainExactly listOf(1, 2, 3, 4, 5)
    }

    test("C.2 ResumeMarker resume-position: strict lexicographic > cascade skips exact and lower") {
        val marker = ResumeMarker(
            markerColumn = "qty",
            tieBreakerColumns = listOf("id"),
            position = ResumeMarker.Position(
                lastMarkerValue = 20,
                lastTieBreakerValues = listOf(2),
            ),
        )
        val rows = reader.streamTable(
            pool = pool,
            table = "items",
            filter = null,
            chunkSize = 100,
            resumeMarker = marker,
        ).toList().flatMap { it.rows.toList() }
        // qty=20,id=2 is excluded (strict >); remaining qty 30,40,50
        rows.map { it[0] as Int } shouldContainExactly listOf(3, 4, 5)
    }

    test("C.2 ResumeMarker with duplicate marker values: tie-breaker resumes precisely") {
        // Insert two rows sharing the same qty to stress the lexicographic
        // tie-breaker path.
        pool.borrow().use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("INSERT INTO items (id, name, qty) VALUES (6, 'item-6', 30)")
                stmt.execute("INSERT INTO items (id, name, qty) VALUES (7, 'item-7', 30)")
            }
        }
        // We stopped at (qty=30, id=6); expect id=7 (same qty=30, higher id)
        // plus everything with qty > 30 to come through on resume.
        val marker = ResumeMarker(
            markerColumn = "qty",
            tieBreakerColumns = listOf("id"),
            position = ResumeMarker.Position(
                lastMarkerValue = 30,
                lastTieBreakerValues = listOf(6),
            ),
        )
        val rows = reader.streamTable(
            pool = pool,
            table = "items",
            filter = null,
            chunkSize = 100,
            resumeMarker = marker,
        ).toList().flatMap { it.rows.toList() }
        // 7 (qty=30, id=7), 4 (qty=40), 5 (qty=50) — order: by qty then id
        rows.map { it[0] as Int } shouldContainExactly listOf(7, 4, 5)
    }

    test("C.2 ResumeMarker without tie-breakers: marker column alone drives WHERE/ORDER BY") {
        val marker = ResumeMarker(
            markerColumn = "qty",
            tieBreakerColumns = emptyList(),
            position = ResumeMarker.Position(
                lastMarkerValue = 20,
                lastTieBreakerValues = emptyList(),
            ),
        )
        val rows = reader.streamTable(
            pool = pool,
            table = "items",
            filter = null,
            chunkSize = 100,
            resumeMarker = marker,
        ).toList().flatMap { it.rows.toList() }
        rows.map { it[0] as Int } shouldContainExactly listOf(3, 4, 5)
    }

    test("C.2 ResumeMarker composes with DataFilter.ParameterizedClause via AND") {
        val marker = ResumeMarker(
            markerColumn = "qty",
            tieBreakerColumns = listOf("id"),
            position = ResumeMarker.Position(
                lastMarkerValue = 10,
                lastTieBreakerValues = listOf(1),
            ),
        )
        val rows = reader.streamTable(
            pool = pool,
            table = "items",
            filter = DataFilter.ParameterizedClause("qty < ?", listOf(50)),
            chunkSize = 100,
            resumeMarker = marker,
        ).toList().flatMap { it.rows.toList() }
        // qty > 10 AND qty < 50 → 20, 30, 40
        rows.map { it[0] as Int } shouldContainExactly listOf(2, 3, 4)
    }

    test("C.2 ResumeMarker composes with DataFilter.ParameterizedClause; marker params appended last") {
        val marker = ResumeMarker(
            markerColumn = "qty",
            tieBreakerColumns = listOf("id"),
            position = ResumeMarker.Position(
                lastMarkerValue = 20,
                lastTieBreakerValues = listOf(2),
            ),
        )
        val rows = reader.streamTable(
            pool = pool,
            table = "items",
            filter = DataFilter.ParameterizedClause("name LIKE ?", listOf("item-%")),
            chunkSize = 100,
            resumeMarker = marker,
        ).toList().flatMap { it.rows.toList() }
        rows.map { it[0] as Int } shouldContainExactly listOf(3, 4, 5)
    }

    test("C.2 ResumeMarker init rejects mismatched tie-breaker size") {
        shouldThrow<IllegalArgumentException> {
            ResumeMarker(
                markerColumn = "qty",
                tieBreakerColumns = listOf("id", "name"),
                position = ResumeMarker.Position(
                    lastMarkerValue = 20,
                    lastTieBreakerValues = listOf(2), // size mismatch vs 2 cols
                ),
            )
        }
    }

    test("C.2 ResumeMarker init rejects blank marker column") {
        shouldThrow<IllegalArgumentException> {
            ResumeMarker(
                markerColumn = "",
                tieBreakerColumns = emptyList(),
            )
        }
    }

    test("C.2 ResumeMarker init rejects blank tie-breaker column") {
        shouldThrow<IllegalArgumentException> {
            ResumeMarker(
                markerColumn = "qty",
                tieBreakerColumns = listOf("id", ""),
                position = null,
            )
        }
    }
})

/** Concrete subclass that mirrors SqliteDataReader's quoting/settings without depending on the SQLite driver module. */
private class TestJdbcReader : AbstractJdbcDataReader() {
    override val dialect: DatabaseDialect = DatabaseDialect.SQLITE
    override fun quoteIdentifier(name: String): String = "\"${name.replace("\"", "\"\"")}\""
    override val needsAutoCommitFalse: Boolean = false
}
