package dev.dmigrate.mcp.server

import java.net.InetAddress
import java.net.URI
import java.net.UnknownHostException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

enum class AuthMode { DISABLED, JWT_JWKS, JWT_INTROSPECTION }

/**
 * Verbindlicher Feldsatz aus `ImpPlan-0.9.6-B.md` §12.12. Defaults
 * sind fail-closed: `JWT_JWKS` ohne issuer/jwksUrl/audience ist
 * absichtlich invalid — Konfiguration muss explizit gesetzt werden,
 * sonst startet der Server nicht (§5.2).
 */
data class McpServerConfig(
    val bindAddress: String = "127.0.0.1",
    val port: Int = 0,
    val publicBaseUrl: URI? = null,
    val allowedOrigins: Set<String> = DEFAULT_LOOPBACK_ORIGINS,
    val authMode: AuthMode = AuthMode.JWT_JWKS,
    val issuer: URI? = null,
    val jwksUrl: URI? = null,
    val introspectionUrl: URI? = null,
    val audience: String? = null,
    val algorithmAllowlist: Set<String> = DEFAULT_ALGORITHMS,
    val clockSkew: Duration = Duration.ofSeconds(60),
    val scopeMapping: Map<String, Set<String>> = DEFAULT_SCOPE_MAPPING,
    val sessionIdleTimeout: Duration = Duration.ofMinutes(30),
    val stdioTokenFile: Path? = null,
) {
    companion object {
        val DEFAULT_LOOPBACK_ORIGINS: Set<String> = setOf(
            "http://localhost:*",
            "http://127.0.0.1:*",
        )
        val DEFAULT_ALGORITHMS: Set<String> = setOf(
            "RS256", "RS384", "RS512", "ES256", "ES384", "ES512",
        )
        val DEFAULT_SCOPE_MAPPING: Map<String, Set<String>> = buildDefaultScopeMapping()

        // §12.12 validation bounds.
        val MAX_CLOCK_SKEW: Duration = Duration.ofMinutes(5)
    }
}

/**
 * §12.12 start-time validation for the **HTTP** transport. Returns
 * the (possibly empty) list of configuration errors; an empty list
 * means the config can be used to start `startHttp`. Callers MUST
 * refuse to start when this list is non-empty (§5.2 — Startfehler vor
 * dem ersten Client-Request).
 *
 * §12.15 explicitly says stdio ignores `authMode`, so stdio callers
 * use [validateForStdio] which skips the auth-mode block.
 */
@Suppress("CyclomaticComplexMethod")
fun McpServerConfig.validate(): List<String> {
    val errors = sharedErrors().toMutableList()

    val bindIsLoopback = bindIsLoopback()

    when (authMode) {
        AuthMode.DISABLED -> {
            if (!bindIsLoopback) {
                errors += "authMode=DISABLED requires loopback bind address (got '$bindAddress')"
            }
            if (publicBaseUrl != null) {
                errors += "authMode=DISABLED forbids publicBaseUrl"
            }
        }
        AuthMode.JWT_JWKS -> {
            if (issuer == null) errors += "authMode=JWT_JWKS requires issuer"
            if (audience == null) errors += "authMode=JWT_JWKS requires audience"
            if (jwksUrl == null) errors += "authMode=JWT_JWKS requires jwksUrl"
        }
        AuthMode.JWT_INTROSPECTION -> {
            if (issuer == null) errors += "authMode=JWT_INTROSPECTION requires issuer"
            if (audience == null) errors += "authMode=JWT_INTROSPECTION requires audience"
            if (introspectionUrl == null) errors += "authMode=JWT_INTROSPECTION requires introspectionUrl"
        }
    }

    if (!bindIsLoopback && allowedOrigins == McpServerConfig.DEFAULT_LOOPBACK_ORIGINS) {
        errors += "non-loopback bind '$bindAddress' requires explicit allowedOrigins"
    }

    return errors
}

/**
 * §12.15 start-time validation for the **stdio** transport. Skips
 * every HTTP-only rule (`authMode` consistency, bind/origin checks,
 * `publicBaseUrl` constraints) — stdio is a per-process pipe, those
 * fields don't apply. The shared rules (port-range, clock-skew,
 * algorithm allowlist, `stdioTokenFile` readability, `publicBaseUrl`
 * scheme, allowedOrigins wildcard) still hold so a stdio-misuse
 * surface (e.g. `stdioTokenFile` pointing at an unreadable file)
 * still fails fast.
 */
fun McpServerConfig.validateForStdio(): List<String> = sharedErrors()

/**
 * §12.12 / §12.15 rules that apply to BOTH transports.
 */
private fun McpServerConfig.sharedErrors(): List<String> {
    val errors = mutableListOf<String>()

    if (port < 0 || port > MAX_PORT) {
        errors += "port must be in 0..$MAX_PORT (got $port)"
    }
    if (clockSkew.isNegative || clockSkew > McpServerConfig.MAX_CLOCK_SKEW) {
        errors += "clockSkew must be in [0, ${McpServerConfig.MAX_CLOCK_SKEW}] (got $clockSkew)"
    }
    if (publicBaseUrl != null && publicBaseUrl.scheme != "https") {
        errors += "publicBaseUrl must use https scheme (got '${publicBaseUrl.scheme}')"
    }
    if ("*" in allowedOrigins) {
        errors += "allowedOrigins must not contain wildcard '*'"
    }
    val forbiddenAlgs = algorithmAllowlist.filter { alg ->
        alg.equals("none", ignoreCase = true) || alg.startsWith("HS")
    }
    if (forbiddenAlgs.isNotEmpty()) {
        errors += "algorithmAllowlist must not contain $forbiddenAlgs"
    }
    if (stdioTokenFile != null && !Files.isReadable(stdioTokenFile)) {
        errors += "stdioTokenFile not readable (path='$stdioTokenFile')"
    }

    return errors
}

private fun McpServerConfig.bindIsLoopback(): Boolean = runCatching {
    InetAddress.getByName(bindAddress).isLoopbackAddress
}.getOrElse { e ->
    if (e is UnknownHostException) false else throw e
}

private const val MAX_PORT = 65535

/**
 * §12.9 verbindliche Scope-Tabelle. Alle 0.9.6-Tools sind enthalten,
 * auch wenn Phase B die meisten nur als Registry-Eintrag (ohne
 * Handler) liefert — die Tabelle ist Vertrag fuer Protected Resource
 * Metadata (§4.4).
 */
private fun buildDefaultScopeMapping(): Map<String, Set<String>> {
    val read = setOf("dmigrate:read")
    val jobStart = setOf("dmigrate:job:start")
    val artifactUpload = setOf("dmigrate:artifact:upload")
    val dataWrite = setOf("dmigrate:data:write")
    val jobCancel = setOf("dmigrate:job:cancel")
    val aiExecute = setOf("dmigrate:ai:execute")
    val admin = setOf("dmigrate:admin")
    return mapOf(
        // MCP discovery
        "capabilities_list" to read,
        "tools/list" to read,
        "resources/list" to read,
        "resources/templates/list" to read,
        "resources/read" to read,
        // Read-only tools
        "schema_validate" to read,
        "schema_compare" to read,
        "schema_generate" to read,
        "schema_list" to read,
        "profile_list" to read,
        "diff_list" to read,
        "job_list" to read,
        "job_status_get" to read,
        "artifact_list" to read,
        "artifact_chunk_get" to read,
        // Job-start tools
        "schema_reverse_start" to jobStart,
        "schema_compare_start" to jobStart,
        "data_profile_start" to jobStart,
        "data_export_start" to jobStart,
        // Upload session
        "artifact_upload_init" to artifactUpload,
        "artifact_upload_chunk" to artifactUpload,
        "artifact_upload_complete" to artifactUpload,
        "artifact_upload_abort" to artifactUpload,
        // Data-write tools
        "data_import_start" to dataWrite,
        "data_transfer_start" to dataWrite,
        // Cancel
        "job_cancel" to jobCancel,
        // AI tools
        "procedure_transform_plan" to aiExecute,
        "procedure_transform_execute" to aiExecute,
        "testdata_plan" to aiExecute,
        "testdata_execute" to aiExecute,
        // Admin
        "connections/list" to admin,
    )
}
