package dev.dmigrate.server.application.policy

import dev.dmigrate.server.core.approval.ApprovalCorrelationKind
import dev.dmigrate.server.core.principal.PrincipalId
import dev.dmigrate.server.core.principal.TenantId

/**
 * LF-012 / LN-011 / LN-017 / LN-027 *
 * Buendelt die Felder, die der Policy-Service zur Entscheidung braucht.
 * Resource-/Connection-Refs und Sensitivitaets-Flags werden ueber
 * [resourceRefs] und [sensitivityFlags] geliefert; konkrete Auspraegungen
 * pflegt der Caller (Tool-Handler) aus LF-012 / LN-011 / LN-017 / LN-027.
 *
 * LF-012 / LN-011 / LN-017 / LN-027-Felder, die NICHT hier wandern, weil sie ausserhalb der
 * Entscheidung leben:
 * - `idempotencyKey` ist als [correlationKey] (mit kind=IDEMPOTENCY_KEY)
 *   modelliert; ein eigenes Feld waere redundant.
 * - Audit-Kontext bleibt im Tool-Handler; der Service erhaelt nur das
 *   entscheidungsrelevante Subset.
 */
data class PolicyAttempt(
    val tenantId: TenantId,
    val callerId: PrincipalId,
    val toolName: String,
    val correlationKind: ApprovalCorrelationKind,
    val correlationKey: String,
    val payloadFingerprint: String,
    val resourceRefs: List<String> = emptyList(),
    val sensitivityFlags: Set<String> = emptySet(),
)
