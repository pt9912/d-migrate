package dev.dmigrate.server.application.fingerprint

import dev.dmigrate.server.core.principal.PrincipalId
import dev.dmigrate.server.core.principal.TenantId

/**
 * Identity binding for [PayloadFingerprintService]. The service folds
 * [tenantId], [callerId], [toolName], the [FingerprintScope] and any
 * scope-specific [extras] into a reserved top-level `_bind` object so
 * that identical fact payloads under different tenants/callers produce
 * different fingerprints.
 *
 * For `UPLOAD_INIT` callers populate [extras] with `artifactKind`,
 * `mimeType`, `sizeBytes`, `checksumSha256`, `uploadIntent` per the
 * plan's per-scope `_bind` schema (§14.6).
 */
data class BindContext(
    val tenantId: TenantId,
    val callerId: PrincipalId,
    val toolName: String,
    val extras: Map<String, JsonValue> = emptyMap(),
)
