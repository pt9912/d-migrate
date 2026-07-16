package dev.dmigrate.server.ports

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf

private class FakeProvider(
    override val scheme: String,
    private val result: CredentialResolution,
) : CredentialProvider {
    var lastRef: String? = null
    override fun resolve(credentialRef: String): CredentialResolution {
        lastRef = credentialRef
        return result
    }
}

class CredentialProviderRegistryTest : FunSpec({

    test("dispatches to the provider whose scheme prefixes the credentialRef") {
        val env = FakeProvider("env:", CredentialResolution.Success("postgresql://h/db?password=x"))
        val file = FakeProvider("file:", CredentialResolution.Success("mysql://h/db"))
        val registry = CredentialProviderRegistry(listOf(env, file))

        registry.resolve("file:/run/secrets/db") shouldBe CredentialResolution.Success("mysql://h/db")
        file.lastRef shouldBe "file:/run/secrets/db"
        env.lastRef shouldBe null
    }

    test("propagates a provider Failure verbatim") {
        val env = FakeProvider(
            "env:",
            CredentialResolution.Failure(CredentialResolution.REASON_ENV_NOT_SET, "var 'X' not set"),
        )
        val registry = CredentialProviderRegistry(listOf(env))

        val result = registry.resolve("env:X")
        result shouldBe CredentialResolution.Failure(CredentialResolution.REASON_ENV_NOT_SET, "var 'X' not set")
    }

    test("unknown scheme is fail-closed with PROVIDER_MISSING listing supported schemes, not the ref") {
        val registry = CredentialProviderRegistry(
            listOf(
                FakeProvider("env:", CredentialResolution.Success("x")),
                FakeProvider("file:", CredentialResolution.Success("x")),
            ),
        )

        val result = registry.resolve("vault:/secret/super-sensitive")
            .shouldBeInstanceOf<CredentialResolution.Failure>()
        result.reason shouldBe CredentialResolution.REASON_PROVIDER_MISSING
        result.detail shouldContain "env:"
        result.detail shouldContain "file:"
        // never echo the (potentially sensitive) credentialRef value
        result.detail shouldNotContain "super-sensitive"
    }

    test("empty credentialRef is fail-closed with PROVIDER_MISSING") {
        val registry = CredentialProviderRegistry(listOf(FakeProvider("env:", CredentialResolution.Success("x"))))
        registry.resolve("")
            .shouldBeInstanceOf<CredentialResolution.Failure>()
            .reason shouldBe CredentialResolution.REASON_PROVIDER_MISSING
    }

    test("supportedSchemes returns the registered schemes sorted") {
        val registry = CredentialProviderRegistry(
            listOf(
                FakeProvider("file:", CredentialResolution.Success("x")),
                FakeProvider("env:", CredentialResolution.Success("x")),
            ),
        )
        registry.supportedSchemes() shouldContainExactly listOf("env:", "file:")
    }

    test("duplicate schemes are rejected at construction") {
        shouldThrow<IllegalArgumentException> {
            CredentialProviderRegistry(
                listOf(
                    FakeProvider("env:", CredentialResolution.Success("x")),
                    FakeProvider("env:", CredentialResolution.Success("y")),
                ),
            )
        }
    }

    test("blank scheme is rejected at construction") {
        shouldThrow<IllegalArgumentException> {
            CredentialProviderRegistry(listOf(FakeProvider("  ", CredentialResolution.Success("x"))))
        }
    }

    test("a scheme that is a prefix of another is rejected (order-independent dispatch invariant)") {
        shouldThrow<IllegalArgumentException> {
            CredentialProviderRegistry(
                listOf(
                    FakeProvider("env:", CredentialResolution.Success("x")),
                    FakeProvider("env:sub:", CredentialResolution.Success("y")),
                ),
            )
        }
    }

    test("shared reason codes mirror ResolvedConnection (single source, no drift)") {
        CredentialResolution.REASON_PROVIDER_MISSING shouldBe ResolvedConnection.REASON_PROVIDER_MISSING
        CredentialResolution.REASON_ENV_NOT_SET shouldBe ResolvedConnection.REASON_ENV_NOT_SET
        CredentialResolution.REASON_FILE_NOT_FOUND shouldBe ResolvedConnection.REASON_FILE_NOT_FOUND
        CredentialResolution.REASON_FILE_UNREADABLE shouldBe ResolvedConnection.REASON_FILE_UNREADABLE
        CredentialResolution.REASON_EMPTY_VALUE shouldBe ResolvedConnection.REASON_EMPTY_VALUE
    }
})
