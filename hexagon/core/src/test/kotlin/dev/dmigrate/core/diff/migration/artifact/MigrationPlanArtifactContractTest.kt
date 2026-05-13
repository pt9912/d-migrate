package dev.dmigrate.core.diff.migration.artifact

import dev.dmigrate.core.util.sha256Hex
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe

class MigrationPlanArtifactContractTest : FunSpec({

    test("F.2 canonical plan artifact JSON is stable and artifactHash is outside the signed payload") {
        val artifact = unsignedArtifact()
        val expectedUnsigned = """
            {
              "formatVersion": "migration-plan.v1",
              "dMigrateVersion": "d-migrate-test",
              "sourceFingerprint": "src-fp",
              "targetFingerprint": "dst-fp",
              "dialect": "postgresql",
              "operations": [
                {
                  "id": "alter-users-age-type",
                  "kind": "AlterColumnType",
                  "objectType": "COLUMN",
                  "objectPath": [
                    "users",
                    "age"
                  ],
                  "phase": "COLUMNS",
                  "reversibility": "AUTOMATIC_WITH_DATA_RISK",
                  "upRisk": {
                    "destructive": false,
                    "dataLossPossible": true,
                    "requiresTableRewrite": false,
                    "requiresManualConfirmation": false,
                    "dataTransformationMode": "NONE"
                  },
                  "downRisk": {
                    "destructive": false,
                    "dataLossPossible": true,
                    "requiresTableRewrite": false,
                    "requiresManualConfirmation": false,
                    "dataTransformationMode": "NONE"
                  }
                }
              ],
              "diagnostics": [
                {
                  "code": "PG_USING_OVERLAY_APPLIED",
                  "severity": "INFO",
                  "operationId": "alter-users-age-type"
                }
              ],
              "reversibilitySummary": {
                "fullyReversible": true,
                "manualRequiredOperationIds": [],
                "notReversibleOperationIds": []
              },
              "requiredFeatures": [],
              "renderedStatements": [
                {
                  "statementId": "stmt-1",
                  "operationIds": [
                    "alter-users-age-type"
                  ],
                  "sqlHash": "sql-hash",
                  "transactionScope": "SINGLE_STATEMENT"
                }
              ],
              "createdAt": "2026-05-13T10:15:30Z"
            }
        """.trimIndent() + "\n"

        MigrationPlanArtifactCanonicalJson.encodeUnsigned(artifact) shouldBe expectedUnsigned
        MigrationPlanArtifactCanonicalJson.computeHash(artifact) shouldBe sha256Hex(expectedUnsigned)
        MigrationPlanArtifactCanonicalJson.encodeUnsigned(artifact.withComputedHash()) shouldBe expectedUnsigned
    }

    test("F.2 validation rejects unsigned stale-version feature and hash mismatch blockers") {
        val result = MigrationPlanArtifactValidator.validate(
            unsignedArtifact(
                formatVersion = "migration-plan.v2",
                requiredFeatures = setOf("requires-new-runner"),
            ).copy(artifactHash = "bad-hash"),
        )

        result.hasBlockers shouldBe true
        result.diagnostics.map { it.code }.shouldContain(MigrationPlanArtifactDiagnostics.UNKNOWN_FORMAT_VERSION)
        result.diagnostics.map { it.code }.shouldContain(MigrationPlanArtifactDiagnostics.HASH_MISMATCH)
        result.diagnostics.map { it.code }.shouldContain(MigrationPlanArtifactDiagnostics.UNKNOWN_REQUIRED_FEATURE)
    }

    test("F.2 decorative producer metadata is accepted but reserved semantic metadata blocks") {
        val decorative = unsignedArtifact(
            producerMetadata = mapOf("producer.note" to "human readable"),
        ).withComputedHash()
        val reserved = unsignedArtifact(
            producerMetadata = mapOf("execution.retry" to "true"),
        ).withComputedHash()

        MigrationPlanArtifactValidator.validate(decorative).hasBlockers shouldBe false
        MigrationPlanArtifactValidator.validate(reserved).diagnostics.map { it.code }
            .shouldContain(MigrationPlanArtifactDiagnostics.RESERVED_PRODUCER_METADATA)
    }

    test("F.2 secret-bearing producer metadata blocks compatibility") {
        val secret = "jdbc:postgresql://db.example/prod?password=prod-secret"
        val artifact = unsignedArtifact(
            producerMetadata = mapOf("producer.note" to secret),
        ).withComputedHash()

        MigrationPlanArtifactValidator.validate(artifact).diagnostics.map { it.code }
            .shouldContain(MigrationPlanArtifactDiagnostics.SECRET_BEARING_PRODUCER_METADATA)
    }

    test("F.2 unknown semantic extensions block compatibility") {
        val artifact = unsignedArtifact(semanticExtensions = setOf("plan-render-binding.v2")).withComputedHash()
        val result = MigrationPlanArtifactValidator.validate(artifact)

        result.diagnostics.map { it.code }
            .shouldContain(MigrationPlanArtifactDiagnostics.UNKNOWN_SEMANTIC_EXTENSION)
    }
})

private fun unsignedArtifact(
    formatVersion: String = MigrationPlanArtifact.FORMAT_VERSION,
    requiredFeatures: Set<String> = emptySet(),
    semanticExtensions: Set<String> = emptySet(),
    producerMetadata: Map<String, String> = emptyMap(),
): MigrationPlanArtifact =
    MigrationPlanArtifact(
        formatVersion = formatVersion,
        dMigrateVersion = "d-migrate-test",
        sourceFingerprint = "src-fp",
        targetFingerprint = "dst-fp",
        dialect = "postgresql",
        operations = listOf(
            MigrationPlanArtifactOperation(
                id = "alter-users-age-type",
                kind = "AlterColumnType",
                objectType = "COLUMN",
                objectPath = listOf("users", "age"),
                phase = "COLUMNS",
                reversibility = "AUTOMATIC_WITH_DATA_RISK",
                upRisk = MigrationPlanRisk(dataLossPossible = true),
                downRisk = MigrationPlanRisk(dataLossPossible = true),
            ),
        ),
        diagnostics = listOf(
            MigrationPlanArtifactDiagnostic(
                code = "PG_USING_OVERLAY_APPLIED",
                severity = "INFO",
                operationId = "alter-users-age-type",
            ),
        ),
        reversibilitySummary = MigrationPlanReversibilitySummary(fullyReversible = true),
        requiredFeatures = requiredFeatures,
        semanticExtensions = semanticExtensions,
        renderedStatements = listOf(
            MigrationPlanRenderedStatement(
                statementId = "stmt-1",
                operationIds = listOf("alter-users-age-type"),
                sqlHash = "sql-hash",
                transactionScope = "SINGLE_STATEMENT",
            ),
        ),
        createdAt = "2026-05-13T10:15:30Z",
        producerMetadata = producerMetadata,
    )
