package dev.dmigrate.server.application.ai

import dev.dmigrate.server.core.ai.AiToolAcquireOutcome
import dev.dmigrate.server.core.ai.AiToolOutcome
import dev.dmigrate.server.core.error.ToolErrorCode
import dev.dmigrate.server.ports.AiToolOutcomeStore
import java.time.Duration

/**
 * Phase G § 6 G.6 (G.6.c) — zentraler Single-Writer-Wrapper für
 * synchrone KI-nahe Tool-Aufrufe.
 *
 * Pflicht-Verträge aus Plan §6 G.6 (Z. 1080-1091), die hier
 * eingelöst werden:
 *
 * - bestehende `AiToolOutcomeStore`-Outcomes (Succeeded /
 *   FailedTerminal) werden replayt **bevor** Policy, Provider-
 *   Konfig, Quota oder Secret-Auflösung erneut laufen
 *   (`work.perform` wird nicht aufgerufen);
 * - parallele identische Caller bekommen `OPERATION_TIMEOUT`
 *   (`InProgress`) statt eines zweiten Provider-Aufrufs;
 * - abweichender Fingerprint im selben `approvalKey`-Scope →
 *   `IDEMPOTENCY_CONFLICT`;
 * - jede unbehandelte Exception aus [AiToolWork.perform] wird
 *   in `FailedTerminal(INTERNAL_AGENT_ERROR)` umgewandelt, damit
 *   ein Bug nicht zu einem dauerhaft offenen Pending-Claim
 *   führt;
 * - Crash zwischen Provider-Aufruf und Outcome-Commit (Lease
 *   abgelaufen, Reclaimer hat neue ClaimId) wird über
 *   [AiToolOutcomeStore.commit] `false`-Pfad behandelt — der
 *   Caller bekommt sein Wire-Ergebnis trotzdem (idempotenter
 *   Replay läuft beim nächsten Acquire).
 *
 * Was der Orchestrator NICHT macht (das bleibt bei den Tool-
 * Handlern in G.6.d/e/f):
 *
 * - Policy-Decision, Quota-Reservation, Hygiene-Prüfung,
 *   Provider-Aufruf, Output-Hygiene, Artefakt-Publish — alles
 *   in [AiToolWork.perform];
 * - Resource-/Plan-Provenance-Lookups;
 * - Wire-spezifische Tool-Output-Form (`summary`, `findings`,
 *   `planRef` vs `targetArtifactId` etc.) — der Handler
 *   übersetzt [AiToolDispatchOutcome] in seinen Tool-Envelope.
 *
 * @param outcomeStore durable Outcome-Store mit
 *   Single-Writer-Lease (G.6.a).
 * @param leaseDuration TTL für die Pending-Lease. Wird beim
 *   Acquire mitgegeben; Tests pinnen einen kleinen Wert, um
 *   Reclaim-Pfade zu treffen.
 */
class AiToolOrchestrator(
    private val outcomeStore: AiToolOutcomeStore,
    private val leaseDuration: Duration = Duration.ofSeconds(60),
) {

    /**
     * Akquiriert den Single-Writer-Claim für [envelope] und
     * läuft je nach Outcome:
     *
     * | Acquire-Outcome | Aktion |
     * |---|---|
     * | `Existing(Succeeded)` | Replay als [AiToolDispatchOutcome.WireSuccess] mit `replayed=true`. |
     * | `Existing(FailedTerminal)` | Replay als [AiToolDispatchOutcome.WireFailure] mit `replayed=true`. |
     * | `InProgress` | [AiToolDispatchOutcome.WireFailure] mit `OPERATION_TIMEOUT`, `retryable=true`. |
     * | `Conflict` | [AiToolDispatchOutcome.WireFailure] mit `IDEMPOTENCY_CONFLICT`. |
     * | `Acquired` | [work].perform(claim) ausführen, Ergebnis committen, Wire mappen. |
     */
    fun dispatch(envelope: AiToolEnvelope, work: AiToolWork): AiToolDispatchOutcome {
        val scope = envelope.scope()
        val acquire = outcomeStore.acquire(
            scope = scope,
            payloadFingerprint = envelope.payloadFingerprint,
            leaseDuration = leaseDuration,
            now = envelope.now,
        )
        return when (acquire) {
            is AiToolAcquireOutcome.Existing -> projectExisting(acquire.outcome)
            is AiToolAcquireOutcome.InProgress ->
                AiToolDispatchOutcome.WireFailure(
                    toolErrorCode = ToolErrorCode.OPERATION_TIMEOUT,
                    scrubbedMessage = "another caller is currently processing this approval-key",
                    replayed = false,
                    retryable = true,
                )
            is AiToolAcquireOutcome.Conflict ->
                AiToolDispatchOutcome.WireFailure(
                    toolErrorCode = ToolErrorCode.IDEMPOTENCY_CONFLICT,
                    scrubbedMessage = "approval-key already used with a different payload",
                    replayed = false,
                    retryable = false,
                )
            is AiToolAcquireOutcome.Acquired -> runWorkAndCommit(envelope, acquire, work)
        }
    }

    private fun runWorkAndCommit(
        envelope: AiToolEnvelope,
        claim: AiToolAcquireOutcome.Acquired,
        work: AiToolWork,
    ): AiToolDispatchOutcome {
        val workResult = try {
            work.perform(claim)
        } catch (e: Throwable) {
            AiToolWorkResult.FailedTerminal(
                toolErrorCode = ToolErrorCode.INTERNAL_AGENT_ERROR,
                scrubbedMessage = "tool work threw ${e.javaClass.simpleName}",
            )
        }
        commitWorkResult(envelope, claim, workResult)
        return projectWorkResult(workResult)
    }

    private fun commitWorkResult(
        envelope: AiToolEnvelope,
        claim: AiToolAcquireOutcome.Acquired,
        result: AiToolWorkResult,
    ) {
        val outcome: AiToolOutcome = when (result) {
            is AiToolWorkResult.Succeeded -> AiToolOutcome.Succeeded(
                scope = envelope.scope(),
                payloadFingerprint = envelope.payloadFingerprint,
                resultRef = result.resultRef,
                outputFingerprint = result.outputFingerprint,
                providerName = result.providerName,
                model = result.model,
                providerRequestId = result.providerRequestId,
                promptFingerprint = result.promptFingerprint,
                modelVersion = result.modelVersion,
                committedAt = envelope.now,
            )
            is AiToolWorkResult.FailedTerminal -> AiToolOutcome.FailedTerminal(
                scope = envelope.scope(),
                payloadFingerprint = envelope.payloadFingerprint,
                toolErrorCode = result.toolErrorCode,
                scrubbedMessage = result.scrubbedMessage,
                details = result.details,
                providerName = result.providerName,
                model = result.model,
                modelVersion = result.modelVersion,
                providerRequestId = result.providerRequestId,
                promptFingerprint = result.promptFingerprint,
                committedAt = envelope.now,
            )
            is AiToolWorkResult.FailedRetryable -> AiToolOutcome.FailedRetryable(
                scope = envelope.scope(),
                payloadFingerprint = envelope.payloadFingerprint,
                toolErrorCode = result.toolErrorCode,
                scrubbedMessage = result.scrubbedMessage,
                attemptCount = claim.attemptCount,
                lastAttemptAt = envelope.now,
                details = result.details,
                providerName = result.providerName,
                model = result.model,
                modelVersion = result.modelVersion,
                providerRequestId = result.providerRequestId,
                promptFingerprint = result.promptFingerprint,
                approvalRequestId = result.approvalRequestId,
                correlationKind = result.correlationKind,
                correlationKey = result.correlationKey,
                requiredScopes = result.requiredScopes,
                reasons = result.reasons,
            )
        }
        // Plan §6 G.6: ein `commit==false` (Lease wurde an einen
        // anderen Reclaimer abgegeben) ist KEIN Fehler im
        // Wire-Pfad — der Caller bekommt sein Ergebnis weiter,
        // und der nächste Acquire findet die durable Version
        // vom Reclaimer. Wir loggen es hier nicht eigens, weil
        // der AuditScope bereits den Tool-Outcome aufzeichnet.
        outcomeStore.commit(envelope.scope(), claim.claimId, outcome, envelope.now)
    }

    private fun projectExisting(outcome: AiToolOutcome): AiToolDispatchOutcome = when (outcome) {
        is AiToolOutcome.Succeeded -> AiToolDispatchOutcome.WireSuccess(
            resultRef = outcome.resultRef,
            outputFingerprint = outcome.outputFingerprint,
            providerName = outcome.providerName,
            model = outcome.model,
            providerRequestId = outcome.providerRequestId,
            promptFingerprint = outcome.promptFingerprint,
            modelVersion = outcome.modelVersion,
            replayed = true,
        )
        is AiToolOutcome.FailedTerminal -> AiToolDispatchOutcome.WireFailure(
            toolErrorCode = outcome.toolErrorCode,
            scrubbedMessage = outcome.scrubbedMessage,
            replayed = true,
            retryable = false,
            details = outcome.details,
        )
        // Existing erlaubt nur terminale Outcomes — aber kotlinc
        // sieht das nicht durchgängig. Defensiv handhaben.
        else -> error("AiToolAcquireOutcome.Existing must carry Succeeded or FailedTerminal")
    }

    private fun projectWorkResult(result: AiToolWorkResult): AiToolDispatchOutcome = when (result) {
        is AiToolWorkResult.Succeeded -> AiToolDispatchOutcome.WireSuccess(
            resultRef = result.resultRef,
            outputFingerprint = result.outputFingerprint,
            providerName = result.providerName,
            model = result.model,
            providerRequestId = result.providerRequestId,
            promptFingerprint = result.promptFingerprint,
            payloadFingerprint = result.payloadFingerprint,
            modelVersion = result.modelVersion,
            replayed = false,
        )
        is AiToolWorkResult.FailedTerminal -> AiToolDispatchOutcome.WireFailure(
            toolErrorCode = result.toolErrorCode,
            scrubbedMessage = result.scrubbedMessage,
            replayed = false,
            retryable = false,
            details = result.details,
        )
        is AiToolWorkResult.FailedRetryable -> AiToolDispatchOutcome.WireFailure(
            toolErrorCode = result.toolErrorCode,
            scrubbedMessage = result.scrubbedMessage,
            replayed = false,
            retryable = true,
            details = result.details,
        )
    }
}
