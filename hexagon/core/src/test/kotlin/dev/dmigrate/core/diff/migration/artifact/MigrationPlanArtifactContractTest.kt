package dev.dmigrate.core.diff.migration.artifact

import dev.dmigrate.core.util.sha256Hex
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

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
                  "transactionScope": "RUNNER_OWNED"
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

    // ── F.4 Sub-Slice E: renameProjections artifact contract ───────

    test("F.4 E: empty renameProjections does not appear in canonical JSON and hash is stable") {
        val artifact = unsignedArtifact()
        // Encoding does NOT contain the field at all when empty.
        MigrationPlanArtifactCanonicalJson.encodeUnsigned(artifact).contains("renameProjections") shouldBe false
        // withRenameProjectionExtension is a no-op when the list is empty.
        artifact.withRenameProjectionExtension() shouldBe artifact
    }

    test("F.4 E: renameProjections encode into canonical JSON between renderedStatements and createdAt") {
        val artifact = unsignedArtifact(
            renameProjections = listOf(sampleNativeProjection(), sampleFallbackProjection()),
        ).withRenameProjectionExtension()
        val encoded = MigrationPlanArtifactCanonicalJson.encodeUnsigned(artifact)
        encoded shouldContain "\"renameProjections\":"
        encoded shouldContain "\"candidateId\": \"cand-rename-users\""
        encoded shouldContain "\"renameOperationId\": \"rename-users\""
        encoded shouldContain "\"candidateId\": \"cand-fallback-audit\""
        encoded shouldContain "\"fallbackOperationIds\":"
        encoded shouldContain "\"fallbackReason\": \"MySQL has no `ALTER TRIGGER ... RENAME`\""
        // Field order: renderedStatements precedes renameProjections precedes createdAt.
        val renderedStatementsIdx = encoded.indexOf("\"renderedStatements\":")
        val renameProjectionsIdx = encoded.indexOf("\"renameProjections\":")
        val createdAtIdx = encoded.indexOf("\"createdAt\":")
        (renderedStatementsIdx in 0..<renameProjectionsIdx && renameProjectionsIdx < createdAtIdx) shouldBe true
        // semanticExtensions auto-includes the gate after withRenameProjectionExtension.
        encoded shouldContain "\"rename-projections.v1\""
    }

    test("F.4 E: validator blocks renameProjections without the rename-projections.v1 extension") {
        // Producer forgot to call withRenameProjectionExtension().
        val artifact = unsignedArtifact(
            renameProjections = listOf(sampleNativeProjection()),
        ).withComputedHash()
        val result = MigrationPlanArtifactValidator.validate(artifact)
        result.diagnostics.map { it.code } shouldContain
            MigrationPlanArtifactDiagnostics.RENAME_PROJECTIONS_REQUIRE_EXTENSION
        result.hasBlockers shouldBe true
    }

    test("F.4 E: consumer that supports the extension accepts artifacts with renameProjections") {
        val artifact = unsignedArtifact(
            renameProjections = listOf(sampleNativeProjection()),
        ).withRenameProjectionExtension().withComputedHash()
        val result = MigrationPlanArtifactValidator.validate(
            artifact,
            context = MigrationPlanArtifactValidationContext(
                supportedSemanticExtensions = setOf(MigrationPlanArtifactFeatures.RENAME_PROJECTIONS_V1),
            ),
        )
        result.hasBlockers shouldBe false
    }

    test("F.4 E: consumer without the extension support rejects artifacts with renameProjections") {
        val artifact = unsignedArtifact(
            renameProjections = listOf(sampleNativeProjection()),
        ).withRenameProjectionExtension().withComputedHash()
        val result = MigrationPlanArtifactValidator.validate(artifact)
        // Default context's supportedSemanticExtensions is empty —
        // the extension flag is unknown so the artifact is rejected.
        result.diagnostics.map { it.code } shouldContain
            MigrationPlanArtifactDiagnostics.UNKNOWN_SEMANTIC_EXTENSION
    }

    test("F.4 E: withRenameProjectionExtension is idempotent") {
        val once = unsignedArtifact(
            renameProjections = listOf(sampleNativeProjection()),
        ).withRenameProjectionExtension()
        val twice = once.withRenameProjectionExtension()
        twice shouldBe once
    }
})

private fun sampleNativeProjection() = MigrationPlanArtifactRenameProjection(
    candidateId = "cand-rename-users",
    objectType = "TABLE",
    fromPath = listOf("users_old"),
    toPath = listOf("users"),
    overlaySource = "ovl/rename.json",
    overlayEntryId = "users_old-to-users",
    overlayHash = "0123456789abcdef",
    renameOperationId = "rename-users",
)

private fun sampleFallbackProjection() = MigrationPlanArtifactRenameProjection(
    candidateId = "cand-fallback-audit",
    objectType = "TRIGGER",
    fromPath = listOf("orders", "audit_old"),
    toPath = listOf("orders", "audit_new"),
    overlaySource = "ovl/rename.json",
    overlayEntryId = "audit_old-to-audit_new",
    overlayHash = "fedcba9876543210",
    renameOperationId = null,
    fallbackOperationIds = listOf("drop-trigger-audit_old", "create-trigger-audit_new"),
    fallbackReason = "MySQL has no `ALTER TRIGGER ... RENAME`",
)

private fun unsignedArtifact(
    formatVersion: String = MigrationPlanArtifact.FORMAT_VERSION,
    requiredFeatures: Set<String> = emptySet(),
    semanticExtensions: Set<String> = emptySet(),
    producerMetadata: Map<String, String> = emptyMap(),
    renameProjections: List<MigrationPlanArtifactRenameProjection> = emptyList(),
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
                transactionScope = "RUNNER_OWNED",
            ),
        ),
        renameProjections = renameProjections,
        createdAt = "2026-05-13T10:15:30Z",
        producerMetadata = producerMetadata,
    )
