package dev.dmigrate.format.data

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets

class EncodingDetectorTest : FunSpec({

    // ──────────────────────────────────────────────────────────────────
    // detectOrFallback — --encoding auto path (BOM detection)
    // ──────────────────────────────────────────────────────────────────

    test("detectOrFallback: UTF-8 BOM is recognised and consumed") {
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        val payload = "hello".toByteArray(StandardCharsets.UTF_8)
        val raw = ByteArrayInputStream(bom + payload)
        val d = EncodingDetector.detectOrFallback(raw)
        d.charset shouldBe StandardCharsets.UTF_8
        // BOM is consumed — reading the stream must return just "hello"
        d.stream.readBytes().toString(StandardCharsets.UTF_8) shouldBe "hello"
    }

    test("detectOrFallback: UTF-16 BE BOM is recognised and consumed") {
        val bom = byteArrayOf(0xFE.toByte(), 0xFF.toByte())
        val payload = "hi".toByteArray(StandardCharsets.UTF_16BE)
        val raw = ByteArrayInputStream(bom + payload)
        val d = EncodingDetector.detectOrFallback(raw)
        d.charset shouldBe StandardCharsets.UTF_16BE
        d.stream.readBytes().toString(StandardCharsets.UTF_16BE) shouldBe "hi"
    }

    test("detectOrFallback: UTF-16 LE BOM is recognised and consumed") {
        val bom = byteArrayOf(0xFF.toByte(), 0xFE.toByte())
        val payload = "hi".toByteArray(StandardCharsets.UTF_16LE)
        val raw = ByteArrayInputStream(bom + payload)
        val d = EncodingDetector.detectOrFallback(raw)
        d.charset shouldBe StandardCharsets.UTF_16LE
        d.stream.readBytes().toString(StandardCharsets.UTF_16LE) shouldBe "hi"
    }

    test("detectOrFallback: UTF-32 BE BOM is rejected with a clear message") {
        val bom = byteArrayOf(0x00, 0x00, 0xFE.toByte(), 0xFF.toByte())
        val raw = ByteArrayInputStream(bom + byteArrayOf(1, 2, 3, 4))
        val ex = shouldThrow<UnsupportedFileEncodingException> {
            EncodingDetector.detectOrFallback(raw)
        }
        ex.message!!.contains("UTF-32") shouldBe true
        ex.message!!.contains("--encoding") shouldBe true
    }

    test("detectOrFallback: UTF-32 LE BOM is rejected (not misread as UTF-16 LE)") {
        val bom = byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0x00, 0x00)
        val raw = ByteArrayInputStream(bom + byteArrayOf(1, 2))
        val ex = shouldThrow<UnsupportedFileEncodingException> {
            EncodingDetector.detectOrFallback(raw)
        }
        ex.message!!.contains("UTF-32") shouldBe true
    }

    test("detectOrFallback: no BOM → UTF-8 fallback, bytes are preserved") {
        val payload = "no bom here".toByteArray(StandardCharsets.UTF_8)
        val raw = ByteArrayInputStream(payload)
        val d = EncodingDetector.detectOrFallback(raw)
        d.charset shouldBe StandardCharsets.UTF_8
        d.stream.readBytes().toString(StandardCharsets.UTF_8) shouldBe "no bom here"
    }

    test("detectOrFallback: short input (< 4 bytes) without BOM → UTF-8 fallback") {
        val raw = ByteArrayInputStream("hi".toByteArray(StandardCharsets.UTF_8))
        val d = EncodingDetector.detectOrFallback(raw)
        d.charset shouldBe StandardCharsets.UTF_8
        d.stream.readBytes().toString(StandardCharsets.UTF_8) shouldBe "hi"
    }

    test("detectOrFallback: empty input → UTF-8 fallback, stream has 0 bytes") {
        val raw = ByteArrayInputStream(ByteArray(0))
        val d = EncodingDetector.detectOrFallback(raw)
        d.charset shouldBe StandardCharsets.UTF_8
        d.stream.readBytes().size shouldBe 0
    }

    test("detectOrFallback: UTF-16 LE with only BOM + 0 payload bytes") {
        val bom = byteArrayOf(0xFF.toByte(), 0xFE.toByte())
        val raw = ByteArrayInputStream(bom)
        val d = EncodingDetector.detectOrFallback(raw)
        d.charset shouldBe StandardCharsets.UTF_16LE
        d.stream.readBytes().size shouldBe 0
    }

    // ──────────────────────────────────────────────────────────────────
    // wrapWithExplicit — --encoding <name> path
    // ──────────────────────────────────────────────────────────────────

    test("wrapWithExplicit UTF-8: matching BOM is consumed") {
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        val payload = "x".toByteArray(StandardCharsets.UTF_8)
        val raw = ByteArrayInputStream(bom + payload)
        val wrapped = EncodingDetector.wrapWithExplicit(raw, StandardCharsets.UTF_8)
        wrapped.readBytes().toString(StandardCharsets.UTF_8) shouldBe "x"
    }

    test("wrapWithExplicit UTF-8: no BOM → stream is unchanged (bytes preserved)") {
        val payload = "plain".toByteArray(StandardCharsets.UTF_8)
        val raw = ByteArrayInputStream(payload)
        val wrapped = EncodingDetector.wrapWithExplicit(raw, StandardCharsets.UTF_8)
        wrapped.readBytes().toString(StandardCharsets.UTF_8) shouldBe "plain"
    }

    test("wrapWithExplicit ISO-8859-1: UTF-8 BOM in stream is NOT consumed (§6.9 mismatch case)") {
        // User explicitly requested ISO-8859-1, but the file starts with a
        // UTF-8 BOM. The BOM must NOT be consumed — the decoder will see
        // it as raw bytes.
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        val payload = "rest".toByteArray(StandardCharsets.ISO_8859_1)
        val raw = ByteArrayInputStream(bom + payload)
        val wrapped = EncodingDetector.wrapWithExplicit(raw, StandardCharsets.ISO_8859_1)
        val all = wrapped.readBytes()
        all.size shouldBe bom.size + payload.size
        all[0] shouldBe 0xEF.toByte()
        all[1] shouldBe 0xBB.toByte()
        all[2] shouldBe 0xBF.toByte()
    }

    test("wrapWithExplicit UTF-16BE: matching BOM is consumed") {
        val bom = byteArrayOf(0xFE.toByte(), 0xFF.toByte())
        val payload = "x".toByteArray(StandardCharsets.UTF_16BE)
        val raw = ByteArrayInputStream(bom + payload)
        val wrapped = EncodingDetector.wrapWithExplicit(raw, StandardCharsets.UTF_16BE)
        wrapped.readBytes().toString(StandardCharsets.UTF_16BE) shouldBe "x"
    }

    test("wrapWithExplicit UTF-16LE: matching BOM is consumed") {
        val bom = byteArrayOf(0xFF.toByte(), 0xFE.toByte())
        val payload = "x".toByteArray(StandardCharsets.UTF_16LE)
        val raw = ByteArrayInputStream(bom + payload)
        val wrapped = EncodingDetector.wrapWithExplicit(raw, StandardCharsets.UTF_16LE)
        wrapped.readBytes().toString(StandardCharsets.UTF_16LE) shouldBe "x"
    }

    test("wrapWithExplicit UTF-16LE: a UTF-32 LE BOM is NOT consumed as if it were UTF-16 LE") {
        // FF FE 00 00 is UTF-32 LE BOM. With explicit UTF-16 LE we must not
        // swallow the first 2 bytes — the decoder will then see the 00 00
        // payload and the mismatch surfaces downstream.
        val bytes = byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0x00, 0x00, 0x01, 0x00)
        val raw = ByteArrayInputStream(bytes)
        val wrapped = EncodingDetector.wrapWithExplicit(raw, StandardCharsets.UTF_16LE)
        // All 6 bytes must remain accessible.
        val back = wrapped.readBytes()
        back.size shouldBe 6
        back[0] shouldBe 0xFF.toByte()
    }

    test("wrapWithExplicit empty input → wrapper is a no-op") {
        val raw = ByteArrayInputStream(ByteArray(0))
        val wrapped = EncodingDetector.wrapWithExplicit(raw, StandardCharsets.UTF_8)
        wrapped.readBytes().size shouldBe 0
    }

    // ──────────────────────────────────────────────────────────────────
    // 0.8.0 Phase F (docs/ImpPlan-0.8.0-F.md §4.5):
    // BOM-/Encoding-Pfade werden mit nicht-lateinischen Payloads abgesichert.
    // ──────────────────────────────────────────────────────────────────

    test("Phase F §4.5: UTF-8 BOM + kyrillischer Payload bleibt zeichenstabil") {
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        val payload = "Ярослав Москва".toByteArray(StandardCharsets.UTF_8)
        val raw = ByteArrayInputStream(bom + payload)
        val d = EncodingDetector.detectOrFallback(raw)
        d.charset shouldBe StandardCharsets.UTF_8
        d.stream.readBytes().toString(StandardCharsets.UTF_8) shouldBe "Ярослав Москва"
    }

    test("Phase F §4.5: UTF-16 BE BOM + CJK-Payload bleibt zeichenstabil") {
        val bom = byteArrayOf(0xFE.toByte(), 0xFF.toByte())
        val payload = "東京 中文 テスト".toByteArray(StandardCharsets.UTF_16BE)
        val raw = ByteArrayInputStream(bom + payload)
        val d = EncodingDetector.detectOrFallback(raw)
        d.charset shouldBe StandardCharsets.UTF_16BE
        d.stream.readBytes().toString(StandardCharsets.UTF_16BE) shouldBe "東京 中文 テスト"
    }

    test("Phase F §4.5: UTF-16 LE BOM + Emoji-Payload bleibt zeichenstabil") {
        val bom = byteArrayOf(0xFF.toByte(), 0xFE.toByte())
        val payload = "Grüße 🇩🇪 Café".toByteArray(StandardCharsets.UTF_16LE)
        val raw = ByteArrayInputStream(bom + payload)
        val d = EncodingDetector.detectOrFallback(raw)
        d.charset shouldBe StandardCharsets.UTF_16LE
        d.stream.readBytes().toString(StandardCharsets.UTF_16LE) shouldBe "Grüße 🇩🇪 Café"
    }

    test("Phase F §4.5: explizites UTF-16 BE konsumiert passendes BOM, Unicode-Payload bleibt") {
        val bom = byteArrayOf(0xFE.toByte(), 0xFF.toByte())
        val payload = "Αθήνα".toByteArray(StandardCharsets.UTF_16BE)
        val raw = ByteArrayInputStream(bom + payload)
        val wrapped = EncodingDetector.wrapWithExplicit(raw, StandardCharsets.UTF_16BE)
        wrapped.readBytes().toString(StandardCharsets.UTF_16BE) shouldBe "Αθήνα"
    }
})
