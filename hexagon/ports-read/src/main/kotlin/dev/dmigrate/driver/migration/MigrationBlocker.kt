package dev.dmigrate.driver.migration

import dev.dmigrate.core.diff.migration.DiffDiagnostic

/**
 * Reason a [MigrationDdlResult] cannot be safely executed without
 * additional caller intervention. A single result may carry several
 * blockers — destructive ops, non-reversible ops, and a dialect-
 * unsupported op can all coexist.
 *
 * `MIGRATION_ERROR` is *not* in this enum: execution-time failures
 * are reported via `MigrationDdlResult.executionError` and friends,
 * never as a planning blocker (Plan §6.1).
 */
enum class MigrationBlockedReason {
    /** Up-plan contains destructive operations and `--allow-destructive` was not set. */
    DESTRUCTIVE_OPERATION_REQUIRES_CONFIRMATION,

    /** Down-plan / Rollback path encountered a `NOT_REVERSIBLE` operation. */
    ROLLBACK_NOT_POSSIBLE,

    /** One or more operations require manual ops work before/after the rendered SQL. */
    MANUAL_ACTION_REQUIRED,

    /** `schema rollback` would run against an unexpected target fingerprint. */
    TARGET_STATE_MISMATCH,

    /** `schema rollback` would run against the wrong dialect / connection. */
    TARGET_DIALECT_MISMATCH,

    /** The chosen dialect cannot render one of the operations in the plan. */
    DIALECT_UNSUPPORTED_OPERATION,
}

/**
 * One concrete blocker against executing this plan. The
 * [diagnostics] carry the structured findings the renderer
 * accumulated; CLI surfaces them under Exit-Code 8
 * `MIGRATION_BLOCKED`.
 */
data class MigrationBlocker(
    val reason: MigrationBlockedReason,
    val operationIds: Set<String> = emptySet(),
    val diagnostics: List<DiffDiagnostic> = emptyList(),
)
