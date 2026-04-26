package dev.dmigrate.mcp.auth

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class BearerTokenReaderTest : FunSpec({

    test("absent / blank header returns null") {
        BearerTokenReader.read(null) shouldBe null
        BearerTokenReader.read("") shouldBe null
        BearerTokenReader.read("   ") shouldBe null
    }

    test("Bearer scheme with single token returns the token") {
        BearerTokenReader.read("Bearer eyJhbGciOiJSUzI1NiJ9.payload.signature") shouldBe
            "eyJhbGciOiJSUzI1NiJ9.payload.signature"
    }

    test("scheme is case-insensitive (RFC 7235)") {
        BearerTokenReader.read("bearer abc") shouldBe "abc"
        BearerTokenReader.read("BEARER abc") shouldBe "abc"
        BearerTokenReader.read("BeArEr abc") shouldBe "abc"
    }

    test("non-Bearer schemes (Basic, etc) return null") {
        BearerTokenReader.read("Basic dXNlcjpwYXNz") shouldBe null
        BearerTokenReader.read("Digest nonce=abc") shouldBe null
    }

    test("empty token after Bearer returns null") {
        BearerTokenReader.read("Bearer") shouldBe null
        BearerTokenReader.read("Bearer ") shouldBe null
    }

    test("multiple whitespace-separated parts return null") {
        // Bearer requires exactly one token (RFC 6750 §2.1)
        BearerTokenReader.read("Bearer abc def") shouldBe null
    }

    test("leading / trailing whitespace is tolerated") {
        BearerTokenReader.read("  Bearer abc  ") shouldBe "abc"
    }
})
