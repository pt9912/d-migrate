package dev.dmigrate.format.data.yaml

import dev.dmigrate.core.data.ImportSchemaMismatchException
import dev.dmigrate.format.data.FormatReadOptions
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.shouldBeNaN
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.ByteArrayInputStream

/**
 * Edge-case tests for [YamlChunkReader] targeting uncovered branches.
 *
 * Covers: special YAML float values, error handling in nextEvent/hasMoreEvents,
 * validateTailAfterSequence, init error paths, YAML aliases, and normalizeRow
 * with extra keys.
 */
class YamlChunkReaderEdgeCaseTest : FunSpec({

    fun reader(yaml: String, chunkSize: Int = 100, options: FormatReadOptions = FormatReadOptions()) =
        YamlChunkReader(ByteArrayInputStream(yaml.toByteArray(Charsets.UTF_8)), "t", chunkSize, options)

    // ── Special YAML Float Values (resolveScalar lines 285-288) ────

    test(".inf resolves to Double.POSITIVE_INFINITY") {
        reader("- v: .inf").use { r ->
            val chunk = r.nextChunk()!!
            chunk.rows[0][0].shouldBeInstanceOf<Double>()
            chunk.rows[0][0] shouldBe Double.POSITIVE_INFINITY
        }
    }

    test("+.inf resolves to Double.POSITIVE_INFINITY") {
        reader("- v: +.inf").use { r ->
            val chunk = r.nextChunk()!!
            chunk.rows[0][0].shouldBeInstanceOf<Double>()
            chunk.rows[0][0] shouldBe Double.POSITIVE_INFINITY
        }
    }

    test("-.inf resolves to Double.NEGATIVE_INFINITY") {
        reader("- v: -.inf").use { r ->
            val chunk = r.nextChunk()!!
            chunk.rows[0][0].shouldBeInstanceOf<Double>()
            chunk.rows[0][0] shouldBe Double.NEGATIVE_INFINITY
        }
    }

    test(".nan resolves to Double.NaN") {
        reader("- v: .nan").use { r ->
            val chunk = r.nextChunk()!!
            chunk.rows[0][0].shouldBeInstanceOf<Double>()
            (chunk.rows[0][0] as Double).shouldBeNaN()
        }
    }

    test("all special floats in one row") {
        val yaml = """
            - a: .inf
              b: +.inf
              c: -.inf
              d: .nan
        """.trimIndent()
        reader(yaml).use { r ->
            val chunk = r.nextChunk()!!
            chunk.rows[0][0] shouldBe Double.POSITIVE_INFINITY
            chunk.rows[0][1] shouldBe Double.POSITIVE_INFINITY
            chunk.rows[0][2] shouldBe Double.NEGATIVE_INFINITY
            (chunk.rows[0][3] as Double).shouldBeNaN()
        }
    }

    // ── YAML Aliases (line 221-223, 257-259) ───────────────────────

    test("YAML alias as top-level sequence element throws ImportSchemaMismatchException") {
        val yaml = """
            - &anchor
              id: 1
            - *anchor
        """.trimIndent()
        reader(yaml, chunkSize = 1).use { r ->
            val first = r.nextChunk()!!
            first.rows[0][0] shouldBe 1L
            shouldThrow<ImportSchemaMismatchException> {
                r.nextChunk()
            }
        }
    }

    test("YAML alias in mapping value position throws ImportSchemaMismatchException") {
        val yaml = """
            - name: &val hello
              alias: *val
        """.trimIndent()
        shouldThrow<ImportSchemaMismatchException> {
            reader(yaml).use { r -> r.nextChunk() }
        }.message shouldBe "Table 't': YAML aliases are not supported for data import"
    }

    test("YAML alias in nested sequence throws ImportSchemaMismatchException") {
        val yaml = """
            - tags:
                - &a hello
                - *a
        """.trimIndent()
        shouldThrow<ImportSchemaMismatchException> {
            reader(yaml).use { r -> r.nextChunk() }
        }.message shouldBe "Table 't': YAML aliases are not supported"
    }

    // ── Init Error: invalid YAML input (line 88-89) ────────────────

    test("completely invalid YAML syntax at init throws ImportSchemaMismatchException") {
        // SnakeYAML Engine parses lazily, but some syntax errors are detected
        // during initial prolog consumption (StreamStart/DocumentStart/SequenceStart)
        val brokenYaml = "{{{"
        shouldThrow<ImportSchemaMismatchException> {
            reader(brokenYaml)
        }
    }

    test("tab indentation triggers YamlEngineException wrapped as ImportSchemaMismatchException") {
        // YAML forbids tabs for indentation; SnakeYAML Engine raises YamlEngineException
        val yaml = "- a: 1\n\t- b: 2"
        shouldThrow<ImportSchemaMismatchException> {
            reader(yaml).use { r -> r.nextChunk() }
        }
    }

    // ── nextEvent NoSuchElementException (line 360-361) ────────────

    test("truncated YAML missing stream end throws ImportSchemaMismatchException") {
        // This YAML has a valid sequence start but is missing SequenceEnd/DocumentEnd/StreamEnd.
        // We craft a stream that ends prematurely by providing raw events through a custom InputStream
        // that cuts off mid-parse. A simpler approach: a mapping that starts but never closes.
        val yaml = "- a: 1\n- a: [1,"
        reader(yaml, chunkSize = 1).use { r ->
            val first = r.nextChunk()!!
            first.rows[0][0] shouldBe 1L
            shouldThrow<ImportSchemaMismatchException> {
                r.nextChunk()
            }
        }
    }

    // ── nextEvent YamlEngineException (line 362-363) ───────────────

    test("malformed YAML in later row wraps YamlEngineException as ImportSchemaMismatchException") {
        // First row is valid, second row has broken YAML that triggers YamlEngineException
        val yaml = "- a: 1\n- a: !!invalid/tag {"
        reader(yaml, chunkSize = 1).use { r ->
            val first = r.nextChunk()!!
            first.rows[0][0] shouldBe 1L
            shouldThrow<ImportSchemaMismatchException> {
                r.nextChunk()
            }
        }
    }

    // ── validateTailAfterSequence: unexpected event after sequence ──

    test("trailing content after sequence end throws ImportSchemaMismatchException") {
        // Two YAML documents: first is valid sequence, second is extra content
        val yaml = """
            ---
            - a: 1
            ---
            - a: 2
        """.trimIndent()
        shouldThrow<ImportSchemaMismatchException> {
            reader(yaml).use { r -> r.nextChunk() }
        }
    }

    test("three documents triggers trailing content error") {
        val yaml = "---\n- a: 1\n---\n- b: 2\n---\n- c: 3"
        shouldThrow<ImportSchemaMismatchException> {
            reader(yaml).use { r -> r.nextChunk() }
        }
    }

    // ── normalizeRow with extra keys (lines 308-325) ───────────────

    test("extra key in second row with multiple extra keys throws ImportSchemaMismatchException") {
        val yaml = """
            - a: 1
            - a: 2
              b: 3
              c: 4
        """.trimIndent()
        reader(yaml).use { r ->
            shouldThrow<ImportSchemaMismatchException> {
                r.nextChunk()
            }.message shouldBe "Table 't': YAML mapping contains key 'b' " +
                "which is not present in the first row's schema [a]"
        }
    }

    test("extra key in third row (cross-chunk) throws ImportSchemaMismatchException") {
        val yaml = """
            - x: 1
            - x: 2
            - x: 3
              extra: 99
        """.trimIndent()
        reader(yaml, chunkSize = 2).use { r ->
            val c0 = r.nextChunk()!!
            c0.rows.size shouldBe 2
            shouldThrow<ImportSchemaMismatchException> {
                r.nextChunk()
            }
        }
    }

    // ── Empty sequence validateTailAfterSequence (line 117) ────────

    test("empty sequence with trailing document throws ImportSchemaMismatchException") {
        val yaml = "---\n[]\n---\n- a: 1"
        shouldThrow<ImportSchemaMismatchException> {
            reader(yaml)
        }
    }

    // ── Quoted special values remain strings ───────────────────────

    test("quoted .inf and .nan are strings, not special doubles") {
        val yaml = """
            - a: '.inf'
              b: '.nan'
              c: '-.inf'
        """.trimIndent()
        reader(yaml).use { r ->
            val chunk = r.nextChunk()!!
            chunk.rows[0][0].shouldBeInstanceOf<String>()
            chunk.rows[0][0] shouldBe ".inf"
            chunk.rows[0][1] shouldBe ".nan"
            chunk.rows[0][2] shouldBe "-.inf"
        }
    }

    // ── Special float values in nested structures ──────────────────

    test("special floats inside nested mapping") {
        val yaml = """
            - data:
                pos: .inf
                neg: -.inf
                nan: .nan
        """.trimIndent()
        reader(yaml).use { r ->
            val chunk = r.nextChunk()!!
            val data = chunk.rows[0][0] as Map<*, *>
            data["pos"] shouldBe Double.POSITIVE_INFINITY
            data["neg"] shouldBe Double.NEGATIVE_INFINITY
            (data["nan"] as Double).shouldBeNaN()
        }
    }

    test("special floats inside nested sequence") {
        val yaml = """
            - values:
                - .inf
                - -.inf
                - .nan
        """.trimIndent()
        reader(yaml).use { r ->
            val chunk = r.nextChunk()!!
            val values = chunk.rows[0][0] as List<*>
            values[0] shouldBe Double.POSITIVE_INFINITY
            values[1] shouldBe Double.NEGATIVE_INFINITY
            (values[2] as Double).shouldBeNaN()
        }
    }

    // ── Non-scalar in mapping key position ─────────────────────────

    test("non-scalar key in top-level mapping throws ImportSchemaMismatchException") {
        // A sequence as a mapping key is not a scalar
        val yaml = "- ? [complex, key]\n  : value"
        shouldThrow<ImportSchemaMismatchException> {
            reader(yaml).use { r -> r.nextChunk() }
        }
    }

    // ── validateTailAfterSequence happy path (via nextChunk) ──────

    test("normal single-row YAML reads correctly and exhausts cleanly") {
        val yaml = "- id: 1\n  name: alice\n"
        reader(yaml).use { r ->
            val c = r.nextChunk()!!
            c.rows.size shouldBe 1
            c.rows[0][0] shouldBe 1L
            c.rows[0][1] shouldBe "alice"
            // Second call triggers validateTailAfterSequence and returns null
            r.nextChunk() shouldBe null
        }
    }

    test("multi-chunk read reaches validateTailAfterSequence on last chunk") {
        val yaml = "- v: 1\n- v: 2\n- v: 3\n"
        reader(yaml, chunkSize = 2).use { r ->
            val c0 = r.nextChunk()!!
            c0.rows.size shouldBe 2
            val c1 = r.nextChunk()!!
            c1.rows.size shouldBe 1
            r.nextChunk() shouldBe null
        }
    }
})
