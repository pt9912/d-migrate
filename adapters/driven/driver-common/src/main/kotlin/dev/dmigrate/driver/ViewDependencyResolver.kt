package dev.dmigrate.driver

import dev.dmigrate.core.model.ViewDefinition

internal object ViewDependencyResolver {

    fun declaredViewDependencies(
        name: String,
        view: ViewDefinition,
        viewNames: Set<String>,
    ): Set<String> = view.dependencies?.views
        ?.asSequence()
        ?.filter { it != name && it in viewNames }
        ?.toSet()
        ?: emptySet()

    fun inferViewDependenciesFromQuery(
        name: String,
        query: String?,
        viewNames: Set<String>,
    ): Set<String> {
        if (query.isNullOrBlank()) return emptySet()

        val regex = Regex(
            """(?i)\b(?:from|join)\s+([`"]?[A-Za-z_][A-Za-z0-9_]*[`"]?(?:\.[`"]?[A-Za-z_][A-Za-z0-9_]*[`"]?)?)"""
        )
        return regex.findAll(query)
            .map { it.groupValues[1] }
            .map { ref -> ref.substringAfterLast('.') }
            .map { ref -> ref.trim('`', '"') }
            .filter { it != name && it in viewNames }
            .toSet()
    }
}
