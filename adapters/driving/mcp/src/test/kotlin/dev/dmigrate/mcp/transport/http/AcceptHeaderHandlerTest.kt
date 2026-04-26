package dev.dmigrate.mcp.transport.http

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class AcceptHeaderHandlerTest : FunSpec({

    test("absent / blank header accepts JSON") {
        AcceptHeaderHandler.acceptsJson(null) shouldBe true
        AcceptHeaderHandler.acceptsJson("") shouldBe true
        AcceptHeaderHandler.acceptsJson("   ") shouldBe true
    }

    test("`*/*` accepts JSON") {
        AcceptHeaderHandler.acceptsJson("*/*") shouldBe true
    }

    test("`application/json` accepts JSON") {
        AcceptHeaderHandler.acceptsJson("application/json") shouldBe true
    }

    test("`application/*` accepts JSON") {
        AcceptHeaderHandler.acceptsJson("application/*") shouldBe true
    }

    test("multi-value with JSON accepts") {
        AcceptHeaderHandler.acceptsJson("application/json, text/event-stream") shouldBe true
        AcceptHeaderHandler.acceptsJson("text/event-stream, application/json") shouldBe true
        AcceptHeaderHandler.acceptsJson("text/html, application/json;q=0.5") shouldBe true
    }

    test("SSE-only rejects JSON") {
        AcceptHeaderHandler.acceptsJson("text/event-stream") shouldBe false
    }

    test("text/html only rejects JSON") {
        AcceptHeaderHandler.acceptsJson("text/html") shouldBe false
    }

    test("quality factor on JSON does not invalidate") {
        AcceptHeaderHandler.acceptsJson("application/json;q=0.9") shouldBe true
    }
})
