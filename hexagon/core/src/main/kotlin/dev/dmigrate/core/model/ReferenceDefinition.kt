package dev.dmigrate.core.model

data class ReferenceDefinition(
    val table: String,
    val column: String,
    val onDelete: ReferentialAction? = null,
    val onUpdate: ReferentialAction? = null,
    /**
     * Der Name des Fremdschluessel-Constraints, wo die Quelle ihn fuehrt —
     * aus demselben Grund wie [ColumnDefinition.uniqueConstraintName]: ohne
     * ihn traefe ein `DROP CONSTRAINT` an keiner echten Datenbank etwas.
     */
    val constraintName: String? = null,
)

enum class ReferentialAction {
    RESTRICT, CASCADE, SET_NULL, SET_DEFAULT, NO_ACTION
}
