package dev.dmigrate.server.application.ai

/**
 * Phase G § 5.2 — typisierter Provider-Identifikator.
 *
 * Stabile, scrub-sichere Provider-Identität (`noop`, `ollama`,
 * `lm-studio`, `openai`, `anthropic`, ...). Dient als Schlüssel im
 * [AiProviderRegistry], als Wert in [ProviderMeta.providerName]
 * und als Audit-Feld.
 *
 * Der Wrapper validiert nur das Format (kein Whitespace, keine
 * Pfad-/URL-Zeichen, max. 64 Zeichen). Welche IDs tatsächlich
 * konfiguriert sind, entscheidet die [AiProviderRegistry].
 */
@JvmInline
value class AiProviderId(val value: String) {

    init {
        require(value.isNotBlank()) { "providerId must not be blank" }
        require(value.length <= MAX_LENGTH) {
            "providerId must be at most $MAX_LENGTH characters, was ${value.length}"
        }
        require(value.all { it in ALLOWED_CHARS }) {
            "providerId may only contain [a-z0-9._-], was '$value'"
        }
    }

    override fun toString(): String = value

    companion object {
        const val MAX_LENGTH: Int = 64
        private val ALLOWED_CHARS: Set<Char> =
            ('a'..'z').toSet() + ('0'..'9').toSet() + setOf('-', '_', '.')

        /** Plan §4.1 verbindlicher Default — immer verfügbar. */
        val NOOP: AiProviderId = AiProviderId("noop")

        /** Plan §6 G.3: lokale Provider — Vorbereitung, kein Adapter in 0.9.6. */
        val OLLAMA: AiProviderId = AiProviderId("ollama")
        val LM_STUDIO: AiProviderId = AiProviderId("lm-studio")
    }
}
