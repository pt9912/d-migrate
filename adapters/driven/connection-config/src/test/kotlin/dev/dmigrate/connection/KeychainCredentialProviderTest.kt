package dev.dmigrate.connection

import dev.dmigrate.server.ports.CredentialResolution
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf

class KeychainCredentialProviderTest : FunSpec({

    class FakeBackend(
        private val available: Boolean = true,
        private val result: KeychainLookup = KeychainLookup.NotFound,
    ) : KeychainBackend {
        var lookupCalls = 0
        var lastService: String? = null
        var lastAccount: String? = null

        override fun isAvailable(): Boolean = available
        override fun lookup(service: String, account: String?): KeychainLookup {
            lookupCalls++
            lastService = service
            lastAccount = account
            return result
        }
    }

    val url = "postgresql://app:s3cret@host:5432/db"

    test("scheme is keychain:") {
        KeychainCredentialProvider(FakeBackend()).scheme shouldBe "keychain:"
    }

    test("Found → Success with the entry value as the full connect URL (BOM stripped, trimmed)") {
        val bom = Char(0xFEFF).toString()
        val backend = FakeBackend(result = KeychainLookup.Found("$bom  $url  \n"))
        val outcome = KeychainCredentialProvider(backend).resolve("keychain://pg-prod")
            .shouldBeInstanceOf<CredentialResolution.Success>()
        outcome.url shouldBe url
    }

    test("service + account are parsed from keychain://<service>/<account>") {
        val backend = FakeBackend(result = KeychainLookup.Found(url))
        KeychainCredentialProvider(backend).resolve("keychain://pg-prod/app")
        backend.lastService shouldBe "pg-prod"
        backend.lastAccount shouldBe "app"
    }

    test("service only (no account) parses account = null") {
        val backend = FakeBackend(result = KeychainLookup.Found(url))
        KeychainCredentialProvider(backend).resolve("keychain://pg-prod")
        backend.lastService shouldBe "pg-prod"
        backend.lastAccount shouldBe null
    }

    test("the // after the scheme is optional (keychain:<service> also parses)") {
        val backend = FakeBackend(result = KeychainLookup.Found(url))
        KeychainCredentialProvider(backend).resolve("keychain:pg-prod")
        backend.lastService shouldBe "pg-prod"
    }

    test("empty service → KEYCHAIN_ENTRY_NOT_FOUND without touching the backend") {
        val backend = FakeBackend()
        val outcome = KeychainCredentialProvider(backend).resolve("keychain://")
            .shouldBeInstanceOf<CredentialResolution.Failure>()
        outcome.reason shouldBe CredentialResolution.REASON_KEYCHAIN_ENTRY_NOT_FOUND
        backend.lookupCalls shouldBe 0
    }

    test("backend unavailable → KEYCHAIN_UNAVAILABLE and lookup is NOT attempted") {
        val backend = FakeBackend(available = false)
        val outcome = KeychainCredentialProvider(backend).resolve("keychain://pg-prod")
            .shouldBeInstanceOf<CredentialResolution.Failure>()
        outcome.reason shouldBe CredentialResolution.REASON_KEYCHAIN_UNAVAILABLE
        backend.lookupCalls shouldBe 0
    }

    test("NotFound → KEYCHAIN_ENTRY_NOT_FOUND") {
        val backend = FakeBackend(result = KeychainLookup.NotFound)
        val outcome = KeychainCredentialProvider(backend).resolve("keychain://missing")
            .shouldBeInstanceOf<CredentialResolution.Failure>()
        outcome.reason shouldBe CredentialResolution.REASON_KEYCHAIN_ENTRY_NOT_FOUND
    }

    test("Unavailable → KEYCHAIN_UNAVAILABLE, detail passed through") {
        val backend = FakeBackend(result = KeychainLookup.Unavailable("keychain lookup timed out after 10s"))
        val outcome = KeychainCredentialProvider(backend).resolve("keychain://pg-prod")
            .shouldBeInstanceOf<CredentialResolution.Failure>()
        outcome.reason shouldBe CredentialResolution.REASON_KEYCHAIN_UNAVAILABLE
        outcome.detail shouldContain "timed out"
    }

    test("empty entry value → EMPTY_VALUE") {
        val backend = FakeBackend(result = KeychainLookup.Found("   \n"))
        val outcome = KeychainCredentialProvider(backend).resolve("keychain://pg-prod")
            .shouldBeInstanceOf<CredentialResolution.Failure>()
        outcome.reason shouldBe CredentialResolution.REASON_EMPTY_VALUE
    }

    test("failure detail never echoes the resolved secret value") {
        val backend = FakeBackend(result = KeychainLookup.Found("   \n"))
        val outcome = KeychainCredentialProvider(backend).resolve("keychain://pg-prod")
            .shouldBeInstanceOf<CredentialResolution.Failure>()
        outcome.detail shouldNotContain "s3cret"
    }
})
