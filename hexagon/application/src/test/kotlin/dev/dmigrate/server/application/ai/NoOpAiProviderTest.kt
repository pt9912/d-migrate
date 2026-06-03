package dev.dmigrate.server.application.ai

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.security.MessageDigest
import java.time.Duration

/**
 * LF-017 / LF-024 / LN-030 / LN-031— Akzeptanztests fuer den deterministischen
 * Default-Provider und den umgebenden Vertrag (Request/Result/Error).
 */
class NoOpAiProviderTest : FunSpec({

    val sampleFingerprint = "0".repeat(64)
    val otherFingerprint = "1".repeat(64)

    fun request(
        prompt: String = "describe procedure",
        model: String = "noop:default",
        promptFp: String = sampleFingerprint,
        payloadFp: String = sampleFingerprint,
        timeout: Duration = Duration.ofSeconds(30),
        maxOutputBytes: Int = 4096,
    ) = AiProviderRequest(
        prompt = prompt,
        model = model,
        promptFingerprint = promptFp,
        payloadFingerprint = payloadFp,
        timeout = timeout,
        maxOutputBytes = maxOutputBytes,
    )

    test("LF-017 / LF-024 / LN-030 / LN-031: NoOp liefert deterministische Outputs") {
        // Akzeptanz "NoOp liefert deterministische Ergebnisse":
        // gleiche Eingabe → byte-identischer Output + identischer
        // outputFingerprint.
        val provider = NoOpAiProvider()
        val first = provider.invoke(request()).shouldBeInstanceOf<AiProviderResult.Success>()
        val second = provider.invoke(request()).shouldBeInstanceOf<AiProviderResult.Success>()
        second.output shouldBe first.output
        second.outputFingerprint shouldBe first.outputFingerprint
    }

    test("LF-017 / LF-024 / LN-030 / LN-031: unterschiedliche Eingaben liefern unterschiedliche Outputs") {
        val provider = NoOpAiProvider()
        val withPromptA = provider.invoke(request(promptFp = sampleFingerprint))
            .shouldBeInstanceOf<AiProviderResult.Success>()
        val withPromptB = provider.invoke(request(promptFp = otherFingerprint))
            .shouldBeInstanceOf<AiProviderResult.Success>()
        withPromptB.output shouldNotBe withPromptA.output
        withPromptB.outputFingerprint shouldNotBe withPromptA.outputFingerprint
    }

    test("LF-017 / LF-024 / LN-030 / LN-031: NoOp ruft kein Netzwerk und liest kein externes Secret") {
        // Strukturzusage: NoOp's Output enthaelt nur die Eingabe-
        // Fingerprints + Provider-Marker. Kein URL-, Endpoint-,
        // Token-, oder Pfad-Hinweis.
        val provider = NoOpAiProvider()
        val output = (provider.invoke(request()) as AiProviderResult.Success).output
        output shouldContain "noop:"
        output shouldNotContain "http"
        output shouldNotContain "://"
        output shouldNotContain "token"
        output shouldNotContain "secret"
    }

    test("LF-017 / LF-024 / LN-030 / LN-031: providerMeta traegt Provider-Identitaet, kein Endpoint") {
        val provider = NoOpAiProvider()
        val meta = (provider.invoke(request(model = "noop:test-model")) as AiProviderResult.Success).providerMeta
        meta.providerName shouldBe "noop"
        meta.model shouldBe "noop:test-model"
        meta.modelVersion shouldBe NoOpAiProvider.DEFAULT_MODEL_VERSION
        // NoOp hat keine externe Korrelation — explizit null,
        // damit Goldens den Unterschied zu realen Providern sehen.
        meta.requestId shouldBe null
    }

    // Silent-Fallback-Guard: das `shouldBe DEFAULT_MODEL_VERSION` oben
    // vergleicht beide Seiten gegen denselben VersionInfo-Singleton.
    // Wenn :hexagon:core's dmigrate-version.properties hier vom
    // Classpath verschwindet, würde der obige Test mit "unknown" ==
    // "unknown" still passen. Dieser Guard fängt das.
    test("DEFAULT_MODEL_VERSION resolves on the application classpath") {
        NoOpAiProvider.DEFAULT_MODEL_VERSION shouldNotBe "unknown"
    }

    test("outputFingerprint ist SHA-256 ueber output-Bytes") {
        val provider = NoOpAiProvider()
        val success = provider.invoke(request()) as AiProviderResult.Success
        val expected = sha256Hex(success.output.toByteArray(Charsets.UTF_8))
        success.outputFingerprint shouldBe expected
    }

    test("LF-017 / LF-024 / LN-030 / LN-031: ueberdimensioniertes Output liefert OUTPUT_TOO_LARGE statt Truncation") {
        // maxOutputBytes=8 ist hart unterhalb der Marker-Form;
        // NoOp respektiert die Cap und liefert Failure statt
        // gekuerztes Output (LF-017 / LF-024 / LN-030 / LN-031 Akzeptanz: keine
        // halben Outputs).
        val provider = NoOpAiProvider()
        val outcome = provider.invoke(request(maxOutputBytes = 8))
        val failure = outcome.shouldBeInstanceOf<AiProviderResult.Failure>()
        failure.error shouldBe AiProviderError.OUTPUT_TOO_LARGE
        failure.retryable shouldBe false
    }

    test("LF-017 / LF-024 / LN-030 / LN-031: AiProviderError.defaultRetryable trennt retryable und terminal sauber") {
        // LF-017 / LF-024 / LN-030 / LN-031 Mappings — wir pinnen sie hier strukturell,
        // damit eine spaetere Aenderung am Enum auffaellt.
        AiProviderError.TIMEOUT.defaultRetryable shouldBe true
        AiProviderError.RATE_LIMITED.defaultRetryable shouldBe true
        AiProviderError.PROVIDER_UNAVAILABLE.defaultRetryable shouldBe true
        AiProviderError.UNAUTHORIZED.defaultRetryable shouldBe false
        AiProviderError.BAD_REQUEST.defaultRetryable shouldBe false
        AiProviderError.OUTPUT_TOO_LARGE.defaultRetryable shouldBe false
        AiProviderError.OUTPUT_HYGIENE_BLOCKED.defaultRetryable shouldBe false
        AiProviderError.INTERNAL.defaultRetryable shouldBe false
    }

    test("AiProviderResult.Failure erlaubt Retryable-Override pro Aufruf") {
        // LF-017 / LF-024 / LN-030 / LN-031: Caller (AiToolOutcomeStore) darf den
        // defaultRetryable-Wert ueberschreiben, wenn der Kontext
        // es rechtfertigt — etwa "Provider-Quota ist hartem
        // Tenant-Limit, nicht retryable".
        val terminal = AiProviderResult.Failure(
            error = AiProviderError.RATE_LIMITED,
            message = "tenant hard cap reached",
            retryable = false,
        )
        terminal.error.defaultRetryable shouldBe true
        terminal.retryable shouldBe false
    }

    test("AiProviderRequest validiert Pflicht-Invarianten am Konstruktor") {
        shouldThrow<IllegalArgumentException> { request(prompt = "") }
        shouldThrow<IllegalArgumentException> { request(prompt = " ") }
        shouldThrow<IllegalArgumentException> { request(model = "") }
        shouldThrow<IllegalArgumentException> {
            request(promptFp = "shorthex")
        }
        shouldThrow<IllegalArgumentException> {
            request(payloadFp = "shorthex")
        }
        shouldThrow<IllegalArgumentException> {
            request(timeout = Duration.ZERO)
        }
        shouldThrow<IllegalArgumentException> {
            request(timeout = Duration.ofSeconds(-1))
        }
        shouldThrow<IllegalArgumentException> {
            request(maxOutputBytes = 0)
        }
    }

    test("AiProviderResult.Success und Failure validieren ihre Felder") {
        shouldThrow<IllegalArgumentException> {
            AiProviderResult.Success(
                output = "",
                outputFingerprint = sampleFingerprint,
                providerMeta = ProviderMeta("noop", "m", null, null),
            )
        }
        shouldThrow<IllegalArgumentException> {
            AiProviderResult.Success(
                output = "x",
                outputFingerprint = "not-64-hex",
                providerMeta = ProviderMeta("noop", "m", null, null),
            )
        }
        shouldThrow<IllegalArgumentException> {
            AiProviderResult.Failure(
                error = AiProviderError.INTERNAL,
                message = "",
            )
        }
    }

    test("ProviderMeta lehnt blank-Werte fuer optionale Felder ab") {
        // Optional, aber wenn gesetzt: nicht blank. Verhindert
        // versehentliches Audit-Schwarzloch.
        shouldThrow<IllegalArgumentException> {
            ProviderMeta("noop", "m", modelVersion = "", requestId = null)
        }
        shouldThrow<IllegalArgumentException> {
            ProviderMeta("noop", "m", modelVersion = null, requestId = " ")
        }
    }
})

private fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
