package dev.dmigrate.server.application.ai

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class AiProviderIdTest : FunSpec({

    test("akzeptiert lowercase, Ziffern, '-', '_', '.'") {
        AiProviderId("noop").value shouldBe "noop"
        AiProviderId("ollama").value shouldBe "ollama"
        AiProviderId("lm-studio").value shouldBe "lm-studio"
        AiProviderId("provider_v2").value shouldBe "provider_v2"
        AiProviderId("anthropic.com").value shouldBe "anthropic.com"
    }

    test("blank, Whitespace und Pfad-Zeichen werden abgewiesen") {
        shouldThrow<IllegalArgumentException> { AiProviderId("") }
        shouldThrow<IllegalArgumentException> { AiProviderId(" ") }
        shouldThrow<IllegalArgumentException> { AiProviderId("provider name") }
        shouldThrow<IllegalArgumentException> { AiProviderId("provider/name") }
        shouldThrow<IllegalArgumentException> { AiProviderId("Provider") } // upper
        shouldThrow<IllegalArgumentException> { AiProviderId("p:roper") }
    }

    test("max-length wird durchgesetzt") {
        val longId = "a".repeat(AiProviderId.MAX_LENGTH)
        AiProviderId(longId).value.length shouldBe AiProviderId.MAX_LENGTH
        shouldThrow<IllegalArgumentException> { AiProviderId(longId + "a") }
    }

    test("Konstanten haben stabile Werte (fuer Goldens und Audit)") {
        AiProviderId.NOOP.value shouldBe "noop"
        AiProviderId.OLLAMA.value shouldBe "ollama"
        AiProviderId.LM_STUDIO.value shouldBe "lm-studio"
    }
})
