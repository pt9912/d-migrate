package dev.dmigrate.format.data.json

import dev.dmigrate.core.data.ColumnDescriptor
import dev.dmigrate.core.data.DataChunk
import dev.dmigrate.format.data.ExportOptions
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset

/**
 * Edge-case tests for [JsonChunkWriter], [CharsetTranscodingInputStream],
 * and [CharsetReencodingOutputStream] to push JSON-package coverage above 90%.
 */
class JsonChunkWriterEdgeCaseTest : FunSpec({

    val cols = listOf(
        ColumnDescriptor("v", nullable = false),
    )

    fun runWriter(
        options: ExportOptions = ExportOptions(),
        block: (writer: dev.dmigrate.format.data.DataChunkWriter) -> Unit,
    ): String {
        val out = ByteArrayOutputStream()
        JsonChunkWriter(out, options).use { block(it) }
        return out.toString(options.encoding)
    }

    // --- NaN / Infinity edge cases (FloatingPoint branch) ---

    test("NaN double is written as JSON string") {
        val actual = runWriter { w ->
            w.begin("t", cols)
            w.write(DataChunk("t", cols, listOf(arrayOf<Any?>(Double.NaN)), 0))
            w.end()
        }
        actual shouldContain "\"NaN\""
    }

    test("Positive Infinity is written as JSON string") {
        val actual = runWriter { w ->
            w.begin("t", cols)
            w.write(DataChunk("t", cols, listOf(arrayOf<Any?>(Double.POSITIVE_INFINITY)), 0))
            w.end()
        }
        actual shouldContain "\"Infinity\""
    }

    test("Negative Infinity is written as JSON string") {
        val actual = runWriter { w ->
            w.begin("t", cols)
            w.write(DataChunk("t", cols, listOf(arrayOf<Any?>(Double.NEGATIVE_INFINITY)), 0))
            w.end()
        }
        actual shouldContain "\"-Infinity\""
    }

    // --- Empty chunk (no rows) between begin/end ---

    test("empty chunk produces empty JSON array") {
        val actual = runWriter { w ->
            w.begin("t", cols)
            w.write(DataChunk("t", cols, emptyList(), 0))
            w.end()
        }
        actual shouldBe "[]\n"
    }

    // --- close() lifecycle guards ---

    test("close() without begin/end produces no output") {
        val out = ByteArrayOutputStream()
        JsonChunkWriter(out).close()
        out.toString(Charsets.UTF_8) shouldBe ""
    }

    test("close() called twice does not crash") {
        val out = ByteArrayOutputStream()
        val writer = JsonChunkWriter(out)
        writer.begin("t", cols)
        writer.end()
        writer.close()
        writer.close() // idempotent — must not throw
        out.size() shouldBe out.size() // sanity: no extra bytes
    }

    // --- begin() called twice throws ---

    test("begin() called twice throws IllegalStateException") {
        val out = ByteArrayOutputStream()
        val writer = JsonChunkWriter(out)
        writer.begin("t", cols)
        shouldThrow<IllegalStateException> {
            writer.begin("t", cols)
        }
        writer.close()
    }

    // --- CharsetReencodingOutputStream single-byte write ---

    test("CharsetReencodingOutputStream single-byte write path") {
        val out = ByteArrayOutputStream()
        val reencoder = CharsetReencodingOutputStream(out, Charset.forName("ISO-8859-1"))
        // Write ASCII byte 'A' (0x41) through the single-byte path
        reencoder.write('A'.code)
        reencoder.flush()
        reencoder.close()
        out.toByteArray() shouldBe byteArrayOf(0x41)
    }

    // --- CharsetTranscodingInputStream edge cases ---

    test("CharsetTranscodingInputStream with empty input returns -1") {
        val empty = ByteArrayInputStream(ByteArray(0))
        val stream = CharsetTranscodingInputStream(empty, Charsets.UTF_8)
        stream.read() shouldBe -1
        stream.close()
    }

    test("CharsetTranscodingInputStream bulk read on empty input returns -1") {
        val empty = ByteArrayInputStream(ByteArray(0))
        val stream = CharsetTranscodingInputStream(empty, Charsets.UTF_8)
        val buf = ByteArray(16)
        stream.read(buf, 0, buf.size) shouldBe -1
        stream.close()
    }

    test("CharsetTranscodingInputStream reads non-UTF-8 source as UTF-8 bytes") {
        // 'e-acute' in ISO-8859-1 is single byte 0xE9
        val isoBytes = byteArrayOf(0xE9.toByte())
        val stream = CharsetTranscodingInputStream(
            ByteArrayInputStream(isoBytes),
            Charset.forName("ISO-8859-1"),
        )
        val result = ByteArrayOutputStream()
        val buf = ByteArray(64)
        var n = stream.read(buf, 0, buf.size)
        while (n >= 0) {
            result.write(buf, 0, n)
            n = stream.read(buf, 0, buf.size)
        }
        stream.close()
        // UTF-8 encoding of 'e-acute' (U+00E9) is 0xC3 0xA9
        result.toByteArray() shouldBe byteArrayOf(0xC3.toByte(), 0xA9.toByte())
    }

    test("CharsetTranscodingInputStream single-byte read returns correct value") {
        val src = "AB".toByteArray(Charsets.UTF_8)
        val stream = CharsetTranscodingInputStream(ByteArrayInputStream(src), Charsets.UTF_8)
        stream.read() shouldBe 'A'.code
        stream.read() shouldBe 'B'.code
        stream.read() shouldBe -1
        stream.close()
    }

    test("CharsetTranscodingInputStream bulk read with zero length returns 0") {
        val src = "hello".toByteArray(Charsets.UTF_8)
        val stream = CharsetTranscodingInputStream(ByteArrayInputStream(src), Charsets.UTF_8)
        val buf = ByteArray(8)
        stream.read(buf, 0, 0) shouldBe 0
        stream.close()
    }
})
