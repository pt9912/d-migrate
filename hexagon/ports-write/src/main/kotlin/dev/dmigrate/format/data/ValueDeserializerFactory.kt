package dev.dmigrate.format.data

import dev.dmigrate.driver.data.TargetColumn

/**
 * Factory port for table-scoped [ValueDeserializer] instances.
 */
interface ValueDeserializerFactory {
    fun create(
        targetColumns: List<TargetColumn>,
        readOptions: FormatReadOptions,
    ): ValueDeserializer
}
