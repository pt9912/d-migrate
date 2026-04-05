package dev.dmigrate.core.model

data class CustomTypeDefinition(
    val kind: CustomTypeKind,
    val values: List<String>? = null,
    val fields: Map<String, ColumnDefinition>? = null,
    val baseType: String? = null,
    val precision: Int? = null,
    val scale: Int? = null,
    val check: String? = null,
    val description: String? = null
)

enum class CustomTypeKind {
    ENUM, COMPOSITE, DOMAIN
}
