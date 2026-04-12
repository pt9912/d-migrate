package dev.dmigrate.core.model

data class ConstraintDefinition(
    val name: String,
    val type: ConstraintType,
    val columns: List<String>? = null,
    val expression: String? = null,
    val references: ConstraintReferenceDefinition? = null
)

enum class ConstraintType {
    CHECK, UNIQUE, EXCLUDE, FOREIGN_KEY
}

data class ConstraintReferenceDefinition(
    val table: String,
    val columns: List<String>,
    val onDelete: ReferentialAction? = null,
    val onUpdate: ReferentialAction? = null
)
