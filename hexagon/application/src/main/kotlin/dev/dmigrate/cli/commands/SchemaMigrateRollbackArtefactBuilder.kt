package dev.dmigrate.cli.commands

import dev.dmigrate.core.diff.migration.DiffResult
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.migration.MigrationDdlResult

/**
 * Rollback-artefact construction split out of [SchemaMigrateRunner]
 * to keep the runner under Detekt's `LargeClass` budget. Two
 * surfaces:
 *
 * - [buildNormal] — the standard `--rollback-output` artefact emitted
 *   after a clean Up + post-compare. Carries the OBSERVED Post-Up
 *   fingerprint and `postUpVerified=true` (Plan §F.5.b) when supplied
 *   by the caller; falls back to the planned `desiredFp` /
 *   `postUpVerified=false` for the no-`--execute` paths.
 *
 * - [buildRecovery] — the marked recovery artefact (Plan §F.5.e/f)
 *   with `recovery=true` and a single-element
 *   `allowedPostUpFingerprints=[allowedFingerprint]`. Caller picks
 *   the FP per failure scenario:
 *
 *   - F.5.e Introspection-Fail: `allowedFingerprint=desiredFp`,
 *     `postUpVerified=false` (no observation possible).
 *   - F.5.f Write-Fail nach sauberem Compare:
 *     `allowedFingerprint=observedFp`, `postUpVerified=true`.
 *
 * Both surfaces share the same canonical risk-fold + base input
 * computation via [baseInput], so the metadata block format stays
 * identical between the two emission paths.
 */
internal object SchemaMigrateRollbackArtefactBuilder {

    fun buildNormal(
        plan: DiffResult,
        down: MigrationDdlResult,
        dialect: DatabaseDialect,
        postUpFingerprint: String,
        postUpVerified: Boolean,
        createdByVersion: String,
    ): String = RollbackArtefactBuilder.build(
        baseInput(plan, down, dialect, postUpFingerprint, postUpVerified, createdByVersion),
    )

    fun buildRecovery(
        plan: DiffResult,
        down: MigrationDdlResult,
        dialect: DatabaseDialect,
        allowedFingerprint: String,
        postUpVerified: Boolean,
        createdByVersion: String,
    ): String = RollbackArtefactBuilder.build(
        baseInput(plan, down, dialect, allowedFingerprint, postUpVerified, createdByVersion).copy(
            recovery = true,
            allowedPostUpFingerprints = listOf(allowedFingerprint),
        ),
    )

    private fun baseInput(
        plan: DiffResult,
        down: MigrationDdlResult,
        dialect: DatabaseDialect,
        postUpFingerprint: String,
        postUpVerified: Boolean,
        createdByVersion: String,
    ): RollbackArtefactBuilder.Input {
        val downRisk = down.statements.fold(
            RollbackArtefactBuilder.Risk(
                destructive = false,
                dataLossPossible = false,
                requiresManualConfirmation = false,
                operationIds = down.operationsRendered,
            ),
        ) { acc, s ->
            RollbackArtefactBuilder.Risk(
                destructive = acc.destructive || s.risk.destructive,
                dataLossPossible = acc.dataLossPossible || s.risk.dataLossPossible,
                requiresManualConfirmation = acc.requiresManualConfirmation || s.risk.requiresManualConfirmation,
                operationIds = acc.operationIds,
            )
        }
        return RollbackArtefactBuilder.Input(
            dialect = dialect,
            currentFingerprint = plan.current.fingerprint ?: "",
            desiredFingerprint = plan.desired.fingerprint ?: "",
            postUpFingerprint = postUpFingerprint,
            postUpVerified = postUpVerified,
            operationIds = down.operationsRendered,
            risk = downRisk,
            downStatements = down.statements,
            createdByVersion = createdByVersion,
        )
    }
}
