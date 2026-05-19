package dev.dmigrate.core.diff.migration

/**
 * F.4 Sub-Slice A.1: marker attached to `Drop*`/`Create*` operations
 * that the Mapper emitted as a `RenameSupport.DropCreateFallback`
 * substitute for a logical rename. Reports project this internal
 * metadatum into the shared [RenameProjectionReport]; the operation-
 * level field is not serialised directly into `migration-plan.v1`
 * outside the gated `renameProjections` carrier.
 *
 * Fields mirror the [RenameProjectionReport] entry contract so the
 * projection step is a structural copy, not a derivation:
 *
 * - [candidateId]: stable per-overlay-entry id (the
 *   `RenameMappingOverlayEntry.id`).
 * - [objectType]: rename target kind (`VIEW`/`TRIGGER`/`FUNCTION`/
 *   `PROCEDURE`/`SEQUENCE`).
 * - [fromPath] / [toPath]: visible name paths (single-element for
 *   schema-wide objects).
 * - [overlaySource]: e.g. file path, `"cli-inline"`.
 * - [overlayEntryId]: mandatory — a single overlay can hold several
 *   rename mappings, so the entry-level id is the authoritative
 *   provenance pin.
 * - [overlayHash]: optional document hash; auxiliary, not authoritative.
 * - [fallbackReason]: human-readable rationale ("MySQL has no
 *   `ALTER TRIGGER … RENAME`", etc.).
 */
/**
 * Visible on the public `DiffOperation` API surface because the data
 * classes that carry the field (`CreateView`/`DropView`/...) are
 * themselves public. "Internal" in the plan refers to the
 * `migration-plan.v1` artifact contract: serialisers ignore this
 * field; the only public projection is via the `RenameProjectionReport`
 * carrier on `DiffResult.renameProjections`.
 */
data class RenameProvenance(
    val candidateId: String,
    val objectType: DiffObjectType,
    val fromPath: List<String>,
    val toPath: List<String>,
    val overlaySource: String,
    val overlayEntryId: String,
    val overlayHash: String?,
    val fallbackReason: String,
)
