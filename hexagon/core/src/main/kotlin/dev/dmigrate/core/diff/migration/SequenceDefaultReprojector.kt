package dev.dmigrate.core.diff.migration

import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.DefaultValue

/**
 * F.4 Sub-Slice D (2026-05-19) + E.3 Sub-Slice D (2026-05-20):
 * post-map step that rewrites `DefaultValue.SequenceNextVal`
 * references in `CreateTable`, `AddColumn` and `AlterColumnDefault`
 * operations whenever the same plan renames the referenced sequence,
 * either via a native [DiffOperation.RenameSequence] (PostgreSQL —
 * `ALTER SEQUENCE … RENAME TO …`) or via the
 * `DropSequence + CreateSequence` Drop-Create fallback that MySQL's
 * `MysqlObjectRenamePolicy` produces (helper-table emulation has no
 * native rename grammar). The fallback pair carries the
 * source / target names on the `renameProvenance` field of both
 * halves; only the `CreateSequence` side contributes to the rewrite
 * map because its `objectRef.rootName` is also the target name the
 * [DependencyAnalyzer] uses for the dependency edge.
 *
 * Without this step, a plan that combines a rename (or its
 * fallback) with a `CreateTable` whose column default references the
 * source name (because the desired schema still uses the old name,
 * or the column is freshly created and the diff carries the
 * current-state default) would emit `CREATE TABLE … DEFAULT
 * nextval('old')` after the sequence has already been renamed —
 * which fails at runtime because `old` no longer exists. The
 * reprojector rewrites the reference to `new` so the emitted SQL is
 * consistent with the renamed sequence; the [DependencyAnalyzer]
 * then attaches an explicit edge from the column-bearing op to the
 * `RenameSequence` (or the fallback's `CreateSequence`) so the
 * topological sort places the rename first.
 *
 * The rewrite is **deterministic** and **order-independent**: the
 * Mapper today emits `CreateTable` / `AddColumn` / `AlterColumnDefault`
 * before it folds the rename overlay (so the column ops carry the
 * pre-rename sequence name when this step runs), but the reprojector
 * does not rely on that order — it walks the full ops list once,
 * picks up every `RenameSequence` op as a rewrite source, and applies
 * the rewrite to every column-bearing op regardless of relative
 * position in the list.
 *
 * Out of scope (deliberately):
 *
 * - Existing columns in the live database whose default references
 *   the old sequence name. PostgreSQL stores `nextval('seq')`
 *   defaults as OID references at column-creation time, so a
 *   subsequent `ALTER SEQUENCE … RENAME` is transparent for already-
 *   live columns. Only ops emitted in the *same plan* need the
 *   textual rewrite.
 *
 * - Op subtypes other than the three the plan specifies. If a future
 *   subtype carries a column-default payload (e.g. a synthetic
 *   table-rebuild op), it must be added here explicitly rather than
 *   relying on a catch-all walk.
 */
internal object SequenceDefaultReprojector {

    /**
     * Returns a new ops list where every `SequenceNextVal` default in
     * `CreateTable` / `AddColumn` / `AlterColumnDefault` that
     * references a sequence renamed by some `RenameSequence` op in
     * [ops] — OR by a Drop-Create fallback pair whose
     * `CreateSequence` half carries a sequence-typed
     * `renameProvenance` — points at the rename's target name. Other
     * ops pass through unchanged. When neither a `RenameSequence`
     * nor a fallback-tagged `CreateSequence` is present, the
     * original list is returned identity-equal.
     */
    fun apply(ops: List<DiffOperation>): List<DiffOperation> {
        val renames = ops.filterIsInstance<DiffOperation.RenameSequence>()
        // E.3 Sub-Slice D: pick up the Drop-Create fallback that
        // MySQL's policy produces. The `CreateSequence` half carries
        // a sequence-typed `RenameProvenance` whose `fromPath[0]` /
        // `toPath[0]` are the source / target names; the
        // `DropSequence` half carries the same provenance but does
        // not contribute to the rewrite map (its rootName is the
        // source, not the target).
        val fallbackCreates = ops.filterIsInstance<DiffOperation.CreateSequence>()
            .filter { it.renameProvenance?.objectType == DiffObjectType.SEQUENCE }
        if (renames.isEmpty() && fallbackCreates.isEmpty()) return ops
        val rewriteMap: Map<String, String> = buildMap {
            for (r in renames) put(r.fromName, r.toName)
            for (create in fallbackCreates) {
                val provenance = create.renameProvenance ?: continue
                val from = provenance.fromPath.singleOrNull() ?: continue
                val to = provenance.toPath.singleOrNull() ?: continue
                // Native + fallback should not name the same source
                // twice in the same plan (the Mapper picks exactly
                // one strategy per overlay entry). `put` overwrites
                // defensively if it ever does.
                put(from, to)
            }
        }
        return ops.map { op -> rewrite(op, rewriteMap) }
    }

    private fun rewrite(op: DiffOperation, rewriteMap: Map<String, String>): DiffOperation = when (op) {
        is DiffOperation.CreateTable -> rewriteCreateTable(op, rewriteMap)
        is DiffOperation.AddColumn -> rewriteAddColumn(op, rewriteMap)
        is DiffOperation.AlterColumnDefault -> rewriteAlterColumnDefault(op, rewriteMap)
        else -> op
    }

    private fun rewriteCreateTable(
        op: DiffOperation.CreateTable,
        rewriteMap: Map<String, String>,
    ): DiffOperation.CreateTable {
        val updatedColumns = op.table.columns.mapValues { (_, col) -> rewriteColumn(col, rewriteMap) }
        if (updatedColumns == op.table.columns) return op
        // Preserve column-map insertion order: mapValues already does
        // this for `Map<String, ColumnDefinition>`. The op id stays
        // stable — it was derived from the pre-rewrite payload and we
        // do not recompute it, mirroring the contract for RenameTable
        // which keeps the rename-target op id even though the renamed
        // table's identity changes.
        return op.copy(table = op.table.copy(columns = updatedColumns))
    }

    private fun rewriteAddColumn(
        op: DiffOperation.AddColumn,
        rewriteMap: Map<String, String>,
    ): DiffOperation.AddColumn {
        val rewritten = rewriteColumn(op.column, rewriteMap)
        return if (rewritten === op.column) op else op.copy(column = rewritten)
    }

    private fun rewriteAlterColumnDefault(
        op: DiffOperation.AlterColumnDefault,
        rewriteMap: Map<String, String>,
    ): DiffOperation.AlterColumnDefault {
        val newBefore = rewriteDefault(op.before, rewriteMap)
        val newAfter = rewriteDefault(op.after, rewriteMap)
        if (newBefore == op.before && newAfter == op.after) return op
        return op.copy(before = newBefore, after = newAfter)
    }

    private fun rewriteColumn(col: ColumnDefinition, rewriteMap: Map<String, String>): ColumnDefinition {
        val newDefault = rewriteDefault(col.default, rewriteMap)
        return if (newDefault == col.default) col else col.copy(default = newDefault)
    }

    private fun rewriteDefault(default: DefaultValue?, rewriteMap: Map<String, String>): DefaultValue? {
        val seq = default as? DefaultValue.SequenceNextVal ?: return default
        val newName = rewriteMap[seq.sequenceName] ?: return default
        return DefaultValue.SequenceNextVal(newName)
    }
}
