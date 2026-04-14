package dev.dmigrate.core.diff

import dev.dmigrate.core.model.SequenceDefinition

data class NamedSequence(val name: String, val definition: SequenceDefinition)

data class SequenceDiff(
    val name: String,
    val start: ValueChange<Long>? = null,
    val increment: ValueChange<Long>? = null,
    val minValue: ValueChange<Long?>? = null,
    val maxValue: ValueChange<Long?>? = null,
    val cycle: ValueChange<Boolean>? = null,
    val cache: ValueChange<Int?>? = null,
) {
    fun hasChanges(): Boolean = start != null || increment != null || minValue != null ||
        maxValue != null || cycle != null || cache != null
}
