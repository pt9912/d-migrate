package dev.dmigrate.core.model

data class ProcedureDefinition(
    val description: String? = null,
    val parameters: List<ParameterDefinition> = emptyList(),
    val language: String? = null,
    val body: String? = null,
    val dependencies: DependencyInfo? = null,
    val sourceDialect: String? = null
)
