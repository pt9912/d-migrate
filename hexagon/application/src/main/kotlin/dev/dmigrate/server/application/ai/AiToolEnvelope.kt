package dev.dmigrate.server.application.ai

import dev.dmigrate.server.core.ai.AiToolScope
import dev.dmigrate.server.core.principal.PrincipalId
import dev.dmigrate.server.core.principal.TenantId
import java.time.Instant

/**
 * Phase G § 6 G.6 (G.6.c) — gemeinsame Eingabe für den
 * [AiToolOrchestrator].
 *
 * Bündelt das, was alle drei KI-Tool-Handler (G.6.d/e/f) gleich
 * brauchen, damit der Orchestrator die Plan-§-6-G.6-Pflichten
 * einheitlich erzwingt: Single-Writer-Acquire, Terminal-Outcome-
 * Replay, Outcome-Commit und Wire-Mapping.
 *
 * Tool-spezifische Felder (Source-Refs, Plan-Refs, Optionen) bleiben
 * im Tool-Handler — der Orchestrator sieht nur den
 * `payloadFingerprint`, der diese Felder bereits stabil
 * normalisiert hat.
 *
 * @param toolName MCP-Tool-Name, der das Audit/Outcome adressiert
 *   (etwa `"procedure_transform_plan"`).
 * @param tenantId / [callerId] Tenant + Principal aus dem
 *   `PrincipalContext`. Plan §6 G.6 Z. 1083: Dedup-Key.
 * @param approvalKey Plan §5 + § 6 G.6 — synchroner
 *   Idempotency-/Approval-Key, den der Caller mitbringt.
 * @param payloadFingerprint hex-codierter SHA-256 über die
 *   normalisierten Tool-Argumente. Plan §6 G.6 Z. 1016-1019:
 *   Control-Felder (`approvalToken`, `idempotencyKey`, `requestId`)
 *   sind hier bereits entfernt — der Tool-Handler hat sie vor dem
 *   Hashen abgeschnitten.
 * @param now `Clock.instant()`-Zeitpunkt zur Aufrufzeit. Wird in
 *   alle Outcomes als `committedAt` eingetragen.
 */
data class AiToolEnvelope(
    val toolName: String,
    val tenantId: TenantId,
    val callerId: PrincipalId,
    val approvalKey: String,
    val payloadFingerprint: String,
    val now: Instant,
) {
    init {
        require(toolName.isNotBlank()) { "toolName must not be blank" }
        require(approvalKey.isNotBlank()) { "approvalKey must not be blank" }
        require(payloadFingerprint.length == FP_LEN) {
            "payloadFingerprint must be a $FP_LEN-char hex SHA-256"
        }
    }

    fun scope(): AiToolScope = AiToolScope(
        tenantId = tenantId,
        callerId = callerId,
        toolName = toolName,
        approvalKey = approvalKey,
    )

    private companion object {
        const val FP_LEN: Int = 64
    }
}
