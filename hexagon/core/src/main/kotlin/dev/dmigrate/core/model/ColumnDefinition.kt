package dev.dmigrate.core.model

data class ColumnDefinition(
    val type: NeutralType,
    val required: Boolean = false,
    val unique: Boolean = false,
    val default: DefaultValue? = null,
    val references: ReferenceDefinition? = null
)
