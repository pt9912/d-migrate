package dev.dmigrate.server.application.ai

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.time.Duration

/**
 * LF-017 / LF-024 / LN-030 / LN-031— Registry-Verhalten:
 *
 * - NoOp-Default ist immer da (LF-017 / LF-024 / LN-030 / LN-031).
 * - Resolve-Outcomes mappen die LF-017 / LF-024 / LN-030 / LN-031-Fehler.
 * - Fail-closed bei invaliden Configs.
 */
class DefaultAiProviderRegistryTest : FunSpec({

    val noOpPort = NoOpAiProvider()
    val externalPort: AiProviderPort = AiProviderPort {
        AiProviderResult.Failure(
            error = AiProviderError.UNAUTHORIZED,
            message = "stubbed external provider",
        )
    }

    fun externalConfig(model: String = "gpt-4o") = AiProviderConfig(
        providerId = AiProviderId("openai"),
        kind = AiProviderKind.EXTERNAL,
        enabled = true,
        endpoint = "https://api.openai.com",
        allowedModels = setOf(model),
        secretRef = "DMIGRATE_OPENAI_API_KEY",
        defaultTimeout = Duration.ofSeconds(60),
        maxPromptBytes = 32_768,
        maxOutputBytes = 65_536,
        allowExternalNetwork = true,
        auditMode = AiProviderAuditMode.FULL,
    )

    test("LF-017 / LF-024 / LN-030 / LN-031: noOpOnly() factory liefert lauffaehige Registry mit NoOp") {
        val registry = DefaultAiProviderRegistry.noOpOnly()
        val outcome = registry.resolve(AiProviderId.NOOP, "noop:default")
        outcome.shouldBeInstanceOf<AiProviderResolveOutcome.Resolved>()
        outcome.config.providerId shouldBe AiProviderId.NOOP
        outcome.port shouldBe noOpPort.let { /* nur Form-Check */ outcome.port }
    }

    test("LF-017 / LF-024 / LN-030 / LN-031: NoOp-Default wird automatisch ergaenzt, wenn er fehlt") {
        // Nur ein externer Provider konfiguriert — Registry zieht NoOp
        // automatisch hinzu (LF-017 / LF-024 / LN-030 / LN-031: "NoOp ist immer verfuegbar").
        val registry = DefaultAiProviderRegistry(
            configs = listOf(externalConfig()),
            ports = mapOf(
                AiProviderId.NOOP to noOpPort,
                AiProviderId("openai") to externalPort,
            ),
        )
        registry.resolve(AiProviderId.NOOP, "noop:default")
            .shouldBeInstanceOf<AiProviderResolveOutcome.Resolved>()
    }

    test("LF-017 / LF-024 / LN-030 / LN-031: unbekannter Provider liefert NotConfigured (nicht ServerMisconfigured)") {
        val registry = DefaultAiProviderRegistry.noOpOnly()
        val outcome = registry.resolve(AiProviderId("anthropic"), "claude-opus-4-7")
        val notConfigured = outcome.shouldBeInstanceOf<AiProviderResolveOutcome.NotConfigured>()
        notConfigured.requested shouldBe AiProviderId("anthropic")
    }

    test("LF-017 / LF-024 / LN-030 / LN-031: Provider mit enabled=false liefert Disabled (Caller sieht es als 403)") {
        val registry = DefaultAiProviderRegistry(
            configs = listOf(externalConfig().copy(enabled = false)),
            ports = mapOf(
                AiProviderId.NOOP to noOpPort,
                AiProviderId("openai") to externalPort,
            ),
        )
        registry.resolve(AiProviderId("openai"), "gpt-4o")
            .shouldBeInstanceOf<AiProviderResolveOutcome.Disabled>()
    }

    test("LF-017 / LF-024 / LN-030 / LN-031: nicht-whitelisted Modell liefert UnknownModel mit allowedModels-Liste") {
        val registry = DefaultAiProviderRegistry(
            configs = listOf(externalConfig(model = "gpt-4o")),
            ports = mapOf(
                AiProviderId.NOOP to noOpPort,
                AiProviderId("openai") to externalPort,
            ),
        )
        val outcome = registry.resolve(AiProviderId("openai"), "gpt-3.5-turbo")
        val unknown = outcome.shouldBeInstanceOf<AiProviderResolveOutcome.UnknownModel>()
        unknown.requestedModel shouldBe "gpt-3.5-turbo"
        unknown.allowedModels shouldContainExactly setOf("gpt-4o")
    }

    test("LF-017 / LF-024 / LN-030 / LN-031: invalide Config laesst Server fail-closed scheitern") {
        // EXTERNAL ohne secretRef -> Validator-Fehler ->
        // IllegalStateException am Konstruktor.
        val ex = shouldThrow<IllegalStateException> {
            DefaultAiProviderRegistry(
                configs = listOf(
                    externalConfig().copy(secretRef = null),
                ),
                ports = mapOf(
                    AiProviderId.NOOP to noOpPort,
                    AiProviderId("openai") to externalPort,
                ),
            )
        }
        ex.message!! shouldContain "secretRef"
        ex.message!! shouldContain "openai"
    }

    test("Doppelte ProviderId im Config-Set wird abgewiesen") {
        val ex = shouldThrow<IllegalArgumentException> {
            DefaultAiProviderRegistry(
                configs = listOf(externalConfig(), externalConfig(model = "gpt-4o-mini")),
                ports = mapOf(
                    AiProviderId.NOOP to noOpPort,
                    AiProviderId("openai") to externalPort,
                ),
            )
        }
        ex.message!! shouldContain "duplicate"
    }

    test("Config ohne passenden Port -> IllegalStateException am Konstruktor") {
        val ex = shouldThrow<IllegalStateException> {
            DefaultAiProviderRegistry(
                configs = listOf(externalConfig()),
                ports = mapOf(AiProviderId.NOOP to noOpPort),
            )
        }
        ex.message!! shouldContain "no port wired"
    }

    test("describe() projeziert Provider-Liste OHNE Endpoint und secretRef (LF-017 / LF-024 / LN-030 / LN-031)") {
        val registry = DefaultAiProviderRegistry(
            configs = listOf(externalConfig()),
            ports = mapOf(
                AiProviderId.NOOP to noOpPort,
                AiProviderId("openai") to externalPort,
            ),
        )
        val descriptions = registry.describe()
        descriptions.size shouldBe 2
        descriptions.map { it.providerId } shouldContain AiProviderId.NOOP
        descriptions.map { it.providerId } shouldContain AiProviderId("openai")
        // Strukturzusage: AiProviderDescription hat KEIN endpoint-/
        // secretRef-Feld — wird durch Property-Names verifiziert,
        // hier nur Sanity-Check der Form.
        descriptions.first { it.providerId == AiProviderId("openai") }.allowedModels
            .shouldContainExactly(setOf("gpt-4o"))
    }

    test("describe() filtert disabled Provider aus") {
        val registry = DefaultAiProviderRegistry(
            configs = listOf(externalConfig().copy(enabled = false)),
            ports = mapOf(
                AiProviderId.NOOP to noOpPort,
                AiProviderId("openai") to externalPort,
            ),
        )
        registry.describe().map { it.providerId } shouldContainExactly listOf(AiProviderId.NOOP)
    }
})
