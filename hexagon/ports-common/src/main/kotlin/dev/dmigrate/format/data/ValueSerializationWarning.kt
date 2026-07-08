package dev.dmigrate.format.data

/**
 * Warning emitted while mapping a database value to a format-neutral
 * representation.
 */
data class ValueSerializationWarning(
    val code: String,
    val table: String,
    val column: String,
    val javaClass: String,
    val message: String,
)
