package dev.dmigrate.server.persistence.jdbc.job

import dev.dmigrate.server.core.job.JobRecord
import dev.dmigrate.server.core.job.JobStatus
import dev.dmigrate.server.core.job.ManagedJob
import dev.dmigrate.server.core.pagination.PageRequest
import dev.dmigrate.server.core.pagination.PageResult
import dev.dmigrate.server.ports.JobTransitionOutcome
import java.time.Instant

/**
 * Offset-Pagination ueber eine bereits sortierte Liste.
 *
 * Token sind string-kodierte Offsets. Ein unlesbares oder negatives Token
 * beginnt bei 0, statt zu scheitern: Tokens kommen von aussen, und ein
 * kaputtes darf keine Ausnahme durch den Port tragen.
 */
internal fun <T> paginate(items: List<T>, page: PageRequest): PageResult<T> {
    val pageSize = page.pageSize.coerceAtLeast(1)
    val offset = page.pageToken?.toIntOrNull()?.coerceAtLeast(0) ?: 0
    val effectiveOffset = offset.coerceAtMost(items.size)
    val end = (effectiveOffset + pageSize).coerceAtMost(items.size)
    return PageResult(
        items = items.subList(effectiveOffset, end),
        nextPageToken = if (end < items.size) end.toString() else null,
    )
}

/**
 * Was auf eine gesperrte Job-Zeile hin zu tun ist — beschrieben, nicht getan.
 */
internal sealed interface JobTransitionDecision {

    /** Das Ergebnis steht fest, es ist nichts zu schreiben. */
    data class Complete(val outcome: JobTransitionOutcome) : JobTransitionDecision

    /** Der geaenderte Stand ist zu schreiben; danach gilt er als angewendet. */
    data class Write(val record: JobRecord) : JobTransitionDecision
}

/**
 * Entscheidet ueber einen Statusuebergang.
 *
 * [transformer] ist die Regel des Aufrufers und wird nur aufgerufen, wenn der
 * Ausgangszustand erlaubt ist — sonst saehe sie Zustaende, aus denen sie nie
 * haette rechnen sollen.
 */
internal fun decideTransition(
    locked: JobRecord?,
    allowedFromStatuses: Set<JobStatus>,
    transformer: (ManagedJob) -> ManagedJob,
): JobTransitionDecision {
    val record = locked
        ?: return JobTransitionDecision.Complete(JobTransitionOutcome.NotFound)

    if (record.managedJob.status !in allowedFromStatuses) {
        return JobTransitionDecision.Complete(
            JobTransitionOutcome.IllegalTransition(record.managedJob.status),
        )
    }
    return JobTransitionDecision.Write(
        record.copy(managedJob = transformer(record.managedJob)),
    )
}

/**
 * Entscheidet ueber eine Abbruch-Anforderung.
 *
 * Idempotent: ist bereits ein Abbruch angefordert, bleibt der **erste** Grund
 * samt Quelle stehen und es wird nicht erneut geschrieben. Ein terminaler Job
 * laesst sich nicht mehr abbrechen.
 */
internal fun decideCancelRequest(
    locked: JobRecord?,
    requestedAt: Instant,
    requestedBy: String,
    signalSource: String,
    reason: String?,
): JobTransitionDecision {
    val record = locked
        ?: return JobTransitionDecision.Complete(JobTransitionOutcome.NotFound)

    if (record.managedJob.status.terminal) {
        return JobTransitionDecision.Complete(
            JobTransitionOutcome.IllegalTransition(record.managedJob.status),
        )
    }
    if (record.managedJob.cancelRequest.requested) {
        return JobTransitionDecision.Complete(JobTransitionOutcome.Applied(record))
    }
    val updatedCancel = record.managedJob.cancelRequest.copy(
        requested = true,
        requestedAt = requestedAt,
        requestedBy = requestedBy,
        requestedReason = reason,
        signalSource = signalSource,
    )
    return JobTransitionDecision.Write(
        record.copy(
            managedJob = record.managedJob.copy(
                updatedAt = requestedAt,
                cancelRequest = updatedCancel,
            ),
        ),
    )
}
