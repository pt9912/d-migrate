package dev.dmigrate.cli.commands

import dev.dmigrate.core.diff.migration.DiffPhase
import dev.dmigrate.core.diff.migration.OperationRisk
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.migration.MigrationDdlStatement

/**
 * Shared builders for `schema rollback` runner specs. Extracted from
 * [SchemaRollbackRunnerTest] so sibling specs (e.g. the fingerprint-algorithm
 * verification spec) reuse them without pushing any single spec past Detekt's
 * `LargeClass` threshold.
 */
private fun stmt(sql: String) = MigrationDdlStatement(
    sql = sql,
    operationIds = setOf("op-1"),
    risk = OperationRisk.SAFE,
    phase = DiffPhase.TABLES,
)

internal fun buildArtefact(
    dialect: DatabaseDialect = DatabaseDialect.POSTGRESQL,
    currentFp: String = "fp-current",
    desiredFp: String = "fp-desired",
    postUpFp: String = "fp-desired",
    sql: String = "DROP TABLE x;",
    destructive: Boolean = false,
    recovery: Boolean = false,
    postUpVerified: Boolean = false,
    allowedPostUpFingerprints: List<String>? = null,
    legacyV1: Boolean = false,
    fingerprintAlgorithm: String = RollbackArtefactBuilder.FINGERPRINT_ALGORITHM,
): String {
    val input = RollbackArtefactBuilder.Input(
        dialect = dialect,
        currentFingerprint = currentFp,
        desiredFingerprint = desiredFp,
        postUpFingerprint = postUpFp,
        fingerprintAlgorithm = fingerprintAlgorithm,
        operationIds = setOf("op-1"),
        risk = RollbackArtefactBuilder.Risk(
            destructive = destructive,
            dataLossPossible = false,
            requiresManualConfirmation = false,
            operationIds = setOf("op-1"),
        ),
        downStatements = listOf(stmt(sql)),
        createdByVersion = "test/0.0.0",
        recovery = recovery,
        postUpVerified = postUpVerified,
        allowedPostUpFingerprints = allowedPostUpFingerprints,
    )
    return if (legacyV1) RollbackArtefactBuilder.buildLegacyV1(input) else RollbackArtefactBuilder.build(input)
}
