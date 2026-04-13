package dev.dmigrate.core.diff

import dev.dmigrate.core.model.DefaultValue
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.ReferenceDefinition

data class ColumnDiff(
    val name: String,
    val type: ValueChange<NeutralType>? = null,
    val required: ValueChange<Boolean>? = null,
    val default: ValueChange<DefaultValue?>? = null,
    val unique: ValueChange<Boolean>? = null,
    val references: ValueChange<ReferenceDefinition?>? = null,
) {
    fun hasChanges(): Boolean =
        type != null || required != null || default != null ||
            unique != null || references != null
}
