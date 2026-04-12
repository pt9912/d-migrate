package dev.dmigrate.core.model

data class ParameterDefinition(
    val name: String,
    val type: String,
    val direction: ParameterDirection = ParameterDirection.IN
)

enum class ParameterDirection {
    IN, OUT, INOUT
}
