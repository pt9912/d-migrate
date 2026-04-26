package dev.dmigrate.server.application.fingerprint

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Test vectors derived from RFC 8785 (JCS) — adapted to the Phase A
 * integer-only restriction. Each vector exercises one normative
 * requirement: lexicographic key sort by UTF-16 code unit, control
 * character escaping (lowercase hex), and NFC normalization of strings
 * and object keys.
 */
class JsonCanonicalizerRfc8785VectorTest : FunSpec({

    test("vector 1: alphabetic key sort with mixed values") {
        val input = JsonValue.Obj(linkedMapOf(
            "z" to JsonValue.Num(1),
            "a" to JsonValue.Arr(listOf(JsonValue.Num(3), JsonValue.Num(2), JsonValue.Num(1))),
            "m" to JsonValue.Str("hello"),
            "k" to JsonValue.Null,
        ))
        JsonCanonicalizer.canonicalize(input) shouldBe
            "{\"a\":[3,2,1],\"k\":null,\"m\":\"hello\",\"z\":1}"
    }

    test("vector 2: code unit ordering separates ASCII from BMP characters") {
        val input = JsonValue.Obj(linkedMapOf(
            "ä" to JsonValue.Num(1),  // U+00E4
            "Z" to JsonValue.Num(2),  // U+005A
            "a" to JsonValue.Num(3),  // U+0061
        ))
        JsonCanonicalizer.canonicalize(input) shouldBe
            "{\"Z\":2,\"a\":3,\"ä\":1}"
    }

    test("vector 3: \"peach\" sorts before \"péché\" by code unit") {
        val input = JsonValue.Obj(linkedMapOf(
            "péché" to JsonValue.Num(1),
            "peach" to JsonValue.Num(2),
        ))
        JsonCanonicalizer.canonicalize(input) shouldBe
            "{\"peach\":2,\"péché\":1}"
    }

    test("vector 4: control character escape uses lowercase hex") {
        val input = JsonValue.Str("a\u0001\u000a\u001fb")
        JsonCanonicalizer.canonicalize(input) shouldBe
            "\"a\\u0001\\u000a\\u001fb\""
    }

    test("vector 5: NFC normalization of decomposed string equals composed string") {
        val decomposed = "cafe\u0301"
        val composed = "café"
        JsonCanonicalizer.canonicalize(JsonValue.Str(decomposed)) shouldBe
            JsonCanonicalizer.canonicalize(JsonValue.Str(composed))
    }
})
