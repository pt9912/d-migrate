package dev.dmigrate.format.data

/**
 * Context passed to every [TypeConverter] invocation.
 *
 * Bundles the per-value metadata that individual converters may need.
 * Not all converters use every field — e.g. [StringConverter] ignores
 * everything except [value] (passed separately).
 */
data class ConversionContext(
    val hint: JdbcTypeHint,
    val table: String,
    val columnName: String,
    val isCsvSource: Boolean,
)

/**
 * Converts a format-typed input value (JSON/YAML/CSV) into the Java
 * value expected by `PreparedStatement.setObject()` for a specific
 * JDBC type.
 *
 * Implementations are stateless singletons registered in
 * [dev.dmigrate.format.data.converters.TypeConverterRegistry].
 */
fun interface TypeConverter {
    fun convert(ctx: ConversionContext, value: Any): Any?
}
