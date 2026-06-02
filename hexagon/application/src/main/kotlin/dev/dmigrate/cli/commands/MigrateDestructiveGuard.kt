package dev.dmigrate.cli.commands

import dev.dmigrate.driver.migration.MigrationBlocker
import dev.dmigrate.driver.migration.MigrationBlockedReason
import dev.dmigrate.driver.migration.MigrationDdlResult

/**
 * `--allow-destructive` enforcement, lifted out of
 * [SchemaMigrateRunner] to keep that class under Detekt's
 * `LargeClass` budget. Pure transformation:
 * `(rendered, allowDestructive) -> MigrationDdlResult` with a
 * `DESTRUCTIVE_OPERATION_REQUIRES_CONFIRMATION` blocker added when
 * the plan contains destructive operations and the user hasn't
 * opted in. Idempotent: if the rendered result already carries the
 * blocker (or has no destructive ops), the input is returned
 * unchanged.
 */
internal object MigrateDestructiveGuard {

    fun apply(rendered: MigrationDdlResult, allowDestructive: Boolean): MigrationDdlResult {
        if (allowDestructive || rendered.destructiveOperations.isEmpty()) return rendered
        if (rendered.blockers.any { it.reason == MigrationBlockedReason.DESTRUCTIVE_OPERATION_REQUIRES_CONFIRMATION }) {
            return rendered
        }
        val withGuard = rendered.copy(
            blockers = rendered.blockers + MigrationBlocker(
                reason = MigrationBlockedReason.DESTRUCTIVE_OPERATION_REQUIRES_CONFIRMATION,
                operationIds = rendered.destructiveOperations,
            ),
        )
        return withGuard.copy(primaryBlockedReason = MigrationBlockedReason.DESTRUCTIVE_OPERATION_REQUIRES_CONFIRMATION)
    }
}
