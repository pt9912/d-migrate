package dev.dmigrate.core.cancel

/**
 * Canonical typed cancel carrier for Phase E0.
 *
 * Runners, invokers and adapters MUST surface cooperative cancellation as
 * this exception (or a value-result that is mapped to it at the runner
 * boundary). It must travel through every catch-all path before generic
 * error mapping; see implementation-plan-0.9.6 §4.5.
 *
 * The first cancel reason wins. [reason] is the same value observed via
 * [CancellationToken.cancellationReason] at the moment the exception was
 * thrown.
 *
 * Phase E §7.7 [source] field: classifies the cancel origin so the
 * [dev.dmigrate.server.application.job.JobDispatcher] can map
 * `JOB_CANCEL` to job-status `CANCELLED` and `RUNNER_TIMEOUT` to
 * `FAILED(error.code=OPERATION_TIMEOUT)`. Default is [OperationCancelSource.JOB_CANCEL]
 * for backward compatibility with Phase-E0 callers that pre-date the
 * source enum.
 */
class OperationCancelledException(
    val reason: String? = null,
    val source: OperationCancelSource = OperationCancelSource.JOB_CANCEL,
    cause: Throwable? = null,
) : RuntimeException(reason ?: "operation cancelled", cause) {

    /** 2-arg legacy ctor (reason + cause); preserved for callers from Phase E0. */
    constructor(reason: String?, cause: Throwable?) : this(reason, OperationCancelSource.JOB_CANCEL, cause)
}
