package dev.dmigrate.server.application.ai

/**
 * Phase G § 5.1 (G.2 4/4) — adapterneutraler Provider-Port.
 *
 * Implementierungen:
 *
 * - [NoOpAiProvider] — deterministischer Default, keine
 *   Netzwerkzugriffe, keine externen Secrets. Wird in jedem
 *   Default- und Test-Pfad verwendet.
 * - lokale Provider (`OllamaAiProvider`, `LmStudioAiProvider` —
 *   G.3): nur über erlaubte Loopback-Endpunkte, ohne
 *   `secretRef`-Pflicht.
 * - externe Provider (G.3): nur mit explizit konfiguriertem
 *   `secretRef`, erlaubender Policy und Audit-Pflicht-Metadaten.
 *
 * Vertrag (Plan §6 G.2 + §7.2):
 *
 * 1. Der Aufruf liefert genau eines: [AiProviderResult.Success]
 *    oder [AiProviderResult.Failure]. Provider-spezifische
 *    Throwables werden NIE durch diesen Port propagiert; der
 *    Adapter normalisiert sie in [AiProviderError].
 * 2. Der Aufruf darf den `request.timeout` nicht überschreiten;
 *    Überschreitung → `Failure(AiProviderError.TIMEOUT, ...)`.
 * 3. Ein Output über `request.maxOutputBytes` →
 *    `Failure(AiProviderError.OUTPUT_TOO_LARGE, ...)` ohne
 *    Truncation des Strings (Caller bekommt eine konsistente
 *    Failure-Antwort, kein halbes Output).
 * 4. Der Port führt keine Output-Hygiene durch — das ist
 *    Tool-Handler-/G.4-Concern. Der Port garantiert nur, dass
 *    der **Input** bereits hygienisiert ist (siehe
 *    [AiProviderRequest]).
 *
 * Sync-Aufruf (kein `suspend`) — passt zur d-migrate-Konvention
 * (vgl. `JobInputFinalizer`, `UploadInitOrchestrator`). Caller,
 * die einen Provider asynchron orchestrieren wollen, wrappen den
 * Aufruf in `CompletableFuture.supplyAsync` (Phase E `JobExecutor`-
 * Pattern).
 */
fun interface AiProviderPort {

    fun invoke(request: AiProviderRequest): AiProviderResult
}
