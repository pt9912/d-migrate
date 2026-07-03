package dev.dmigrate.core.diff.migration.artifact

data class MigrationPlanArtifactValidationContext(
    val supportedFormatVersions: Set<String> = setOf(MigrationPlanArtifact.FORMAT_VERSION),
    val supportedRequiredFeatures: Set<String> = emptySet(),
    val supportedSemanticExtensions: Set<String> = emptySet(),
)

data class MigrationPlanArtifactValidationResult(
    val artifactHash: String,
    val diagnostics: List<MigrationPlanArtifactValidationDiagnostic>,
) {
    val hasBlockers: Boolean
        get() = diagnostics.any { it.severity == MigrationPlanArtifactValidationSeverity.BLOCKER }
}

data class MigrationPlanArtifactValidationDiagnostic(
    val code: String,
    val message: String,
    val severity: MigrationPlanArtifactValidationSeverity = MigrationPlanArtifactValidationSeverity.BLOCKER,
)

enum class MigrationPlanArtifactValidationSeverity {
    INFO,
    WARNING,
    BLOCKER,
}

object MigrationPlanArtifactDiagnostics {
    const val REQUIRED_FIELD_MISSING: String = "PLAN_ARTIFACT_REQUIRED_FIELD_MISSING"
    const val UNKNOWN_FORMAT_VERSION: String = "PLAN_ARTIFACT_UNKNOWN_FORMAT_VERSION"
    const val HASH_MISSING: String = "PLAN_ARTIFACT_HASH_MISSING"
    const val HASH_MISMATCH: String = "PLAN_ARTIFACT_HASH_MISMATCH"
    const val UNKNOWN_REQUIRED_FEATURE: String = "PLAN_ARTIFACT_UNKNOWN_REQUIRED_FEATURE"
    const val UNKNOWN_SEMANTIC_EXTENSION: String = "PLAN_ARTIFACT_UNKNOWN_SEMANTIC_EXTENSION"
    const val RESERVED_PRODUCER_METADATA: String = "PLAN_ARTIFACT_RESERVED_PRODUCER_METADATA"
    const val SECRET_BEARING_PRODUCER_METADATA: String = "PLAN_ARTIFACT_SECRET_BEARING_PRODUCER_METADATA"
    const val REVERSIBILITY_SUMMARY_MISMATCH: String = "PLAN_ARTIFACT_REVERSIBILITY_SUMMARY_MISMATCH"
    const val UNKNOWN_REVERSIBILITY_OPERATION: String = "PLAN_ARTIFACT_UNKNOWN_REVERSIBILITY_OPERATION"

    /**
     * F.4 Sub-Slice E: a non-empty
     * [MigrationPlanArtifact.renameProjections] payload requires the
     * producer to advertise
     * [MigrationPlanArtifactFeatures.RENAME_PROJECTIONS_V1] in
     * [MigrationPlanArtifact.semanticExtensions]. Without the gate, an
     * old consumer would silently execute the Drop+Create fallback as
     * an ordinary destructive change instead of treating it as a
     * logical rename. This diagnostic surfaces a producer bug — the
     * standard fix is to call
     * [MigrationPlanArtifact.withRenameProjectionExtension] before
     * signing.
     */
    const val RENAME_PROJECTIONS_REQUIRE_EXTENSION: String =
        "PLAN_ARTIFACT_RENAME_PROJECTIONS_REQUIRE_EXTENSION"
}

object MigrationPlanArtifactValidator {

    fun validate(
        artifact: MigrationPlanArtifact,
        context: MigrationPlanArtifactValidationContext = MigrationPlanArtifactValidationContext(),
    ): MigrationPlanArtifactValidationResult {
        val actualHash = MigrationPlanArtifactCanonicalJson.computeHash(artifact)
        val reportHash = artifact.artifactHash ?: actualHash
        val diagnostics = mutableListOf<MigrationPlanArtifactValidationDiagnostic>()

        fun block(code: String, message: String) {
            diagnostics += MigrationPlanArtifactValidationDiagnostic(code = code, message = message)
        }

        requireNonBlank("formatVersion", artifact.formatVersion, ::block)
        requireNonBlank("dMigrateVersion", artifact.dMigrateVersion, ::block)
        requireNonBlank("sourceFingerprint", artifact.sourceFingerprint, ::block)
        requireNonBlank("targetFingerprint", artifact.targetFingerprint, ::block)
        requireNonBlank("fingerprintAlgorithm", artifact.fingerprintAlgorithm, ::block)
        requireNonBlank("dialect", artifact.dialect, ::block)
        requireNonBlank("createdAt", artifact.createdAt, ::block)

        if (artifact.formatVersion.isNotBlank() && artifact.formatVersion !in context.supportedFormatVersions) {
            block(
                MigrationPlanArtifactDiagnostics.UNKNOWN_FORMAT_VERSION,
                "Unsupported plan artifact formatVersion '${artifact.formatVersion}'",
            )
        }

        if (artifact.artifactHash.isNullOrBlank()) {
            block(MigrationPlanArtifactDiagnostics.HASH_MISSING, "artifactHash is required")
        } else if (artifact.artifactHash != actualHash) {
            block(
                MigrationPlanArtifactDiagnostics.HASH_MISMATCH,
                "artifactHash does not match canonical plan artifact content",
            )
        }

        val unknownFeature = (artifact.requiredFeatures - context.supportedRequiredFeatures).sorted().firstOrNull()
        if (unknownFeature != null) {
            block(
                MigrationPlanArtifactDiagnostics.UNKNOWN_REQUIRED_FEATURE,
                "Unsupported required feature '$unknownFeature'",
            )
        }

        val unknownExtension = (artifact.semanticExtensions - context.supportedSemanticExtensions).sorted().firstOrNull()
        if (unknownExtension != null) {
            block(
                MigrationPlanArtifactDiagnostics.UNKNOWN_SEMANTIC_EXTENSION,
                "Unsupported semantic extension '$unknownExtension'",
            )
        }

        if (artifact.renameProjections.isNotEmpty() &&
            MigrationPlanArtifactFeatures.RENAME_PROJECTIONS_V1 !in artifact.semanticExtensions
        ) {
            block(
                MigrationPlanArtifactDiagnostics.RENAME_PROJECTIONS_REQUIRE_EXTENSION,
                "renameProjections is non-empty but '${MigrationPlanArtifactFeatures.RENAME_PROJECTIONS_V1}' " +
                    "is missing from semanticExtensions — old consumers would execute the Drop+Create " +
                    "fallback as an ordinary destructive change.",
            )
        }

        val reserved = artifact.producerMetadata.keys.sorted().firstOrNull { it.isReservedSemanticField() }
        if (reserved != null) {
            block(
                MigrationPlanArtifactDiagnostics.RESERVED_PRODUCER_METADATA,
                "Producer metadata field '$reserved' requires a versioned contract",
            )
        }

        val secretBearing = artifact.producerMetadata.entries.sortedBy { it.key }.firstOrNull {
            it.key.containsSecretMarker() || it.value.containsSecretMarker()
        }
        if (secretBearing != null) {
            block(
                MigrationPlanArtifactDiagnostics.SECRET_BEARING_PRODUCER_METADATA,
                "Producer metadata field '${secretBearing.key}' may contain secret-bearing material",
            )
        }

        validateReversibilitySummary(artifact, ::block)

        return MigrationPlanArtifactValidationResult(
            artifactHash = reportHash,
            diagnostics = diagnostics,
        )
    }

    private fun validateReversibilitySummary(
        artifact: MigrationPlanArtifact,
        block: (String, String) -> Unit,
    ) {
        val operationIds = artifact.operations.map { it.id }.toSet()
        validateSummaryOperationIds(artifact.reversibilitySummary, operationIds, block)

        val manualRequired = artifact.operations.filter { it.reversibility == REVERSIBILITY_MANUAL_REQUIRED }
        val notReversible = artifact.operations.filter { it.reversibility == REVERSIBILITY_NOT_REVERSIBLE }
        if (artifact.reversibilitySummary.fullyReversible && (manualRequired.isNotEmpty() || notReversible.isNotEmpty())) {
            block(
                MigrationPlanArtifactDiagnostics.REVERSIBILITY_SUMMARY_MISMATCH,
                "fullyReversible cannot be true while operations require manual or impossible rollback",
            )
        }

        validateSummaryCoversOperations(
            expectedOperationIds = manualRequired.map { it.id }.toSet(),
            reportedOperationIds = artifact.reversibilitySummary.manualRequiredOperationIds.toSet(),
            reversibility = REVERSIBILITY_MANUAL_REQUIRED,
            block = block,
        )
        validateSummaryCoversOperations(
            expectedOperationIds = notReversible.map { it.id }.toSet(),
            reportedOperationIds = artifact.reversibilitySummary.notReversibleOperationIds.toSet(),
            reversibility = REVERSIBILITY_NOT_REVERSIBLE,
            block = block,
        )

        val summaryListsIncompleteRollback = artifact.reversibilitySummary.manualRequiredOperationIds.isNotEmpty() ||
            artifact.reversibilitySummary.notReversibleOperationIds.isNotEmpty()
        if (artifact.reversibilitySummary.fullyReversible && summaryListsIncompleteRollback) {
            block(
                MigrationPlanArtifactDiagnostics.REVERSIBILITY_SUMMARY_MISMATCH,
                "fullyReversible cannot be true while reversibilitySummary lists incomplete rollback operations",
            )
        }
    }

    private fun validateSummaryOperationIds(
        summary: MigrationPlanReversibilitySummary,
        operationIds: Set<String>,
        block: (String, String) -> Unit,
    ) {
        val staleOperationId = (summary.manualRequiredOperationIds + summary.notReversibleOperationIds)
            .sorted()
            .firstOrNull { it !in operationIds }
        if (staleOperationId != null) {
            block(
                MigrationPlanArtifactDiagnostics.UNKNOWN_REVERSIBILITY_OPERATION,
                "reversibilitySummary references unknown operation '$staleOperationId'",
            )
        }
    }

    private fun validateSummaryCoversOperations(
        expectedOperationIds: Set<String>,
        reportedOperationIds: Set<String>,
        reversibility: String,
        block: (String, String) -> Unit,
    ) {
        val missingOperationId = (expectedOperationIds - reportedOperationIds).sorted().firstOrNull()
        if (missingOperationId != null) {
            block(
                MigrationPlanArtifactDiagnostics.REVERSIBILITY_SUMMARY_MISMATCH,
                "reversibilitySummary omits $reversibility operation '$missingOperationId'",
            )
        }
        val unexpectedOperationId = (reportedOperationIds - expectedOperationIds).sorted().firstOrNull()
        if (unexpectedOperationId != null) {
            block(
                MigrationPlanArtifactDiagnostics.REVERSIBILITY_SUMMARY_MISMATCH,
                "reversibilitySummary lists operation '$unexpectedOperationId' as $reversibility",
            )
        }
    }

    private fun requireNonBlank(
        fieldName: String,
        value: String,
        block: (String, String) -> Unit,
    ) {
        if (value.isBlank()) {
            block(MigrationPlanArtifactDiagnostics.REQUIRED_FIELD_MISSING, "$fieldName is required")
        }
    }

    private fun String.isReservedSemanticField(): Boolean =
        startsWith("execution.") ||
            startsWith("risk.") ||
            startsWith("rollback.") ||
            startsWith("locking.") ||
            startsWith("preflights.") ||
            startsWith("secrets.") ||
            startsWith("sql.")

    private fun String.containsSecretMarker(): Boolean {
        val normalized = lowercase()
        val compact = normalized.filter(Char::isLetterOrDigit)
        return secretMarkers.any { marker ->
            normalized.contains(marker) || compact.contains(marker.filter(Char::isLetterOrDigit))
        }
    }

    private val secretMarkers: Set<String> = setOf(
        "connection string",
        "connectionstring",
        "credential",
        "jdbc:",
        "mysql://",
        "passwd",
        "password",
        "postgres://",
        "postgresql://",
        "pwd",
        "secret",
        "token",
    )

    private const val REVERSIBILITY_MANUAL_REQUIRED: String = "MANUAL_REQUIRED"
    private const val REVERSIBILITY_NOT_REVERSIBLE: String = "NOT_REVERSIBLE"
}
