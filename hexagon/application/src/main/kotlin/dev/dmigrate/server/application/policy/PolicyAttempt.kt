package dev.dmigrate.server.application.policy

import dev.dmigrate.server.core.approval.ApprovalCorrelationKind
import dev.dmigrate.server.core.principal.PrincipalId
import dev.dmigrate.server.core.principal.TenantId

/**
 * Phase E §5.4 input DTO fuer [PolicyService.decide].
 *
 * Buendelt die Felder, die der Policy-Service zur Entscheidung braucht.
 * Resource-/Connection-Refs und Sensitivitaets-Flags werden ueber
 * [resourceRefs] und [sensitivityFlags] geliefert; konkrete Auspraegungen
 * pflegt der Caller (Tool-Handler) aus AP E.6.
 *
 * Plan §5.4-Felder, die NICHT hier wandern, weil sie ausserhalb der
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
