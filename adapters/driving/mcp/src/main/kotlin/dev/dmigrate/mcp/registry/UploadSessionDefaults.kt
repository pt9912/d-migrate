package dev.dmigrate.mcp.registry

import java.time.Duration

/**
 * Cross-handler constants for the read-only upload-session lifecycle.
 *
 * Consolidates the lease-window defaults and the SHA-256 hex pattern
 * that `ArtifactUploadInitHandler` (AP 6.7), `ArtifactUploadHandler`
 * (AP 6.8), and the upcoming `ArtifactUploadAbortHandler` (AP 6.10)
 * would otherwise each redefine. Spec/ki-mcp.md §5.3 lines 605-619
 * pin the canonical values: 900s initial TTL, 300s idle, 3600s
 * absolute lease.
 */
internal object UploadSessionDefaults {

    /** Initial TTL advertised at session init per spec line 609. */
    val INITIAL_TTL: Duration = Duration.ofSeconds(900)

    /** Idle window between successful segment writes per spec line 616-617. */
    val IDLE_TIMEOUT: Duration = Duration.ofSeconds(300)

    /** Absolute hard cap from session creation per spec line 610/614. */
    val ABSOLUTE_LEASE: Duration = Duration.ofSeconds(3600)

    /** Lower-case 64-char hex SHA-256 — canonical wire form across the codebase. */
    val SHA256_HEX_PATTERN: Regex = Regex("^[0-9a-f]{64}$")
}
