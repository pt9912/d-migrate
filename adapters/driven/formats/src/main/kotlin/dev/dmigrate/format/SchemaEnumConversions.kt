package dev.dmigrate.format

import dev.dmigrate.core.model.*

internal fun String.toReferentialAction(): ReferentialAction = when (lowercase()) {
    "restrict" -> ReferentialAction.RESTRICT
    "cascade" -> ReferentialAction.CASCADE
    "set_null" -> ReferentialAction.SET_NULL
    "set_default" -> ReferentialAction.SET_DEFAULT
    "no_action" -> ReferentialAction.NO_ACTION
    else -> throw IllegalArgumentException("Unknown referential action: $this")
}

internal fun String.toIndexType(): IndexType = when (lowercase()) {
    "btree" -> IndexType.BTREE
    "hash" -> IndexType.HASH
    "gin" -> IndexType.GIN
    "gist" -> IndexType.GIST
    "brin" -> IndexType.BRIN
    else -> throw IllegalArgumentException("Unknown index type: $this")
}

internal fun String.toConstraintType(): ConstraintType = when (lowercase()) {
    "check" -> ConstraintType.CHECK
    "unique" -> ConstraintType.UNIQUE
    "exclude" -> ConstraintType.EXCLUDE
    "foreign_key" -> ConstraintType.FOREIGN_KEY
    else -> throw IllegalArgumentException("Unknown constraint type: $this")
}

internal fun String.toPartitionType(): PartitionType = when (lowercase()) {
    "range" -> PartitionType.RANGE
    "hash" -> PartitionType.HASH
    "list" -> PartitionType.LIST
    else -> throw IllegalArgumentException("Unknown partition type: $this")
}

internal fun String.toCustomTypeKind(): CustomTypeKind = when (lowercase()) {
    "enum" -> CustomTypeKind.ENUM
    "composite" -> CustomTypeKind.COMPOSITE
    "domain" -> CustomTypeKind.DOMAIN
    else -> throw IllegalArgumentException("Unknown custom type kind: $this")
}

internal fun String.toParameterDirection(): ParameterDirection = when (lowercase()) {
    "in" -> ParameterDirection.IN
    "out" -> ParameterDirection.OUT
    "inout" -> ParameterDirection.INOUT
    else -> throw IllegalArgumentException("Unknown parameter direction: $this")
}

internal fun String.toTriggerEvent(): TriggerEvent = when (lowercase()) {
    "insert" -> TriggerEvent.INSERT
    "update" -> TriggerEvent.UPDATE
    "delete" -> TriggerEvent.DELETE
    else -> throw IllegalArgumentException("Unknown trigger event: $this")
}

internal fun String.toTriggerTiming(): TriggerTiming = when (lowercase()) {
    "before" -> TriggerTiming.BEFORE
    "after" -> TriggerTiming.AFTER
    "instead_of" -> TriggerTiming.INSTEAD_OF
    else -> throw IllegalArgumentException("Unknown trigger timing: $this")
}

internal fun String.toTriggerForEach(): TriggerForEach = when (lowercase()) {
    "row" -> TriggerForEach.ROW
    "statement" -> TriggerForEach.STATEMENT
    else -> throw IllegalArgumentException("Unknown trigger for_each: $this")
}
