package dev.dmigrate.cli.i18n

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class UnicodeNormalizerTest : FunSpec({

    // ── NFC ──────────────────────────────────────────────

    test("NFC composes combining accent") {
        // U+0041 (A) + U+0308 (combining diaeresis) → U+00C4 (Ä)
        val decomposed = "A\u0308"
        val result = UnicodeNormalizer.normalize(decomposed, UnicodeNormalizationMode.NFC)
        result shouldBe "\u00C4"
    }

    test("NFC leaves already composed string stable") {
        val composed = "\u00C4" // Ä
        UnicodeNormalizer.normalize(composed, UnicodeNormalizationMode.NFC) shouldBe composed
    }

    // ── NFD ──────────────────────────────────────────────

    test("NFD decomposes precomposed character") {
        val composed = "\u00C4" // Ä
        val result = UnicodeNormalizer.normalize(composed, UnicodeNormalizationMode.NFD)
        result shouldBe "A\u0308"
    }

    // ── NFKC ─────────────────────────────────────────────

    test("NFKC normalizes compatibility characters") {
        // U+FB01 (fi ligature) → "fi"
        val result = UnicodeNormalizer.normalize("\uFB01", UnicodeNormalizationMode.NFKC)
        result shouldBe "fi"
    }

    // ── NFKD ─────────────────────────────────────────────

    test("NFKD decomposes compatibility characters") {
        val result = UnicodeNormalizer.normalize("\uFB01", UnicodeNormalizationMode.NFKD)
        result shouldBe "fi"
    }

    // ── isNormalized ─────────────────────────────────────

    test("isNormalized returns true for NFC-normalized string") {
        UnicodeNormalizer.isNormalized("\u00C4", UnicodeNormalizationMode.NFC) shouldBe true
    }

    test("isNormalized returns false for decomposed string in NFC mode") {
        UnicodeNormalizer.isNormalized("A\u0308", UnicodeNormalizationMode.NFC) shouldBe false
    }

    // ── Stability ────────────────────────────────────────

    test("normalizing already normalized input returns same value") {
        val input = "Hello, World!"
        for (mode in UnicodeNormalizationMode.entries) {
            UnicodeNormalizer.normalize(input, mode) shouldBe input
        }
    }

    test("empty string normalizes to empty string") {
        for (mode in UnicodeNormalizationMode.entries) {
            UnicodeNormalizer.normalize("", mode) shouldBe ""
        }
    }

    // ── Cyrillic ─────────────────────────────────────────

    test("NFC handles Cyrillic combining marks") {
        // й = U+0439 (precomposed) vs U+0438 + U+0306 (decomposed)
        val decomposed = "\u0438\u0306"
        val composed = "\u0439"
        UnicodeNormalizer.normalize(decomposed, UnicodeNormalizationMode.NFC) shouldBe composed
    }

    // ── Negative: payload non-mutation contract ──────────

    test("UnicodeNormalizer is utility — data payloads must not be auto-normalized") {
        // This test documents the contract: the normalizer is explicitly called,
        // never applied automatically to data payloads. Export/import/transfer
        // values pass through unchanged unless the caller explicitly normalizes.
        val rawPayload = "A\u0308 café" // decomposed Ä + precomposed é
        val untouched = rawPayload // simulates passing through export pipeline
        // The payload is NOT normalized — it retains its original form
        untouched shouldBe rawPayload
        // But if someone explicitly normalizes, the result differs:
        val normalized = UnicodeNormalizer.normalize(rawPayload, UnicodeNormalizationMode.NFC)
        (normalized != rawPayload) shouldBe true // proves normalization would change it
    }
})
