package dev.dmigrate.text

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Direct exercise of [FakeUnicodeTextService] so the JDK-only fake
 * carries its own coverage instead of relying on incidental use from
 * application-side test suites.
 *
 * Behaviour parity with `IcuUnicodeTextService` is checked separately
 * in the `adapters:driven:text-icu` module — the ICU-specific edge
 * cases (ZWJ emoji, regional-indicator flags, complex Unicode
 * clusters) live next to the ICU implementation.
 */
class FakeUnicodeTextServiceTest : FunSpec({

    val service = FakeUnicodeTextService()

    // ── normalize across all four modes ───────────────────

    test("NFC composes A + combining diaeresis to Ä") {
        val decomposed = "Ä"
        service.normalize(decomposed, UnicodeNormalizationMode.NFC) shouldBe "Ä"
    }

    test("NFD decomposes Ä to A + combining diaeresis") {
        service.normalize("Ä", UnicodeNormalizationMode.NFD) shouldBe "Ä"
    }

    test("NFKC and NFKD fold the fi ligature to plain ASCII") {
        service.normalize("ﬁ", UnicodeNormalizationMode.NFKC) shouldBe "fi"
        service.normalize("ﬁ", UnicodeNormalizationMode.NFKD) shouldBe "fi"
    }

    test("normalizing already normalized input is identity across all modes") {
        val input = "Hello, World!"
        for (mode in UnicodeNormalizationMode.entries) {
            service.normalize(input, mode) shouldBe input
        }
    }

    test("empty string normalizes to empty string in every mode") {
        for (mode in UnicodeNormalizationMode.entries) {
            service.normalize("", mode) shouldBe ""
        }
    }

    // ── isNormalized ─────────────────────────────────────

    test("isNormalized is true for NFC-composed string") {
        service.isNormalized("Ä", UnicodeNormalizationMode.NFC) shouldBe true
    }

    test("isNormalized is false for decomposed string under NFC") {
        service.isNormalized("Ä", UnicodeNormalizationMode.NFC) shouldBe false
    }

    // ── graphemeCount basics ─────────────────────────────

    test("graphemeCount of empty string is zero") {
        service.graphemeCount("") shouldBe 0
    }

    test("graphemeCount of pure ASCII counts each character") {
        service.graphemeCount("abc") shouldBe 3
    }

    test("graphemeCount treats combining accent as one cluster") {
        // U+0041 + U+0308 should be a single grapheme cluster
        service.graphemeCount("Ä") shouldBe 1
    }

    test("graphemeCount counts precomposed Ä as one cluster") {
        service.graphemeCount("Ä") shouldBe 1
    }
})
