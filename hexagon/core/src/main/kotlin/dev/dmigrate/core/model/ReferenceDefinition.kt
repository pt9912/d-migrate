package dev.dmigrate.core.model

data class ReferenceDefinition(
    val table: String,
    val column: String,
    val onDelete: ReferentialAction? = null,
    val onUpdate: ReferentialAction? = null
)

enum class ReferentialAction {
    RESTRICT, CASCADE, SET_NULL, SET_DEFAULT, NO_ACTION
}
