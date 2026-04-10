package dev.dmigrate.format.data.json

import dev.dmigrate.core.data.ColumnDescriptor
import dev.dmigrate.core.data.DataChunk
import dev.dmigrate.format.data.ExportOptions
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.nio.charset.Charset

/**
 * Golden-Master-Tests für JsonChunkWriter (DSL-JSON).
 *
 * Plan §4 Phase D Schritt 22: "Unit-Tests pro Writer mit Golden-Master-Output
 * (inkl. leerer Tabelle, §6.17)". Wir verwenden exakte Inline-Strings statt
 * externer Fixture-Files, weil die Writer-Outputs überschaubar sind und
 * der Diff bei Test-Failure direkt im IDE-Test-Runner sichtbar wird.
 */
class JsonChunkWriterTest : FunSpec({

    val cols = listOf(
        ColumnDescriptor("id", nullable = false),
        ColumnDescriptor("name", nullable = false),
    )

    fun runWriter(
        options: ExportOptions = ExportOptions(),
        block: (writer: dev.dmigrate.format.data.DataChunkWriter) -> Unit,
    ): String {
        val out = ByteArrayOutputStream()
        JsonChunkWriter(out, options).use { block(it) }
        return out.toString(options.encoding)
    }

    // ─── Golden Masters ──────────────────────────────────────────

    test("golden: two rows of two columns") {
        val actual = runWriter { w ->
            w.begin("users", cols)
            w.write(DataChunk("users", cols, listOf(arrayOf<Any?>(1, "alice"), arrayOf<Any?>(2, "bob")), 0))
            w.end()
        }
        val expected = "[\n" +
            "  {\"id\": 1,\"name\": \"alice\"},\n" +
            "  {\"id\": 2,\"name\": \"bob\"}\n" +
            "]\n"
        actual shouldBe expected
    }

    test("golden: §6.17 empty table → []") {
        val actual = runWriter { w ->
            w.begin("empty", cols)
            w.write(DataChunk("empty", cols, emptyList(), 0))
            w.end()
        }
        actual shouldBe "[]\n"
    }

    test("golden: null values are JSON null") {
        val actual = runWriter { w ->
            w.begin("users", cols)
            w.write(DataChunk("users", cols, listOf(arrayOf<Any?>(1, null)), 0))
            w.end()
        }
        actual shouldBe "[\n" +
            "  {\"id\": 1,\"name\": null}\n" +
            "]\n"
    }

    test("golden: BigDecimal is encoded as JSON string for precision") {
        val actual = runWriter { w ->
            w.begin("users", cols)
            w.write(DataChunk("users", cols, listOf(arrayOf<Any?>(BigDecimal("12345.6789"), "x")), 0))
            w.end()
        }
        actual shouldBe "[\n" +
            "  {\"id\": \"12345.6789\",\"name\": \"x\"}\n" +
            "]\n"
    }

    test("golden: special characters are properly escaped") {
        val actual = runWriter { w ->
            w.begin("users", cols)
            w.write(DataChunk("users", cols, listOf(arrayOf<Any?>(1, "a\"b\\c")), 0))
            w.end()
        }
        // DSL-JSON escapes " and \ correctly
        actual shouldContain "\"a\\\"b\\\\c\""
    }

    test("golden: unicode is preserved in UTF-8 default") {
        val actual = runWriter { w ->
            w.begin("users", cols)
            w.write(DataChunk("users", cols, listOf(arrayOf<Any?>(1, "Ünicödé")), 0))
            w.end()
        }
        actual shouldContain "Ünicödé"
    }

    test("close() without begin() does not write any JSON content") {
        val out = ByteArrayOutputStream()
        JsonChunkWriter(out).close()
        out.toString(Charsets.UTF_8) shouldBe ""
    }

    test("multi-chunk export concatenates rows in one array") {
        val actual = runWriter { w ->
            w.begin("users", cols)
            w.write(DataChunk("users", cols, listOf(arrayOf<Any?>(1, "a")), 0))
            w.write(DataChunk("users", cols, listOf(arrayOf<Any?>(2, "b")), 1))
            w.end()
        }
        actual shouldBe "[\n" +
            "  {\"id\": 1,\"name\": \"a\"},\n" +
            "  {\"id\": 2,\"name\": \"b\"}\n" +
            "]\n"
    }

    // ─── F25: ExportOptions.encoding wird respektiert ────────────

    test("F25: --encoding iso-8859-1 produces ISO-8859-1 bytes") {
        val out = ByteArrayOutputStream()
        JsonChunkWriter(out, ExportOptions(encoding = Charset.forName("ISO-8859-1"))).use { w ->
            w.begin("users", cols)
            w.write(DataChunk("users", cols, listOf(arrayOf<Any?>(1, "café")), 0))
            w.end()
        }
        // 'é' ist 0xE9 in ISO-8859-1 (1 byte), in UTF-8 wäre es 0xC3 0xA9 (2 bytes)
        val bytes = out.toByteArray()
        bytes.contains(0xE9.toByte()) shouldBe true
        // Re-decoding bestätigt
        val asString = String(bytes, Charsets.ISO_8859_1)
        asString shouldContain "café"
    }

    test("F25: UTF-8 default produces UTF-8 multi-byte chars") {
        val out = ByteArrayOutputStream()
        JsonChunkWriter(out).use { w ->
            w.begin("users", cols)
            w.write(DataChunk("users", cols, listOf(arrayOf<Any?>(1, "café")), 0))
            w.end()
        }
        val bytes = out.toByteArray()
        // UTF-8 'é' = 0xC3 0xA9 als sequence
        val seq = bytes.indices.firstOrNull { bytes[it] == 0xC3.toByte() && it + 1 < bytes.size && bytes[it + 1] == 0xA9.toByte() }
        (seq != null) shouldBe true
    }
})
