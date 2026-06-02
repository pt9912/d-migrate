package dev.dmigrate.server.application.ai

/**
 * LF-017 / LF-024 / LN-030 / LN-031— adapterneutraler Provider-Port.
 *
 * Implementierungen:
 *
 * - [NoOpAiProvider] — deterministischer Default, keine
 *   Netzwerkzugriffe, keine externen Secrets. Wird in jedem
 *   Default- und Test-Pfad verwendet.
 * - lokale Provider (`OllamaAiProvider`, `LmStudioAiProvider` —
 *   LF-017 / LF-024 / LN-030 / LN-031): nur über erlaubte Loopback-Endpunkte, ohne
 *   `secretRef`-Pflicht.
 * - externe Provider (LF-017 / LF-024 / LN-030 / LN-031): nur mit explizit konfiguriertem
 *   `secretRef`, erlaubender Policy und Audit-Pflicht-Metadaten.
 *
 * Vertrag (LF-017 / LF-024 / LN-030 / LN-031 + §7.2):
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
 *    Tool-Handler-/LF-017 / LF-024 / LN-030 / LN-031-Concern. Der Port garantiert nur, dass
 *    der **Input** bereits hygienisiert ist (siehe
 *    [AiProviderRequest]).
 *
 * Sync-Aufruf (kein `suspend`) — passt zur d-migrate-Konvention
 * (vgl. `JobInputFinalizer`, `UploadInitOrchestrator`). Caller,
 * die einen Provider asynchron orchestrieren wollen, wrappen den
 * Aufruf in `CompletableFuture.supplyAsync` (LF-012 / LN-011 / LN-017 / LN-027 `JobExecutor`-
 * Pattern).
 */
fun interface AiProviderPort {

    fun invoke(request: AiProviderRequest): AiProviderResult
}
