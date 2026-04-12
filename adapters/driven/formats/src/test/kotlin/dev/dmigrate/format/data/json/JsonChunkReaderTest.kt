package dev.dmigrate.format.data.json

import dev.dmigrate.core.data.ImportSchemaMismatchException
import dev.dmigrate.driver.data.ImportOptions
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.ByteArrayInputStream

/**
 * Unit-Tests für [JsonChunkReader].
 *
 * Plan §4 Phase B Schritt 7: JsonChunkReader mit DSL-JSON Pull-Parser.
 * Tests verwenden In-Memory-JSON-Strings via [ByteArrayInputStream].
 */
class JsonChunkReaderTest : FunSpec({

    fun reader(json: String, chunkSize: Int = 100, options: ImportOptions = ImportOptions()) =
        JsonChunkReader(ByteArrayInputStream(json.toByteArray(Charsets.UTF_8)), "t", chunkSize, options)

    // ─── Happy Path ─────────────────────────────────────────────────

    test("two rows in single chunk") {
        reader("""[{"id": 1, "name": "alice"}, {"id": 2, "name": "bob"}]""").use { r ->
            val chunk = r.nextChunk()!!
            chunk.table shouldBe "t"
            chunk.columns.map { it.name } shouldBe listOf("id", "name")
            chunk.rows shouldHaveSize 2
            chunk.rows[0][0] shouldBe 1L
            chunk.rows[0][1] shouldBe "alice"
            chunk.rows[1][0] shouldBe 2L
            chunk.rows[1][1] shouldBe "bob"
            chunk.chunkIndex shouldBe 0L

            r.nextChunk().shouldBeNull()
        }
    }

    test("single row, single chunk") {
        reader("""[{"x": 42}]""").use { r ->
            val chunk = r.nextChunk()!!
            chunk.rows shouldHaveSize 1
            chunk.rows[0][0] shouldBe 42L
            r.nextChunk().shouldBeNull()
        }
    }

    test("chunkSize larger than data returns one chunk") {
        reader("""[{"a": 1}, {"a": 2}, {"a": 3}]""", chunkSize = 100).use { r ->
            val chunk = r.nextChunk()!!
            chunk.rows shouldHaveSize 3
            r.nextChunk().shouldBeNull()
        }
    }

    // ─── Multi-Chunk ────────────────────────────────────────────────

    test("splits into multiple chunks at chunkSize boundary") {
        val json = """[{"v":1},{"v":2},{"v":3},{"v":4},{"v":5}]"""
        reader(json, chunkSize = 2).use { r ->
            val c0 = r.nextChunk()!!
            c0.rows shouldHaveSize 2
            c0.chunkIndex shouldBe 0L
            c0.rows[0][0] shouldBe 1L
            c0.rows[1][0] shouldBe 2L

            val c1 = r.nextChunk()!!
            c1.rows shouldHaveSize 2
            c1.chunkIndex shouldBe 1L
            c1.rows[0][0] shouldBe 3L
            c1.rows[1][0] shouldBe 4L

            val c2 = r.nextChunk()!!
            c2.rows shouldHaveSize 1
            c2.chunkIndex shouldBe 2L
            c2.rows[0][0] shouldBe 5L

            r.nextChunk().shouldBeNull()
        }
    }

    // ─── Empty Array ────────────────────────────────────────────────

    test("empty array returns null on first nextChunk, headerColumns is null") {
        reader("[]").use { r ->
            r.headerColumns().shouldBeNull()
            r.nextChunk().shouldBeNull()
        }
    }

    // ─── Header Discovery ───────────────────────────────────────────

    test("headerColumns returns keys from first object") {
        reader("""[{"alpha": 1, "beta": 2}]""").use { r ->
            r.headerColumns() shouldBe listOf("alpha", "beta")
        }
    }

    // ─── F8: Reordered Keys ─────────────────────────────────────────

    test("F8: reordered keys in later row normalized to first-row order") {
        val json = """[{"a": 1, "b": 2}, {"b": 20, "a": 10}]"""
        reader(json).use { r ->
            val chunk = r.nextChunk()!!
            chunk.columns.map { it.name } shouldBe listOf("a", "b")
            // First row: a=1, b=2
            chunk.rows[0][0] shouldBe 1L
            chunk.rows[0][1] shouldBe 2L
            // Second row: reordered keys, but slots follow first-row order
            chunk.rows[1][0] shouldBe 10L
            chunk.rows[1][1] shouldBe 20L
        }
    }

    // ─── R9: Empty First Object ─────────────────────────────────────

    test("R9: empty first object yields empty headerColumns list") {
        reader("[{}]").use { r ->
            r.headerColumns() shouldBe emptyList()
            val chunk = r.nextChunk()!!
            chunk.rows shouldHaveSize 1
            chunk.rows[0] shouldBe emptyArray()
        }
    }

    test("R9: non-empty row after empty first object throws ImportSchemaMismatchException") {
        reader("""[{}, {"a": 1}]""").use { r ->
            r.headerColumns() shouldBe emptyList()
            shouldThrow<ImportSchemaMismatchException> {
                r.nextChunk()
            }
        }
    }

    // ─── Schema Mismatch ────────────────────────────────────────────

    test("extra key in later row throws ImportSchemaMismatchException") {
        val json = """[{"a": 1}, {"a": 2, "b": 3}]"""
        reader(json).use { r ->
            shouldThrow<ImportSchemaMismatchException> {
                r.nextChunk()
            }
        }
    }

    test("missing key in later row filled with null") {
        val json = """[{"a": 1, "b": 2}, {"a": 10}]"""
        reader(json).use { r ->
            val chunk = r.nextChunk()!!
            chunk.rows shouldHaveSize 2
            chunk.rows[1][0] shouldBe 10L
            chunk.rows[1][1].shouldBeNull()
        }
    }

    // ─── Format Errors ──────────────────────────────────────────────

    test("non-array top-level object throws ImportSchemaMismatchException") {
        shouldThrow<ImportSchemaMismatchException> {
            reader("""{"a": 1}""")
        }
    }

    test("wrapper object {\"rows\": [...]} throws ImportSchemaMismatchException") {
        shouldThrow<ImportSchemaMismatchException> {
            reader("""{"rows": [{"a": 1}, {"a": 2}]}""")
        }
    }

    test("NDJSON input throws ImportSchemaMismatchException") {
        shouldThrow<ImportSchemaMismatchException> {
            reader(
                """
                {"a": 1}
                {"a": 2}
                """.trimIndent()
            )
        }
    }

    test("non-object element in array throws ImportSchemaMismatchException") {
        shouldThrow<ImportSchemaMismatchException> {
            // The first element (integer) is parsed in init → throws
            reader("[1, 2, 3]")
        }
    }

    // ─── Lifecycle ──────────────────────────────────────────────────

    test("close is idempotent") {
        val r = reader("[]")
        r.close()
        r.close() // no exception
    }

    test("nextChunk after close throws IllegalStateException") {
        val r = reader("""[{"a": 1}]""")
        r.close()
        shouldThrow<IllegalStateException> {
            r.nextChunk()
        }
    }

    // ─── Value Types ────────────────────────────────────────────────

    test("null values preserved") {
        reader("""[{"a": null, "b": 1}]""").use { r ->
            val chunk = r.nextChunk()!!
            chunk.rows[0][0].shouldBeNull()
            chunk.rows[0][1] shouldBe 1L
        }
    }

    test("nested objects and arrays preserved as Map and List") {
        reader("""[{"data": {"x": 1}, "tags": [1, 2]}]""").use { r ->
            val chunk = r.nextChunk()!!
            val data = chunk.rows[0][0]
            data.shouldBeInstanceOf<Map<*, *>>()
            // Nested values are NOT normalized (only top-level row values are)
            ((data as Map<*, *>)["x"] as Number).toLong() shouldBe 1L

            val tags = chunk.rows[0][1]
            tags.shouldBeInstanceOf<List<*>>()
            (tags as List<*>).map { (it as Number).toLong() } shouldBe listOf(1L, 2L)
        }
    }

    test("type discrimination: integer vs decimal") {
        reader("""[{"i": 42, "d": 42.5, "z": 0, "pi": 3.14}]""").use { r ->
            val chunk = r.nextChunk()!!
            val row = chunk.rows[0]
            // Integers: DSL-JSON returns Long after normalization
            row[0].shouldBeInstanceOf<Long>()
            row[0] shouldBe 42L
            row[2].shouldBeInstanceOf<Long>()
            row[2] shouldBe 0L
            // Decimals: DSL-JSON returns a Number that is NOT Long
            // (could be Double or BigDecimal depending on DSL-JSON's ObjectConverter)
            val d = row[1]
            d.shouldBeInstanceOf<Number>()
            (d is Long) shouldBe false
            (d as Number).toDouble() shouldBe 42.5
            val pi = row[3]
            pi.shouldBeInstanceOf<Number>()
            (pi is Long) shouldBe false
            (pi as Number).toDouble() shouldBe 3.14
        }
    }

    test("boolean values preserved") {
        reader("""[{"t": true, "f": false}]""").use { r ->
            val chunk = r.nextChunk()!!
            chunk.rows[0][0] shouldBe true
            chunk.rows[0][1] shouldBe false
        }
    }

    test("string values preserved") {
        reader("""[{"s": "hello world", "e": ""}]""").use { r ->
            val chunk = r.nextChunk()!!
            chunk.rows[0][0] shouldBe "hello world"
            chunk.rows[0][1] shouldBe ""
        }
    }

    // ─── Encoding ───────────────────────────────────────────────────

    test("UTF-8 BOM handled correctly") {
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        val json = """[{"v": 1}]""".toByteArray(Charsets.UTF_8)
        val input = ByteArrayInputStream(bom + json)
        JsonChunkReader(input, "t", 100).use { r ->
            val chunk = r.nextChunk()!!
            chunk.rows[0][0] shouldBe 1L
        }
    }

    test("UTF-16 LE input transcoded correctly") {
        val bom = byteArrayOf(0xFF.toByte(), 0xFE.toByte())
        val json = """[{"v": 1}]""".toByteArray(Charsets.UTF_16LE)
        val input = ByteArrayInputStream(bom + json)
        JsonChunkReader(input, "t", 100).use { r ->
            val chunk = r.nextChunk()!!
            chunk.rows[0][0] shouldBe 1L
        }
    }
})
