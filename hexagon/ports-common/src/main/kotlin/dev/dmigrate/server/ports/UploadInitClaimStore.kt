package dev.dmigrate.server.ports

import dev.dmigrate.server.core.principal.PrincipalId
import dev.dmigrate.server.core.principal.TenantId
import java.time.Instant

/**
 * Phase F § 5.1 + § 8.3 (F.3 2/4) — atomarer Single-Writer-Claim
 * fuer den policy-pflichtigen `artifact_upload_init`-Pfad.
 *
 * Zweck: zwischen `SyncEffectIdempotencyStore.reserve(...)` und der
 * durablen Erzeugung von [UploadSessionStore]-Eintrag,
 * Upload-Berechtigung und Quota-Reservierung wird ein dedizierter
 * Single-Writer-Claim gehalten. Identische
 * `(approvalKey, payloadFingerprint)`-Retries kollidieren am Claim und
 * liefern `InProgress` mit derselben aktiven Lease — nur der Claim-
 * Inhaber darf weiter materialisieren. Damit fuellt der Store die
 * Luecke aus Plan § 8.3, dass `SyncEffectIdempotencyStore.reserve`
 * gleiche pending-Reserves nicht als `AlreadyClaimed` unterscheidet.
 *
 * Lease-Vertrag:
 *
 * - `claimId` + `claimedAt` + `leaseExpiresAt` werden atomar gesetzt.
 * - Eine aktive Lease blockiert konkurrente Acquires mit gleichem
 *   `(scope, fingerprint)` als `InProgress`.
 * - Nach Lease-Ablauf darf reclaimed werden — der reclaim-Inhaber
 *   MUSS vor weiterer Materialisierung nach durablem Session-/
 *   Outcome-Record suchen und ggf. den SyncEffect-Commit replayen.
 * - Negative Clock-Jumps verlaengern keine bestehende Lease (siehe
 *   Plan-Wortlaut „Negative Clock-Jumps duerfen Leases nicht
 *   verlaengern").
 */
data class UploadInitClaimScope(
    val tenantId: TenantId,
    val callerId: PrincipalId,
    val toolName: String,
    val approvalKey: String,
)

data class UploadInitClaim(
    val scope: UploadInitClaimScope,
    val payloadFingerprint: String,
    val claimId: String,
    val claimedAt: Instant,
    val leaseExpiresAt: Instant,
)

sealed interface UploadInitClaimOutcome {

    /** Frischer Claim oder Reclaim — Caller darf weiter materialisieren. */
    data class Acquired(val claim: UploadInitClaim) : UploadInitClaimOutcome

    /**
     * Bestehende aktive Lease mit identischem Fingerprint. Caller MUSS
     * warten oder den durablen Outcome replayen; KEINE zweite Session
     * erzeugen.
     */
    data class InProgress(val current: UploadInitClaim) : UploadInitClaimOutcome

    /**
     * Lease des bestehenden Claims ist abgelaufen; der neue Claim wurde
     * uebernommen. Der reclaim-Inhaber MUSS vor weiterer Session-
     * Materialisierung pruefen, ob bereits ein durabler Outcome-Record
     * existiert (Plan § 8.3).
     */
    data class Reclaimed(
        val claim: UploadInitClaim,
        val previous: UploadInitClaim,
    ) : UploadInitClaimOutcome

    /**
     * Gleicher Scope, aber abweichender Payload-Fingerprint — der
     * Caller bekommt einen Replay-Hint. Egal ob die bestehende Lease
     * aktiv oder abgelaufen ist; abweichender Fingerprint im selben
     * SyncEffect-Scope ist ein Caller-Bug bzw. Crash-Window-Indikator.
     */
    data class Conflict(val existingFingerprint: String) : UploadInitClaimOutcome
}

/**
 * Implementoren MUESSEN die `UploadInitClaimStoreContractTests`-
 * Suite durchlaufen — atomare Single-Writer-Garantie unter parallelem
 * Acquire ist die zentrale Eigenschaft.
 */
interface UploadInitClaimStore {

    /**
     * Versucht einen neuen Single-Writer-Claim zu erwerben.
     * Atomicity-Vertrag siehe Klassen-KDoc.
     */
    fun acquire(
        scope: UploadInitClaimScope,
        payloadFingerprint: String,
        claimId: String,
        leaseExpiresAt: Instant,
        now: Instant,
    ): UploadInitClaimOutcome

    /**
     * Loescht den Claim nach erfolgreicher durabler Session/Outcome-
     * Materialisierung. Idempotent — `release` fuer einen Fremd-
     * `claimId` ist no-op (CAS-Verlierer).
     */
    fun release(scope: UploadInitClaimScope, claimId: String): Boolean

    fun findById(scope: UploadInitClaimScope): UploadInitClaim?
}
