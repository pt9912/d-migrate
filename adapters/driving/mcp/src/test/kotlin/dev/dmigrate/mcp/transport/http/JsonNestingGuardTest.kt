package dev.dmigrate.mcp.transport.http

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class JsonNestingGuardTest : FunSpec({

    test("shallow JSON is well under the limit") {
        JsonNestingGuard.exceedsMaxDepth("""{"jsonrpc":"2.0","params":{"a":[1,2,3]}}""") shouldBe false
    }

    test("empty / scalar bodies never exceed") {
        JsonNestingGuard.exceedsMaxDepth("") shouldBe false
        JsonNestingGuard.exceedsMaxDepth("123") shouldBe false
        JsonNestingGuard.exceedsMaxDepth("\"just a string\"") shouldBe false
    }

    test("depth exactly at the limit is allowed, one deeper is rejected") {
        val atLimit = "[".repeat(JsonNestingGuard.MAX_DEPTH) + "]".repeat(JsonNestingGuard.MAX_DEPTH)
        val overLimit = "[".repeat(JsonNestingGuard.MAX_DEPTH + 1) + "]".repeat(JsonNestingGuard.MAX_DEPTH + 1)
        JsonNestingGuard.exceedsMaxDepth(atLimit) shouldBe false
        JsonNestingGuard.exceedsMaxDepth(overLimit) shouldBe true
    }

    test("deeply nested arrays are rejected") {
        JsonNestingGuard.exceedsMaxDepth("[".repeat(100_000) + "]".repeat(100_000)) shouldBe true
    }

    test("deeply nested objects are rejected") {
        val open = "{\"a\":".repeat(100_000)
        val close = "}".repeat(100_000)
        JsonNestingGuard.exceedsMaxDepth("$open 1 $close") shouldBe true
    }

    test("brackets inside a string literal do NOT count as structural nesting") {
        val payload = "{\"note\":\"" + "[".repeat(100_000) + "\"}"
        JsonNestingGuard.exceedsMaxDepth(payload) shouldBe false
    }

    test("escaped quote inside a string keeps the scanner in-string") {
        // The \" must not end the string early; the following [… stays literal.
        val payload = "{\"note\":\"he said \\\"hi\\\" " + "[".repeat(100_000) + "\"}"
        JsonNestingGuard.exceedsMaxDepth(payload) shouldBe false
    }

    test("sibling structures do not accumulate depth") {
        // One array with 50k empty-object siblings: depth oscillates 1..2, never deep.
        JsonNestingGuard.exceedsMaxDepth("[" + "{},".repeat(50_000).removeSuffix(",") + "]") shouldBe false
    }

    test("unbalanced closers floor at zero and do not underflow") {
        JsonNestingGuard.exceedsMaxDepth("]]]]}}}}") shouldBe false
    }
})
