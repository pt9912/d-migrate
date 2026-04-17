package dev.dmigrate.core.model

data class ConstraintDefinition(
    val name: String,
    val type: ConstraintType,
    val columns: List<String>? = null,
    /**
     * Raw SQL expression for CHECK/EXCLUDE constraints.
     *
     * **Trusted Input**: This value is interpolated directly into generated
     * DDL without sanitization. It originates from schema YAML files authored
     * by the schema owner. See `docs/ImpPlan-0.9.1-A.md` §4.3.
     */
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
