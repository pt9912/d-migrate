package dev.dmigrate.text.icu

import dev.dmigrate.text.UnicodeNormalizationMode
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Behavioural test for the ICU4J-backed [IcuUnicodeTextService].
 *
 * The cases here are the ones that previously lived in
 * `hexagon/application/.../UnicodeNormalizerTest` and
 * `GraphemeCounterTest`. With the port migration, ICU-specific
 * behaviour belongs next to the ICU implementation; the application
 * layer drops its direct ICU4J dependency.
 */
class IcuUnicodeTextServiceTest : FunSpec({

    val service = IcuUnicodeTextService()

    // ── normalize: NFC ───────────────────────────────────

    test("NFC composes combining accent") {
        // U+0041 (A) + U+0308 (combining diaeresis) → U+00C4 (Ä)
        val decomposed = "Ä"
        service.normalize(decomposed, UnicodeNormalizationMode.NFC) shouldBe "Ä"
    }

    test("NFC leaves already composed string stable") {
        val composed = "Ä" // Ä
        service.normalize(composed, UnicodeNormalizationMode.NFC) shouldBe composed
    }

    // ── normalize: NFD ───────────────────────────────────

    test("NFD decomposes precomposed character") {
        val composed = "Ä" // Ä
        service.normalize(composed, UnicodeNormalizationMode.NFD) shouldBe "Ä"
    }

    // ── normalize: NFKC / NFKD ───────────────────────────

    test("NFKC normalizes compatibility characters") {
        // U+FB01 (fi ligature) → "fi"
        service.normalize("ﬁ", UnicodeNormalizationMode.NFKC) shouldBe "fi"
    }

    test("NFKD decomposes compatibility characters") {
        service.normalize("ﬁ", UnicodeNormalizationMode.NFKD) shouldBe "fi"
    }

    // ── isNormalized ─────────────────────────────────────

    test("isNormalized returns true for NFC-normalized string") {
        service.isNormalized("Ä", UnicodeNormalizationMode.NFC) shouldBe true
    }

    test("isNormalized returns false for decomposed string in NFC mode") {
        service.isNormalized("Ä", UnicodeNormalizationMode.NFC) shouldBe false
    }

    // ── Stability / empty input ──────────────────────────

    test("normalizing already normalized input returns same value") {
        val input = "Hello, World!"
        for (mode in UnicodeNormalizationMode.entries) {
            service.normalize(input, mode) shouldBe input
        }
    }

    test("empty string normalizes to empty string") {
        for (mode in UnicodeNormalizationMode.entries) {
            service.normalize("", mode) shouldBe ""
        }
    }

    // ── Cyrillic edge case ───────────────────────────────

    test("NFC handles Cyrillic combining marks") {
        // й = U+0439 (precomposed) vs U+0438 + U+0306 (decomposed)
        val decomposed = "й"
        val composed = "й"
        service.normalize(decomposed, UnicodeNormalizationMode.NFC) shouldBe composed
    }

    // ── grapheme counting ────────────────────────────────

    test("graphemeCount counts ASCII as one each") {
        service.graphemeCount("abc") shouldBe 3
    }

    test("graphemeCount returns zero for empty string") {
        service.graphemeCount("") shouldBe 0
    }

    test("graphemeCount counts precomposed character as one") {
        service.graphemeCount("Ä") shouldBe 1
    }

    test("graphemeCount counts combined sequence as one") {
        service.graphemeCount("Ä") shouldBe 1
    }

    test("graphemeCount counts simple emoji as one") {
        service.graphemeCount("😀") shouldBe 1
    }

    test("graphemeCount counts ZWJ family sequence as one cluster") {
        // 👨‍👩‍👧‍👦 = man + ZWJ + woman + ZWJ + girl + ZWJ + boy
        val zwj = "👨‍👩‍👧‍👦"
        service.graphemeCount(zwj) shouldBe 1
    }

    test("graphemeCount counts regional-indicator flag as one cluster") {
        // 🇩🇪 = U+1F1E9 + U+1F1EA
        val flag = "🇩🇪"
        service.graphemeCount(flag) shouldBe 1
    }
})
