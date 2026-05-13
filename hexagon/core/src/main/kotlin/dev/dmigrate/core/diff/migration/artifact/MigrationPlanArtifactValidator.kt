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

        return MigrationPlanArtifactValidationResult(
            artifactHash = reportHash,
            diagnostics = diagnostics,
        )
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
}
