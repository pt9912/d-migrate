package dev.dmigrate.server.core.idempotency

import dev.dmigrate.server.core.principal.PrincipalId
import dev.dmigrate.server.core.principal.TenantId

@JvmInline
value class IdempotencyKey(val value: String)

/**
 * Phase E §5.2 Idempotency-Zustandsautomat.
 *
 * - `PENDING`: Reservierung angelegt, Job noch nicht committed.
 * - `AWAITING_APPROVAL`: Policy-Challenge offen.
 * - `COMMITTED`: Job wurde erzeugt und ist deduplizierbar.
 * - `DENIED`: Freigabe explizit abgelehnt.
 * - `FAILED`: endgültige, nicht-retrybare Reservierung ohne Job
 *   (z.B. Resource-/Tenant-/Validation-Fehler nach Ref-Lookup oder
 *   andere endgültige Materialisierungsfehler). Identische Retries
 *   liefern deterministisch dasselbe gespeicherte Fehler-Outcome
 *   (Plan §5.2). FAILED ist final für denselben Scope/Fingerprint und
 *   darf NICHT für abgelaufene `PENDING`-Leases oder
 *   `AWAITING_APPROVAL`-Challenges verwendet werden — diese erlauben
 *   eine Recovery-Reservierung.
 */
enum class IdempotencyState { PENDING, AWAITING_APPROVAL, COMMITTED, DENIED, FAILED }

data class IdempotencyScope(
    val tenantId: TenantId,
    val callerId: PrincipalId,
    val toolName: String,
    val idempotencyKey: IdempotencyKey,
)

data class SyncEffectScope(
    val tenantId: TenantId,
    val callerId: PrincipalId,
    val toolName: String,
    val approvalKey: String,
)

data class InitResumeScope(
    val tenantId: TenantId,
    val callerId: PrincipalId,
    val toolName: String,
    val clientRequestId: String,
)
