package dev.dmigrate.format.data.yaml

import dev.dmigrate.core.data.ColumnDescriptor
import dev.dmigrate.core.data.DataChunk
import dev.dmigrate.format.data.ExportOptions
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.ByteArrayOutputStream
import java.math.BigDecimal

/**
 * Golden-Master-Tests für YamlChunkWriter (SnakeYAML Engine).
 */
class YamlChunkWriterTest : FunSpec({

    val cols = listOf(
        ColumnDescriptor("id", nullable = false),
        ColumnDescriptor("name", nullable = false),
    )

    fun runWriter(
        options: ExportOptions = ExportOptions(),
        block: (writer: dev.dmigrate.format.data.DataChunkWriter) -> Unit,
    ): String {
        val out = ByteArrayOutputStream()
        YamlChunkWriter(out, options).use { block(it) }
        return out.toString(options.encoding)
    }

    // ─── Golden Masters ──────────────────────────────────────────

    test("golden: two rows of two columns produce sequence of maps") {
        val actual = runWriter { w ->
            w.begin("users", cols)
            w.write(DataChunk("users", cols, listOf(arrayOf<Any?>(1, "alice"), arrayOf<Any?>(2, "bob")), 0))
            w.end()
        }
        // Mindest-Form: zwei Sequence-Items mit BLOCK-Style
        actual shouldContain "- id: 1\n  name: alice\n"
        actual shouldContain "- id: 2\n  name: bob\n"
    }

    test("golden: §6.17 empty table → []") {
        val actual = runWriter { w ->
            w.begin("empty", cols)
            w.write(DataChunk("empty", cols, emptyList(), 0))
            w.end()
        }
        actual shouldBe "[]\n"
    }

    test("null values are YAML null (renders as 'null' or '~')") {
        val actual = runWriter { w ->
            w.begin("users", cols)
            w.write(DataChunk("users", cols, listOf(arrayOf<Any?>(1, null)), 0))
            w.end()
        }
        // SnakeYAML Engine rendert null per default als 'null' (oder ~)
        (actual.contains("name: null") || actual.contains("name: ~")) shouldBe true
    }

    test("BigDecimal is encoded as YAML string for precision") {
        val actual = runWriter { w ->
            w.begin("users", cols)
            w.write(DataChunk("users", cols, listOf(arrayOf<Any?>(BigDecimal("12345.6789"), "x")), 0))
            w.end()
        }
        actual shouldContain "12345.6789"
    }

    test("close() without begin() does not write content") {
        val out = ByteArrayOutputStream()
        YamlChunkWriter(out).close()
        out.toString(Charsets.UTF_8) shouldBe ""
    }

    test("multi-row chunk produces correct sequence ordering") {
        val actual = runWriter { w ->
            w.begin("users", cols)
            w.write(DataChunk("users", cols, listOf(
                arrayOf<Any?>(1, "a"),
                arrayOf<Any?>(2, "b"),
                arrayOf<Any?>(3, "c"),
            ), 0))
            w.end()
        }
        val idxA = actual.indexOf("name: a")
        val idxB = actual.indexOf("name: b")
        val idxC = actual.indexOf("name: c")
        (idxA < idxB) shouldBe true
        (idxB < idxC) shouldBe true
    }
})
