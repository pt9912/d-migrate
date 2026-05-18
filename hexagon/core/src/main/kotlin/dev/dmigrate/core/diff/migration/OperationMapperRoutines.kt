package dev.dmigrate.core.diff.migration

import dev.dmigrate.core.diff.FunctionDiff
import dev.dmigrate.core.diff.ProcedureDiff
import dev.dmigrate.core.diff.SchemaDiff
import dev.dmigrate.core.model.SchemaDefinition

/**
 * Routine + trigger mapping helpers extracted from [OperationMapper] so
 * the parent file stays under Detekt's `LargeClass` threshold. Pure
 * builders — no rendering or SQL emission happens here. The split is
 * mechanical: each `map*` function lifts straight from the original
 * `OperationMapper.map*` body without behaviour change.
 */
internal object OperationMapperRoutines {

    fun mapFunctions(
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
            if (changed.hasSignatureChange()) {
                val drop = DiffOperation.DropFunction(
                    id = OperationIdFactory.makeId("DropFunction", ref, before.toString()),
                    objectRef = ref,
                    function = before,
                )
                val create = DiffOperation.CreateFunction(
                    id = OperationIdFactory.makeId("CreateFunction", ref, after.toString()),
                    objectRef = ref,
                    function = after,
                    dependencies = setOf(drop.id),
                )
                ops += drop
                ops += create
            } else {
                ops += DiffOperation.ReplaceFunction(
                    id = OperationIdFactory.makeId("ReplaceFunction", ref, changed.toString()),
                    objectRef = ref,
                    before = before,
                    after = after,
                )
            }
        }
    }

    fun mapProcedures(
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
            if (changed.hasSignatureChange()) {
                val drop = DiffOperation.DropProcedure(
                    id = OperationIdFactory.makeId("DropProcedure", ref, before.toString()),
                    objectRef = ref,
                    procedure = before,
                )
                val create = DiffOperation.CreateProcedure(
                    id = OperationIdFactory.makeId("CreateProcedure", ref, after.toString()),
                    objectRef = ref,
                    procedure = after,
                    dependencies = setOf(drop.id),
                )
                ops += drop
                ops += create
            } else {
                ops += DiffOperation.ReplaceProcedure(
                    id = OperationIdFactory.makeId("ReplaceProcedure", ref, changed.toString()),
                    objectRef = ref,
                    before = before,
                    after = after,
                )
            }
        }
    }

    fun mapTriggers(
        diff: SchemaDiff,
        current: SchemaDefinition,
        desired: SchemaDefinition,
        ops: MutableList<DiffOperation>,
        triggerPlanningContext: TriggerPlanningContext = TriggerPlanningContext(),
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
            // E.2 Sub-Slice A.3: when the target dialect cannot render
            // `CREATE OR REPLACE TRIGGER` natively, the renderer falls
            // back to DROP + CREATE which leaves a short window in
            // which the trigger does not fire. The Mapper marks both
            // directions symmetrically — the Down inverse of a
            // Drop+Create Up is itself a Drop+Create with the same
            // gap — so strict-mode consumers see the gap regardless
            // of render direction.
            val hasGap = triggerPlanningContext.replaceMode == TriggerReplaceMode.DROP_CREATE_FALLBACK
            val risks = if (hasGap) {
                OperationRisks(
                    up = OperationRisk(hasGap = true),
                    down = OperationRisk(hasGap = true),
                )
            } else {
                OperationRisks(up = OperationRisk.SAFE, down = OperationRisk.SAFE)
            }
            ops += DiffOperation.ReplaceTrigger(
                id = OperationIdFactory.makeId("ReplaceTrigger", ref, changed.toString()),
                objectRef = ref,
                before = before,
                after = after,
                risks = risks,
            )
        }
    }

    private fun FunctionDiff.hasSignatureChange(): Boolean =
        parameters != null || returns != null || language != null

    private fun ProcedureDiff.hasSignatureChange(): Boolean =
        parameters != null || language != null
}
