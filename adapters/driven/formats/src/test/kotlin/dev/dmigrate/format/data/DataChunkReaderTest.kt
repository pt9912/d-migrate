package dev.dmigrate.format.data

import dev.dmigrate.core.data.ColumnDescriptor
import dev.dmigrate.core.data.DataChunk
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * Contract-level tests for the [DataChunkReader] / [DataChunkReaderFactory]
 * interfaces. The concrete JSON/YAML/CSV readers land in Phase B — this
 * suite exercises a **Fake** reader that documents the required contract
 * shape so Phase-D streaming tests can build against the same semantics
 * before the Phase-B readers exist.
 */
class DataChunkReaderTest : FunSpec({

    test("fake reader: returns chunks up to chunkSize, then null") {
        val reader = FakeChunkReader(
            header = listOf("id", "name"),
            rows = listOf(
                arrayOf<Any?>(1, "alice"),
                arrayOf<Any?>(2, "bob"),
                arrayOf<Any?>(3, "carol"),
            ),
            chunkSize = 2,
            table = "users",
        )
        val first = reader.nextChunk()!!
        first.rows.size shouldBe 2
        first.columns.map { it.name } shouldContainExactly listOf("id", "name")
        val second = reader.nextChunk()!!
        second.rows.size shouldBe 1
        reader.nextChunk() shouldBe null
        // After exhaustion, further calls stay at null (no exception).
        reader.nextChunk() shouldBe null
    }

    test("fake reader: headerColumns() is deterministic after construction") {
        val reader = FakeChunkReader(
            header = listOf("id", "name"),
            rows = emptyList(),
            chunkSize = 10,
            table = "users",
        )
        reader.headerColumns() shouldContainExactly listOf("id", "name")
        // Empty input → nextChunk() returns null directly (no empty-chunk-
        // with-columns pattern like the 0.3.0 writer)
        reader.nextChunk() shouldBe null
        reader.headerColumns() shouldContainExactly listOf("id", "name")
    }

    test("fake reader: headerless variant returns null for headerColumns()") {
        val reader = FakeChunkReader(
            header = null,
            rows = listOf(arrayOf<Any?>(1, "alice")),
            chunkSize = 10,
            table = "users",
        )
        reader.headerColumns() shouldBe null
        reader.nextChunk()!!.rows.size shouldBe 1
    }

    test("R9: empty-object first row yields emptyList headerColumns (not null)") {
        val reader = FakeChunkReader(
            header = emptyList(), // explicit empty set — first row was {}
            rows = listOf(arrayOf<Any?>()),
            chunkSize = 10,
            table = "sparse",
        )
        reader.headerColumns() shouldBe emptyList()
        // The sparse row still parses — mismatch detection happens in the importer
        reader.nextChunk()!!.rows.size shouldBe 1
    }

    test("close() is idempotent") {
        val reader = FakeChunkReader(
            header = listOf("id"),
            rows = listOf(arrayOf<Any?>(1)),
            chunkSize = 10,
            table = "x",
        )
        reader.close()
        reader.close() // must not throw
        // After close, a subsequent nextChunk() call on the fake surfaces the
        // "closed" state as an IllegalStateException — concrete readers MAY
        // instead return null, but the fake is stricter so Phase-D tests can
        // assert the stricter behavior when they want to.
        shouldThrow<IllegalStateException> { reader.nextChunk() }
    }

    test("factory creates readers per table and format (fake factory)") {
        val factory = FakeChunkReaderFactory()
        val reader = factory.create(
            format = DataExportFormat.JSON,
            input = ByteArrayInputStream(ByteArray(0)),
            table = "items",
            chunkSize = 500,
        )
        reader.headerColumns() shouldBe null
        reader.nextChunk() shouldBe null
    }

    test("factory passes chunkSize through to the reader (L1)") {
        val factory = FakeChunkReaderFactory(
            header = listOf("id"),
            rows = List(7) { arrayOf<Any?>(it) },
        )
        val reader = factory.create(
            format = DataExportFormat.CSV,
            input = ByteArrayInputStream(ByteArray(0)),
            table = "nums",
            chunkSize = 3,
        )
        val c1 = reader.nextChunk()!!; c1.rows.size shouldBe 3
        val c2 = reader.nextChunk()!!; c2.rows.size shouldBe 3
        val c3 = reader.nextChunk()!!; c3.rows.size shouldBe 1
        reader.nextChunk() shouldBe null
    }
})

// ──────────────────────────────────────────────────────────────────────
// In-memory Fake implementing the DataChunkReader contract — used by the
// Phase-A contract tests and (in Phase D) as a cheap stand-in for the
// StreamingImporter-level tests.
// ──────────────────────────────────────────────────────────────────────

private class FakeChunkReader(
    private val header: List<String>?,
    rows: List<Array<Any?>>,
    private val chunkSize: Int,
    private val table: String,
) : DataChunkReader {

    private val remaining = ArrayDeque<Array<Any?>>().also { it.addAll(rows) }
    private var chunkIndex = 0L
    private var closed = false

    private val columns: List<ColumnDescriptor> =
        (header ?: emptyList()).map { ColumnDescriptor(name = it, nullable = true) }

    override fun nextChunk(): DataChunk? {
        check(!closed) { "reader is closed" }
        if (remaining.isEmpty()) return null
        val take = ArrayList<Array<Any?>>(chunkSize)
        repeat(chunkSize) {
            if (remaining.isNotEmpty()) take += remaining.removeFirst()
        }
        val chunk = DataChunk(
            table = table,
            columns = columns,
            rows = take,
            chunkIndex = chunkIndex++,
        )
        return chunk
    }

    override fun headerColumns(): List<String>? = header

    override fun close() { closed = true }
}

private class FakeChunkReaderFactory(
    private val header: List<String>? = null,
    private val rows: List<Array<Any?>> = emptyList(),
) : DataChunkReaderFactory {

    override fun create(
        format: DataExportFormat,
        input: InputStream,
        table: String,
        chunkSize: Int,
        options: ImportOptions,
    ): DataChunkReader {
        require(chunkSize > 0) { "chunkSize must be > 0, got $chunkSize" }
        // Input stream is intentionally ignored by the fake; concrete Phase-B
        // readers wrap it in EncodingDetector.detectOrFallback(...) first.
        return FakeChunkReader(header, rows, chunkSize, table)
    }
}
