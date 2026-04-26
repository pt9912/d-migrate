package dev.dmigrate.server.application.audit

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

class SecretScrubberTest : FunSpec({

    test("scrubs JDBC password value via ConnectionSecretMasker, keeps the rest") {
        val redacted = SecretScrubber.scrub(
            "jdbc:postgresql://db.example.com:5432/app?user=svc&password=topsecret&sslmode=require",
        )
        redacted shouldNotContain "topsecret"
        redacted shouldContain "user=svc"
        redacted shouldContain "sslmode=require"
        redacted shouldContain "password=***"
    }

    test("scrubs URL authority password (user:pwd@host)") {
        val redacted = SecretScrubber.scrub("postgresql://admin:hunter2@host/db")
        redacted shouldNotContain "hunter2"
        redacted shouldContain "admin:***@host"
    }

    test("scrubs additional sensitive query keys (token, api_key, secret)") {
        SecretScrubber.scrub("https://api.example/?token=abc123") shouldNotContain "abc123"
        SecretScrubber.scrub("https://api.example/?api_key=k1") shouldNotContain "k1"
        SecretScrubber.scrub("https://api.example/?secret=s1") shouldNotContain "s1"
    }

    test("scrubs Bearer tokens regardless of case, keeps the keyword") {
        val a = SecretScrubber.scrub("Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.payload.sig")
        a shouldNotContain "eyJ"
        a shouldContain "Bearer ***"

        val b = SecretScrubber.scrub("authorization: bearer 1234567890abcdef")
        b shouldNotContain "1234567890abcdef"
    }

    test("scrubs approval token raw form tok_*") {
        SecretScrubber.scrub("ref=tok_ABCDEFGH123456") shouldNotContain "tok_ABCDEFGH123456"
    }

    test("leaves clean text untouched") {
        SecretScrubber.scrub("dmigrate://tenants/acme/jobs/job-1") shouldBe
            "dmigrate://tenants/acme/jobs/job-1"
    }

    test("scrubs multiple patterns in one string") {
        val mixed = SecretScrubber.scrub(
            "url=jdbc:mysql://host?password=p123 auth=Bearer abc.def ref=tok_supersecret1",
        )
        mixed shouldNotContain "p123"
        mixed shouldNotContain "abc.def"
        mixed shouldNotContain "supersecret1"
    }

    test("does not over-scrub legitimate content") {
        SecretScrubber.scrub("the password field is required") shouldBe
            "the password field is required"
    }
})
