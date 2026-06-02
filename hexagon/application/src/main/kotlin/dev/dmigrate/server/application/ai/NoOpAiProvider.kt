package dev.dmigrate.server.application.ai

import dev.dmigrate.core.util.sha256Hex

/**
 * LF-017 / LF-024 / LN-030 / LN-031— deterministischer Default-Provider.
 *
 * `NoOpAiProvider` ist der **verbindliche Default** für jeden
 * Tenant ohne explizit konfigurierten externen Provider. Die
 * Akzeptanz aus LF-017 / LF-024 / LN-030 / LN-031:
 *
 * - liefert deterministische Ergebnisse (gleicher Request →
 *   gleicher Output, byte-identisch)
 * - kein Netzwerkzugriff (kein I/O über die JVM-Grenze hinaus)
 * - kein externes Secret (nur lokale Konstanten)
 * - kein Stacktrace-Leak (Failure-Pfad existiert nur über
 *   [AiProviderResult.Failure])
 *
 * Der Output ist eine kurze, maschinenlesbare Marker-Antwort, die
 * die Eingabe-Fingerprints widerspiegelt. Tests und der
 * Default-Tool-Pfad in LF-017 / LF-024 / LN-030 / LN-031 nutzen ihn, um den ganzen
 * Provider-→-Hygiene-→-Artefakt-Pfad zu validieren, ohne einen
 * realen Modell-Aufruf zu brauchen.
 *
 * Bewusst **kein** Sleep, kein Timeout-Simulieren, kein
 * Random-Output: alle drei würden Test-Flake einführen oder die
 * Determinismus-Akzeptanz brechen. Tests, die Timeout-/Failure-
 * Pfade pinnen wollen, nutzen [FakeFailingAiProvider] aus den
 * Test-Fixtures (LF-017 / LF-024 / LN-030 / LN-031).
 */
class NoOpAiProvider(
    private val providerName: String = DEFAULT_PROVIDER_NAME,
    private val modelVersion: String = DEFAULT_MODEL_VERSION,
) : AiProviderPort {

    override fun invoke(request: AiProviderRequest): AiProviderResult {
        val output = renderOutput(request)
        // LF-017 / LF-024 / LN-030 / LN-031: maxOutputBytes ist Provider-Vertrag —
        // schon der NoOp respektiert die Cap. Die Marker-Antwort
        // ist klein (deterministisch <512 Zeichen für jeden
        // Eingabe-Fingerprint), aber wir halten den Vertrag
        // explizit, damit die Akzeptanz für externe Provider
        // (LF-017 / LF-024 / LN-030 / LN-031) gleich aussieht.
        if (output.toByteArray(Charsets.UTF_8).size > request.maxOutputBytes) {
            return AiProviderResult.Failure(
                error = AiProviderError.OUTPUT_TOO_LARGE,
                message = "noop output (${output.length} chars) exceeds " +
                    "maxOutputBytes=${request.maxOutputBytes}",
            )
        }
        return AiProviderResult.Success(
            output = output,
            outputFingerprint = sha256Hex(output.toByteArray(Charsets.UTF_8)),
            providerMeta = ProviderMeta(
                providerName = providerName,
                model = request.model,
                modelVersion = modelVersion,
                // NoOp hat keine externe Korrelation; null statt
                // synthetisierter ID, damit Goldens den Unterschied
                // zu einem realen Provider (mit echter requestId)
                // sehen.
                requestId = null,
            ),
        )
    }

    /**
     * Deterministische Marker-Form. Format hält sich kurz, ist
     * aber strukturiert genug, dass Tool-Handler ihn als
     * "Plan-Inhalt" weiterverarbeiten können (LF-017 / LF-024 / LN-030 / LN-031: NoOp-
     * Antwort fließt in `procedure_transform_plan`-Plan-Artefakt
     * ein).
     */
    private fun renderOutput(request: AiProviderRequest): String =
        buildString {
            append("noop:")
            append(providerName)
            append(":model=").append(request.model)
            append(":prompt=").append(request.promptFingerprint.take(SHORT_FP_LENGTH))
            append(":payload=").append(request.payloadFingerprint.take(SHORT_FP_LENGTH))
        }

    companion object {
        const val DEFAULT_PROVIDER_NAME: String = "noop"
        const val DEFAULT_MODEL_VERSION: String = "0.9.7"
        private const val SHORT_FP_LENGTH: Int = 16
    }
}
