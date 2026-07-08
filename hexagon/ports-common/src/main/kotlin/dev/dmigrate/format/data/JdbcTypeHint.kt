package dev.dmigrate.format.data

/**
 * Per-column type hint for format import.
 *
 * The [jdbcType] integer is intentionally retained for the G1 architecture
 * gate: it is a narrow interop contract in ports/formats, not a neutral core
 * type model. See ADR 0028.
 */
data class JdbcTypeHint(
    val jdbcType: Int,
    val sqlTypeName: String? = null,
    val precision: Int? = null,
    val scale: Int? = null,
)
