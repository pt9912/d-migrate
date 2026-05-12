package dev.dmigrate.core.diff.migration.overlay

data class MigrationOverlayValidationContext(
    val expectedSourceFingerprint: String,
    val expectedTargetFingerprint: String,
    val expectedDialect: String,
    val supportedFormatVersions: Set<String> = setOf(MigrationOverlay.FORMAT_VERSION),
    val supportedOverlayKinds: Set<String> = setOf(
        MigrationOverlayKinds.USING_EXPRESSION,
        MigrationOverlayKinds.RENAME_MAPPING,
    ),
    val supportedRequiredFeatures: Set<String> = emptySet(),
)

data class MigrationOverlayValidationResult(
    val source: String,
    val overlayHash: String,
    val diagnostics: List<MigrationOverlayDiagnostic>,
) {
    val hasBlockers: Boolean
        get() = diagnostics.any { it.severity == MigrationOverlayDiagnostic.Severity.BLOCKER }
}

data class MigrationOverlayDiagnostic(
    val code: String,
    val message: String,
    val severity: Severity = Severity.BLOCKER,
    val entryId: String? = null,
    val overlayHash: String,
) {
    enum class Severity {
        INFO,
        WARNING,
        BLOCKER,
    }
}

object MigrationOverlayDiagnostics {
    const val STALE_SOURCE_FINGERPRINT: String = "OVERLAY_STALE_SOURCE_FINGERPRINT"
    const val STALE_TARGET_FINGERPRINT: String = "OVERLAY_STALE_TARGET_FINGERPRINT"
    const val DIALECT_MISMATCH: String = "OVERLAY_DIALECT_MISMATCH"
    const val UNKNOWN_FORMAT_VERSION: String = "OVERLAY_UNKNOWN_FORMAT_VERSION"
    const val UNKNOWN_OVERLAY_KIND: String = "OVERLAY_UNKNOWN_KIND"
    const val HASH_MISSING: String = "OVERLAY_HASH_MISSING"
    const val HASH_MISMATCH: String = "OVERLAY_HASH_MISMATCH"
    const val REQUIRED_FIELD_MISSING: String = "OVERLAY_REQUIRED_FIELD_MISSING"
    const val FIELD_TYPE_MISMATCH: String = "OVERLAY_FIELD_TYPE_MISMATCH"
    const val UNKNOWN_REQUIRED_FIELD: String = "OVERLAY_UNKNOWN_REQUIRED_FIELD"
    const val UNKNOWN_ENTRY_KIND: String = "OVERLAY_UNKNOWN_ENTRY_KIND"
    const val ENTRY_KIND_MISMATCH: String = "OVERLAY_ENTRY_KIND_MISMATCH"
    const val UNKNOWN_REQUIRED_FEATURE: String = "OVERLAY_UNKNOWN_REQUIRED_FEATURE"
    const val RESERVED_OPTIONAL_FIELD: String = "OVERLAY_RESERVED_OPTIONAL_FIELD"
    const val USER_REVIEW_REQUIRED: String = "OVERLAY_USER_REVIEW_REQUIRED"
}

object MigrationOverlayValidator {

    fun validate(
        overlay: MigrationOverlay,
        context: MigrationOverlayValidationContext,
        source: String,
    ): MigrationOverlayValidationResult {
        val actualHash = MigrationOverlayCanonicalJson.computeHash(overlay)
        val reportHash = overlay.overlayHash ?: actualHash
        val diagnostics = mutableListOf<MigrationOverlayDiagnostic>()

        fun block(code: String, message: String, entryId: String? = null) {
            diagnostics += MigrationOverlayDiagnostic(
                code = code,
                message = message,
                entryId = entryId,
                overlayHash = reportHash,
            )
        }

        if (overlay.formatVersion.isBlank()) {
            block(MigrationOverlayDiagnostics.REQUIRED_FIELD_MISSING, "formatVersion is required")
        } else if (overlay.formatVersion !in context.supportedFormatVersions) {
            block(
                MigrationOverlayDiagnostics.UNKNOWN_FORMAT_VERSION,
                "Unsupported overlay formatVersion '${overlay.formatVersion}'",
            )
        }

        if (overlay.overlayKind.isBlank()) {
            block(MigrationOverlayDiagnostics.REQUIRED_FIELD_MISSING, "overlayKind is required")
        } else if (overlay.overlayKind !in context.supportedOverlayKinds) {
            block(MigrationOverlayDiagnostics.UNKNOWN_OVERLAY_KIND, "Unsupported overlayKind '${overlay.overlayKind}'")
        }

        requireNonBlank("sourceFingerprint", overlay.sourceFingerprint, ::block)
        requireNonBlank("targetFingerprint", overlay.targetFingerprint, ::block)
        requireNonBlank("dialect", overlay.dialect, ::block)
        requireNonBlank("createdAt", overlay.createdAt, ::block)
        requireNonBlank("createdByVersion", overlay.createdByVersion, ::block)

        if (overlay.sourceFingerprint != context.expectedSourceFingerprint) {
            block(
                MigrationOverlayDiagnostics.STALE_SOURCE_FINGERPRINT,
                "Overlay sourceFingerprint does not match the current schema fingerprint",
            )
        }
        if (overlay.targetFingerprint != context.expectedTargetFingerprint) {
            block(
                MigrationOverlayDiagnostics.STALE_TARGET_FINGERPRINT,
                "Overlay targetFingerprint does not match the desired schema fingerprint",
            )
        }
        if (overlay.dialect != context.expectedDialect) {
            block(MigrationOverlayDiagnostics.DIALECT_MISMATCH, "Overlay dialect '${overlay.dialect}' is not applicable")
        }

        if (overlay.overlayHash.isNullOrBlank()) {
            block(MigrationOverlayDiagnostics.HASH_MISSING, "overlayHash is required")
        } else if (overlay.overlayHash != actualHash) {
            block(MigrationOverlayDiagnostics.HASH_MISMATCH, "overlayHash does not match canonical overlay content")
        }

        for (entry in overlay.entries) {
            if (entry.id.isBlank()) {
                block(MigrationOverlayDiagnostics.REQUIRED_FIELD_MISSING, "entry id is required", entryId = entry.id)
            }
            validateEntryRequiredFields(entry, ::block)
            if (entry.kind != overlay.overlayKind) {
                block(
                    MigrationOverlayDiagnostics.ENTRY_KIND_MISMATCH,
                    "Entry kind '${entry.kind}' is not valid for overlayKind '${overlay.overlayKind}'",
                    entryId = entry.id,
                )
            }
            val unknownFeatures = entry.requiredFeatures - context.supportedRequiredFeatures
            if (unknownFeatures.isNotEmpty()) {
                block(
                    MigrationOverlayDiagnostics.UNKNOWN_REQUIRED_FEATURE,
                    "Entry requires unsupported feature '${unknownFeatures.sorted().first()}'",
                    entryId = entry.id,
                )
            }
        }

        for (key in overlay.producerMetadata.keys) {
            if (key.isReservedExecutionField()) {
                block(
                    MigrationOverlayDiagnostics.RESERVED_OPTIONAL_FIELD,
                    "Optional producerMetadata field '$key' requires a versioned contract",
                )
            }
        }

        return MigrationOverlayValidationResult(
            source = source,
            overlayHash = reportHash,
            diagnostics = diagnostics,
        )
    }

    private fun requireNonBlank(
        fieldName: String,
        value: String,
        block: (String, String, String?) -> Unit,
    ) {
        if (value.isBlank()) {
            block(MigrationOverlayDiagnostics.REQUIRED_FIELD_MISSING, "$fieldName is required", null)
        }
    }

    private fun validateEntryRequiredFields(
        entry: MigrationOverlayEntry,
        block: (String, String, String?) -> Unit,
    ) {
        fun requireEntryNonBlank(fieldName: String, value: String) {
            if (value.isBlank()) {
                block(MigrationOverlayDiagnostics.REQUIRED_FIELD_MISSING, "$fieldName is required", entry.id)
            }
        }

        when (entry) {
            is UsingExpressionOverlayEntry -> {
                requireEntryNonBlank("table", entry.table)
                requireEntryNonBlank("column", entry.column)
                requireEntryNonBlank("sourceType", entry.sourceType)
                requireEntryNonBlank("targetType", entry.targetType)
                requireEntryNonBlank("upUsingExpression.value", entry.upUsingExpression.value)
                entry.downUsingExpression?.let { requireEntryNonBlank("downUsingExpression.value", it.value) }
                requireEntryNonBlank("expressionSource", entry.expressionSource)
                if (!entry.reviewedByUser) {
                    block(
                        MigrationOverlayDiagnostics.USER_REVIEW_REQUIRED,
                        "reviewedByUser must be true for using-expression entries",
                        entry.id,
                    )
                }
            }

            is RenameMappingOverlayEntry -> {
                requireEntryNonBlank("objectType", entry.objectType)
                requireEntryNonBlank("fromName", entry.fromName)
                requireEntryNonBlank("toName", entry.toName)
            }
        }

        if (entry.requiredFeatures.any { it.isBlank() }) {
            block(MigrationOverlayDiagnostics.REQUIRED_FIELD_MISSING, "requiredFeatures entries must be non-blank", entry.id)
        }
    }

    private fun String.isReservedExecutionField(): Boolean =
        startsWith("execution.") ||
            startsWith("risk.") ||
            startsWith("rollback.") ||
            startsWith("dependencies.") ||
            startsWith("preflights.") ||
            startsWith("secrets.")
}
