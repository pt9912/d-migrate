package dev.dmigrate.core.model

data class FunctionDefinition(
    val description: String? = null,
    val parameters: List<ParameterDefinition> = emptyList(),
    val returns: ReturnType? = null,
    val language: String? = null,
    val deterministic: Boolean? = null,
    val body: String? = null,
    val dependencies: DependencyInfo? = null,
    val sourceDialect: String? = null
)

data class ReturnType(
    val type: String,
    val precision: Int? = null,
    val scale: Int? = null
)
