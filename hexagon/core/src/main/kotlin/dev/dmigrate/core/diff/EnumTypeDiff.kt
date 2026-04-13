package dev.dmigrate.core.diff

data class EnumTypeDiff(
    val name: String,
    val values: ValueChange<List<String>>,
)
