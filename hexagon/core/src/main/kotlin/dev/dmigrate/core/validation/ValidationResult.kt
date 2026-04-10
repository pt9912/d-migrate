package dev.dmigrate.core.validation

data class ValidationError(
    val code: String,
    val message: String,
    val objectPath: String
)

data class ValidationWarning(
    val code: String,
    val message: String,
    val objectPath: String
)

data class ValidationResult(
    val errors: List<ValidationError> = emptyList(),
    val warnings: List<ValidationWarning> = emptyList()
) {
    val isValid: Boolean get() = errors.isEmpty()
}
