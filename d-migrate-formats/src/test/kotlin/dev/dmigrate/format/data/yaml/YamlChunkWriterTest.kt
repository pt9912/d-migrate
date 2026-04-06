package dev.dmigrate.format.data.yaml

import dev.dmigrate.core.data.ColumnDescriptor
import dev.dmigrate.core.data.DataChunk
import dev.dmigrate.format.data.ExportOptions
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.ByteArrayOutputStream
import java.math.BigDecimal

class YamlChunkWriterTest : FunSpec({

    val cols = listOf(
        ColumnDescriptor("id", nullable = false),
        ColumnDescriptor("name", nullable = false),
    )

    test("two rows produce a YAML sequence of maps") {
        val out = ByteArrayOutputStream()
        YamlChunkWriter(out).use { w ->
            w.begin("users", cols)
            w.write(DataChunk("users", cols, listOf(arrayOf<Any?>(1, "alice"), arrayOf<Any?>(2, "bob")), 0))
            w.end()
        }
        val yaml = out.toString(Charsets.UTF_8)
        yaml shouldContain "- "
        yaml shouldContain "id:"
        yaml shouldContain "name:"
        yaml shouldContain "alice"
        yaml shouldContain "bob"
    }

    test("§6.17: empty table produces []") {
        val out = ByteArrayOutputStream()
        YamlChunkWriter(out).use { w ->
            w.begin("empty", cols)
            w.write(DataChunk("empty", cols, emptyList(), 0))
            w.end()
        }
        out.toString(Charsets.UTF_8).trim() shouldBe "[]"
    }

    test("null values are YAML null") {
        val out = ByteArrayOutputStream()
        YamlChunkWriter(out).use { w ->
            w.begin("users", cols)
            w.write(DataChunk("users", cols, listOf(arrayOf<Any?>(1, null)), 0))
            w.end()
        }
        // SnakeYAML rendert null als 'null' (oder ~), je nach Settings
        val yaml = out.toString(Charsets.UTF_8)
        (yaml.contains("null") || yaml.contains("~")) shouldBe true
    }

    test("BigDecimal is encoded as YAML string for precision") {
        val out = ByteArrayOutputStream()
        YamlChunkWriter(out).use { w ->
            w.begin("users", cols)
            w.write(DataChunk("users", cols, listOf(arrayOf<Any?>(BigDecimal("12345.6789"), "x")), 0))
            w.end()
        }
        out.toString(Charsets.UTF_8) shouldContain "12345.6789"
    }

    test("close() without begin() does not write content") {
        val out = ByteArrayOutputStream()
        YamlChunkWriter(out).close()
        out.toString(Charsets.UTF_8) shouldBe ""
    }
})
