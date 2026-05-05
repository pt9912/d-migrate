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
 */
class OperationCancelledException(
    val reason: String? = null,
    cause: Throwable? = null,
) : RuntimeException(reason ?: "operation cancelled", cause)
