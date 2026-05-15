package dev.dmigrate.core.diff.migration

/**
 * F.4 dependency-projection T2/T3 carrier between the mapper and the
 * [RenameDependencyProjector]. Each item describes one overlay-bound
 * rename candidate plus the operations that would otherwise be
 * produced by the regular drop+add path if the rename is not folded.
 *
 * Today the projector either emits a `Rename*` op (when the candidate
 * is structurally compatible AND no stale dependency points at the old
 * name) or it falls back to the carried [fallbackOperations]. The
 * [postRenameDeltaOperations] list is intentionally empty in T2; the
 * delta-synthesis slice (T4) populates it for mixed renames.
 *
 * The two concrete variants share the candidate provenance metadata via
 * the [RenamePlanningItem] hierarchy so the projector can identify the
 * authorising overlay entry without inspecting the candidate type.
 */
internal sealed interface RenamePlanningItem {
    /** Overlay source path (e.g. `rename-overlay.json`). */
    val overlaySource: String

    /** Stable overlay entry identifier — required by F.4 report provenance. */
    val overlayEntryId: String

    /** Optional content hash for the overlay document. */
    val overlayHash: String?

    /**
     * Drop+Add (Drop+Create for columns) operations the regular mapper
     * path would emit if the rename does not fold. The projector either
     * absorbs the candidate names (and emits the candidate's rename
     * operation) or appends these operations alongside a structural-
     * mismatch / dependency-projection diagnostic.
     */
    val fallbackOperations: List<DiffOperation>

    /**
     * Synthetic operations that must run after the rename to bring the
     * renamed object's intra-object structure to the desired state.
     * Empty in T2 (pass-through projector); the T4 slice synthesises
     * Add/AlterColumn/AddIndex operations from the schema diff.
     */
    val postRenameDeltaOperations: List<DiffOperation>
}

/**
 * F.4 candidate metadata for a table rename. [id] is the deterministic
 * operation ID the projector will use when emitting the final
 * `RenameTable` operation; the candidate carries it pre-decision so the
 * later `finalizeIds` step can remap dependency references atomically.
 */
internal data class RenameTableCandidate(
    val id: String,
    val fromName: String,
    val toName: String,
    val overlaySource: String,
    val overlayEntryId: String,
    val overlayHash: String?,
    /**
     * True when the projector may emit a native `Rename*` operation
     * for this candidate. This is satisfied by **either**:
     *
     * - `CanonicalPayload.table` for source and target is byte-
     *   identical (T2 pass-through happy path), **or**
     * - the source/target differ but
     *   [RenameIntraObjectDeltaSynthesizer] covers every difference
     *   via [RenamePlanningItem.postRenameDeltaOperations] (T4
     *   mixed-case synthesis).
     *
     * `false` means the projector falls back to drop+create with a
     * `RENAME_OVERLAY_STRUCTURAL_MISMATCH` warning — the synthesiser
     * could not project at least one residual difference (today: table
     * metadata drift, plus the T5 cross-object dependency cases).
     */
    val renamable: Boolean,
    /** Operator-friendly summary of the deltas surfaced when the candidate is rejected. */
    val structuralDifferences: List<String>,
    /**
     * Non-null when another table in the diff still references the old
     * name (e.g. an FK targeting `users_old` after `users_old -> users`).
     * The projector treats this as a fallback trigger so the cross-
     * table reference is not silently broken; T5 lifts this into a
     * dialect-aware policy decision.
     */
    val staleReferenceObject: String?,
)

/**
 * F.4 candidate metadata for a column rename. Mirrors
 * [RenameTableCandidate] but binds to a per-table column pair.
 */
internal data class RenameColumnCandidate(
    val id: String,
    val tableName: String,
    val fromColumn: String,
    val toColumn: String,
    val overlaySource: String,
    val overlayEntryId: String,
    val overlayHash: String?,
    /**
     * Same semantics as [RenameTableCandidate.renamable]: true when
     * the projector may emit a native `RenameColumn` (either source
     * and target are byte-identical, or
     * [RenameIntraObjectDeltaSynthesizer] covered every drift via
     * `postRenameDeltaOperations`).
     */
    val renamable: Boolean,
    val structuralDifferences: List<String>,
    /**
     * Non-null when an index, constraint, or primary-key change in the
     * same table touches either the old or the new column name. The
     * projector falls back to drop+add so the dependency is not
     * silently re-pointed at the new name (T5 cross-object territory).
     */
    val referencingObject: String?,
)

internal data class RenameTablePlanningItem(
    val candidate: RenameTableCandidate,
    override val fallbackOperations: List<DiffOperation> = emptyList(),
    override val postRenameDeltaOperations: List<DiffOperation> = emptyList(),
) : RenamePlanningItem {
    override val overlaySource: String get() = candidate.overlaySource
    override val overlayEntryId: String get() = candidate.overlayEntryId
    override val overlayHash: String? get() = candidate.overlayHash
}

internal data class RenameColumnPlanningItem(
    val candidate: RenameColumnCandidate,
    override val fallbackOperations: List<DiffOperation> = emptyList(),
    override val postRenameDeltaOperations: List<DiffOperation> = emptyList(),
) : RenamePlanningItem {
    override val overlaySource: String get() = candidate.overlaySource
    override val overlayEntryId: String get() = candidate.overlayEntryId
    override val overlayHash: String? get() = candidate.overlayHash
}
