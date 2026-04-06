package dev.dmigrate.format.data.json

import dev.dmigrate.core.data.ColumnDescriptor
import dev.dmigrate.core.data.DataChunk
import dev.dmigrate.format.data.ExportOptions
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.ByteArrayOutputStream
import java.math.BigDecimal

class JsonChunkWriterTest : FunSpec({

    val cols = listOf(
        ColumnDescriptor("id", nullable = false),
        ColumnDescriptor("name", nullable = false),
    )

    fun chunk(vararg rows: Array<Any?>) = DataChunk(
        table = "users",
        columns = cols,
        rows = rows.toList(),
        chunkIndex = 0,
    )

    test("two rows produce a JSON array of two objects") {
        val out = ByteArrayOutputStream()
        JsonChunkWriter(out).use { w ->
            w.begin("users", cols)
            w.write(chunk(arrayOf<Any?>(1, "alice"), arrayOf<Any?>(2, "bob")))
            w.end()
        }
        val json = out.toString(Charsets.UTF_8)
        json shouldContain "\"id\":"
        json shouldContain "\"name\":"
        json shouldContain "alice"
        json shouldContain "bob"
        json.trim().startsWith("[") shouldBe true
        json.trim().endsWith("]") shouldBe true
    }

    test("§6.17: empty table produces []") {
        val out = ByteArrayOutputStream()
        JsonChunkWriter(out).use { w ->
            w.begin("empty", cols)
            w.write(DataChunk("empty", cols, emptyList(), 0))
            w.end()
        }
        out.toString(Charsets.UTF_8).trim() shouldBe "[]"
    }

    test("null values are JSON null") {
        val out = ByteArrayOutputStream()
        JsonChunkWriter(out).use { w ->
            w.begin("users", cols)
            w.write(chunk(arrayOf<Any?>(1, null)))
            w.end()
        }
        out.toString(Charsets.UTF_8) shouldContain "\"name\": null"
    }

    test("BigDecimal is encoded as JSON string for precision") {
        val out = ByteArrayOutputStream()
        JsonChunkWriter(out).use { w ->
            w.begin("users", cols)
            w.write(chunk(arrayOf<Any?>(BigDecimal("12345.6789"), "x")))
            w.end()
        }
        // Als String, nicht als JSON-Number
        out.toString(Charsets.UTF_8) shouldContain "\"12345.6789\""
    }

    test("close() without begin() does not write any JSON content") {
        val out = ByteArrayOutputStream()
        JsonChunkWriter(out).close()
        out.toString(Charsets.UTF_8) shouldBe ""
    }

    test("multi-chunk export concatenates rows in one array") {
        val out = ByteArrayOutputStream()
        JsonChunkWriter(out).use { w ->
            w.begin("users", cols)
            w.write(DataChunk("users", cols, listOf(arrayOf<Any?>(1, "a")), 0))
            w.write(DataChunk("users", cols, listOf(arrayOf<Any?>(2, "b")), 1))
            w.end()
        }
        val json = out.toString(Charsets.UTF_8)
        json shouldContain "\"a\""
        json shouldContain "\"b\""
        (json.indexOf("\"a\"") < json.indexOf("\"b\"")) shouldBe true
    }
})
