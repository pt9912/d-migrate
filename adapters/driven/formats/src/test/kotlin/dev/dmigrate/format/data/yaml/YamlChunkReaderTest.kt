package dev.dmigrate.format.data.yaml

import dev.dmigrate.core.data.ImportSchemaMismatchException
import dev.dmigrate.format.data.FormatReadOptions
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.ByteArrayInputStream

/**
 * Unit-Tests für [YamlChunkReader].
 *
 * Plan §4 Phase B Schritt 8: YamlChunkReader mit SnakeYAML Engine Event-API.
 * Tests spiegeln die JsonChunkReaderTest-Struktur für Konsistenz.
 */
class YamlChunkReaderTest : FunSpec({

    fun reader(yaml: String, chunkSize: Int = 100, options: FormatReadOptions = FormatReadOptions()) =
        YamlChunkReader(ByteArrayInputStream(yaml.toByteArray(Charsets.UTF_8)), "t", chunkSize, options)

    // ─── Happy Path ─────────────────────────────────────────────────

    test("two rows in single chunk (block style)") {
        val yaml = """
            - id: 1
              name: alice
            - id: 2
              name: bob
        """.trimIndent()
        reader(yaml).use { r ->
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

    test("two rows in flow style") {
        reader("[{id: 1, name: alice}, {id: 2, name: bob}]").use { r ->
            val chunk = r.nextChunk()!!
            chunk.rows shouldHaveSize 2
            chunk.rows[0][0] shouldBe 1L
            chunk.rows[0][1] shouldBe "alice"
        }
    }

    test("single row, single chunk") {
        reader("- x: 42").use { r ->
            val chunk = r.nextChunk()!!
            chunk.rows shouldHaveSize 1
            chunk.rows[0][0] shouldBe 42L
            r.nextChunk().shouldBeNull()
        }
    }

    test("chunkSize larger than data returns one chunk") {
        val yaml = """
            - a: 1
            - a: 2
            - a: 3
        """.trimIndent()
        reader(yaml, chunkSize = 100).use { r ->
            val chunk = r.nextChunk()!!
            chunk.rows shouldHaveSize 3
            r.nextChunk().shouldBeNull()
        }
    }

    // ─── Multi-Chunk ────────────────────────────────────────────────

    test("splits into multiple chunks at chunkSize boundary") {
        val yaml = """
            - v: 1
            - v: 2
            - v: 3
            - v: 4
            - v: 5
        """.trimIndent()
        reader(yaml, chunkSize = 2).use { r ->
            val c0 = r.nextChunk()!!
            c0.rows shouldHaveSize 2
            c0.chunkIndex shouldBe 0L
            c0.rows[0][0] shouldBe 1L
            c0.rows[1][0] shouldBe 2L

            val c1 = r.nextChunk()!!
            c1.rows shouldHaveSize 2
            c1.chunkIndex shouldBe 1L

            val c2 = r.nextChunk()!!
            c2.rows shouldHaveSize 1
            c2.chunkIndex shouldBe 2L
            c2.rows[0][0] shouldBe 5L

            r.nextChunk().shouldBeNull()
        }
    }

    // ─── Empty Sequence ─────────────────────────────────────────────

    test("empty sequence returns null on first nextChunk, headerColumns is null") {
        reader("[]").use { r ->
            r.headerColumns().shouldBeNull()
            r.nextChunk().shouldBeNull()
        }
    }

    // ─── Header Discovery ───────────────────────────────────────────

    test("headerColumns returns keys from first mapping") {
        reader("- alpha: 1\n  beta: 2").use { r ->
            r.headerColumns() shouldBe listOf("alpha", "beta")
        }
    }

    // ─── F8: Reordered Keys ─────────────────────────────────────────

    test("F8: reordered keys in later row normalized to first-row order") {
        val yaml = """
            - a: 1
              b: 2
            - b: 20
              a: 10
        """.trimIndent()
        reader(yaml).use { r ->
            val chunk = r.nextChunk()!!
            chunk.columns.map { it.name } shouldBe listOf("a", "b")
            chunk.rows[0][0] shouldBe 1L
            chunk.rows[0][1] shouldBe 2L
            // Reordered keys → slots follow first-row order
            chunk.rows[1][0] shouldBe 10L
            chunk.rows[1][1] shouldBe 20L
        }
    }

    // ─── R9: Empty First Mapping ────────────────────────────────────

    test("R9: empty first mapping yields empty headerColumns list") {
        reader("[{}]").use { r ->
            r.headerColumns() shouldBe emptyList()
            val chunk = r.nextChunk()!!
            chunk.rows shouldHaveSize 1
            chunk.rows[0] shouldBe emptyArray()
        }
    }

    test("R9: non-empty row after empty first mapping throws ImportSchemaMismatchException") {
        reader("[{}, {a: 1}]").use { r ->
            r.headerColumns() shouldBe emptyList()
            shouldThrow<ImportSchemaMismatchException> {
                r.nextChunk()
            }
        }
    }

    // ─── Schema Mismatch ────────────────────────────────────────────

    test("extra key in later row throws ImportSchemaMismatchException") {
        val yaml = """
            - a: 1
            - a: 2
              b: 3
        """.trimIndent()
        reader(yaml).use { r ->
            shouldThrow<ImportSchemaMismatchException> {
                r.nextChunk()
            }
        }
    }

    test("missing key in later row filled with null") {
        val yaml = """
            - a: 1
              b: 2
            - a: 10
        """.trimIndent()
        reader(yaml).use { r ->
            val chunk = r.nextChunk()!!
            chunk.rows shouldHaveSize 2
            chunk.rows[1][0] shouldBe 10L
            chunk.rows[1][1].shouldBeNull()
        }
    }

    // ─── Format Errors ──────────────────────────────────────────────

    test("top-level mapping (not sequence) throws ImportSchemaMismatchException") {
        shouldThrow<ImportSchemaMismatchException> {
            reader("a: 1\nb: 2")
        }
    }

    test("top-level scalar throws ImportSchemaMismatchException") {
        shouldThrow<ImportSchemaMismatchException> {
            reader("hello world")
        }
    }

    test("non-mapping element in sequence throws ImportSchemaMismatchException") {
        shouldThrow<ImportSchemaMismatchException> {
            reader("- 1\n- 2\n- 3")
        }
    }

    test("second YAML document after records sequence throws ImportSchemaMismatchException") {
        val yaml = """
            ---
            - a: 1
            ---
            - a: 2
        """.trimIndent()
        shouldThrow<ImportSchemaMismatchException> {
            reader(yaml).use { r ->
                r.nextChunk()
            }
        }
    }

    test("truncated YAML in later nextChunk is normalized to ImportSchemaMismatchException") {
        val yaml = """
            - a: 1
            - a: [1,
        """.trimIndent()
        reader(yaml, chunkSize = 1).use { r ->
            val first = r.nextChunk()!!
            first.rows shouldHaveSize 1
            first.rows[0][0] shouldBe 1L

            shouldThrow<ImportSchemaMismatchException> {
                r.nextChunk()
            }
        }
    }

    // ─── Lifecycle ──────────────────────────────────────────────────

    test("close is idempotent") {
        val r = reader("[]")
        r.close()
        r.close() // no exception
    }

    test("nextChunk after close throws IllegalStateException") {
        val r = reader("- a: 1")
        r.close()
        shouldThrow<IllegalStateException> {
            r.nextChunk()
        }
    }

    // ─── Value Types / YAML Core Schema ─────────────────────────────

    test("null values: null keyword, tilde, empty") {
        val yaml = """
            - a: null
              b: ~
              c:
        """.trimIndent()
        reader(yaml).use { r ->
            val chunk = r.nextChunk()!!
            chunk.rows[0][0].shouldBeNull()
            chunk.rows[0][1].shouldBeNull()
            chunk.rows[0][2].shouldBeNull()
        }
    }

    test("boolean values: true and false") {
        reader("- t: true\n  f: false").use { r ->
            val chunk = r.nextChunk()!!
            chunk.rows[0][0] shouldBe true
            chunk.rows[0][1] shouldBe false
        }
    }

    test("integer vs decimal type discrimination") {
        reader("- i: 42\n  d: 42.5\n  z: 0").use { r ->
            val chunk = r.nextChunk()!!
            val row = chunk.rows[0]
            row[0].shouldBeInstanceOf<Long>()
            row[0] shouldBe 42L
            row[1].shouldBeInstanceOf<Double>()
            row[1] shouldBe 42.5
            row[2].shouldBeInstanceOf<Long>()
            row[2] shouldBe 0L
        }
    }

    test("quoted scalar is always string, even if it looks like a number") {
        reader("- v: '42'").use { r ->
            val chunk = r.nextChunk()!!
            chunk.rows[0][0].shouldBeInstanceOf<String>()
            chunk.rows[0][0] shouldBe "42"
        }
    }

    test("quoted null keyword is string, not null") {
        reader("- v: 'null'").use { r ->
            val chunk = r.nextChunk()!!
            chunk.rows[0][0] shouldBe "null"
        }
    }

    test("string values preserved") {
        reader("- s: hello world\n  e: ''").use { r ->
            val chunk = r.nextChunk()!!
            chunk.rows[0][0] shouldBe "hello world"
            chunk.rows[0][1] shouldBe ""
        }
    }

    // ─── Nested Structures ──────────────────────────────────────────

    test("nested mapping preserved as Map") {
        val yaml = """
            - data:
                x: 1
                y: 2
        """.trimIndent()
        reader(yaml).use { r ->
            val chunk = r.nextChunk()!!
            val data = chunk.rows[0][0]
            data.shouldBeInstanceOf<Map<*, *>>()
            (data as Map<*, *>)["x"] shouldBe 1L
            data["y"] shouldBe 2L
        }
    }

    test("nested sequence preserved as List") {
        val yaml = """
            - tags:
                - alpha
                - bravo
        """.trimIndent()
        reader(yaml).use { r ->
            val chunk = r.nextChunk()!!
            val tags = chunk.rows[0][0]
            tags.shouldBeInstanceOf<List<*>>()
            (tags as List<*>) shouldBe listOf("alpha", "bravo")
        }
    }

    // ─── Encoding ───────────────────────────────────────────────────

    test("UTF-8 BOM handled correctly") {
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        val yaml = "- v: 1".toByteArray(Charsets.UTF_8)
        val input = ByteArrayInputStream(bom + yaml)
        YamlChunkReader(input, "t", 100).use { r ->
            val chunk = r.nextChunk()!!
            chunk.rows[0][0] shouldBe 1L
        }
    }

    // 0.8.0 Phase F (docs/ImpPlan-0.8.0-F.md §4.5):
    // Der geteilte EncodingDetector wird auch auf dem YAML-Pfad mit
    // nicht-lateinischen Payloads abgesichert — §O2 Empfehlung.

    test("Phase F §4.5: UTF-8 BOM + Unicode-YAML-Inhalt bleibt zeichenstabil") {
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        val yaml = "- city: Москва\n  emoji: 🇯🇵".toByteArray(Charsets.UTF_8)
        val input = ByteArrayInputStream(bom + yaml)
        YamlChunkReader(input, "t", 100).use { r ->
            val chunk = r.nextChunk()!!
            chunk.rows[0][0] shouldBe "Москва"
            chunk.rows[0][1] shouldBe "🇯🇵"
        }
    }

    test("Phase F §4.5: UTF-16 LE BOM + Unicode-YAML wird transcodiert") {
        val bom = byteArrayOf(0xFF.toByte(), 0xFE.toByte())
        val yaml = "- name: 東京".toByteArray(Charsets.UTF_16LE)
        val input = ByteArrayInputStream(bom + yaml)
        YamlChunkReader(input, "t", 100).use { r ->
            val chunk = r.nextChunk()!!
            chunk.rows[0][0] shouldBe "東京"
        }
    }
})
