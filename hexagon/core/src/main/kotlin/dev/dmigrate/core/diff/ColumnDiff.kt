package dev.dmigrate.core.diff

import dev.dmigrate.core.model.DefaultValue
import dev.dmigrate.core.model.NeutralType

data class ColumnDiff(
    val name: String,
    val type: ValueChange<NeutralType>? = null,
    val required: ValueChange<Boolean>? = null,
    val default: ValueChange<DefaultValue?>? = null,
)
