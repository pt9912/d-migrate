package dev.dmigrate.server.application.fingerprint

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class JsonCanonicalizerTest : FunSpec({

    test("primitives serialize without whitespace") {
        JsonCanonicalizer.canonicalize(JsonValue.Null) shouldBe "null"
        JsonCanonicalizer.canonicalize(JsonValue.Bool(true)) shouldBe "true"
        JsonCanonicalizer.canonicalize(JsonValue.Bool(false)) shouldBe "false"
        JsonCanonicalizer.canonicalize(JsonValue.Num(0)) shouldBe "0"
        JsonCanonicalizer.canonicalize(JsonValue.Num(-42)) shouldBe "-42"
        JsonCanonicalizer.canonicalize(JsonValue.Num(Long.MAX_VALUE)) shouldBe Long.MAX_VALUE.toString()
        JsonCanonicalizer.canonicalize(JsonValue.Str("abc")) shouldBe "\"abc\""
    }

    test("object keys are sorted by UTF-16 code unit") {
        val obj = JsonValue.Obj(linkedMapOf(
            "z" to JsonValue.Num(1),
            "a" to JsonValue.Num(2),
            "m" to JsonValue.Num(3),
        ))
        JsonCanonicalizer.canonicalize(obj) shouldBe "{\"a\":2,\"m\":3,\"z\":1}"
    }

    test("array order is preserved") {
        val arr = JsonValue.Arr(listOf(JsonValue.Num(3), JsonValue.Num(1), JsonValue.Num(2)))
        JsonCanonicalizer.canonicalize(arr) shouldBe "[3,1,2]"
    }

    test("strings escape only quote, backslash and controls below 0x20") {
        JsonCanonicalizer.canonicalize(JsonValue.Str("a\"b")) shouldBe "\"a\\\"b\""
        JsonCanonicalizer.canonicalize(JsonValue.Str("a\\b")) shouldBe "\"a\\\\b\""
        JsonCanonicalizer.canonicalize(JsonValue.Str("a\nb")) shouldBe "\"a\\u000ab\""
        JsonCanonicalizer.canonicalize(JsonValue.Str("a\tb")) shouldBe "\"a\\u0009b\""
        JsonCanonicalizer.canonicalize(JsonValue.Str("a\u0000b")) shouldBe "\"a\\u0000b\""
    }

    test("non-control extended characters stay literal (not escaped)") {
        JsonCanonicalizer.canonicalize(JsonValue.Str("café")) shouldBe "\"café\""
        JsonCanonicalizer.canonicalize(JsonValue.Str("über")) shouldBe "\"über\""
    }

    test("string values are NFC-normalized before output") {
        val decomposed = "cafe\u0301"
        val composed = "café"
        JsonCanonicalizer.canonicalize(JsonValue.Str(decomposed)) shouldBe
            JsonCanonicalizer.canonicalize(JsonValue.Str(composed))
    }

    test("object keys are NFC-normalized before sorting") {
        val decomposed = "cafe\u0301"
        val composed = "café"
        val a = JsonValue.Obj(linkedMapOf(decomposed to JsonValue.Num(1)))
        val b = JsonValue.Obj(linkedMapOf(composed to JsonValue.Num(1)))
        JsonCanonicalizer.canonicalize(a) shouldBe JsonCanonicalizer.canonicalize(b)
    }

    test("nested structures canonicalize recursively") {
        val nested = JsonValue.obj(
            "outer" to JsonValue.obj(
                "z" to JsonValue.Arr(listOf(JsonValue.Num(2), JsonValue.Num(1))),
                "a" to JsonValue.Bool(true),
            ),
        )
        JsonCanonicalizer.canonicalize(nested) shouldBe
            "{\"outer\":{\"a\":true,\"z\":[2,1]}}"
    }

    test("empty object and array") {
        JsonCanonicalizer.canonicalize(JsonValue.Obj.EMPTY) shouldBe "{}"
        JsonCanonicalizer.canonicalize(JsonValue.Arr(emptyList())) shouldBe "[]"
    }

    test("null is preserved as a value") {
        val obj = JsonValue.obj("missing" to JsonValue.Null, "present" to JsonValue.Num(1))
        JsonCanonicalizer.canonicalize(obj) shouldBe "{\"missing\":null,\"present\":1}"
    }

    test("UTF-16 code unit ordering separates ascii and BMP characters") {
        val obj = JsonValue.Obj(linkedMapOf(
            "ä" to JsonValue.Num(1), // 0x00E4
            "z" to JsonValue.Num(2), // 0x007A
        ))
        JsonCanonicalizer.canonicalize(obj) shouldBe "{\"z\":2,\"ä\":1}"
    }

})
