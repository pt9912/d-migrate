package dev.dmigrate.server.core.upload

import java.time.Instant

/**
 * Phase F § 5.3 + § 8.6 (F.6 2/3) — durabel persistierter Outcome
 * eines administrativen / fremden `artifact_upload_abort`-Aufrufs.
 *
 * Wird vom [dev.dmigrate.server.ports.AbortOutcomeStore] indiziert
 * ueber den vom [dev.dmigrate.server.ports.SyncEffectIdempotencyStore]
 * vergebenen `resultRef`. Beim Replay vergleicht der Handler den
 * gespeicherten [abortFingerprint] gegen den frisch berechneten
 * Request-Fingerprint, sodass abweichende `reason`/Caller/Session-
 * Felder NICHT das alte Outcome zurueckliefern (Plan § 5.3:
 * "abweichende Request-Felder ... liefern IDEMPOTENCY_CONFLICT").
 *
 * @property abortFingerprint vollstaendiger Pre-Abort-Fingerprint
 *   (siehe [dev.dmigrate.server.application.upload.AbortApprovalFingerprint]).
 *   Bindung an Toolname, sessionId, Tenant, Owner, Caller,
 *   Pre-Abort-Status, artifactKind, uploadIntent, Pre-Abort-Bytes,
 *   optional `reason` — Plan § 5.3 wortlaeufig.
 * @property uploadSessionId redundant zur Diagnose; der eigentliche
 *   Identitaetsanker ist der [abortFingerprint].
 * @property preAbortState Session-Status zum Zeitpunkt der
 *   Abort-Berechnung (Plan: ACTIVE / FINALIZING / etc.). Idempotenz-
 *   Check vergleicht dies gegen den aktuellen Session-Status auf Replay.
 * @property terminalState Final-Status nach Abort (typischerweise
 *   `ABORTED`). `null` bevor der Abort durabel persistiert wurde —
 *   nur In-Progress-Records.
 * @property quotaReleased ob die Init-Quotas (Session-Slot + Bytes)
 *   beim Abort freigegeben wurden. Plan § 5.3: terminaler
 *   Erfolgs-`resultRef` wird erst committed, wenn ABORTED + Cleanup +
 *   Quota-Release durabel sind.
 * @property completedAt Wall-Clock-Zeitpunkt der durablen
 *   Outcome-Persistierung; diagnostisch.
 * @property reason optionaler Caller-supplied Grund (Plan § 5.3
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
