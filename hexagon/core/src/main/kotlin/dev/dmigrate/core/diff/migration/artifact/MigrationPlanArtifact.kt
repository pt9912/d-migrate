package dev.dmigrate.core.diff.migration.artifact

/**
 * Public, versioned plan artifact contract.
 *
 * This is deliberately narrower than the internal DiffResult: it carries
 * stable identifiers, diagnostics, reversibility, and rendered-SQL bindings
 * without embedding arbitrary SQL or secret-bearing payloads.
 */
data class MigrationPlanArtifact(
    val formatVersion: String = FORMAT_VERSION,
    val dMigrateVersion: String,
    val sourceFingerprint: String,
    val targetFingerprint: String,
    val dialect: String,
    val operations: List<MigrationPlanArtifactOperation>,
    val diagnostics: List<MigrationPlanArtifactDiagnostic>,
    val reversibilitySummary: MigrationPlanReversibilitySummary,
    val requiredFeatures: Set<String> = emptySet(),
    val semanticExtensions: Set<String> = emptySet(),
    val renderedStatements: List<MigrationPlanRenderedStatement> = emptyList(),
    val createdAt: String,
    val artifactHash: String? = null,
    val producerMetadata: Map<String, String> = emptyMap(),
) {
    fun withComputedHash(): MigrationPlanArtifact =
        copy(artifactHash = MigrationPlanArtifactCanonicalJson.computeHash(this))

    companion object {
        const val FORMAT_VERSION: String = "migration-plan.v1"
    }
}

data class MigrationPlanArtifactOperation(
    val id: String,
    val kind: String,
    val objectType: String,
    val objectPath: List<String>,
    val phase: String,
    val reversibility: String,
    val upRisk: MigrationPlanRisk,
    val downRisk: MigrationPlanRisk? = null,
)

data class MigrationPlanRisk(
    val destructive: Boolean = false,
    val dataLossPossible: Boolean = false,
    val requiresTableRewrite: Boolean = false,
    val requiresManualConfirmation: Boolean = false,
    val dataTransformationMode: String = "NONE",
    val dataTransformationModelVersion: String? = null,
    val dataTransformationModelId: String? = null,
)

data class MigrationPlanArtifactDiagnostic(
    val code: String,
    val severity: String,
    val operationId: String? = null,
)

data class MigrationPlanReversibilitySummary(
    val fullyReversible: Boolean,
    val manualRequiredOperationIds: List<String> = emptyList(),
    val notReversibleOperationIds: List<String> = emptyList(),
)

data class MigrationPlanRenderedStatement(
    val statementId: String,
    val operationIds: List<String>,
    val sqlHash: String,
    val transactionScope: String,
)
