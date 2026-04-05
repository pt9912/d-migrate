package dev.dmigrate.core.model

data class SequenceDefinition(
    val description: String? = null,
    val start: Long = 1,
    val increment: Long = 1,
    val minValue: Long? = null,
    val maxValue: Long? = null,
    val cycle: Boolean = false,
    val cache: Int? = null
)
