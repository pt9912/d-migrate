package dev.dmigrate.core.diff.migration

import dev.dmigrate.core.diff.ColumnDiff
import dev.dmigrate.core.diff.SchemaDiff
import dev.dmigrate.core.diff.TableDiff
import dev.dmigrate.core.model.ConstraintType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition

/**
 * Maps a [SchemaDiff] to a flat list of [DiffOperation]s with stable
 * IDs but no dependency edges yet. The [DiffPlanner] orchestrates,
 * [DependencyAnalyzer] then attaches edges, [TopologicalSorter]
 * orders the result.
 *
 * `CHECK` and `EXCLUDE` constraints are explicitly skipped (Phase A
 * decision: tables carrying them are surfaced via
 * `CONSTRAINT_NOT_DIFFABLE` blockers; the comparator does not lossless-
 * diff their semantics).
 */
internal object OperationMapper {

    fun map(
        diff: SchemaDiff,
        current: SchemaDefinition,
        desired: SchemaDefinition,
        blockedTables: Set<String>,
    ): List<DiffOperation> {
        val ops = mutableListOf<DiffOperation>()
        mapCustomTypes(diff, current, desired, ops)
        mapTables(diff, blockedTables, ops)
        mapViews(diff, current, desired, ops)
        mapSequences(diff, current, desired, ops)
        mapFunctions(diff, current, desired, ops)
        mapProcedures(diff, current, desired, ops)
        mapTriggers(diff, current, desired, ops)
        return ops
    }

    private fun mapCustomTypes(
        diff: SchemaDiff,
        current: SchemaDefinition,
        desired: SchemaDefinition,
        ops: MutableList<DiffOperation>,
    ) {
        for (added in diff.customTypesAdded) {
            val ref = DiffObjectRef(DiffObjectType.CUSTOM_TYPE, listOf(added.name))
            ops += DiffOperation.CreateCustomType(
                id = OperationIdFactory.makeId("CreateCustomType", ref, added.definition.toString()),
                objectRef = ref,
                customType = added.definition,
            )
        }
        for (removed in diff.customTypesRemoved) {
            val ref = DiffObjectRef(DiffObjectType.CUSTOM_TYPE, listOf(removed.name))
            ops += DiffOperation.DropCustomType(
                id = OperationIdFactory.makeId("DropCustomType", ref, removed.definition.toString()),
                objectRef = ref,
                customType = removed.definition,
            )
        }
        for (changed in diff.customTypesChanged) {
            val ref = DiffObjectRef(DiffObjectType.CUSTOM_TYPE, listOf(changed.name))
            val before = current.customTypes[changed.name] ?: continue
            val after = desired.customTypes[changed.name] ?: continue
            ops += DiffOperation.AlterCustomType(
                id = OperationIdFactory.makeId("AlterCustomType", ref, changed.toString()),
                objectRef = ref,
                before = before,
                after = after,
            )
        }
    }

    private fun mapTables(
        diff: SchemaDiff,
        blockedTables: Set<String>,
        ops: MutableList<DiffOperation>,
    ) {
        for (added in diff.tablesAdded) {
            if (added.name in blockedTables) continue
            val ref = DiffObjectRef(DiffObjectType.TABLE, listOf(added.name))
            ops += DiffOperation.CreateTable(
                id = OperationIdFactory.makeId("CreateTable", ref, canonicalTablePayload(added.definition)),
                objectRef = ref,
                table = added.definition,
            )
        }
        for (removed in diff.tablesRemoved) {
            if (removed.name in blockedTables) continue
            val ref = DiffObjectRef(DiffObjectType.TABLE, listOf(removed.name))
            ops += DiffOperation.DropTable(
                id = OperationIdFactory.makeId("DropTable", ref, canonicalTablePayload(removed.definition)),
                objectRef = ref,
                table = removed.definition,
            )
        }
        for (changed in diff.tablesChanged) {
            if (changed.name in blockedTables) continue
            mapTableColumns(changed, ops)
            mapTableConstraints(changed, ops)
            mapTableIndices(changed, ops)
            mapTablePrimaryKey(changed, ops)
        }
    }

    private fun mapTableColumns(table: TableDiff, ops: MutableList<DiffOperation>) {
        for ((name, def) in table.columnsAdded) {
            val ref = DiffObjectRef(DiffObjectType.COLUMN, listOf(table.name, name))
            ops += DiffOperation.AddColumn(
                id = OperationIdFactory.makeId("AddColumn", ref, def.toString()),
                objectRef = ref,
                column = def,
            )
        }
        for ((name, def) in table.columnsRemoved) {
            val ref = DiffObjectRef(DiffObjectType.COLUMN, listOf(table.name, name))
            ops += DiffOperation.DropColumn(
                id = OperationIdFactory.makeId("DropColumn", ref, def.toString()),
                objectRef = ref,
                column = def,
            )
        }
        for (cd in table.columnsChanged) mapColumnChange(table.name, cd, ops)
    }

    private fun mapColumnChange(tableName: String, cd: ColumnDiff, ops: MutableList<DiffOperation>) {
        val ref = DiffObjectRef(DiffObjectType.COLUMN, listOf(tableName, cd.name))
        cd.type?.let {
            ops += DiffOperation.AlterColumnType(
                id = OperationIdFactory.makeId("AlterColumnType", ref, "${it.before}->${it.after}"),
                objectRef = ref,
                before = it.before,
                after = it.after,
            )
        }
        cd.required?.let {
            ops += DiffOperation.AlterColumnNullability(
                id = OperationIdFactory.makeId("AlterColumnNullability", ref, "${it.before}->${it.after}"),
                objectRef = ref,
                before = it.before,
                after = it.after,
            )
        }
        cd.default?.let {
            ops += DiffOperation.AlterColumnDefault(
                id = OperationIdFactory.makeId("AlterColumnDefault", ref, "${it.before}->${it.after}"),
                objectRef = ref,
                before = it.before,
                after = it.after,
            )
        }
    }

    private fun mapTableConstraints(table: TableDiff, ops: MutableList<DiffOperation>) {
        for (c in table.constraintsAdded) {
            if (c.type == ConstraintType.CHECK || c.type == ConstraintType.EXCLUDE) continue
            val ref = DiffObjectRef(DiffObjectType.CONSTRAINT, listOf(table.name, c.name))
            ops += DiffOperation.AddConstraint(
                id = OperationIdFactory.makeId("AddConstraint", ref, c.toString()),
                objectRef = ref,
                constraint = c,
            )
        }
        for (c in table.constraintsRemoved) {
            if (c.type == ConstraintType.CHECK || c.type == ConstraintType.EXCLUDE) continue
            val ref = DiffObjectRef(DiffObjectType.CONSTRAINT, listOf(table.name, c.name))
            ops += DiffOperation.DropConstraint(
                id = OperationIdFactory.makeId("DropConstraint", ref, c.toString()),
                objectRef = ref,
                constraint = c,
            )
        }
        for (vc in table.constraintsChanged) {
            val refOld = DiffObjectRef(DiffObjectType.CONSTRAINT, listOf(table.name, vc.before.name))
            ops += DiffOperation.DropConstraint(
                id = OperationIdFactory.makeId("DropConstraint", refOld, vc.before.toString()),
                objectRef = refOld,
                constraint = vc.before,
            )
            val refNew = DiffObjectRef(DiffObjectType.CONSTRAINT, listOf(table.name, vc.after.name))
            ops += DiffOperation.AddConstraint(
                id = OperationIdFactory.makeId("AddConstraint", refNew, vc.after.toString()),
                objectRef = refNew,
                constraint = vc.after,
            )
        }
    }

    private fun mapTableIndices(table: TableDiff, ops: MutableList<DiffOperation>) {
        for (idx in table.indicesAdded) {
            val ref = indexRef(table.name, idx.name, idx.columns.joinToString("_"))
            ops += DiffOperation.AddIndex(
                id = OperationIdFactory.makeId("AddIndex", ref, idx.toString()),
                objectRef = ref,
                index = idx,
            )
        }
        for (idx in table.indicesRemoved) {
            val ref = indexRef(table.name, idx.name, idx.columns.joinToString("_"))
            ops += DiffOperation.DropIndex(
                id = OperationIdFactory.makeId("DropIndex", ref, idx.toString()),
                objectRef = ref,
                index = idx,
            )
        }
        for (vc in table.indicesChanged) {
            val refOld = indexRef(table.name, vc.before.name, vc.before.columns.joinToString("_"))
            ops += DiffOperation.DropIndex(
                id = OperationIdFactory.makeId("DropIndex", refOld, vc.before.toString()),
                objectRef = refOld,
                index = vc.before,
            )
            val refNew = indexRef(table.name, vc.after.name, vc.after.columns.joinToString("_"))
            ops += DiffOperation.AddIndex(
                id = OperationIdFactory.makeId("AddIndex", refNew, vc.after.toString()),
                objectRef = refNew,
                index = vc.after,
            )
        }
    }

    private fun indexRef(tableName: String, indexName: String?, columnsKey: String): DiffObjectRef =
        DiffObjectRef(
            DiffObjectType.INDEX,
            listOf(tableName, indexName ?: "anon_$columnsKey"),
        )

    private fun mapTablePrimaryKey(table: TableDiff, ops: MutableList<DiffOperation>) {
        val pk = table.primaryKey ?: return
        val ref = DiffObjectRef(DiffObjectType.PRIMARY_KEY, listOf(table.name))
        if (pk.before.isNotEmpty()) {
            ops += DiffOperation.DropPrimaryKey(
                id = OperationIdFactory.makeId("DropPrimaryKey", ref, pk.before.joinToString(",")),
                objectRef = ref,
                columns = pk.before,
            )
        }
        if (pk.after.isNotEmpty()) {
            ops += DiffOperation.AddPrimaryKey(
                id = OperationIdFactory.makeId("AddPrimaryKey", ref, pk.after.joinToString(",")),
                objectRef = ref,
                columns = pk.after,
            )
        }
    }

    private fun mapViews(
        diff: SchemaDiff,
        current: SchemaDefinition,
        desired: SchemaDefinition,
        ops: MutableList<DiffOperation>,
    ) {
        for (added in diff.viewsAdded) {
            val ref = DiffObjectRef(DiffObjectType.VIEW, listOf(added.name))
            ops += DiffOperation.CreateView(
                id = OperationIdFactory.makeId("CreateView", ref, added.definition.toString()),
                objectRef = ref,
                view = added.definition,
            )
        }
        for (removed in diff.viewsRemoved) {
            val ref = DiffObjectRef(DiffObjectType.VIEW, listOf(removed.name))
            ops += DiffOperation.DropView(
                id = OperationIdFactory.makeId("DropView", ref, removed.definition.toString()),
                objectRef = ref,
                view = removed.definition,
            )
        }
        for (changed in diff.viewsChanged) {
            val ref = DiffObjectRef(DiffObjectType.VIEW, listOf(changed.name))
            val before = current.views[changed.name] ?: continue
            val after = desired.views[changed.name] ?: continue
            ops += DiffOperation.ReplaceView(
                id = OperationIdFactory.makeId("ReplaceView", ref, changed.toString()),
                objectRef = ref,
                before = before,
                after = after,
            )
        }
    }

    private fun mapSequences(
        diff: SchemaDiff,
        current: SchemaDefinition,
        desired: SchemaDefinition,
        ops: MutableList<DiffOperation>,
    ) {
        for (added in diff.sequencesAdded) {
            val ref = DiffObjectRef(DiffObjectType.SEQUENCE, listOf(added.name))
            ops += DiffOperation.CreateSequence(
                id = OperationIdFactory.makeId("CreateSequence", ref, added.definition.toString()),
                objectRef = ref,
                sequence = added.definition,
            )
        }
        for (removed in diff.sequencesRemoved) {
            val ref = DiffObjectRef(DiffObjectType.SEQUENCE, listOf(removed.name))
            ops += DiffOperation.DropSequence(
                id = OperationIdFactory.makeId("DropSequence", ref, removed.definition.toString()),
                objectRef = ref,
                sequence = removed.definition,
            )
        }
        for (changed in diff.sequencesChanged) {
            val ref = DiffObjectRef(DiffObjectType.SEQUENCE, listOf(changed.name))
            val before = current.sequences[changed.name] ?: continue
            val after = desired.sequences[changed.name] ?: continue
            ops += DiffOperation.AlterSequence(
                id = OperationIdFactory.makeId("AlterSequence", ref, changed.toString()),
                objectRef = ref,
                before = before,
                after = after,
            )
        }
    }

    private fun mapFunctions(
        diff: SchemaDiff,
        current: SchemaDefinition,
        desired: SchemaDefinition,
        ops: MutableList<DiffOperation>,
    ) {
        for (added in diff.functionsAdded) {
            val ref = DiffObjectRef(DiffObjectType.FUNCTION, listOf(added.name))
            ops += DiffOperation.CreateFunction(
                id = OperationIdFactory.makeId("CreateFunction", ref, added.definition.toString()),
                objectRef = ref,
                function = added.definition,
            )
        }
        for (removed in diff.functionsRemoved) {
            val ref = DiffObjectRef(DiffObjectType.FUNCTION, listOf(removed.name))
            ops += DiffOperation.DropFunction(
                id = OperationIdFactory.makeId("DropFunction", ref, removed.definition.toString()),
                objectRef = ref,
                function = removed.definition,
            )
        }
        for (changed in diff.functionsChanged) {
            val ref = DiffObjectRef(DiffObjectType.FUNCTION, listOf(changed.name))
            val before = current.functions[changed.name] ?: continue
            val after = desired.functions[changed.name] ?: continue
            ops += DiffOperation.ReplaceFunction(
                id = OperationIdFactory.makeId("ReplaceFunction", ref, changed.toString()),
                objectRef = ref,
                before = before,
                after = after,
            )
        }
    }

    private fun mapProcedures(
        diff: SchemaDiff,
        current: SchemaDefinition,
        desired: SchemaDefinition,
        ops: MutableList<DiffOperation>,
    ) {
        for (added in diff.proceduresAdded) {
            val ref = DiffObjectRef(DiffObjectType.PROCEDURE, listOf(added.name))
            ops += DiffOperation.CreateProcedure(
                id = OperationIdFactory.makeId("CreateProcedure", ref, added.definition.toString()),
                objectRef = ref,
                procedure = added.definition,
            )
        }
        for (removed in diff.proceduresRemoved) {
            val ref = DiffObjectRef(DiffObjectType.PROCEDURE, listOf(removed.name))
            ops += DiffOperation.DropProcedure(
                id = OperationIdFactory.makeId("DropProcedure", ref, removed.definition.toString()),
                objectRef = ref,
                procedure = removed.definition,
            )
        }
        for (changed in diff.proceduresChanged) {
            val ref = DiffObjectRef(DiffObjectType.PROCEDURE, listOf(changed.name))
            val before = current.procedures[changed.name] ?: continue
            val after = desired.procedures[changed.name] ?: continue
            ops += DiffOperation.ReplaceProcedure(
                id = OperationIdFactory.makeId("ReplaceProcedure", ref, changed.toString()),
                objectRef = ref,
                before = before,
                after = after,
            )
        }
    }

    private fun mapTriggers(
        diff: SchemaDiff,
        current: SchemaDefinition,
        desired: SchemaDefinition,
        ops: MutableList<DiffOperation>,
    ) {
        for (added in diff.triggersAdded) {
            val ref = DiffObjectRef(DiffObjectType.TRIGGER, listOf(added.name))
            ops += DiffOperation.CreateTrigger(
                id = OperationIdFactory.makeId("CreateTrigger", ref, added.definition.toString()),
                objectRef = ref,
                trigger = added.definition,
            )
        }
        for (removed in diff.triggersRemoved) {
            val ref = DiffObjectRef(DiffObjectType.TRIGGER, listOf(removed.name))
            ops += DiffOperation.DropTrigger(
                id = OperationIdFactory.makeId("DropTrigger", ref, removed.definition.toString()),
                objectRef = ref,
                trigger = removed.definition,
            )
        }
        for (changed in diff.triggersChanged) {
            val ref = DiffObjectRef(DiffObjectType.TRIGGER, listOf(changed.name))
            val before = current.triggers[changed.name] ?: continue
            val after = desired.triggers[changed.name] ?: continue
            ops += DiffOperation.ReplaceTrigger(
                id = OperationIdFactory.makeId("ReplaceTrigger", ref, changed.toString()),
                objectRef = ref,
                before = before,
                after = after,
            )
        }
    }

    private fun canonicalTablePayload(table: TableDefinition): String =
        "cols=${table.columns.size},pk=${table.primaryKey.joinToString(",")}," +
            "constraints=${table.constraints.size},indices=${table.indices.size}"
}
