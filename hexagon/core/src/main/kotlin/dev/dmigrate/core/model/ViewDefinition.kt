package dev.dmigrate.core.model

data class ViewDefinition(
    val description: String? = null,
    val materialized: Boolean = false,
    val refresh: String? = null,
    val query: String? = null,
    val dependencies: DependencyInfo? = null,
    val sourceDialect: String? = null
)
