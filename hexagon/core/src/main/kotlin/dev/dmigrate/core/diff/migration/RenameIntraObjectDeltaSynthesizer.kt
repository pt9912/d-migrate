package dev.dmigrate.core.diff.migration

import dev.dmigrate.core.diff.TableComparator
import dev.dmigrate.core.diff.TableDiff
import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.IndexDefinition
import dev.dmigrate.core.model.TableDefinition

/**
 * F.4 dependency-projection T4: synthesises the intra-object delta
 * operations that accompany a rename whose source and target are NOT
 * structurally identical.
 *
 * Example — table rename `users_old → users` where `users` adds a new
 * `created_at` column: the projector emits `RenameTable(users_old →
 * users)` AND an `AddColumn(users.created_at)` whose
 * `dependencies = setOf(rename.id)`. The dependency edge keeps the
 * synthetic op anchored to the rename so the topological sorter places
 * it strictly after the `Rename*`.
 *
 * **In scope (T4)**:
 *
 * - Table rename + column add / drop / change → emit standard
 *   `AddColumn` / `DropColumn` / `AlterColumn*` against the **new**
 *   table name.
 * - Table rename + index drift → emit `AddIndex` / `DropIndex`.
 *   Changed indices map to `DropIndex(before) + AddIndex(after)`.
 * - Table rename + constraint drift (UNIQUE / FK / multi-column;
 *   CHECK and EXCLUDE are skipped — same exclusion as
 *   [OperationMapper.mapTableConstraints]).
 * - Table rename + primary-key reshape.
 * - Column rename + type / nullability / default drift on the
 *   renamed column → emit `AlterColumn*` ops against
 *   `(tableName, toColumn)`.
 *
 * **Carve-out (T5)**:
 *
 * - Column unique / references / generation drift → reported as
 *   residual differences; the projector falls back to drop+add.
 * - Table metadata drift → reported as residual.
 * - Cross-object dependencies (FK from other tables pointing at the
 *   renamed object, view bodies referring to the old name, …) — these
 *   are reflected via the existing `staleReferenceObject` /
 *   `referencingObject` mapper-pre-flagged signals, not by this
 *   synthesiser. View-column-dependency protection
 *   (`DiffPlanner.detectViewColumnDepsBlockers` / Plan §G.3 view
 *   split) runs on the final flat operation list and treats a
 *   synthesised `AlterColumn*` the same way it treats a regular one
 *   — see `RenameOverlayMapperT4Test`'s G.3 safety test.
 *
 * The synthesised operations carry `dependencies = setOf(candidateId)`
 * so the planner's topological sorter runs them strictly after the
 * native `Rename*`. The candidate ID is the same string the
 * projector pins on the rename operation via
 * [RenameOverlayMapper.buildRenameTableOperation] /
 * [RenameOverlayMapper.buildRenameColumnOperation]; the
 * [OperationMapper.finalizeIds] step remaps any later ID
 * disambiguation atomically.
 */
internal object RenameIntraObjectDeltaSynthesizer {

    data class SynthesisResult(
        val operations: List<DiffOperation>,
        val residualDifferences: List<String>,
    ) {
        val isComplete: Boolean get() = residualDifferences.isEmpty()

        companion object {
            val EMPTY: SynthesisResult = SynthesisResult(emptyList(), emptyList())
        }
    }

    /**
     * Synthesises the operations that bring a renamed table from
     * [before] (source-side definition) to [after] (target-side
     * definition). Each operation targets [targetTableName] and
     * carries `dependencies = setOf(candidateId)`.
     */
    fun synthesizeForTableRename(
        candidateId: String,
        targetTableName: String,
        before: TableDefinition,
        after: TableDefinition,
    ): SynthesisResult {
        val tableDiff = TableComparator().compareTable(targetTableName, before, after)
            ?: return SynthesisResult.EMPTY
        val ops = mutableListOf<DiffOperation>()
        emitColumnOps(tableDiff.name, tableDiff, candidateId, ops)
        emitConstraintOps(tableDiff.name, tableDiff, candidateId, ops)
        emitIndexOps(tableDiff.name, tableDiff, candidateId, ops)
        emitPrimaryKeyOps(tableDiff.name, tableDiff, candidateId, ops)
        val residual = mutableListOf<String>()
        if (tableDiff.metadata != null) {
            residual += "table metadata changed (${tableDiff.metadata.before} -> ${tableDiff.metadata.after})"
        }
        return SynthesisResult(ops, residual)
    }

    /**
     * Synthesises the `AlterColumn*` operations for a column rename
     * whose source and target columns differ in type, nullability, or
     * default. Each operation targets `(tableName, toColumn)` and
     * carries `dependencies = setOf(candidateId)`.
     *
     * Drift in `unique` / `references` / `generation` is *not*
     * synthesised in T4 — those attribute drifts are normally
     * absorbed by table-level constraints/indices that touch the
     * column, which puts them in T5's cross-object territory.
     */
    fun synthesizeForColumnRename(
        candidateId: String,
        tableName: String,
        toColumn: String,
        before: ColumnDefinition,
        after: ColumnDefinition,
    ): SynthesisResult {
        val ref = DiffObjectRef(DiffObjectType.COLUMN, listOf(tableName, toColumn))
        val ops = mutableListOf<DiffOperation>()
        val residual = mutableListOf<String>()

        // Emission order is type → nullability → default. No
        // dialect renderer treats these three as an ordered tuple
        // — each is its own statement, and the topo sorter only
        // orders against `dependencies`. Keep the order stable so
        // golden-file snapshots and dependency-rewrite paths stay
        // deterministic.
        if (before.type != after.type) {
            ops += DiffOperation.AlterColumnType(
                id = OperationIdFactory.makeId("AlterColumnType", ref, "${before.type}->${after.type}"),
                objectRef = ref,
                before = before.type,
                after = after.type,
                dependencies = setOf(candidateId),
            )
        }
        if (before.required != after.required) {
            ops += DiffOperation.AlterColumnNullability(
                id = OperationIdFactory.makeId(
                    "AlterColumnNullability", ref, "${before.required}->${after.required}",
                ),
                objectRef = ref,
                before = before.required,
                after = after.required,
                dependencies = setOf(candidateId),
            )
        }
        if (before.default != after.default) {
            ops += DiffOperation.AlterColumnDefault(
                id = OperationIdFactory.makeId(
                    "AlterColumnDefault", ref, "${before.default}->${after.default}",
                ),
                objectRef = ref,
                before = before.default,
                after = after.default,
                dependencies = setOf(candidateId),
            )
        }
        if (before.unique != after.unique) {
            residual += "column unique drift (${before.unique} -> ${after.unique}) " +
                "— absorbed by single-column UNIQUE constraint; T5"
        }
        if (before.references != after.references) {
            residual += "column references drift (${before.references} -> ${after.references}) " +
                "— absorbed by single-column FK constraint; T5"
        }
        if (before.generation != after.generation) {
            residual += "column generation drift (${before.generation} -> ${after.generation}) — T5"
        }
        return SynthesisResult(ops, residual)
    }

    // ── Emit helpers (mirror the OperationMapper patterns; we attach
    //    dependencies = setOf(candidateId) for the topo-sort edge) ───

    private fun emitColumnOps(
        tableName: String,
        diff: TableDiff,
        candidateId: String,
        ops: MutableList<DiffOperation>,
    ) {
        for ((name, def) in diff.columnsAdded) {
            val ref = DiffObjectRef(DiffObjectType.COLUMN, listOf(tableName, name))
            ops += DiffOperation.AddColumn(
                id = OperationIdFactory.makeId("AddColumn", ref, CanonicalPayload.column(def)),
                objectRef = ref,
                column = def,
                dependencies = setOf(candidateId),
            )
        }
        for ((name, def) in diff.columnsRemoved) {
            val ref = DiffObjectRef(DiffObjectType.COLUMN, listOf(tableName, name))
            ops += DiffOperation.DropColumn(
                id = OperationIdFactory.makeId("DropColumn", ref, CanonicalPayload.column(def)),
                objectRef = ref,
                column = def,
                dependencies = setOf(candidateId),
            )
        }
        for (cd in diff.columnsChanged) {
            val ref = DiffObjectRef(DiffObjectType.COLUMN, listOf(tableName, cd.name))
            cd.type?.let {
                ops += DiffOperation.AlterColumnType(
                    id = OperationIdFactory.makeId("AlterColumnType", ref, "${it.before}->${it.after}"),
                    objectRef = ref,
                    before = it.before,
                    after = it.after,
                    dependencies = setOf(candidateId),
                )
            }
            cd.required?.let {
                ops += DiffOperation.AlterColumnNullability(
                    id = OperationIdFactory.makeId("AlterColumnNullability", ref, "${it.before}->${it.after}"),
                    objectRef = ref,
                    before = it.before,
                    after = it.after,
                    dependencies = setOf(candidateId),
                )
            }
            cd.default?.let {
                ops += DiffOperation.AlterColumnDefault(
                    id = OperationIdFactory.makeId("AlterColumnDefault", ref, "${it.before}->${it.after}"),
                    objectRef = ref,
                    before = it.before,
                    after = it.after,
                    dependencies = setOf(candidateId),
                )
            }
        }
    }

    private fun emitConstraintOps(
        tableName: String,
        diff: TableDiff,
        candidateId: String,
        ops: MutableList<DiffOperation>,
    ) {
        // F.5 Sub-Slice A (2026-05-19): CHECK + EXCLUDE constraints
        // now ride alongside UNIQUE / FOREIGN_KEY through the
        // intra-object delta synthesiser. The per-dialect renderer
        // decides whether to render or block.
        for (c in diff.constraintsAdded) {
            val ref = DiffObjectRef(DiffObjectType.CONSTRAINT, listOf(tableName, c.name))
            ops += DiffOperation.AddConstraint(
                id = OperationIdFactory.makeId("AddConstraint", ref, CanonicalPayload.constraint(c)),
                objectRef = ref,
                constraint = c,
                dependencies = setOf(candidateId),
            )
        }
        for (c in diff.constraintsRemoved) {
            val ref = DiffObjectRef(DiffObjectType.CONSTRAINT, listOf(tableName, c.name))
            ops += DiffOperation.DropConstraint(
                id = OperationIdFactory.makeId("DropConstraint", ref, CanonicalPayload.constraint(c)),
                objectRef = ref,
                constraint = c,
                dependencies = setOf(candidateId),
            )
        }
        for (vc in diff.constraintsChanged) {
            val refOld = DiffObjectRef(DiffObjectType.CONSTRAINT, listOf(tableName, vc.before.name))
            val dropId = OperationIdFactory.makeId("DropConstraint", refOld, CanonicalPayload.constraint(vc.before))
            ops += DiffOperation.DropConstraint(
                id = dropId,
                objectRef = refOld,
                constraint = vc.before,
                dependencies = setOf(candidateId),
            )
            val refNew = DiffObjectRef(DiffObjectType.CONSTRAINT, listOf(tableName, vc.after.name))
            ops += DiffOperation.AddConstraint(
                id = OperationIdFactory.makeId("AddConstraint", refNew, CanonicalPayload.constraint(vc.after)),
                objectRef = refNew,
                constraint = vc.after,
                // Dieselbe Ordnungskante wie im OperationMapper (siehe dort
                // [ORDERING]) -- zusaetzlich zum Rename-Kandidaten, auf den beide
                // ohnehin warten.
                dependencies = setOf(candidateId, dropId),
            )
        }
    }

    private fun emitIndexOps(
        tableName: String,
        diff: TableDiff,
        candidateId: String,
        ops: MutableList<DiffOperation>,
    ) {
        for (idx in diff.indicesAdded) {
            val ref = indexRef(tableName, idx)
            ops += DiffOperation.AddIndex(
                id = OperationIdFactory.makeId("AddIndex", ref, CanonicalPayload.index(idx)),
                objectRef = ref,
                index = idx,
                dependencies = setOf(candidateId),
            )
        }
        for (idx in diff.indicesRemoved) {
            val ref = indexRef(tableName, idx)
            ops += DiffOperation.DropIndex(
                id = OperationIdFactory.makeId("DropIndex", ref, CanonicalPayload.index(idx)),
                objectRef = ref,
                index = idx,
                dependencies = setOf(candidateId),
            )
        }
        for (vc in diff.indicesChanged) {
            val refOld = indexRef(tableName, vc.before)
            val dropId = OperationIdFactory.makeId("DropIndex", refOld, CanonicalPayload.index(vc.before))
            ops += DiffOperation.DropIndex(
                id = dropId,
                objectRef = refOld,
                index = vc.before,
                dependencies = setOf(candidateId),
            )
            val refNew = indexRef(tableName, vc.after)
            ops += DiffOperation.AddIndex(
                id = OperationIdFactory.makeId("AddIndex", refNew, CanonicalPayload.index(vc.after)),
                objectRef = refNew,
                index = vc.after,
                dependencies = setOf(candidateId, dropId),
            )
        }
    }

    private fun emitPrimaryKeyOps(
        tableName: String,
        diff: TableDiff,
        candidateId: String,
        ops: MutableList<DiffOperation>,
    ) {
        // Mirrors `OperationMapper.mapTablePrimaryKey`: PK reshape
        // (both sides non-empty) emits `DropPrimaryKey` + `AddPrimaryKey`
        // — the two `isNotEmpty()` guards are independent on purpose.
        val pk = diff.primaryKey ?: return
        val ref = DiffObjectRef(DiffObjectType.PRIMARY_KEY, listOf(tableName))
        val dropId = OperationIdFactory.makeId("DropPrimaryKey", ref, pk.before.joinToString(","))
        if (pk.before.isNotEmpty()) {
            ops += DiffOperation.DropPrimaryKey(
                id = dropId,
                objectRef = ref,
                columns = pk.before,
                dependencies = setOf(candidateId),
            )
        }
        if (pk.after.isNotEmpty()) {
            ops += DiffOperation.AddPrimaryKey(
                id = OperationIdFactory.makeId("AddPrimaryKey", ref, pk.after.joinToString(",")),
                objectRef = ref,
                columns = pk.after,
                dependencies = if (pk.before.isNotEmpty()) setOf(candidateId, dropId) else setOf(candidateId),
            )
        }
    }

    private fun indexRef(tableName: String, idx: IndexDefinition): DiffObjectRef {
        val name = idx.name ?: anonIndexKey(idx)
        return DiffObjectRef(DiffObjectType.INDEX, listOf(tableName, name))
    }

    private fun anonIndexKey(idx: IndexDefinition): String {
        val cols = idx.columns.joinToString("_") { it.name }
        val whereHash = idx.where?.hashCode()?.toString(16) ?: "0"
        return "anon_${cols}_${idx.type.name}_${idx.unique}_$whereHash"
    }
}
