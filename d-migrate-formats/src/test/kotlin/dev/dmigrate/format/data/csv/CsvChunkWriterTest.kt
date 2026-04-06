package dev.dmigrate.format.data.csv

import dev.dmigrate.core.data.ColumnDescriptor
import dev.dmigrate.core.data.DataChunk
import dev.dmigrate.format.data.ExportOptions
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.io.ByteArrayOutputStream

class CsvChunkWriterTest : FunSpec({

    val cols = listOf(
        ColumnDescriptor("id", nullable = false),
        ColumnDescriptor("name", nullable = false),
    )

    test("default writes header + rows") {
        val out = ByteArrayOutputStream()
        CsvChunkWriter(out).use { w ->
            w.begin("users", cols)
            w.write(DataChunk("users", cols, listOf(arrayOf<Any?>(1, "alice"), arrayOf<Any?>(2, "bob")), 0))
            w.end()
        }
        val csv = out.toString(Charsets.UTF_8)
        // Header + 2 Datenzeilen
        val lines = csv.lines().filter { it.isNotEmpty() }
        lines.size shouldBe 3
        lines[0] shouldContain "id"
        lines[0] shouldContain "name"
        lines[1] shouldContain "alice"
        lines[2] shouldContain "bob"
    }

    test("§6.17: empty table with header → only header line") {
        val out = ByteArrayOutputStream()
        CsvChunkWriter(out).use { w ->
            w.begin("empty", cols)
            w.write(DataChunk("empty", cols, emptyList(), 0))
            w.end()
        }
        val lines = out.toString(Charsets.UTF_8).lines().filter { it.isNotEmpty() }
        lines.size shouldBe 1
        lines[0] shouldContain "id"
        lines[0] shouldContain "name"
    }

    test("§6.17: empty table without header → empty file") {
        val out = ByteArrayOutputStream()
        CsvChunkWriter(out, ExportOptions(csvHeader = false)).use { w ->
            w.begin("empty", cols)
            w.write(DataChunk("empty", cols, emptyList(), 0))
            w.end()
        }
        out.toString(Charsets.UTF_8).filter { !it.isWhitespace() } shouldBe ""
    }

    test("--csv-no-header omits header line") {
        val out = ByteArrayOutputStream()
        CsvChunkWriter(out, ExportOptions(csvHeader = false)).use { w ->
            w.begin("users", cols)
            w.write(DataChunk("users", cols, listOf(arrayOf<Any?>(1, "alice")), 0))
            w.end()
        }
        val csv = out.toString(Charsets.UTF_8)
        csv shouldContain "alice"
        csv shouldNotContain "id"
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

    test("custom delimiter") {
        val out = ByteArrayOutputStream()
        CsvChunkWriter(out, ExportOptions(csvDelimiter = ';')).use { w ->
            w.begin("users", cols)
            w.write(DataChunk("users", cols, listOf(arrayOf<Any?>(1, "alice")), 0))
            w.end()
        }
        out.toString(Charsets.UTF_8) shouldContain "1;alice"
    }

    test("null values use csvNullString") {
        val out = ByteArrayOutputStream()
        CsvChunkWriter(out, ExportOptions(csvNullString = "NULL")).use { w ->
            w.begin("users", cols)
            w.write(DataChunk("users", cols, listOf(arrayOf<Any?>(1, null)), 0))
            w.end()
        }
        out.toString(Charsets.UTF_8) shouldContain "1,NULL"
    }

    test("close() without begin() does not throw") {
        val out = ByteArrayOutputStream()
        CsvChunkWriter(out).close()
        out.toString(Charsets.UTF_8) shouldBe ""
    }
})
