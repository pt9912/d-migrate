package dev.dmigrate.driver.migration

import dev.dmigrate.core.diff.migration.DiffDiagnostic

/**
 * Reason a [MigrationDdlResult] cannot be safely executed without
 * additional caller intervention. A single result may carry several
 * blockers — destructive ops, non-reversible ops, and a dialect-
 * unsupported op can all coexist.
 *
 * `MIGRATION_ERROR` is *not* in this enum: execution-time failures
 * are reported via `MigrationDdlResult.executionError` and friends,
 * never as a planning blocker (Plan §6.1).
 */
enum class MigrationBlockedReason {
    /** Up-plan contains destructive operations and `--allow-destructive` was not set. */
    DESTRUCTIVE_OPERATION_REQUIRES_CONFIRMATION,

    /** Down-plan / Rollback path encountered a `NOT_REVERSIBLE` operation. */
    ROLLBACK_NOT_POSSIBLE,

    /** One or more operations require manual ops work before/after the rendered SQL. */
    MANUAL_ACTION_REQUIRED,

    /** `schema rollback` would run against an unexpected target fingerprint. */
    TARGET_STATE_MISMATCH,

    /** `schema rollback` would run against the wrong dialect / connection. */
    TARGET_DIALECT_MISMATCH,

    /** The chosen dialect cannot render one of the operations in the plan. */
    DIALECT_UNSUPPORTED_OPERATION,

    /**
     * The rendered statement stream mixes transaction ownership models
     * that the runner cannot execute as one safe unit.
     */
    TRANSACTION_SCOPE_UNSUPPORTED,

    /**
     * F.4 rename-overlay-specific blocker: the operator's rename
     * mapping is structurally invalid (stale fingerprint, ambiguous
     * source/target, case conflict, chain rename in the same slice,
     * duplicate entry, unsupported `objectType` outside the current
     * whitelist, or `OBJECT_RENAME_UNSUPPORTED` from a renderer).
     * The operator must edit the rename-mapping overlay to resolve
     * the conflict; this is NOT a generic "manual action" case where
     * the schema itself needs operator decisions, so reports
     * distinguish it from [MANUAL_ACTION_REQUIRED] for tooling
     * convenience. New value appended at the end of the enum —
     * existing ordinals stay unchanged so report fixtures that
     * compare by string see only an additive change.
     */
    RENAME_MAPPING_INVALID,

    /**
     * E.2 Sub-Slice A.1 blocker: two trigger entries share a name but
     * belong to different tables. The current
     * `SchemaDefinition.triggers: Map<String, TriggerDefinition>` would
     * lose this ambiguity at `.toMap()`, so the detector flags it
     * before materialisation. Diagnostics list the colliding
     * `(name, tableA, tableB)` tuples.
     */
    TRIGGER_NAME_COLLISION,

    /**
     * E.2 Sub-Slice A.2 blocker (PostgreSQL): `TriggerDefinition.body`
     * is not the strict `[schema.]identifier([arg, ...])` function
     * reference PostgreSQL's `EXECUTE FUNCTION` expects. Inline
     * PL/pgSQL bodies are deliberately out of E.2 scope.
     */
    TRIGGER_BODY_NOT_FUNCTION_REFERENCE,

    /**
     * F.4 Sub-Slice A.1 blocker: the Mapper- or Planner-phase
     * `ObjectRenamePolicy.classify(...)` returned `RenameSupport.Blocked`
     * for a rename candidate (e.g. a dialect that has no rename path
     * at all for the kind, or a body-drift / missing-body case where
     * the Drop+Create-fallback contract cannot reconstruct the prior
     * body). The diagnostic message carries the dialect, object kind
     * and a specific rationale code.
     *
     * Distinct from [RENAME_MAPPING_INVALID]: the latter is the
     * pre-plan overlay validator's "your overlay is structurally
     * wrong" signal (also surfaced when the renderer emits the
     * legacy `OBJECT_RENAME_UNSUPPORTED` diagnostic code on
     * `RenameTable`/`RenameColumn`, which `MigrationOverlayPreflight`
     * classifies as `RENAME_MAPPING_INVALID` for tooling
     * convenience). This new enum value is used by F.4 Mapper-/
     * Planner-paths directly on the new `Rename{View,Trigger,Function,
     * Procedure,Sequence}` subtypes where the decision is a policy
     * outcome, not an operator-fixable overlay shape.
     */
    OBJECT_RENAME_UNSUPPORTED,
}

/**
 * One concrete blocker against executing this plan. The
 * [diagnostics] carry the structured findings the renderer
 * accumulated; CLI surfaces them under Exit-Code 8
 * `MIGRATION_BLOCKED`.
 */
data class MigrationBlocker(
    val reason: MigrationBlockedReason,
    val operationIds: Set<String> = emptySet(),
    val diagnostics: List<DiffDiagnostic> = emptyList(),
)
