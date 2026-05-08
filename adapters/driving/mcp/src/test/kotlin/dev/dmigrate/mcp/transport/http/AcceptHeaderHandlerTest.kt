package dev.dmigrate.mcp.transport.http

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class AcceptHeaderHandlerTest : FunSpec({

    test("absent / blank header is rejected") {
        AcceptHeaderHandler.acceptsJson(null) shouldBe false
        AcceptHeaderHandler.acceptsJson("") shouldBe false
        AcceptHeaderHandler.acceptsJson("   ") shouldBe false
    }

    test("wildcard without explicit streamable types is rejected") {
        AcceptHeaderHandler.acceptsJson("*/*") shouldBe false
    }

    test("`application/json` alone is rejected") {
        AcceptHeaderHandler.acceptsJson("application/json") shouldBe false
    }

    test("`application/*` alone is rejected") {
        AcceptHeaderHandler.acceptsJson("application/*") shouldBe false
    }

    test("multi-value with JSON and event-stream accepts") {
        AcceptHeaderHandler.acceptsJson("application/json, text/event-stream") shouldBe true
        AcceptHeaderHandler.acceptsJson("text/event-stream, application/json") shouldBe true
        AcceptHeaderHandler.acceptsJson("text/event-stream; charset=utf-8, application/json;q=0.5") shouldBe true
    }

    test("event-stream alone is rejected") {
        AcceptHeaderHandler.acceptsJson("text/event-stream") shouldBe false
    }

    test("text/html only rejects JSON") {
        AcceptHeaderHandler.acceptsJson("text/html") shouldBe false
    }

    test("quality factor on both required values does not invalidate") {
        AcceptHeaderHandler.acceptsJson("application/json;q=0.9, text/event-stream;q=0.5") shouldBe true
    }
})
