package dev.dmigrate.cli.commands

import dev.dmigrate.core.diff.migration.DiffDiagnostic
import dev.dmigrate.core.diff.migration.DiffResult
import dev.dmigrate.core.diff.migration.overlay.MigrationOverlayDiagnostic
import dev.dmigrate.core.diff.migration.overlay.MigrationOverlayDiagnostics
import dev.dmigrate.core.diff.migration.overlay.MigrationOverlayDocument
import dev.dmigrate.core.diff.migration.overlay.MigrationOverlayKinds
import dev.dmigrate.core.diff.migration.overlay.MigrationOverlayReport
import dev.dmigrate.core.diff.migration.overlay.MigrationOverlayReportItem
import dev.dmigrate.core.diff.migration.overlay.MigrationOverlayValidator
import dev.dmigrate.core.diff.migration.overlay.MigrationOverlayValidationContext
import dev.dmigrate.core.diff.migration.overlay.RenameMappingOverlayEntry
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.migration.MigrationBlockedReason
import dev.dmigrate.driver.migration.MigrationBlocker
import dev.dmigrate.driver.migration.MigrationDdlResult
import java.util.Locale

/**
 * F.0 pre-render gate for versioned migration overlays. The actual
 * feature-specific consumers (PostgreSQL USING, rename mappings, later
 * transformation plans) may still enforce narrower semantics, but every
 * overlay document must first satisfy the shared fingerprint/hash/dialect
 * contract.
 */
internal object MigrationOverlayPreflight {

    /**
     * Plan-2 §F.4 dependency-projection T1: validate overlays
     * **before** the first `DiffPlanner.plan(...)` so a Rename-
     * mapping blocker can surface as a pre-plan failure without
     * forcing the planner to walk a doomed schema diff.
     *
     * Naming inversion: in the migrate pipeline the IS-state lives
     * under `target` (the live DB to mutate) and the SOLL-state lives
     * under `source` (the schema file). The overlay validator's
     * "current"/"desired" semantics align with the IS/SOLL split, so
     * [sourceFingerprint] must carry the IS-state fingerprint and
     * [targetFingerprint] the SOLL-state fingerprint regardless of
     * how the caller labels its variables. Fingerprints are computed
     * up-front by the runner.
     *
     * [dialect] is the engine identifier the overlay must match.
     * Comparison is locale-insensitive: the helper normalises both
     * the incoming value and the overlay's `dialect` field via
     * [Locale.ROOT] before equality so a Turkish JVM cannot turn
     * `"POSTGRESQL"` into `"postgresqı"`.
     */
    fun validateBeforePlan(
        documents: List<MigrationOverlayDocument>,
        sourceFingerprint: String,
        targetFingerprint: String,
        dialect: String,
        loadFailures: List<MigrationOverlayLoadFailure> = emptyList(),
        supportedRenameObjectTypes: Set<String> = DEFAULT_SUPPORTED_RENAME_OBJECT_TYPES,
    ): MigrationOverlayPreflightResult {
        if (documents.isEmpty() && loadFailures.isEmpty()) {
            return MigrationOverlayPreflightResult(emptyList(), emptyList())
        }
        val normalisedDialect = dialect.lowercase(Locale.ROOT)
        val validationReports = documents.flatMap { document ->
            val result = MigrationOverlayValidator.validate(
                overlay = document.overlay,
                context = MigrationOverlayValidationContext(
                    expectedSourceFingerprint = sourceFingerprint,
                    expectedTargetFingerprint = targetFingerprint,
                    expectedDialect = normalisedDialect,
                    supportedRenameObjectTypes = supportedRenameObjectTypes,
                ),
                source = document.source,
            )
            MigrationOverlayReport.fromValidation(result)
        }
        val crossDocFindings = crossDocumentRenameFindings(documents)
        val loadFailureReports = loadFailures.map { failure ->
            MigrationOverlayReportItem(
                source = failure.source,
                entryId = null,
                overlayHash = MigrationOverlayReport.UNAVAILABLE_OVERLAY_HASH,
                diagnosticCode = failure.diagnosticCode,
                severity = MigrationOverlayDiagnostic.Severity.BLOCKER,
                message = failure.message ?: "Migration overlay '${failure.source}' could not be loaded: ${failure.diagnosticCode}",
            )
        }
        val reports = validationReports + crossDocFindings + loadFailureReports
        // F.4 cli-inline-overlay §3.4: Provenance (reportItems) and
        // Failure-Diagnostics are deliberately separated. INFO-level
        // OVERLAY_ACCEPTED rows MUST NOT become DiffDiagnostic
        // failure entries, and the Diagnostic message uses the
        // validator's own text rather than a synthesised "failed
        // F.0 contract validation" string.
        val diagnostics = reports
            .filter { it.severity != MigrationOverlayDiagnostic.Severity.INFO }
            .map { item ->
                DiffDiagnostic(
                    code = item.diagnosticCode,
                    message = item.message ?: defaultFindingMessage(item),
                    severity = item.severity.toDiffSeverity(),
                    operationId = null,
                )
            }
        return MigrationOverlayPreflightResult(reports, diagnostics)
    }

    private fun defaultFindingMessage(item: MigrationOverlayReportItem): String =
        "Migration overlay source=${item.source} entry=${item.entryId ?: "<document>"} " +
            "hash=${item.overlayHash} reports ${item.diagnosticCode}"

    /**
     * F.4 cli-inline-overlay §3.5: collect rename mappings across
     * all overlay documents (file-overlays + the synthetic
     * `cli-inline` overlay) and emit BLOCKER report items for
     * conflicts that span >=2 distinct `source` documents. The
     * single-document validator already catches in-document
     * duplicates / ambiguity / case / chain — this helper layers
     * cross-document detection on top.
     *
     * Codes mirror the in-doc validator so the reason-classifier
     * already maps both to [MigrationBlockedReason.RENAME_MAPPING_INVALID].
     * Each conflicting entry produces its own report item so the
     * downstream report shows every `source`/`entryId` pair the
     * operator must resolve.
     */
    private fun crossDocumentRenameFindings(
        documents: List<MigrationOverlayDocument>,
    ): List<MigrationOverlayReportItem> {
        if (documents.size < 2) return emptyList()
        val refs = documents.flatMap { doc ->
            doc.overlay.entries
                .filterIsInstance<RenameMappingOverlayEntry>()
                .filter { it.id.isNotBlank() && it.objectType.isNotBlank() }
                .map { entry -> RenameRef(doc.source, doc.overlay.overlayHash, entry) }
        }
        if (refs.size < 2) return emptyList()

        val findings = linkedMapOf<Pair<String, String>, MigrationOverlayReportItem>()

        fun addFinding(ref: RenameRef, code: String, message: String) {
            // De-duplicate per (source, entryId, code) so the same
            // ref shown by two conflict categories surfaces once.
            findings.putIfAbsent(ref.dedupKey(code), renameCrossDocItem(ref, code, message))
        }

        val byTriple = refs.groupBy {
            Triple(it.fold(it.entry.objectType), it.fold(it.entry.fromName), it.fold(it.entry.toName))
        }
        for (group in byTriple.values) {
            val distinctSources = group.map { it.source }.distinct().size
            if (distinctSources >= 2) {
                group.forEach { ref ->
                    addFinding(
                        ref,
                        MigrationOverlayDiagnostics.RENAME_MAPPING_DUPLICATE,
                        "Rename mapping '${ref.entry.fromName}' -> '${ref.entry.toName}' is duplicated " +
                            "across overlay documents (${distinctSources} sources).",
                    )
                }
            }
        }

        val bySource = refs.groupBy { it.fold(it.entry.objectType) to it.fold(it.entry.fromName) }
        for (group in bySource.values) {
            val sources = group.map { it.source }.distinct()
            val targets = group.map { it.fold(it.entry.toName) }.distinct()
            if (sources.size >= 2 && targets.size > 1) {
                group.forEach { ref ->
                    addFinding(
                        ref,
                        MigrationOverlayDiagnostics.RENAME_MAPPING_AMBIGUOUS,
                        "Rename mapping source '${ref.entry.fromName}' is ambiguous across overlay documents.",
                    )
                }
            }
        }

        val byTarget = refs.groupBy { it.fold(it.entry.objectType) to it.fold(it.entry.toName) }
        for (group in byTarget.values) {
            val sources = group.map { it.source }.distinct()
            val froms = group.map { it.fold(it.entry.fromName) }.distinct()
            if (sources.size >= 2 && froms.size > 1) {
                group.forEach { ref ->
                    addFinding(
                        ref,
                        MigrationOverlayDiagnostics.RENAME_MAPPING_AMBIGUOUS,
                        "Rename mapping target '${ref.entry.toName}' is ambiguous across overlay documents.",
                    )
                }
            }
        }

        return findings.values.toList()
    }

    private fun renameCrossDocItem(
        ref: RenameRef,
        code: String,
        message: String,
    ): MigrationOverlayReportItem = MigrationOverlayReportItem(
        source = ref.source,
        entryId = ref.entry.id,
        overlayHash = ref.overlayHash ?: MigrationOverlayReport.UNAVAILABLE_OVERLAY_HASH,
        diagnosticCode = code,
        severity = MigrationOverlayDiagnostic.Severity.BLOCKER,
        entryKind = ref.entry.kind,
        renameObjectType = ref.entry.objectType,
        message = message,
    )

    private data class RenameRef(
        val source: String,
        val overlayHash: String?,
        val entry: RenameMappingOverlayEntry,
    ) {
        fun fold(value: String): String = value.lowercase(Locale.ROOT)
        fun dedupKey(code: String): Pair<String, String> = "$source ${entry.id}" to code
    }

    /**
     * F.4 rename-mapping-invalid-enum slice: until the
     * View-/Trigger-/Routine-Rename slice ships, only
     * `{table, column}` are accepted. The Pre-Plan-Gate blocks every
     * other `rename-mapping.objectType` value with
     * `OVERLAY_UNKNOWN_ENTRY_KIND` and the application-layer
     * reason-classifier groups those findings under
     * [MigrationBlockedReason.RENAME_MAPPING_INVALID].
     */
    val DEFAULT_SUPPORTED_RENAME_OBJECT_TYPES: Set<String> = setOf("table", "column")

    /**
     * Backward-compatible wrapper around [validateBeforePlan] for the
     * pre-T1 call site that already had a [DiffResult] in hand. New
     * call sites should compute fingerprints up-front and call
     * [validateBeforePlan] directly so the gate runs before
     * `DiffPlanner.plan(...)`.
     */
    fun validate(
        plan: DiffResult,
        dialect: DatabaseDialect,
        loadFailures: List<MigrationOverlayLoadFailure> = emptyList(),
        supportedRenameObjectTypes: Set<String> = DEFAULT_SUPPORTED_RENAME_OBJECT_TYPES,
    ): MigrationOverlayPreflightResult = validateBeforePlan(
        documents = plan.migrationOverlays,
        sourceFingerprint = plan.current.fingerprint.orEmpty(),
        targetFingerprint = plan.desired.fingerprint.orEmpty(),
        dialect = dialect.name,
        loadFailures = loadFailures,
        supportedRenameObjectTypes = supportedRenameObjectTypes,
    )

    fun buildFailureResult(plan: DiffResult, result: MigrationOverlayPreflightResult): MigrationDdlResult {
        val skipped = plan.operations.map { it.id }.toSet()
        val groups = classifyBlockers(result)
        val blockers = buildList {
            groups[MigrationBlockedReason.RENAME_MAPPING_INVALID]?.let { diags ->
                add(MigrationBlocker(MigrationBlockedReason.RENAME_MAPPING_INVALID, skipped, diags))
            }
            groups[MigrationBlockedReason.MANUAL_ACTION_REQUIRED]?.let { diags ->
                add(MigrationBlocker(MigrationBlockedReason.MANUAL_ACTION_REQUIRED, skipped, diags))
            }
        }
        val primary = when {
            blockers.any { it.reason == MigrationBlockedReason.RENAME_MAPPING_INVALID } ->
                MigrationBlockedReason.RENAME_MAPPING_INVALID
            blockers.any { it.reason == MigrationBlockedReason.MANUAL_ACTION_REQUIRED } ->
                MigrationBlockedReason.MANUAL_ACTION_REQUIRED
            else -> null
        }
        return MigrationDdlResult(
            statements = emptyList(),
            operationsRendered = emptySet(),
            operationsSkipped = skipped,
            blockers = blockers,
            primaryBlockedReason = primary,
            diagnostics = result.diagnostics,
        )
    }

    /**
     * F.4 rename-mapping-invalid-enum slice §4.2/§4.3: group BLOCKER
     * findings by [MigrationBlockedReason] using the structured
     * [MigrationOverlayReportItem.entryKind] /
     * [MigrationOverlayReportItem.renameObjectType] tags rather than
     * parsing free-form text or entry-ID conventions. Diagnostics that
     * the validator emitted without a report item (none today, but the
     * shape allows it) keep their natural ordering inside the
     * `MANUAL_ACTION_REQUIRED` bucket.
     *
     * Pairing rule: report items and diagnostics are built 1:1 in
     * [validateBeforePlan], so `reportItems[i]` describes
     * `diagnostics[i]`. Length mismatches would be a defect rather
     * than something to silently work around — we map by index and
     * fall back to the report-item count.
     */
    private fun classifyBlockers(
        result: MigrationOverlayPreflightResult,
    ): Map<MigrationBlockedReason, List<DiffDiagnostic>> {
        val grouped = linkedMapOf<MigrationBlockedReason, MutableList<DiffDiagnostic>>()
        val items = result.reportItems
        result.diagnostics.forEachIndexed { index, diagnostic ->
            if (diagnostic.severity != DiffDiagnostic.Severity.BLOCKER) return@forEachIndexed
            val item = items.getOrNull(index)
            val reason = classifyDiagnostic(diagnostic.code, item)
            grouped.getOrPut(reason) { mutableListOf() } += diagnostic
        }
        return grouped
    }

    /**
     * Reason-classification table for one BLOCKER finding. See
     * `docs/planning/done/ImpPlan-0.9.7-F.4-rename-mapping-invalid-enum.md`
     * §4.3. Any rename-mapping-bound `OVERLAY_RENAME_MAPPING_*` or
     * rename-mapping-bound `OVERLAY_UNKNOWN_ENTRY_KIND` (objectType
     * outside the current whitelist) maps to
     * `RENAME_MAPPING_INVALID`; everything else stays at
     * `MANUAL_ACTION_REQUIRED`. Generic `OVERLAY_UNKNOWN_ENTRY_KIND`
     * findings without rename-mapping context fall through to
     * `MANUAL_ACTION_REQUIRED` so the slice does not accidentally
     * widen the new reason past its documented scope.
     */
    private fun classifyDiagnostic(
        code: String,
        item: MigrationOverlayReportItem?,
    ): MigrationBlockedReason {
        if (code in RENAME_MAPPING_CODES) return MigrationBlockedReason.RENAME_MAPPING_INVALID
        if (code == OBJECT_RENAME_UNSUPPORTED_CODE) return MigrationBlockedReason.RENAME_MAPPING_INVALID
        if (code == MigrationOverlayDiagnostics.UNKNOWN_ENTRY_KIND &&
            item?.entryKind == MigrationOverlayKinds.RENAME_MAPPING &&
            !item.renameObjectType.isNullOrBlank()
        ) {
            return MigrationBlockedReason.RENAME_MAPPING_INVALID
        }
        return MigrationBlockedReason.MANUAL_ACTION_REQUIRED
    }

    private val RENAME_MAPPING_CODES: Set<String> = setOf(
        MigrationOverlayDiagnostics.RENAME_MAPPING_STALE_FINGERPRINT,
        MigrationOverlayDiagnostics.RENAME_MAPPING_AMBIGUOUS,
        MigrationOverlayDiagnostics.RENAME_MAPPING_CASE_CONFLICT,
        MigrationOverlayDiagnostics.RENAME_MAPPING_CHAIN_UNSUPPORTED,
        MigrationOverlayDiagnostics.RENAME_MAPPING_DUPLICATE,
    )

    /**
     * Forward-looking renderer diagnostic the Routine-/Trigger-/View-
     * Rename slice will emit when an object class is rejected by the
     * dialect. Pinned as a string constant here (not as a typed
     * reference) because the renderer code that will produce it has
     * not landed yet. Listing it in the classifier table now keeps
     * the slice spec §4.3 contract honest without forcing today's
     * code to depend on a renderer that does not exist yet.
     */
    private const val OBJECT_RENAME_UNSUPPORTED_CODE: String = "OBJECT_RENAME_UNSUPPORTED"

    private fun MigrationOverlayDiagnostic.Severity.toDiffSeverity(): DiffDiagnostic.Severity = when (this) {
        MigrationOverlayDiagnostic.Severity.INFO -> DiffDiagnostic.Severity.INFO
        MigrationOverlayDiagnostic.Severity.WARNING -> DiffDiagnostic.Severity.WARNING
        MigrationOverlayDiagnostic.Severity.BLOCKER -> DiffDiagnostic.Severity.BLOCKER
    }
}

internal data class MigrationOverlayPreflightResult(
    val reportItems: List<MigrationOverlayReportItem>,
    val diagnostics: List<DiffDiagnostic>,
) {
    val hasBlockers: Boolean
        get() = diagnostics.any { it.severity == DiffDiagnostic.Severity.BLOCKER }
}

data class MigrationOverlayLoadFailure(
    val source: String,
    val diagnosticCode: String = MigrationOverlayDiagnostics.FIELD_TYPE_MISMATCH,
    /** Optional fact-bearing message — falls back to a synthesised default. */
    val message: String? = null,
)
