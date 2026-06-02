package dev.dmigrate.server.application.ai

/**
 * LF-017 / LF-024 / LN-030 / LN-031— Provider-Kategorisierung für Konfiguration und
 * Endpoint-Validierung.
 *
 * - [NOOP] — der deterministische Default-Provider. Kein Endpoint,
 *   kein Secret, kein Netzwerk. Immer verfügbar.
 * - [LOCAL_LOOPBACK] — lokale Provider wie Ollama, LM Studio.
 *   Endpoint MUSS Loopback (`127.0.0.1`/`localhost`) oder ein
 *   explizit erlaubtes lokales Netzwerkziel sein.
 *   `secretRef` ist optional (lokal nicht auth-pflichtig).
 * - [EXTERNAL] — Cloud-Provider wie OpenAI, Anthropic. Endpoint
 *   MUSS HTTPS sein, `secretRef` ist Pflicht (LF-017 / LF-024 / LN-030 / LN-031).
 *   `allowExternalNetwork=true` Pflicht im Config.
 */
enum class AiProviderKind {
    NOOP,
    LOCAL_LOOPBACK,
    EXTERNAL,
}
