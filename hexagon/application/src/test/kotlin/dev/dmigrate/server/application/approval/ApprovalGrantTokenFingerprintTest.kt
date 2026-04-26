package dev.dmigrate.server.application.approval

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldHaveLength
import io.kotest.matchers.string.shouldMatch
import java.security.MessageDigest

class ApprovalGrantTokenFingerprintTest : FunSpec({

    test("compute returns 64 lowercase hex chars") {
        val fp = ApprovalTokenFingerprint.compute("token-abc")
        fp shouldHaveLength 64
        fp shouldMatch Regex("[0-9a-f]{64}")
    }

    test("compute is deterministic across calls") {
        ApprovalTokenFingerprint.compute("same-token") shouldBe
            ApprovalTokenFingerprint.compute("same-token")
    }

    test("different tokens produce different fingerprints") {
        val a = ApprovalTokenFingerprint.compute("token-1")
        val b = ApprovalTokenFingerprint.compute("token-2")
        (a == b) shouldBe false
    }

    test("compute matches plain SHA-256 hex of UTF-8 bytes") {
        val raw = "approval-grant-2026"
        val expected = MessageDigest.getInstance("SHA-256")
            .digest(raw.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        ApprovalTokenFingerprint.compute(raw) shouldBe expected
    }

    test("empty string is hashable") {
        val fp = ApprovalTokenFingerprint.compute("")
        fp shouldHaveLength 64
        // SHA-256 of empty input is well-known.
        fp shouldBe "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
    }

    test("unicode tokens are normalized to UTF-8 bytes") {
        // We don't NFC the token here — adapters are expected to feed
        // exactly the bytes the issuer signed. Different unicode forms
        // produce different fingerprints by design.
        val composed = ApprovalTokenFingerprint.compute("café")
        val decomposed = ApprovalTokenFingerprint.compute("cafe\u0301")
        (composed == decomposed) shouldBe false
    }
})
