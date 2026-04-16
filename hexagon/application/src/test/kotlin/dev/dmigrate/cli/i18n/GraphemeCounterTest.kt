package dev.dmigrate.cli.i18n

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class GraphemeCounterTest : FunSpec({

    test("ASCII characters count as 1 each") {
        GraphemeCounter.count("abc") shouldBe 3
    }

    test("empty string returns 0") {
        GraphemeCounter.count("") shouldBe 0
    }

    test("precomposed umlaut counts as 1") {
        GraphemeCounter.count("\u00C4") shouldBe 1 // Ä
    }

    test("combining accent counts as 1 grapheme") {
        // A + combining diaeresis = 1 user-perceived character
        GraphemeCounter.count("A\u0308") shouldBe 1
    }

    test("emoji counts as 1") {
        GraphemeCounter.count("😀") shouldBe 1
    }

    test("ZWJ family emoji counts as 1") {
        // 👨‍👩‍👧‍👦 = man + ZWJ + woman + ZWJ + girl + ZWJ + boy
        GraphemeCounter.count("👨\u200D👩\u200D👧\u200D👦") shouldBe 1
    }

    test("flag emoji counts as 1") {
        // 🇩🇪 = regional indicator D + regional indicator E
        GraphemeCounter.count("🇩🇪") shouldBe 1
    }

    test("CJK characters count as 1 each") {
        GraphemeCounter.count("漢字") shouldBe 2
    }

    test("Korean syllable counts as 1") {
        GraphemeCounter.count("한") shouldBe 1
    }

    test("mixed ASCII and emoji") {
        GraphemeCounter.count("Hi 👋") shouldBe 4 // H, i, space, wave
    }

    test("Cyrillic counts correctly") {
        GraphemeCounter.count("Привет") shouldBe 6
    }

    test("String.length differs from grapheme count for combining marks") {
        val combining = "A\u0308" // Ä as A + combining diaeresis
        combining.length shouldBe 2 // UTF-16 code units
        GraphemeCounter.count(combining) shouldBe 1 // grapheme clusters
    }
})
