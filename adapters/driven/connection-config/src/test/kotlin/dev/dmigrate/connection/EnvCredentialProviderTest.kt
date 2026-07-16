package dev.dmigrate.connection

import dev.dmigrate.server.ports.CredentialResolution
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

class EnvCredentialProviderTest : FunSpec({

    test("scheme is env:") {
        EnvCredentialProvider().scheme shouldBe "env:"
    }

    test("env:VAR with value present yields Success carrying the full URL verbatim") {
        val provider = EnvCredentialProvider(
            envLookup = { if (it == "PG_PASS") "jdbc:postgresql://localhost:5432/prod?password=s3cret" else null },
        )
        val outcome = provider.resolve("env:PG_PASS").shouldBeInstanceOf<CredentialResolution.Success>()
        outcome.url shouldBe "jdbc:postgresql://localhost:5432/prod?password=s3cret"
    }

    test("urlFromEnv transform is applied to the env value") {
        val provider = EnvCredentialProvider(
            envLookup = { "s3cret" },
            urlFromEnv = { pw -> "postgresql://app:$pw@host/db" },
        )
        val outcome = provider.resolve("env:PW").shouldBeInstanceOf<CredentialResolution.Success>()
        outcome.url shouldBe "postgresql://app:s3cret@host/db"
    }

    test("env:VAR not set surfaces ENV_NOT_SET and names the variable, not a value") {
        val provider = EnvCredentialProvider(envLookup = { null })
        val outcome = provider.resolve("env:MISSING").shouldBeInstanceOf<CredentialResolution.Failure>()
        outcome.reason shouldBe CredentialResolution.REASON_ENV_NOT_SET
        outcome.detail shouldBe "environment variable 'MISSING' is not set"
    }
})
