package dev.dmigrate.format.data

import dev.dmigrate.driver.data.TargetColumn

class DefaultValueDeserializerFactory : ValueDeserializerFactory {
    override fun create(
        targetColumns: List<TargetColumn>,
        readOptions: FormatReadOptions,
    ): ValueDeserializer {
        val hints = targetColumns.associate { it.name to JdbcTypeHint(it.jdbcType, it.sqlTypeName) }
        return DefaultValueDeserializer(typeHintOf = { hints[it] }, csvNullString = readOptions.csvNullString)
    }
}
