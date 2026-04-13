package dev.dmigrate.core.diff

data class ValueChange<T>(
    val before: T,
    val after: T,
)
