package dev.dmigrate.server.application.ai

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import java.time.Duration

/**
 * Phase G § 5.2 (G.3) — semantische Config-Validierung pro
 * Provider-Kind.
 */
class AiProviderConfigValidatorTest : FunSpec({

    fun cfg(
        id: AiProviderId,
        kind: AiProviderKind,
        endpoint: String? = null,
        secretRef: String? = null,
        allowExternalNetwork: Boolean = false,
        models: Set<String> = setOf("default"),
    ) = AiProviderConfig(
        providerId = id,
        kind = kind,
        enabled = true,
        endpoint = endpoint,
        allowedModels = models,
        secretRef = secretRef,
        defaultTimeout = Duration.ofSeconds(30),
        maxPromptBytes = 32_768,
        maxOutputBytes = 65_536,
        allowExternalNetwork = allowExternalNetwork,
        auditMode = AiProviderAuditMode.FULL,
    )

    test("Plan §4.1: NoOp default config validiert ohne Fehler") {
        AiProviderConfigValidator.validate(AiProviderConfig.noOpDefault()).shouldBeEmpty()
    }

    test("NOOP mit Endpoint -> field-Fehler 'endpoint must be null'") {
        val errors = AiProviderConfigValidator.validate(
            cfg(AiProviderId.NOOP, AiProviderKind.NOOP, endpoint = "http://localhost:1234"),
        )
        errors.size shouldBe 1
        errors[0].field shouldBe "endpoint"
        errors[0].reason shouldBe "must be null for kind=NOOP"
    }

    test("NOOP mit secretRef -> field-Fehler 'secretRef must be null'") {
        val errors = AiProviderConfigValidator.validate(
            cfg(AiProviderId.NOOP, AiProviderKind.NOOP, secretRef = "DMIGRATE_NOOP_SECRET"),
        )
        errors.map { it.field } shouldContain "secretRef"
    }

    test("LOCAL_LOOPBACK ohne Endpoint -> 'endpoint required'") {
        val errors = AiProviderConfigValidator.validate(
            cfg(AiProviderId.OLLAMA, AiProviderKind.LOCAL_LOOPBACK, endpoint = null),
        )
        errors.size shouldBe 1
        errors[0].field shouldBe "endpoint"
        errors[0].reason shouldBe "is required for kind=LOCAL_LOOPBACK"
    }

    test("Plan §6 G.3: lokaler Provider mit Loopback-Endpoint und ohne secretRef ist gueltig") {
        AiProviderConfigValidator.validate(
            cfg(
                AiProviderId.OLLAMA, AiProviderKind.LOCAL_LOOPBACK,
                endpoint = "http://localhost:11434",
                secretRef = null,
            ),
        ).shouldBeEmpty()

        AiProviderConfigValidator.validate(
            cfg(
                AiProviderId.LM_STUDIO, AiProviderKind.LOCAL_LOOPBACK,
                endpoint = "http://127.0.0.1:1234",
                secretRef = null,
            ),
        ).shouldBeEmpty()
    }

    test("Plan §6 G.3: lokaler Provider mit nicht-loopback Endpoint wird fail-closed abgewiesen") {
        val errors = AiProviderConfigValidator.validate(
            cfg(
                AiProviderId.OLLAMA, AiProviderKind.LOCAL_LOOPBACK,
                endpoint = "http://10.0.0.5:11434",
                secretRef = null,
            ),
        )
        errors.size shouldBe 1
        errors[0].field shouldBe "endpoint"
        errors[0].reason.contains("loopback") shouldBe true
    }

    test("LOCAL_LOOPBACK mit allowExternalNetwork=true wird abgewiesen") {
        val errors = AiProviderConfigValidator.validate(
            cfg(
                AiProviderId.OLLAMA, AiProviderKind.LOCAL_LOOPBACK,
                endpoint = "http://localhost:11434",
                allowExternalNetwork = true,
            ),
        )
        errors.map { it.field } shouldContain "allowExternalNetwork"
    }

    test("Plan §6 G.3: auth-pflichtiger externer Provider ohne secretRef wird fail-closed abgewiesen") {
        val errors = AiProviderConfigValidator.validate(
            cfg(
                AiProviderId("openai"), AiProviderKind.EXTERNAL,
                endpoint = "https://api.openai.com",
                secretRef = null,
                allowExternalNetwork = true,
            ),
        )
        errors.map { it.field } shouldContain "secretRef"
    }

    test("EXTERNAL mit gueltiger HTTPS-URL und secretRef ist akzeptabel") {
        AiProviderConfigValidator.validate(
            cfg(
                AiProviderId("openai"), AiProviderKind.EXTERNAL,
                endpoint = "https://api.openai.com",
                secretRef = "DMIGRATE_OPENAI_API_KEY",
                allowExternalNetwork = true,
            ),
        ).shouldBeEmpty()
    }

    test("EXTERNAL mit HTTP (nicht HTTPS) wird abgewiesen") {
        val errors = AiProviderConfigValidator.validate(
            cfg(
                AiProviderId("openai"), AiProviderKind.EXTERNAL,
                endpoint = "http://api.openai.com",
                secretRef = "DMIGRATE_OPENAI_API_KEY",
                allowExternalNetwork = true,
            ),
        )
        errors.map { it.field } shouldContain "endpoint"
        errors.first { it.field == "endpoint" }.reason.contains("HTTPS") shouldBe true
    }

    test("EXTERNAL mit Loopback-Endpoint wird abgewiesen") {
        val errors = AiProviderConfigValidator.validate(
            cfg(
                AiProviderId("openai"), AiProviderKind.EXTERNAL,
                endpoint = "https://localhost:8443",
                secretRef = "DMIGRATE_OPENAI_API_KEY",
                allowExternalNetwork = true,
            ),
        )
        errors.map { it.field } shouldContain "endpoint"
    }

    test("EXTERNAL mit allowExternalNetwork=false wird abgewiesen") {
        val errors = AiProviderConfigValidator.validate(
            cfg(
                AiProviderId("openai"), AiProviderKind.EXTERNAL,
                endpoint = "https://api.openai.com",
                secretRef = "DMIGRATE_OPENAI_API_KEY",
                allowExternalNetwork = false,
            ),
        )
        errors.map { it.field } shouldContain "allowExternalNetwork"
    }
})
