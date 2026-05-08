package dev.dmigrate.server.application.ai

/**
 * Phase G § 5.1 (G.2 1/4) — typisierte Provider-Fehler.
 *
 * Provider-spezifische Exceptions (HTTP-Status, SDK-Throwables,
 * lokale Pipe-Brüche) werden in der Adapter-Schicht zu einem dieser
 * Werte normalisiert; der Handler darf provider-spezifische Klassen
 * **nie** weiter durch die Audit-/Wire-Schicht reichen, weil der
 * Stacktrace Secrets oder Endpunkt-Hinweise enthalten kann.
 *
 * Plan §7.2 verbindliche Fehler-Mappings (für G.6):
 *
 * - [TIMEOUT] → `OPERATION_TIMEOUT` (retryable)
 * - [RATE_LIMITED] → `RATE_LIMITED` (retryable)
 * - [PROVIDER_UNAVAILABLE] → `INTERNAL_AGENT_ERROR` (retryable)
 * - [UNAUTHORIZED] → `INTERNAL_AGENT_ERROR` (terminal — Server-
 *   Konfiguration ist defekt, der Caller kann nicht heilen)
 * - [BAD_REQUEST] → `INTERNAL_AGENT_ERROR` (terminal — der Server
 *   hat ein nicht akzeptables Prompt erzeugt; das ist ein Bug)
 * - [OUTPUT_TOO_LARGE] → `PAYLOAD_TOO_LARGE` (terminal)
 * - [OUTPUT_HYGIENE_BLOCKED] → `PROMPT_HYGIENE_BLOCKED` (terminal —
 *   Provider-Output enthält Secrets/Rohdaten und wird verworfen)
 * - [INTERNAL] → `INTERNAL_AGENT_ERROR` (terminal)
 *
 * `defaultRetryable` ist der Plan-§-7.2-Default; ein konkreter Caller
 * (etwa der `AiToolOutcomeStore`-Pfad) darf für eine Instanz das
 * `Failure.retryable`-Flag überschreiben, wenn der Kontext es
 * rechtfertigt.
 */
enum class AiProviderError(val defaultRetryable: Boolean) {
    /** Provider hat in der vorgegebenen Zeit nicht geantwortet. */
    TIMEOUT(defaultRetryable = true),

    /** Provider hat ein Quota- oder Rate-Limit signalisiert. */
    RATE_LIMITED(defaultRetryable = true),

    /** Provider ist temporär nicht erreichbar (HTTP 5xx, Netzwerk). */
    PROVIDER_UNAVAILABLE(defaultRetryable = true),

    /**
     * Authentifizierung beim Provider ist fehlgeschlagen.
     * Caller-seitig nicht heilbar — Server-Config defekt.
     */
    UNAUTHORIZED(defaultRetryable = false),

    /**
     * Provider hat den Request abgelehnt (z. B. unbekanntes Modell,
     * inkompatible Parameter). Caller-seitig nicht heilbar.
     */
    BAD_REQUEST(defaultRetryable = false),

    /**
     * Provider-Output überschreitet das `maxOutputBytes`-Limit.
     * Caller-Replay würde dieselbe Antwort erzeugen — terminal.
     */
    OUTPUT_TOO_LARGE(defaultRetryable = false),

    /**
     * Output-Hygiene-Service hat den Provider-Output blockiert
     * (Secrets, Rohdaten erkannt). Caller-Replay erzeugt dieselbe
     * Antwort — terminal.
     */
    OUTPUT_HYGIENE_BLOCKED(defaultRetryable = false),

    /**
     * Unerwarteter interner Fehler im Provider-Adapter (Parsing,
     * Mapping). Terminal, weil Replay nicht hilft.
     */
    INTERNAL(defaultRetryable = false),
}
