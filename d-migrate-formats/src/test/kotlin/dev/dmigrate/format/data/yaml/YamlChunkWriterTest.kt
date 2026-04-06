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
        actual shouldBe "- id: 1\n  name: alice\n- id: 2\n  name: bob\n"
    }

    test("golden: §6.17 empty table → []") {
        val actual = runWriter { w ->
            w.begin("empty", cols)
            w.write(DataChunk("empty", cols, emptyList(), 0))
            w.end()
        }
        actual shouldBe "[]\n"
    }

    test("golden: null values are YAML null") {
        val actual = runWriter { w ->
            w.begin("users", cols)
            w.write(DataChunk("users", cols, listOf(arrayOf<Any?>(1, null)), 0))
            w.end()
        }
        actual shouldBe "- id: 1\n  name: null\n"
    }

    test("golden: BigDecimal is encoded as quoted YAML string for precision") {
        val actual = runWriter { w ->
            w.begin("users", cols)
            w.write(DataChunk("users", cols, listOf(arrayOf<Any?>(BigDecimal("12345.6789"), "x")), 0))
            w.end()
        }
        // SnakeYAML emits a numeric-looking String in single quotes so it
        // round-trips as a String (and not as a YAML float). This is the
        // §6.4.1 precision-protection contract.
        actual shouldBe "- id: '12345.6789'\n  name: x\n"
    }

    test("golden: BigInteger is encoded as YAML number") {
        val actual = runWriter { w ->
            w.begin("users", cols)
            w.write(DataChunk("users", cols, listOf(arrayOf<Any?>(java.math.BigInteger("99999999999999999999"), "x")), 0))
            w.end()
        }
        actual shouldBe "- id: 99999999999999999999\n  name: x\n"
    }

    test("close() without begin() does not write content") {
        val out = ByteArrayOutputStream()
        YamlChunkWriter(out).close()
        out.toString(Charsets.UTF_8) shouldBe ""
    }

    test("golden: multi-row chunk produces correct sequence ordering") {
        val actual = runWriter { w ->
            w.begin("users", cols)
            w.write(DataChunk("users", cols, listOf(
                arrayOf<Any?>(1, "a"),
                arrayOf<Any?>(2, "b"),
                arrayOf<Any?>(3, "c"),
            ), 0))
            w.end()
        }
        actual shouldBe "- id: 1\n  name: a\n- id: 2\n  name: b\n- id: 3\n  name: c\n"
    }

    // ─── F29: java.sql.Array → YAML-Sequence (rekursiv) ─────────

    test("golden: java.sql.Array is rendered as nested YAML sequence") {
        val sqlArray = object : java.sql.Array {
            override fun getBaseTypeName() = "int4"
            override fun getBaseType() = java.sql.Types.INTEGER
            override fun getArray(): Any = arrayOf<Any?>(10, 20, 30)
            override fun getArray(map: MutableMap<String, Class<*>>?) = array
            override fun getArray(index: Long, count: Int) = array
            override fun getArray(index: Long, count: Int, map: MutableMap<String, Class<*>>?) = array
            override fun getResultSet(): java.sql.ResultSet = throw UnsupportedOperationException()
            override fun getResultSet(map: MutableMap<String, Class<*>>?) = throw UnsupportedOperationException()
            override fun getResultSet(index: Long, count: Int) = throw UnsupportedOperationException()
            override fun getResultSet(index: Long, count: Int, map: MutableMap<String, Class<*>>?) = throw UnsupportedOperationException()
            override fun free() {}
        }
        val actual = runWriter { w ->
            w.begin("users", cols)
            w.write(DataChunk("users", cols, listOf(arrayOf<Any?>(1, sqlArray)), 0))
            w.end()
        }
        // SnakeYAML rendert eine Sequence-of-Numbers im BLOCK-Style innerhalb der Map
        actual shouldBe "- id: 1\n  name:\n  - 10\n  - 20\n  - 30\n"
    }
})
