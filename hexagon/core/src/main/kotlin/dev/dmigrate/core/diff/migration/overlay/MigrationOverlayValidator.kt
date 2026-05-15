package dev.dmigrate.core.diff.migration.overlay

import java.util.Locale

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
    /**
     * F.4 rename-mapping-invalid-enum slice: the set of `objectType`
     * values the current build accepts on a `rename-mapping` entry.
     * Until the View-/Trigger-/Routine-Rename slice ships, only
     * `{table, column}` are valid. Any other `objectType` blocks
     * pre-plan with [MigrationOverlayDiagnostics.UNKNOWN_ENTRY_KIND]
     * (tagged with the offending `renameObjectType`) so the operator
     * has to edit the overlay rather than silently skip a stale entry.
     */
    val supportedRenameObjectTypes: Set<String> = setOf("table", "column"),
)

data class MigrationOverlayValidationResult(
    val source: String,
    val overlayHash: String,
    val diagnostics: List<MigrationOverlayDiagnostic>,
    /**
     * F.4 cli-inline-overlay slice §3.4: entries that passed
     * per-entry validation without a BLOCKER finding referencing
     * their `entryId`. The report layer turns each into an
     * `OVERLAY_ACCEPTED` INFO provenance row so successful inline-
     * overlay entries are visible in the migrate report. Entries
     * with a blank id and entries the validator could not even
     * type-match are excluded.
     */
    val acceptedEntries: List<MigrationOverlayEntry> = emptyList(),
) {
    val hasBlockers: Boolean
        get() = diagnostics.any { it.severity == MigrationOverlayDiagnostic.Severity.BLOCKER }
}

/**
 * Structured overlay finding. Beyond [code] / [message] / [severity]
 * / [entryId] / [overlayHash], the diagnostic carries optional
 * provenance fields so the F.4 reason-classifier
 * ([dev.dmigrate.cli.commands.MigrationOverlayPreflight.buildFailureResult])
 * can decide between `RENAME_MAPPING_INVALID` and
 * `MANUAL_ACTION_REQUIRED` without parsing free-form messages or
 * entry-ID naming patterns.
 *
 * - [entryKind] is the [MigrationOverlayEntry.kind] (e.g.
 *   `"rename-mapping"`, `"using-expression"`) for entry-scoped findings;
 *   `null` for document-level findings such as
 *   `OVERLAY_STALE_SOURCE_FINGERPRINT` or `OVERLAY_HASH_MISMATCH`.
 * - [renameObjectType] is the offending `objectType` value when an
 *   `OVERLAY_UNKNOWN_ENTRY_KIND` (or other rename-mapping-bound
 *   blocker) originates from a `rename-mapping` entry; `null` for
 *   other entry kinds and for document-level findings. This is the
 *   sole structured tag the classifier reads to attribute an
 *   `OVERLAY_UNKNOWN_ENTRY_KIND` to a rename-mapping objectType
 *   outside the current `{table, column}` whitelist.
 */
data class MigrationOverlayDiagnostic(
    val code: String,
    val message: String,
    val severity: Severity = Severity.BLOCKER,
    val entryId: String? = null,
    val overlayHash: String,
    val entryKind: String? = null,
    val renameObjectType: String? = null,
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
    const val SECRET_BEARING_FIELD: String = "OVERLAY_SECRET_BEARING_FIELD"
    const val SECRET_BEARING_PRODUCER_METADATA: String = "OVERLAY_SECRET_BEARING_PRODUCER_METADATA"
    const val USER_REVIEW_REQUIRED: String = "OVERLAY_USER_REVIEW_REQUIRED"
    const val RENAME_MAPPING_STALE_FINGERPRINT: String = "OVERLAY_RENAME_MAPPING_STALE_FINGERPRINT"
    const val RENAME_MAPPING_AMBIGUOUS: String = "OVERLAY_RENAME_MAPPING_AMBIGUOUS"
    const val RENAME_MAPPING_CASE_CONFLICT: String = "OVERLAY_RENAME_MAPPING_CASE_CONFLICT"
    const val RENAME_MAPPING_CHAIN_UNSUPPORTED: String = "OVERLAY_RENAME_MAPPING_CHAIN_UNSUPPORTED"
    const val RENAME_MAPPING_DUPLICATE: String = "OVERLAY_RENAME_MAPPING_DUPLICATE"

    /**
     * F.4 cli-inline-overlay slice §3.4 INFO-severity provenance
     * row: an overlay entry passed every per-entry contract check
     * without producing a BLOCKER finding. Used purely so report
     * consumers can attribute valid `cli-inline` entries back to
     * their flag slot; never appears in `MigrationOverlayPreflightResult.diagnostics`.
     */
    const val OVERLAY_ACCEPTED: String = "OVERLAY_ACCEPTED"
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

        fun block(
            code: String,
            message: String,
            entryId: String? = null,
            entryKind: String? = null,
            renameObjectType: String? = null,
        ) {
            diagnostics += MigrationOverlayDiagnostic(
                code = code,
                message = message,
                entryId = entryId,
                entryKind = entryKind,
                renameObjectType = renameObjectType,
                overlayHash = reportHash,
            )
        }

        fun blockEntry(code: String, message: String, entry: MigrationOverlayEntry) {
            block(
                code = code,
                message = message,
                entryId = entry.id,
                entryKind = entry.kind,
                renameObjectType = (entry as? RenameMappingOverlayEntry)?.objectType,
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

        val blockDocument: (String, String) -> Unit = { c, m -> block(c, m) }
        requireNonBlank("sourceFingerprint", overlay.sourceFingerprint, blockDocument)
        requireNonBlank("targetFingerprint", overlay.targetFingerprint, blockDocument)
        requireNonBlank("dialect", overlay.dialect, blockDocument)
        requireNonBlank("createdAt", overlay.createdAt, blockDocument)
        requireNonBlank("createdByVersion", overlay.createdByVersion, blockDocument)

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
                blockEntry(MigrationOverlayDiagnostics.REQUIRED_FIELD_MISSING, "entry id is required", entry)
            }
            validateEntryRequiredFields(entry, context, ::blockEntry)
            if (entry.kind != overlay.overlayKind) {
                blockEntry(
                    MigrationOverlayDiagnostics.ENTRY_KIND_MISMATCH,
                    "Entry kind '${entry.kind}' is not valid for overlayKind '${overlay.overlayKind}'",
                    entry,
                )
            }
            val unknownFeatures = entry.requiredFeatures - context.supportedRequiredFeatures
            if (unknownFeatures.isNotEmpty()) {
                blockEntry(
                    MigrationOverlayDiagnostics.UNKNOWN_REQUIRED_FEATURE,
                    "Entry requires unsupported feature '${unknownFeatures.sorted().first()}'",
                    entry,
                )
            }
        }
        validateRenameMappings(overlay, context, ::blockEntry)

        for (key in overlay.producerMetadata.keys) {
            if (key.isReservedExecutionField()) {
                block(
                    MigrationOverlayDiagnostics.RESERVED_OPTIONAL_FIELD,
                    "Optional producerMetadata field '$key' requires a versioned contract",
                )
            }
        }
        val secretBearingMetadata = overlay.producerMetadata.entries.sortedBy { it.key }.firstOrNull {
            it.key.containsSecretMarker() || it.value.containsSecretMarker()
        }
        if (secretBearingMetadata != null) {
            block(
                MigrationOverlayDiagnostics.SECRET_BEARING_PRODUCER_METADATA,
                "Producer metadata field '${secretBearingMetadata.key}' may contain secret-bearing material",
            )
        }

        // F.4 cli-inline-overlay review fix: suppress OVERLAY_ACCEPTED
        // provenance whenever the document itself is rejected.
        // Otherwise an operator reading the report sees an
        // "ACCEPTED" row next to a doc-level HASH_MISSING /
        // STALE_*_FINGERPRINT / DIALECT_MISMATCH BLOCKER for the
        // same source and might think the entry will still be
        // applied — it won't, because the whole document gates out.
        // The provenance is only meaningful when no BLOCKER (entry-
        // scoped OR doc-level) is present.
        val anyBlocker = diagnostics.any { it.severity == MigrationOverlayDiagnostic.Severity.BLOCKER }
        val accepted = if (anyBlocker) {
            emptyList()
        } else {
            overlay.entries.filter { it.id.isNotBlank() }
        }

        return MigrationOverlayValidationResult(
            source = source,
            overlayHash = reportHash,
            diagnostics = diagnostics,
            acceptedEntries = accepted,
        )
    }

    private fun requireNonBlank(
        fieldName: String,
        value: String,
        block: (String, String) -> Unit,
    ) {
        if (value.isBlank()) {
            block(MigrationOverlayDiagnostics.REQUIRED_FIELD_MISSING, "$fieldName is required")
        }
    }

    private fun validateEntryRequiredFields(
        entry: MigrationOverlayEntry,
        context: MigrationOverlayValidationContext,
        block: (String, String, MigrationOverlayEntry) -> Unit,
    ) {
        fun requireEntryNonBlank(fieldName: String, value: String) {
            if (value.isBlank()) {
                block(MigrationOverlayDiagnostics.REQUIRED_FIELD_MISSING, "$fieldName is required", entry)
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
                if (entry.upUsingExpression.secret) {
                    block(
                        MigrationOverlayDiagnostics.SECRET_BEARING_FIELD,
                        "upUsingExpression is marked secret and cannot be embedded in migration overlays",
                        entry,
                    )
                }
                if (entry.downUsingExpression?.secret == true) {
                    block(
                        MigrationOverlayDiagnostics.SECRET_BEARING_FIELD,
                        "downUsingExpression is marked secret and cannot be embedded in migration overlays",
                        entry,
                    )
                }
                requireEntryNonBlank("expressionSource", entry.expressionSource)
                if (!entry.reviewedByUser) {
                    block(
                        MigrationOverlayDiagnostics.USER_REVIEW_REQUIRED,
                        "reviewedByUser must be true for using-expression entries",
                        entry,
                    )
                }
            }

            is RenameMappingOverlayEntry -> {
                requireEntryNonBlank("objectType", entry.objectType)
                requireEntryNonBlank("fromName", entry.fromName)
                requireEntryNonBlank("toName", entry.toName)
                entry.fromStructureFingerprint?.let { requireEntryNonBlank("fromStructureFingerprint", it) }
                entry.toStructureFingerprint?.let { requireEntryNonBlank("toStructureFingerprint", it) }
                if (entry.objectType.isNotBlank() &&
                    context.supportedRenameObjectTypes.none { it.caseFold() == entry.objectType.caseFold() }
                ) {
                    // Case-fold both sides because the rest of the rename validator
                    // (sourceKey/targetKey/groupBy) already case-folds; treating the
                    // whitelist asymmetrically would surprise operators who type
                    // `TABLE` instead of `table`. The diagnostic message keeps the
                    // operator-supplied value so they see what they wrote.
                    block(
                        MigrationOverlayDiagnostics.UNKNOWN_ENTRY_KIND,
                        "Rename mapping objectType '${entry.objectType}' is not supported in this build " +
                            "(supported: ${context.supportedRenameObjectTypes.sorted().joinToString(", ")}).",
                        entry,
                    )
                }
            }
        }

        if (entry.requiredFeatures.any { it.isBlank() }) {
            block(MigrationOverlayDiagnostics.REQUIRED_FIELD_MISSING, "requiredFeatures entries must be non-blank", entry)
        }
    }

    private fun validateRenameMappings(
        overlay: MigrationOverlay,
        context: MigrationOverlayValidationContext,
        block: (String, String, MigrationOverlayEntry) -> Unit,
    ) {
        val entries = overlay.entries.filterIsInstance<RenameMappingOverlayEntry>()
        if (entries.isEmpty()) return
        if (overlay.sourceFingerprint != context.expectedSourceFingerprint ||
            overlay.targetFingerprint != context.expectedTargetFingerprint
        ) {
            entries.forEach { entry ->
                block(
                    MigrationOverlayDiagnostics.RENAME_MAPPING_STALE_FINGERPRINT,
                    "Rename mapping is stale for the current source/target fingerprints",
                    entry,
                )
            }
        }
        validateRenameDuplicates(entries, block)
        validateRenameUniqueness(entries, block)
        validateRenameChains(entries, block)
    }

    /**
     * Two entries with identical `(objectType, fromName, toName)` —
     * after case folding — describe the same rename. The mapper would
     * happily emit one [DiffOperation.RenameTable]/[RenameColumn] per
     * entry, which violates the F.4 plan ("genau ein … Rename-Op").
     * Block in the overlay layer so the duplication is rejected
     * before render.
     */
    private fun validateRenameDuplicates(
        entries: List<RenameMappingOverlayEntry>,
        block: (String, String, MigrationOverlayEntry) -> Unit,
    ) {
        val groups = entries.groupBy { Triple(it.objectType.caseFold(), it.fromName.caseFold(), it.toName.caseFold()) }
        for ((_, group) in groups) {
            if (group.size < 2) continue
            for (entry in group) {
                block(
                    MigrationOverlayDiagnostics.RENAME_MAPPING_DUPLICATE,
                    "Rename mapping '${entry.fromName}' -> '${entry.toName}' is duplicated " +
                        "(${group.size} entries describe the same rename).",
                    entry,
                )
            }
        }
    }

    private fun validateRenameUniqueness(
        entries: List<RenameMappingOverlayEntry>,
        block: (String, String, MigrationOverlayEntry) -> Unit,
    ) {
        val sources = entries.groupBy { it.sourceKey() }
        val targets = entries.groupBy { it.targetKey() }
        val sourceConflict = sources.values.firstOrNull { group ->
            group.map { it.toName.caseFold() }.distinct().size > 1 || group.map { it.fromName }.distinct().size > 1
        }
        val targetConflict = targets.values.firstOrNull { group ->
            group.map { it.fromName.caseFold() }.distinct().size > 1 || group.map { it.toName }.distinct().size > 1
        }
        sourceConflict?.forEach { entry ->
            block(
                MigrationOverlayDiagnostics.RENAME_MAPPING_AMBIGUOUS,
                "Rename mapping source '${entry.fromName}' is ambiguous",
                entry,
            )
        }
        targetConflict?.forEach { entry ->
            block(
                MigrationOverlayDiagnostics.RENAME_MAPPING_AMBIGUOUS,
                "Rename mapping target '${entry.toName}' is ambiguous",
                entry,
            )
        }
        val caseConflict = (sources.values + targets.values).firstOrNull { group ->
            group.map { it.fromName }.distinct().size > 1 || group.map { it.toName }.distinct().size > 1
        }
        caseConflict?.forEach { entry ->
            block(
                MigrationOverlayDiagnostics.RENAME_MAPPING_CASE_CONFLICT,
                "Rename mapping '${entry.fromName}' -> '${entry.toName}' conflicts after case folding",
                entry,
            )
        }
    }

    private fun validateRenameChains(
        entries: List<RenameMappingOverlayEntry>,
        block: (String, String, MigrationOverlayEntry) -> Unit,
    ) {
        val sources = entries.map { it.sourceKey() }.toSet()
        entries.filter { it.targetKey() in sources }.forEach { entry ->
            block(
                MigrationOverlayDiagnostics.RENAME_MAPPING_CHAIN_UNSUPPORTED,
                "Rename mapping '${entry.fromName}' -> '${entry.toName}' forms an unsupported chain",
                entry,
            )
        }
    }

    private fun RenameMappingOverlayEntry.sourceKey(): RenameMappingKey =
        RenameMappingKey(objectType.caseFold(), fromName.caseFold())

    private fun RenameMappingOverlayEntry.targetKey(): RenameMappingKey =
        RenameMappingKey(objectType.caseFold(), toName.caseFold())

    private data class RenameMappingKey(
        val objectType: String,
        val name: String,
    )

    private fun String.isReservedExecutionField(): Boolean =
        startsWith("execution.") ||
            startsWith("risk.") ||
            startsWith("rollback.") ||
            startsWith("dependencies.") ||
            startsWith("preflights.") ||
            startsWith("secrets.")

    private fun String.containsSecretMarker(): Boolean {
        val normalized = lowercase(Locale.ROOT)
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

    private fun String.caseFold(): String = lowercase(Locale.ROOT)
}
