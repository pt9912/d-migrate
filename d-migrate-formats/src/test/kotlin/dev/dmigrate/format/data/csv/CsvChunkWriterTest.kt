package dev.dmigrate.format.data.csv

import dev.dmigrate.core.data.ColumnDescriptor
import dev.dmigrate.core.data.DataChunk
import dev.dmigrate.format.data.ExportOptions
import dev.dmigrate.format.data.ValueSerializer
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.io.ByteArrayOutputStream

/**
 * Golden-Master-Tests für CsvChunkWriter (uniVocity-parsers).
 */
class CsvChunkWriterTest : FunSpec({

    val cols = listOf(
        ColumnDescriptor("id", nullable = false),
        ColumnDescriptor("name", nullable = false),
    )

    fun runWriter(
        options: ExportOptions = ExportOptions(),
        block: (writer: dev.dmigrate.format.data.DataChunkWriter) -> Unit,
    ): String {
        val out = ByteArrayOutputStream()
        CsvChunkWriter(out, options).use { block(it) }
        return out.toString(options.encoding)
    }

    // ─── Golden Masters ──────────────────────────────────────────

    test("golden: default writes header + rows") {
        val actual = runWriter { w ->
            w.begin("users", cols)
            w.write(DataChunk("users", cols, listOf(arrayOf<Any?>(1, "alice"), arrayOf<Any?>(2, "bob")), 0))
            w.end()
        }
        actual shouldBe "id,name\n1,alice\n2,bob\n"
    }

    test("golden: §6.17 empty table with header → only header line") {
        val actual = runWriter { w ->
            w.begin("empty", cols)
            w.write(DataChunk("empty", cols, emptyList(), 0))
            w.end()
        }
        actual shouldBe "id,name\n"
    }

    test("golden: §6.17 empty table without header → empty file") {
        val actual = runWriter(ExportOptions(csvHeader = false)) { w ->
            w.begin("empty", cols)
            w.write(DataChunk("empty", cols, emptyList(), 0))
            w.end()
        }
        actual shouldBe ""
    }

    test("golden: --csv-no-header omits header line") {
        val actual = runWriter(ExportOptions(csvHeader = false)) { w ->
            w.begin("users", cols)
            w.write(DataChunk("users", cols, listOf(arrayOf<Any?>(1, "alice")), 0))
            w.end()
        }
        actual shouldBe "1,alice\n"
    }

    test("golden: custom delimiter") {
        val actual = runWriter(ExportOptions(csvDelimiter = ';')) { w ->
            w.begin("users", cols)
            w.write(DataChunk("users", cols, listOf(arrayOf<Any?>(1, "alice")), 0))
            w.end()
        }
        actual shouldBe "id;name\n1;alice\n"
    }

    test("golden: null values use csvNullString") {
        val actual = runWriter(ExportOptions(csvNullString = "NULL")) { w ->
            w.begin("users", cols)
            w.write(DataChunk("users", cols, listOf(arrayOf<Any?>(1, null)), 0))
            w.end()
        }
        actual shouldBe "id,name\n1,NULL\n"
    }

    test("golden: values with commas are quoted") {
        val actual = runWriter { w ->
            w.begin("users", cols)
            w.write(DataChunk("users", cols, listOf(arrayOf<Any?>(1, "a, b")), 0))
            w.end()
        }
        actual shouldContain "\"a, b\""
    }

    test("--csv-bom prefixes UTF-8 BOM bytes") {
        val out = ByteArrayOutputStream()
        CsvChunkWriter(out, ExportOptions(csvBom = true)).use { w ->
            w.begin("users", cols)
            w.write(DataChunk("users", cols, listOf(arrayOf<Any?>(1, "alice")), 0))
            w.end()
        }
        val bytes = out.toByteArray()
        // UTF-8 BOM = 0xEF 0xBB 0xBF
        bytes[0] shouldBe 0xEF.toByte()
        bytes[1] shouldBe 0xBB.toByte()
        bytes[2] shouldBe 0xBF.toByte()
    }

    test("close() without begin() does not throw and writes nothing") {
        val out = ByteArrayOutputStream()
        CsvChunkWriter(out).close()
        out.toString(Charsets.UTF_8) shouldBe ""
    }

    // ─── F27: echte Spaltennamen werden an ValueSerializer übergeben ───

    test("F27: W202 warning is attributed to the real column name, not col0/col1") {
        val warnings = mutableListOf<ValueSerializer.Warning>()
        val out = ByteArrayOutputStream()
        CsvChunkWriter(out, warningSink = { warnings += it }).use { w ->
            w.begin("users", cols)
            // Custom anonymous class → W202
            val custom = object { override fun toString() = "x" }
            w.write(DataChunk("users", cols, listOf(arrayOf<Any?>(1, custom)), 0))
            w.end()
        }
        warnings.size shouldBe 1
        warnings.single().column shouldBe "name"   // NOT "col1"
        warnings.single().column shouldNotContain "col"
    }
})
