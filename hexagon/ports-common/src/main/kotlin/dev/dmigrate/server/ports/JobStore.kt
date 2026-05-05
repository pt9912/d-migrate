package dev.dmigrate.server.ports

import dev.dmigrate.server.core.job.JobRecord
import dev.dmigrate.server.core.job.JobStatus
import dev.dmigrate.server.core.job.ManagedJob
import dev.dmigrate.server.core.pagination.PageRequest
import dev.dmigrate.server.core.pagination.PageResult
import dev.dmigrate.server.core.principal.PrincipalId
import dev.dmigrate.server.core.principal.TenantId
import java.time.Instant

/**
 * Phase-D §6.3 + §10.4 filter for `job_list`. Every field is
 * optional; an empty filter selects every job in the tenant.
 *
 * Time window: `createdAfter` is INCLUSIVE, `createdBefore` is
 * INCLUSIVE on the upper bound — the §10.4 store-contract test
 * pins the boundary case.
 */
data class JobListFilter(
    val ownerFilter: PrincipalId? = null,
    val status: JobStatus? = null,
    val operation: String? = null,
    val createdAfter: Instant? = null,
    val createdBefore: Instant? = null,
)

interface JobStore {

    fun save(record: JobRecord): JobRecord

    fun findById(tenantId: TenantId, jobId: String): JobRecord?

    fun list(
        tenantId: TenantId,
        page: PageRequest,
        ownerFilter: PrincipalId? = null,
    ): PageResult<JobRecord>

    /**
     * Phase-D filtered list. Default sort:
     *   1. `managedJob.createdAt` DESC
     *   2. `managedJob.jobId` ASC (stable id tiebreaker)
     */
    fun list(
        tenantId: TenantId,
        filter: JobListFilter,
        page: PageRequest,
    ): PageResult<JobRecord>

    fun deleteExpired(now: Instant): Int

    /**
     * Phase E §7.2: Compare-and-set-Statusübergang. Liest den aktuellen
     * [JobRecord] atomar aus dem Store, prüft dass der heutige Status in
     * [allowedFromStatuses] enthalten ist, und schreibt den durch
     * [transformer] erzeugten neuen [ManagedJob]. [transformer] empfängt
     * den aktuellen [ManagedJob] und liefert die Ziel-Variante (typisch:
     * neuer Status, neue `updatedAt`, optional weitere Felder wie
     * `error` oder `progress`).
     *
     * Die Operation MUSS atomar sein — kein anderer Caller darf
     * zwischen Lesen und Schreiben einen abweichenden Status sehen.
     * Der Aufruf ist deshalb der einzige zulässige Pfad für nicht-blinde
     * Statuswechsel; reines `save(...)` ist für das Erstanlegen reserviert
     * und überschreibt nicht.
     *
     * Ergebnis-Outcomes (Plan §7.2):
     * - [JobTransitionOutcome.Applied] mit dem geschriebenen
     *   [JobRecord], wenn der Übergang stattgefunden hat.
     * - [JobTransitionOutcome.IllegalTransition] mit `currentStatus`,
     *   wenn der heutige Status nicht in [allowedFromStatuses] liegt
     *   (Race-Konflikt oder bereits-terminal).
     * - [JobTransitionOutcome.NotFound], wenn kein Job für
     *   `(tenantId, jobId)` existiert.
     */
    fun transitionStatus(
        tenantId: TenantId,
        jobId: String,
        allowedFromStatuses: Set<JobStatus>,
        transformer: (ManagedJob) -> ManagedJob,
    ): JobTransitionOutcome

    /**
     * Phase E §7.2: durable Cancel-Request-Markierung. Setzt
     * [ManagedJob.cancelRequest].`requested = true` mit den übergebenen
     * Metadaten, ohne den Status-Übergang nach `CANCELLED` selbst
     * auszulösen — dieser kommt erst nach Worker-Ack via
     * [transitionStatus].
     *
     * Idempotenz: wenn `cancelRequest.requested` bereits `true` ist,
     * MUSS der Store den ersten Reason und die ersten Request-Metadaten
     * UNVERÄNDERT lassen und [JobTransitionOutcome.Applied] mit dem
     * unveränderten Record liefern. Das ist Plan §7.2: "Retry nach
     * durablem `cancelRequested*`, aber vor Worker-Ack ... ohne Reason
     * oder Request-Metadaten zu überschreiben".
     *
     * Übergänge auf bereits terminale Jobs liefern
     * [JobTransitionOutcome.IllegalTransition].
     */
    fun markCancelRequested(
        tenantId: TenantId,
        jobId: String,
        requestedAt: Instant,
        requestedBy: String,
        signalSource: String,
        reason: String? = null,
    ): JobTransitionOutcome
}

/**
 * Phase E §7.2 Statusübergangs-Ergebnis. Pinned in
 * [JobStore.transitionStatus] und [JobStore.markCancelRequested].
 */
sealed interface JobTransitionOutcome {
    data class Applied(val record: JobRecord) : JobTransitionOutcome
    data class IllegalTransition(val currentStatus: JobStatus) : JobTransitionOutcome
    data object NotFound : JobTransitionOutcome
}
