package dev.dmigrate.core.diff.migration.artifact

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe

class MigrationPlanArtifactReversibilityContractTest : FunSpec({

    test("F.2 product contract accepts matching manual rollback summary") {
        val artifact = reversibilityArtifact(
            operation = reversibilityOperation(reversibility = "MANUAL_REQUIRED"),
            summary = MigrationPlanReversibilitySummary(
                fullyReversible = false,
                manualRequiredOperationIds = listOf("alter-users-age-type"),
            ),
        ).withComputedHash()

        MigrationPlanArtifactValidator.validate(artifact).hasBlockers shouldBe false
    }

    test("F.2 product contract rejects manual rollback marked as fully reversible") {
        val artifact = reversibilityArtifact(
            operation = reversibilityOperation(reversibility = "MANUAL_REQUIRED"),
            summary = MigrationPlanReversibilitySummary(fullyReversible = true),
        ).withComputedHash()
        val result = MigrationPlanArtifactValidator.validate(artifact)

        result.hasBlockers shouldBe true
        result.diagnostics.map { it.code }
            .shouldContain(MigrationPlanArtifactDiagnostics.REVERSIBILITY_SUMMARY_MISMATCH)
    }

    test("F.2 product contract rejects stale reversibility operation ids") {
        val artifact = reversibilityArtifact(
            summary = MigrationPlanReversibilitySummary(
                fullyReversible = false,
                manualRequiredOperationIds = listOf("missing-operation"),
            ),
        ).withComputedHash()
        val result = MigrationPlanArtifactValidator.validate(artifact)

        result.hasBlockers shouldBe true
        result.diagnostics.map { it.code }
            .shouldContain(MigrationPlanArtifactDiagnostics.UNKNOWN_REVERSIBILITY_OPERATION)
    }

    test("F.2 product contract rejects omitted not-reversible operations") {
        val artifact = reversibilityArtifact(
            operation = reversibilityOperation(reversibility = "NOT_REVERSIBLE"),
            summary = MigrationPlanReversibilitySummary(fullyReversible = false),
        ).withComputedHash()
        val result = MigrationPlanArtifactValidator.validate(artifact)

        result.hasBlockers shouldBe true
        result.diagnostics.map { it.code }
            .shouldContain(MigrationPlanArtifactDiagnostics.REVERSIBILITY_SUMMARY_MISMATCH)
    }

    test("F.2 product contract rejects operations listed in the wrong reversibility bucket") {
        val artifact = reversibilityArtifact(
            summary = MigrationPlanReversibilitySummary(
                fullyReversible = false,
                manualRequiredOperationIds = listOf("alter-users-age-type"),
            ),
        ).withComputedHash()
        val result = MigrationPlanArtifactValidator.validate(artifact)

        result.hasBlockers shouldBe true
        result.diagnostics.map { it.code }
            .shouldContain(MigrationPlanArtifactDiagnostics.REVERSIBILITY_SUMMARY_MISMATCH)
    }
})

private fun reversibilityArtifact(
    operation: MigrationPlanArtifactOperation = reversibilityOperation(),
    summary: MigrationPlanReversibilitySummary = MigrationPlanReversibilitySummary(fullyReversible = true),
): MigrationPlanArtifact =
    MigrationPlanArtifact(
        dMigrateVersion = "d-migrate-test",
        sourceFingerprint = "src-fp",
        targetFingerprint = "dst-fp",
        fingerprintAlgorithm = "schema-fingerprint-v9",
        dialect = "postgresql",
        operations = listOf(operation),
        diagnostics = emptyList(),
        reversibilitySummary = summary,
        createdAt = "2026-05-13T10:15:30Z",
    )

private fun reversibilityOperation(
    reversibility: String = "AUTOMATIC_WITH_DATA_RISK",
): MigrationPlanArtifactOperation =
    MigrationPlanArtifactOperation(
        id = "alter-users-age-type",
        kind = "AlterColumnType",
        objectType = "COLUMN",
        objectPath = listOf("users", "age"),
        phase = "COLUMNS",
        reversibility = reversibility,
        upRisk = MigrationPlanRisk(dataLossPossible = true),
        downRisk = MigrationPlanRisk(dataLossPossible = true),
    )
