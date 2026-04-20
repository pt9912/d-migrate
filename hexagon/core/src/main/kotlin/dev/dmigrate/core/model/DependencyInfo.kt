package dev.dmigrate.core.model

data class DependencyInfo(
    val tables: List<String> = emptyList(),
    val views: List<String> = emptyList(),
    val columns: Map<String, List<String>> = emptyMap(),
    val functions: List<String> = emptyList(),
)
