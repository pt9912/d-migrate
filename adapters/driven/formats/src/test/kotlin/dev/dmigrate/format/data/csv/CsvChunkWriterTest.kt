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
import java.nio.charset.StandardCharsets

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

    // 0.8.0 Phase F (docs/ImpPlan-0.8.0-F.md §4.4 / Entscheidung D1):
    // --csv-bom schreibt das BOM passend zum --encoding. Die drei UTF-BOMs
    // sind produktiv; Nicht-UTF-Encodings sind explizit No-op.

    test("Phase F §4.4: --csv-bom + UTF-16 BE prefixes 0xFE 0xFF BOM bytes") {
        val out = ByteArrayOutputStream()
        CsvChunkWriter(
            out,
            ExportOptions(csvBom = true, encoding = StandardCharsets.UTF_16BE),
        ).use { w ->
            w.begin("users", cols)
            w.write(DataChunk("users", cols, listOf(arrayOf<Any?>(1, "Ярослав")), 0))
            w.end()
        }
        val bytes = out.toByteArray()
        bytes[0] shouldBe 0xFE.toByte()
        bytes[1] shouldBe 0xFF.toByte()
        // Payload danach muss als UTF-16 BE lesbar sein, Unicode stabil.
        val content = String(bytes, 2, bytes.size - 2, StandardCharsets.UTF_16BE)
        content shouldContain "Ярослав"
    }

    test("Phase F §4.4: --csv-bom + UTF-16 LE prefixes 0xFF 0xFE BOM bytes") {
        val out = ByteArrayOutputStream()
        CsvChunkWriter(
            out,
            ExportOptions(csvBom = true, encoding = StandardCharsets.UTF_16LE),
        ).use { w ->
            w.begin("users", cols)
            w.write(DataChunk("users", cols, listOf(arrayOf<Any?>(1, "中文测试")), 0))
            w.end()
        }
        val bytes = out.toByteArray()
        bytes[0] shouldBe 0xFF.toByte()
        bytes[1] shouldBe 0xFE.toByte()
        val content = String(bytes, 2, bytes.size - 2, StandardCharsets.UTF_16LE)
        content shouldContain "中文测试"
    }

    test("Phase F §4.4: --csv-bom + ISO-8859-1 ist No-op (kein BOM definiert)") {
        val out = ByteArrayOutputStream()
        CsvChunkWriter(
            out,
            ExportOptions(csvBom = true, encoding = StandardCharsets.ISO_8859_1),
        ).use { w ->
            w.begin("users", cols)
            w.write(DataChunk("users", cols, listOf(arrayOf<Any?>(1, "café")), 0))
            w.end()
        }
        val bytes = out.toByteArray()
        // Kein UTF-BOM-Prefix; direkt die Header-Zeile.
        // ASCII 'i' = 0x69, erstes Byte von "id,name"
        bytes[0] shouldBe 0x69.toByte()
        val content = String(bytes, StandardCharsets.ISO_8859_1)
        content shouldContain "café"
    }

    test("Phase F §4.5: Unicode-Inhalte bleiben im UTF-8-Default byte- und zeichenstabil") {
        val out = ByteArrayOutputStream()
        CsvChunkWriter(out).use { w ->
            w.begin("users", cols)
            w.write(
                DataChunk(
                    "users",
                    cols,
                    listOf(
                        arrayOf<Any?>(1, "Москва"),
                        arrayOf<Any?>(2, "東京"),
                        arrayOf<Any?>(3, "München 🇩🇪"),
                    ),
                    0,
                ),
            )
            w.end()
        }
        val content = out.toString(StandardCharsets.UTF_8)
        content shouldContain "Москва"
        content shouldContain "東京"
        content shouldContain "München 🇩🇪"
    }

    test("close() without begin() does not throw and writes nothing") {
        val out = ByteArrayOutputStream()
        CsvChunkWriter(out).close()
        out.toString(Charsets.UTF_8) shouldBe ""
    }

    // ─── F29: java.sql.Array → W201 + null in CSV ────────────────

    test("F29: java.sql.Array column is rendered as null and emits W201 once per column") {
        val warnings = mutableListOf<ValueSerializer.Warning>()
        val out = ByteArrayOutputStream()
        val sqlArray = object : java.sql.Array {
            override fun getBaseTypeName() = "int4"
            override fun getBaseType() = java.sql.Types.INTEGER
            override fun getArray(): Any = intArrayOf(1, 2, 3)
            override fun getArray(map: MutableMap<String, Class<*>>?) = array
            override fun getArray(index: Long, count: Int) = array
            override fun getArray(index: Long, count: Int, map: MutableMap<String, Class<*>>?) = array
            override fun getResultSet(): java.sql.ResultSet = throw UnsupportedOperationException()
            override fun getResultSet(map: MutableMap<String, Class<*>>?) = throw UnsupportedOperationException()
            override fun getResultSet(index: Long, count: Int) = throw UnsupportedOperationException()
            override fun getResultSet(index: Long, count: Int, map: MutableMap<String, Class<*>>?) = throw UnsupportedOperationException()
            override fun free() {}
        }
        CsvChunkWriter(out, warningSink = { warnings += it }).use { w ->
            w.begin("users", cols)
            w.write(DataChunk("users", cols, listOf(
                arrayOf<Any?>(1, sqlArray),
                arrayOf<Any?>(2, sqlArray),
            ), 0))
            w.end()
        }
        val text = out.toString(Charsets.UTF_8)
        text shouldBe "id,name\n1,\n2,\n"
        // W201 only emitted once per (table, column)
        warnings.size shouldBe 1
        warnings.single().code shouldBe "W201"
        warnings.single().column shouldBe "name"
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
