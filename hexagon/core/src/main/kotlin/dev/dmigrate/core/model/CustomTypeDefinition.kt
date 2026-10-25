package dev.dmigrate.core.model

data class CustomTypeDefinition(
    val kind: CustomTypeKind,
    val values: List<String>? = null,
    val fields: Map<String, ColumnDefinition>? = null,
    val baseType: String? = null,
    val precision: Int? = null,
    val scale: Int? = null,
    val check: String? = null,
    val description: String? = null,
    /** Nur `DOMAIN`: Standardwert der Domain. Nur PostgreSQL wertet ihn aus (Issue #59). */
    val default: DefaultValue? = null,
    /** Nur `DOMAIN`: die Domain verbietet `NULL` -- Eigenschaft der Domain, nicht der Spalte. */
    val notNull: Boolean = false,
    /** Nur `DOMAIN`: Kollation, die vom Basistyp abweicht. */
    val collation: String? = null,
)

enum class CustomTypeKind {
    ENUM, COMPOSITE, DOMAIN
}
