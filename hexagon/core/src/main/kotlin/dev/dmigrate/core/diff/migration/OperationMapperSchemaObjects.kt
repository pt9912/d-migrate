package dev.dmigrate.core.diff.migration

import dev.dmigrate.core.diff.SchemaDiff
import dev.dmigrate.core.model.SchemaDefinition

/**
 * Custom-type + sequence mapping helpers extracted from
 * [OperationMapper] so the parent file stays under Detekt's
 * `LargeClass` threshold. Pure builders — no rendering or SQL emission
 * happens here. The split is mechanical: each `map*` function lifts
 * straight from the original `OperationMapper.map*` body without
 * behaviour change.
 */
internal object OperationMapperSchemaObjects {

    fun mapCustomTypes(
        diff: SchemaDiff,
        current: SchemaDefinition,
        desired: SchemaDefinition,
        ops: MutableList<DiffOperation>,
    ) {
        for (added in diff.customTypesAdded) {
            val ref = DiffObjectRef(DiffObjectType.CUSTOM_TYPE, listOf(added.name))
            ops += DiffOperation.CreateCustomType(
                id = OperationIdFactory.makeId("CreateCustomType", ref, CanonicalPayload.customType(added.definition)),
                objectRef = ref,
                customType = added.definition,
            )
        }
        for (removed in diff.customTypesRemoved) {
            val ref = DiffObjectRef(DiffObjectType.CUSTOM_TYPE, listOf(removed.name))
            ops += DiffOperation.DropCustomType(
                id = OperationIdFactory.makeId("DropCustomType", ref, CanonicalPayload.customType(removed.definition)),
                objectRef = ref,
                customType = removed.definition,
            )
        }
        for (changed in diff.customTypesChanged) {
            val ref = DiffObjectRef(DiffObjectType.CUSTOM_TYPE, listOf(changed.name))
            val before = current.customTypes[changed.name] ?: continue
            val after = desired.customTypes[changed.name] ?: continue
            ops += DiffOperation.AlterCustomType(
                id = OperationIdFactory.makeId(
                    "AlterCustomType",
                    ref,
                    "before=" + CanonicalPayload.customType(before) +
                        "->after=" + CanonicalPayload.customType(after),
                ),
                objectRef = ref,
                before = before,
                after = after,
            )
        }
    }

    fun mapSequences(
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
}
