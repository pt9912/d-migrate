package dev.dmigrate.server.core.upload

import java.time.Instant

/**
 * LF-010 / LF-013 / LN-009 / LN-011— durabel persistierter Outcome
 * eines administrativen / fremden `artifact_upload_abort`-Aufrufs.
 *
 * Wird vom [dev.dmigrate.server.ports.AbortOutcomeStore] indiziert
 * ueber den vom [dev.dmigrate.server.ports.SyncEffectIdempotencyStore]
 * vergebenen `resultRef`. Beim Replay vergleicht der Handler den
 * gespeicherten [abortFingerprint] gegen den frisch berechneten
 * Request-Fingerprint, sodass abweichende `reason`/Caller/Session-
 * Felder NICHT das alte Outcome zurueckliefern (LF-010 / LF-013 / LN-009 / LN-011:
 * "abweichende Request-Felder ... liefern IDEMPOTENCY_CONFLICT").
 *
 * @property abortFingerprint vollstaendiger Pre-Abort-Fingerprint
 *   (siehe [dev.dmigrate.server.application.upload.AbortApprovalFingerprint]).
 *   Bindung an Toolname, sessionId, Tenant, Owner, Caller,
 *   Pre-Abort-Status, artifactKind, uploadIntent, Pre-Abort-Bytes,
 *   optional `reason` — LF-010 / LF-013 / LN-009 / LN-011 wortlaeufig.
 * @property uploadSessionId redundant zur Diagnose; der eigentliche
 *   Identitaetsanker ist der [abortFingerprint].
 * @property preAbortState Session-Status zum Zeitpunkt der
 *   Abort-Berechnung (Plan: ACTIVE / FINALIZING / etc.). Idempotenz-
 *   Check vergleicht dies gegen den aktuellen Session-Status auf Replay.
 * @property terminalState Final-Status nach Abort (typischerweise
 *   `ABORTED`). `null` bevor der Abort durabel persistiert wurde —
 *   nur In-Progress-Records.
 * @property quotaReleased ob die Init-Quotas (Session-Slot + Bytes)
 *   beim Abort freigegeben wurden. LF-010 / LF-013 / LN-009 / LN-011: terminaler
 *   Erfolgs-`resultRef` wird erst committed, wenn ABORTED + Cleanup +
 *   Quota-Release durabel sind.
 * @property completedAt Wall-Clock-Zeitpunkt der durablen
 *   Outcome-Persistierung; diagnostisch.
 * @property reason optionaler Caller-supplied Grund (LF-010 / LF-013 / LN-009 / LN-011
 *   `reason`-Eingabe).
 */
data class AbortOutcome(
    val abortFingerprint: String,
    val uploadSessionId: String,
    val preAbortState: UploadSessionState,
    val terminalState: UploadSessionState?,
    val quotaReleased: Boolean,
    val completedAt: Instant,
    val reason: String? = null,
)
