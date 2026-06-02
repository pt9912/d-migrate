package dev.dmigrate.cli.commands

import dev.dmigrate.core.diff.migration.DiffResult
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.migration.MigrationDdlResult

/**
 * Decides whether and how a rollback artefact is finalised for a
 * `schema migrate` run. Bundles the rollback-artefact text builder, the
 * recovery-artefact text builder (Plan §F.5.e/f), and the recovery
 * context handed off to `finalize` so the artefact-write paths can
 * emit a marked recovery artefact for the F.5.h failure branch.
 *
 * Extracted from [SchemaMigrateRunner] so the runner stays under
 * Detekt's `LargeClass` budget; no instance state beyond the
 * `createdByVersion` string that pins the artefact metadata block.
 */
internal class SchemaMigrateRollbackComposer(
    private val createdByVersion: String,
) {

    /**
     * Returns the rollback artefact text if [request] asked for it AND
     * Down rendered cleanly AND the execute path didn't leave the
     * target in an unknown state. F.5.b: when post-compare confirmed
     * a clean target, the OBSERVED fingerprint is pinned and
     * `postUpVerified=true`; otherwise the planned desired FP is used
     * and `postUpVerified=false`.
     */
    fun maybeBuildRollback(
        request: SchemaMigrateRequest,
        combined: MigrationDdlResult,
        renderedDown: MigrationDdlResult?,
        executionTrace: ExecutionTrace?,
        postCompareOutcome: PostCompareOutcome?,
        plan: DiffResult,
        dialect: DatabaseDialect,
    ): String? {
        if (!request.generateRollback || renderedDown == null) return null
        if (combined.isBlocked) return null
        // Execute is "ok" when either there was no execute attempt, or the
        // execute completed without error AND the post-compare was a
        // [PostCompareOutcome.Clean] (or absent). Drift / IntrospectionFailed
        // outcomes block the normal rollback artefact per Plan §F.5.g.
        val executeOk = executionTrace == null ||
            (executionTrace.executionError == null &&
                (postCompareOutcome == null || postCompareOutcome is PostCompareOutcome.Clean))
        if (!executeOk) return null
        val (postUpFp, postUpVerified) = when (postCompareOutcome) {
            is PostCompareOutcome.Clean -> postCompareOutcome.observedFingerprint to true
            else -> (plan.desired.fingerprint ?: "") to false
        }
        return buildRollbackArtefact(plan, renderedDown, dialect, postUpFp, postUpVerified)
    }

    /**
     * F.5.e/f recovery-context factory: returns a closure the artefact
     * sink can call when the finalize stage needs to emit a marked
     * recovery artefact. Returns `null` when no recovery branch can
     * fire (rollback wasn't requested, Down was blocked, or the
     * combined result is blocked).
     */
    fun buildRecoveryContextIfApplicable(
        request: SchemaMigrateRequest,
        combined: MigrationDdlResult,
        renderedDown: MigrationDdlResult?,
        plan: DiffResult,
        dialect: DatabaseDialect,
    ): RecoveryContext? = if (
        request.generateRollback &&
        renderedDown != null &&
        !combined.isBlocked
    ) {
        RecoveryContext(
            build = { fp, verified -> buildRecoveryArtefact(plan, renderedDown, dialect, fp, verified) },
            desiredFingerprint = plan.desired.fingerprint ?: "",
        )
    } else {
        null
    }

    private fun buildRollbackArtefact(
        plan: DiffResult,
        down: MigrationDdlResult,
        dialect: DatabaseDialect,
        postUpFingerprint: String,
        postUpVerified: Boolean,
    ): String = SchemaMigrateRollbackArtefactBuilder.buildNormal(
        plan, down, dialect, postUpFingerprint, postUpVerified, createdByVersion,
    )

    private fun buildRecoveryArtefact(
        plan: DiffResult,
        down: MigrationDdlResult,
        dialect: DatabaseDialect,
        allowedFingerprint: String,
        postUpVerified: Boolean,
    ): String = SchemaMigrateRollbackArtefactBuilder.buildRecovery(
        plan, down, dialect, allowedFingerprint, postUpVerified, createdByVersion,
    )
}
