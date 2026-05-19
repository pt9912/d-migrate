package dev.dmigrate.driver.migration

/**
 * F.4 Renderer-Blocker-Bridge (2026-05-19): maps a planner-emitted
 * BLOCKER-severity `DiffDiagnostic.code` to the
 * [MigrationBlockedReason] enum value that should surface as the
 * report-level `primaryBlockedReason`.
 *
 * Background. The PG, MySQL and SQLite render contexts used to wrap
 * EVERY planner-emitted BLOCKER diagnostic into a single
 * `MigrationBlocker(reason = DIALECT_UNSUPPORTED_OPERATION)`. That
 * was correct for the original F.5 carve-out (`CONSTRAINT_NOT_DIFFABLE`
 * — the dialect genuinely cannot render the operation) but became
 * wrong once F.4 introduced
 * [MigrationBlockedReason.OBJECT_RENAME_UNSUPPORTED] as the
 * Mapper-/Planner-phase rename-policy outcome. F.4 plan-doc §5.2
 * reserves `OBJECT_RENAME_UNSUPPORTED` for those Mapper-/Planner
 * cases and forbids conflating it with `DIALECT_UNSUPPORTED_OPERATION`
 * (which stays reserved for renderer-side dialect-unsupported
 * operations).
 *
 * Renderers now run each planner-emitted diagnostic code through
 * [classify] and group the resulting `MigrationBlocker`s by reason,
 * so the report's `primaryBlockedReason` accurately reflects the
 * Mapper/Planner intent.
 *
 * The classifier is intentionally conservative: only diagnostic
 * codes that map to a more specific
 * [MigrationBlockedReason] are listed here; everything else falls
 * back to [MigrationBlockedReason.DIALECT_UNSUPPORTED_OPERATION] so
 * the pre-F.4 behaviour stays unchanged for unrelated codes
 * (`CONSTRAINT_NOT_DIFFABLE`, `MATERIALIZED_VIEW_DIFF_UNSUPPORTED`,
 * etc.). Additional mappings land via dedicated follow-up slices,
 * one diagnostic code at a time, after the contract has been
 * validated against the F.5 / D.3b carve-outs.
 *
 * Out of scope (separate concerns):
 *
 * - The pre-plan overlay-validator classifier at
 *   `dev.dmigrate.cli.commands.MigrationOverlayPreflight.classifyDiagnostic`
 *   maps the OVERLAY-side string code `OBJECT_RENAME_UNSUPPORTED`
 *   onto `RENAME_MAPPING_INVALID` (legacy renderer-emitted-code
 *   shortcut). That schedule is independent from this classifier
 *   — both can coexist because they run on different pipeline
 *   stages.
 */
object PlannerBlockerClassifier {

    /**
     * F.4 plan-doc §5.2: `OBJECT_RENAME_UNSUPPORTED` is the Mapper-/
     * Planner-phase diagnostic code emitted by `RenameObjectMapper`
     * when [ObjectRenamePolicy.classify][] returns
     * `RenameSupport.Blocked` (materialized-view rename, body-drift,
     * missing prior body in Drop+Create-fallback, SQLite-routines-
     * carve-out, MySQL/SQLite-sequence-carve-out). The renderer must
     * preserve this reason through the wrap to keep the contract
     * consistent.
     */
    const val OBJECT_RENAME_UNSUPPORTED_CODE: String = "OBJECT_RENAME_UNSUPPORTED"

    fun classify(code: String): MigrationBlockedReason = when (code) {
        OBJECT_RENAME_UNSUPPORTED_CODE -> MigrationBlockedReason.OBJECT_RENAME_UNSUPPORTED
        else -> MigrationBlockedReason.DIALECT_UNSUPPORTED_OPERATION
    }
}
