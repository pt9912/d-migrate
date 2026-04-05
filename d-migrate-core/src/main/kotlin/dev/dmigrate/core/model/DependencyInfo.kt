package dev.dmigrate.core.model

data class DependencyInfo(
    val tables: List<String> = emptyList(),
    val columns: Map<String, List<String>> = emptyMap()
)
