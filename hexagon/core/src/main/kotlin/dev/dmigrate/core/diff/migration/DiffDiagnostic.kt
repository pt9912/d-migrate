package dev.dmigrate.core.diff.migration

/**
 * Diagnostic message attached to a [DiffOperation] or [DiffResult].
 *
 * Diagnostics carry planner / generator findings that are not
 * automatically blockers — they ride along with the operation in
 * reports and migration-SQL metadata blocks. A blocker is signalled
 * via the operation's [Reversibility] / [OperationRisk] flags plus
 * [DiffResult.diagnostics] entries with [severity] = [Severity.BLOCKER].
 */
data class DiffDiagnostic(
    val code: String,
    val message: String,
    val severity: Severity = Severity.INFO,
    /** Optional reference to the operation this diagnostic belongs to. */
    val operationId: String? = null,
) {
    enum class Severity {
        /** Informational note — surfaced in reports, no Exit-Code impact. */
        INFO,

        /** Operator should review — does not block by itself. */
        WARNING,

        /**
         * Migration cannot proceed under current configuration.
         * `schema migrate` surfaces this as Exit `8` with the diagnostic
         * code in the structured error.
         */
        BLOCKER,
    }
}
