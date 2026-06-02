package dev.dmigrate.server.application.ai

import java.net.URI

/**
 * LF-017 / LF-024 / LN-030 / LN-031— semantische Validierung einer
 * [AiProviderConfig] vor der Aufnahme in die [AiProviderRegistry].
 *
 * Form-Invarianten (Pflichtfeld-Längen, positive Caps, Whitespace)
 * werden bereits vom [AiProviderConfig]-Konstruktor durchgesetzt.
 * Diese Stufe prüft die *kontextabhängigen* Regeln aus LF-017 / LF-024 / LN-030 / LN-031:
 *
 * - `NOOP`: kein Endpoint, kein `secretRef`,
 *   `allowExternalNetwork=false`.
 * - `LOCAL_LOOPBACK`: Endpoint Pflicht und MUSS Loopback
 *   (`localhost` / `127.0.0.1` / `::1`); `secretRef` optional;
 *   `allowExternalNetwork=false`.
 * - `EXTERNAL`: Endpoint Pflicht, MUSS HTTPS und kein Loopback;
 *   `secretRef` Pflicht; `allowExternalNetwork=true`.
 *
 * `validate` liefert eine sortierte Fehlerliste; ein leeres
 * Ergebnis bedeutet "Config darf in die Registry". Der Caller
 * (Bootstrap) entscheidet, ob er bei Fehlern fail-closed startet
 * oder die einzelne Config überspringt.
 */
object AiProviderConfigValidator {

    fun validate(config: AiProviderConfig): List<AiProviderConfigError> {
        val errors = mutableListOf<AiProviderConfigError>()
        when (config.kind) {
            AiProviderKind.NOOP -> validateNoOp(config, errors)
            AiProviderKind.LOCAL_LOOPBACK -> validateLocalLoopback(config, errors)
            AiProviderKind.EXTERNAL -> validateExternal(config, errors)
        }
        return errors.toList()
    }

    private fun validateNoOp(
        config: AiProviderConfig,
        errors: MutableList<AiProviderConfigError>,
    ) {
        if (config.endpoint != null) {
            errors += AiProviderConfigError(
                providerId = config.providerId,
                field = "endpoint",
                reason = "must be null for kind=NOOP",
            )
        }
        if (config.secretRef != null) {
            errors += AiProviderConfigError(
                providerId = config.providerId,
                field = "secretRef",
                reason = "must be null for kind=NOOP",
            )
        }
        if (config.allowExternalNetwork) {
            errors += AiProviderConfigError(
                providerId = config.providerId,
                field = "allowExternalNetwork",
                reason = "must be false for kind=NOOP",
            )
        }
    }

    private fun validateLocalLoopback(
        config: AiProviderConfig,
        errors: MutableList<AiProviderConfigError>,
    ) {
        val endpoint = config.endpoint
        if (endpoint == null) {
            errors += AiProviderConfigError(
                providerId = config.providerId,
                field = "endpoint",
                reason = "is required for kind=LOCAL_LOOPBACK",
            )
        } else if (!isLoopback(endpoint)) {
            errors += AiProviderConfigError(
                providerId = config.providerId,
                field = "endpoint",
                reason = "must point to a loopback host for kind=LOCAL_LOOPBACK, was '$endpoint'",
            )
        }
        if (config.allowExternalNetwork) {
            errors += AiProviderConfigError(
                providerId = config.providerId,
                field = "allowExternalNetwork",
                reason = "must be false for kind=LOCAL_LOOPBACK",
            )
        }
        // LF-017 / LF-024 / LN-030 / LN-031: secretRef ist für lokale, nicht-auth-pflichtige
        // Provider explizit absent/null erlaubt — keine
        // Pflicht-Prüfung hier.
    }

    private fun validateExternal(
        config: AiProviderConfig,
        errors: MutableList<AiProviderConfigError>,
    ) {
        val endpoint = config.endpoint
        if (endpoint == null) {
            errors += AiProviderConfigError(
                providerId = config.providerId,
                field = "endpoint",
                reason = "is required for kind=EXTERNAL",
            )
        } else {
            if (!isHttps(endpoint)) {
                errors += AiProviderConfigError(
                    providerId = config.providerId,
                    field = "endpoint",
                    reason = "must be HTTPS for kind=EXTERNAL, was '$endpoint'",
                )
            }
            if (isLoopback(endpoint)) {
                errors += AiProviderConfigError(
                    providerId = config.providerId,
                    field = "endpoint",
                    reason = "must not be loopback for kind=EXTERNAL, was '$endpoint'",
                )
            }
        }
        if (config.secretRef == null) {
            errors += AiProviderConfigError(
                providerId = config.providerId,
                field = "secretRef",
                reason = "is required for kind=EXTERNAL (auth-pflichtig)",
            )
        }
        if (!config.allowExternalNetwork) {
            errors += AiProviderConfigError(
                providerId = config.providerId,
                field = "allowExternalNetwork",
                reason = "must be true for kind=EXTERNAL",
            )
        }
    }

    private fun isHttps(endpoint: String): Boolean = try {
        URI.create(endpoint).scheme.equals("https", ignoreCase = true)
    } catch (_: IllegalArgumentException) {
        false
    }

    private fun isLoopback(endpoint: String): Boolean = try {
        val host = URI.create(endpoint).host?.lowercase() ?: return false
        host == "localhost" || host == "127.0.0.1" || host == "::1" || host == "[::1]"
    } catch (_: IllegalArgumentException) {
        false
    }
}

/**
 * Strukturierter Konfigurationsfehler. Wird vom Bootstrap in die
 * Server-Logs / Start-Failure-Message gespiegelt; **nie** über die
 * MCP-Wire-Schicht ausgegeben (LF-017 / LF-024 / LN-030 / LN-031: keine Server-
 * Konfig-Details an Caller).
 */
data class AiProviderConfigError(
    val providerId: AiProviderId,
    val field: String,
    val reason: String,
)
