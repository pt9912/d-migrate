package dev.dmigrate.format.data

/**
 * Converts format-typed input values to writer-ready values for a target table.
 */
interface ValueDeserializer {
    fun deserialize(
        table: String,
        columnName: String,
        value: Any?,
        isCsvSource: Boolean = false,
    ): Any?
}
