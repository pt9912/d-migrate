package dev.dmigrate.server.core.ai

import dev.dmigrate.server.core.principal.PrincipalId
import dev.dmigrate.server.core.principal.TenantId

/**
 * LF-017 / LF-024 / LN-030 / LN-031— Korrelations-Schlüssel für synchrone
 * KI-nahe Tool-Aufrufe.
 *
 * Identisch zu [dev.dmigrate.server.core.idempotency.SyncEffectScope]
 * in der Form, aber als eigener Typ benannt, weil der zugehörige
 * [AiToolOutcome]-Lebenszyklus (Pending/Succeeded/FailedTerminal/
 * FailedRetryable mit Lease/Reclaim) reicher ist als die binäre
 * Reserved/Existing-Form aus LF-010 / LF-013 / LN-009 / LN-011. LF-017 / LF-024 / LN-030 / LN-031:
 * Dedup-Key ist `(tenantId, callerId, toolName, approvalKey)` —
 * der `payloadFingerprint` wird beim `acquire` mitgegeben und im
 * Outcome persistiert.
 */
data class AiToolScope(
    val tenantId: TenantId,
    val callerId: PrincipalId,
    val toolName: String,
    val approvalKey: String,
) {
    init {
        require(toolName.isNotBlank()) { "toolName must not be blank" }
        require(approvalKey.isNotBlank()) { "approvalKey must not be blank" }
    }
}

/**
 * Opaker Single-Writer-Claim, ausgestellt vom
 * [dev.dmigrate.server.ports.AiToolOutcomeStore]. Pflicht beim
 * [dev.dmigrate.server.ports.AiToolOutcomeStore.commit] — verhindert,
 * dass ein Caller, dessen Lease abgelaufen und an einen anderen
 * Reclaimer gegangen ist, sein Outcome nachträglich hineinpresst.
 */
@JvmInline
value class AiToolClaimId(val value: String) {
    init {
        require(value.isNotBlank()) { "claimId must not be blank" }
    }
}
