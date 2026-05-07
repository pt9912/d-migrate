package dev.dmigrate.server.core.ai

import dev.dmigrate.server.core.error.ToolErrorCode
import java.time.Instant

/**
 * Phase G § 5.1 + § 6 G.6 — durabler Lebenszyklus eines synchronen
 * KI-Tool-Aufrufs.
 *
 * Plan-§-6-G.6-Vertrag (Z. 1080-1087):
 *
 * - [Pending] — Single-Writer-Claim hält die Lease; parallele
 *   gleiche Caller bekommen `InProgress`-Outcome. Crashes werden
 *   per `reclaimExpired` aufgeräumt → Pending wird zu
 *   [FailedRetryable] mit `OPERATION_TIMEOUT`.
 * - [Succeeded] — Provider-Aufruf + Output-Hygiene + Artefakt-
 *   Publish ist abgeschlossen. Replay liefert deterministisch
 *   dasselbe `resultRef` ohne neuen Provider-Aufruf.
 * - [FailedTerminal] — Tool-Pfad ist endgültig fehlgeschlagen
 *   (Output-Hygiene-Block, BAD_REQUEST, OUTPUT_TOO_LARGE,
 *   INTERNAL). Replay liefert denselben strukturierten Fehler;
 *   ein neuer Versuch würde dasselbe Ergebnis erzeugen.
 * - [FailedRetryable] — temporärer Fehler (TIMEOUT, RATE_LIMITED,
 *   PROVIDER_UNAVAILABLE, expired Lease). Replay erlaubt einen
 *   neuen Versuch (Counter wird inkrementiert), ohne den vorigen
 *   Fehler zu vergessen.
 *
 * `payloadFingerprint` wird beim ersten `acquire` mitgegeben und
 * über alle Statusübergänge stabil gehalten — er ist die einzige
 * Achse, an der ein Caller mit abweichendem Payload erkannt und
 * mit `Conflict` abgewiesen wird.
 */
sealed interface AiToolOutcome {

    val scope: AiToolScope
    val payloadFingerprint: String

    data class Pending(
        override val scope: AiToolScope,
        override val payloadFingerprint: String,
        val claimId: AiToolClaimId,
        val leaseExpiresAt: Instant,
        val attemptCount: Int,
    ) : AiToolOutcome {
        init {
            require(attemptCount >= 1) { "attemptCount must be >= 1" }
        }
    }

    data class Succeeded(
        override val scope: AiToolScope,
        override val payloadFingerprint: String,
        val resultRef: String,
        val outputFingerprint: String,
        val providerName: String,
        val model: String,
        val providerRequestId: String?,
        val committedAt: Instant,
    ) : AiToolOutcome {
        init {
            require(resultRef.isNotBlank()) { "resultRef must not be blank" }
            require(outputFingerprint.length == FINGERPRINT_HEX_LENGTH) {
                "outputFingerprint must be a $FINGERPRINT_HEX_LENGTH-char hex SHA-256"
            }
            require(providerName.isNotBlank()) { "providerName must not be blank" }
            require(model.isNotBlank()) { "model must not be blank" }
            require(providerRequestId?.isNotBlank() != false) {
                "providerRequestId must be non-blank or null"
            }
        }
    }

    data class FailedTerminal(
        override val scope: AiToolScope,
        override val payloadFingerprint: String,
        val toolErrorCode: ToolErrorCode,
        val scrubbedMessage: String,
        val committedAt: Instant,
    ) : AiToolOutcome {
        init {
            require(scrubbedMessage.isNotBlank()) { "scrubbedMessage must not be blank" }
        }
    }

    data class FailedRetryable(
        override val scope: AiToolScope,
        override val payloadFingerprint: String,
        val toolErrorCode: ToolErrorCode,
        val scrubbedMessage: String,
        val attemptCount: Int,
        val lastAttemptAt: Instant,
    ) : AiToolOutcome {
        init {
            require(scrubbedMessage.isNotBlank()) { "scrubbedMessage must not be blank" }
            require(attemptCount >= 1) { "attemptCount must be >= 1" }
        }
    }

    private companion object {
        const val FINGERPRINT_HEX_LENGTH: Int = 64
    }
}

/**
 * Phase G § 6 G.6 — Ergebnis eines
 * [dev.dmigrate.server.ports.AiToolOutcomeStore.acquire]-Aufrufs.
 *
 * Plan-Mapping in den Tool-Handlern (G.6.d/e/f):
 *
 * | Outcome | Caller-Aktion |
 * |---|---|
 * | [Acquired] | Pipeline weiter (Policy → Hygiene → Provider → Commit). |
 * | [Existing] (Succeeded) | Direkt aus dem Outcome antworten — kein Provider-Aufruf. |
 * | [Existing] (FailedTerminal) | Strukturierter Fehler aus dem Outcome — kein Provider-Aufruf. |
 * | [InProgress] | `OPERATION_TIMEOUT` mit `retryAfter`-Hint, oder Caller wartet/replayt. |
 * | [Conflict] | `IDEMPOTENCY_CONFLICT` an den Wire-Caller. |
 */
sealed interface AiToolAcquireOutcome {

    val scope: AiToolScope

    data class Acquired(
        override val scope: AiToolScope,
        val claimId: AiToolClaimId,
        val leaseExpiresAt: Instant,
        val attemptCount: Int,
    ) : AiToolAcquireOutcome {
        init {
            require(attemptCount >= 1) { "attemptCount must be >= 1" }
        }
    }

    data class Existing(
        override val scope: AiToolScope,
        val outcome: AiToolOutcome,
    ) : AiToolAcquireOutcome {
        init {
            require(
                outcome is AiToolOutcome.Succeeded || outcome is AiToolOutcome.FailedTerminal,
            ) {
                "Existing outcome must be Succeeded or FailedTerminal, was ${outcome::class.simpleName}"
            }
            require(outcome.scope == scope) { "outcome scope must match" }
        }
    }

    data class InProgress(
        override val scope: AiToolScope,
        val leaseExpiresAt: Instant,
    ) : AiToolAcquireOutcome

    data class Conflict(
        override val scope: AiToolScope,
        val existingFingerprint: String,
    ) : AiToolAcquireOutcome {
        init {
            require(existingFingerprint.isNotBlank()) {
                "existingFingerprint must not be blank"
            }
        }
    }
}
