package dev.dmigrate.mcp.transport.http

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class OriginValidatorTest : FunSpec({

    val loopback = setOf("http://localhost:*", "http://127.0.0.1:*")

    test("null Origin (server-to-server / curl) is allowed") {
        OriginValidator.isAllowed(null, loopback) shouldBe true
    }

    test("exact match passes") {
        OriginValidator.isAllowed("https://app.example", setOf("https://app.example")) shouldBe true
    }

    test("port wildcard `:*` matches any numeric port") {
        OriginValidator.isAllowed("http://localhost:8080", loopback) shouldBe true
        OriginValidator.isAllowed("http://localhost:53000", loopback) shouldBe true
        OriginValidator.isAllowed("http://127.0.0.1:0", loopback) shouldBe true
    }

    test("port wildcard does NOT match non-numeric port") {
        OriginValidator.isAllowed("http://localhost:abc", loopback) shouldBe false
    }

    test("port wildcard does NOT match other hosts") {
        OriginValidator.isAllowed("http://evil.example:80", loopback) shouldBe false
    }

    test("port wildcard does NOT match path appended after port") {
        // §12.6: Origin is scheme+host+port — never a path.
        OriginValidator.isAllowed("http://localhost:8080/admin", loopback) shouldBe false
    }

    test("scheme mismatch rejects") {
        OriginValidator.isAllowed("https://localhost:8080", loopback) shouldBe false
    }

    test("disallowed origin returns false") {
        OriginValidator.isAllowed("https://attacker.example", loopback) shouldBe false
    }

    test("port out of range rejects") {
        OriginValidator.isAllowed("http://localhost:99999", loopback) shouldBe false
    }
})
