package dev.dmigrate.mcp.auth

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class StdioTokenFingerprintTest : FunSpec({

    test("known SHA-256 vector") {
        // Cross-checked against `printf abc | sha256sum`.
        StdioTokenFingerprint.of("abc") shouldBe
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
    }

    test("empty string is rejected") {
        shouldThrow<IllegalArgumentException> { StdioTokenFingerprint.of("") }
    }

    test("output is deterministic and lowercase hex") {
        val fp = StdioTokenFingerprint.of("tok_abc123")
        fp shouldBe StdioTokenFingerprint.of("tok_abc123")
        fp.length shouldBe 64
        fp.all { it in '0'..'9' || it in 'a'..'f' } shouldBe true
    }

    test("different tokens produce different fingerprints") {
        val a = StdioTokenFingerprint.of("tok_alice")
        val b = StdioTokenFingerprint.of("tok_bob")
        (a == b) shouldBe false
    }
})
