package dev.dmigrate.server.application.ai

import java.time.Duration

/**
 * Phase G § 5.2 — serverseitige Provider-Konfiguration.
 *
 * **Verboten in jedem Feld**: Roh-Secrets, expandierte API-Keys,
 * Connection-Secrets. Nur [secretRef] (Env-Variablenname o. Ä.) ist
 * erlaubt; die tatsächliche Materialisierung passiert erst im
 * Provider-Adapter nach Scope-/Outcome-/Policy-/Hygiene-/Quota-
 * Entscheidungen (Plan §5.2 Z. 603-606).
 *
 * Die Konfiguration wird vom Bootstrap geladen (YAML, Env, …) und
 * an [AiProviderRegistry] übergeben. Validierung über
 * [AiProviderConfigValidator]; ungültige Configs lassen den Server
 * fail-closed starten (Plan §6 G.3 Akzeptanz).
 *
 * @param providerId stabile Identität (Plan §4.1 + §5.2).
 * @param kind Kategorisierung — entscheidet Endpoint-/Secret-Regeln.
 * @param enabled Plan §5.2 Z. 601: `enabled=false` blockiert
 *   Provider-Aufrufe ohne weitere Prüfung.
 * @param endpoint URL für `LOCAL_LOOPBACK`/`EXTERNAL`, `null` für
 *   `NOOP`. Validator erzwingt für `LOCAL_LOOPBACK` Loopback und für
 *   `EXTERNAL` HTTPS.
 * @param allowedModels Whitelist der Modelle, die der Caller
 *   anfragen darf. Modell außerhalb der Liste → der Tool-Handler
 *   liefert `VALIDATION_ERROR` (Plan §5.2 + §6 G.5).
 * @param secretRef Env-Variable, aus der das Provider-Secret
 *   gelesen wird. Pflicht für `EXTERNAL`, optional für
 *   `LOCAL_LOOPBACK`, **muss** `null` für `NOOP` sein.
 * @param defaultTimeout Provider-Aufruf-Timeout, sofern der Caller
 *   keinen eigenen Wert in [AiProviderRequest.timeout] setzt.
 * @param maxPromptBytes Cap für das hygienisierte Prompt vor
 *   Provider-Aufruf (Plan §5.2). Auch `capabilities_list.limits`
 *   weist diesen Wert aus.
 * @param maxOutputBytes Cap für die Provider-Antwort. Plan §5.1:
 *   Überschreitung → [AiProviderError.OUTPUT_TOO_LARGE].
 * @param allowExternalNetwork Plan §5.2 Z. 602: `false` blockiert
 *   nicht-lokale Endpoints. Für `EXTERNAL` Pflicht `true`, für
 *   `LOCAL_LOOPBACK` Pflicht `false` (lokale Provider dürfen den
 *   Loopback-Bereich nicht verlassen), für `NOOP` egal.
 * @param auditMode Plan §5.2 Z. 597: Audit-Detail-Stufe für
 *   Provider-Aufrufe. `FULL` schreibt Provider-/Modell-/Fingerprint-
 *   Metadaten ins Audit-Event; `REDACTED` reduziert auf Provider-
 *   Name + Outcome.
 */
data class AiProviderConfig(
    val providerId: AiProviderId,
    val kind: AiProviderKind,
    val enabled: Boolean,
    val endpoint: String?,
    val allowedModels: Set<String>,
    val secretRef: String?,
    val defaultTimeout: Duration,
    val maxPromptBytes: Int,
    val maxOutputBytes: Int,
    val allowExternalNetwork: Boolean,
    val auditMode: AiProviderAuditMode,
) {
    init {
        // Form-Invarianten am Konstruktor — semantische Regeln
        // (Endpoint-Format, secretRef-Pflicht je Kind) prüft
        // [AiProviderConfigValidator]. Hier nur "darf nie verletzt
        // werden, egal welcher Kind".
        require(allowedModels.isNotEmpty()) {
            "allowedModels must not be empty for provider ${providerId.value}"
        }
        require(allowedModels.all { it.isNotBlank() }) {
            "allowedModels must contain non-blank entries for provider ${providerId.value}"
        }
        require(!defaultTimeout.isNegative && !defaultTimeout.isZero) {
            "defaultTimeout must be positive for provider ${providerId.value}"
        }
        require(maxPromptBytes > 0) {
            "maxPromptBytes must be > 0 for provider ${providerId.value}"
        }
        require(maxOutputBytes > 0) {
            "maxOutputBytes must be > 0 for provider ${providerId.value}"
        }
        require(secretRef?.isNotBlank() != false) {
            "secretRef must be non-blank or null for provider ${providerId.value}"
        }
        require(endpoint?.isNotBlank() != false) {
            "endpoint must be non-blank or null for provider ${providerId.value}"
        }
    }

    companion object {

        /**
         * Plan §4.1 verbindlicher Default — der NoOp-Provider, der
         * jedem Bootstrap zur Verfügung steht. `enabled=true`,
         * keine Modelle whitelisted ausser `"noop:default"` —
         * Konfigurationen können diese erweitern, dürfen sie aber
         * nicht durch ein leeres Set ersetzen.
         */
        fun noOpDefault(
            allowedModels: Set<String> = setOf("noop:default"),
            defaultTimeout: Duration = Duration.ofSeconds(30),
            maxPromptBytes: Int = 32_768,
            maxOutputBytes: Int = 65_536,
        ): AiProviderConfig = AiProviderConfig(
            providerId = AiProviderId.NOOP,
            kind = AiProviderKind.NOOP,
            enabled = true,
            endpoint = null,
            allowedModels = allowedModels,
            secretRef = null,
            defaultTimeout = defaultTimeout,
            maxPromptBytes = maxPromptBytes,
            maxOutputBytes = maxOutputBytes,
            allowExternalNetwork = false,
            auditMode = AiProviderAuditMode.FULL,
        )
    }
}

/**
 * Plan §5.2 Z. 597: Audit-Detail-Stufe für Provider-Aufrufe.
 */
enum class AiProviderAuditMode {
    /**
     * Schreibt Provider-/Modell-/Fingerprint-Metadaten ins Audit-
     * Event. Standard für interne und produktiv geprüfte Provider.
     */
    FULL,

    /**
     * Reduziert auf Provider-Name + Outcome (kein Modell, kein
     * Fingerprint). Für Provider mit besonders restriktiven
     * Compliance-Anforderungen.
     */
    REDACTED,
}
