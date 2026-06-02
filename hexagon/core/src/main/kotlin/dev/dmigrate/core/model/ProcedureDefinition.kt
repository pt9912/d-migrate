package dev.dmigrate.core.model

/**
 * E.1 Routine-Migration Slice A: see [FunctionDefinition] for the
 * security/definer/searchPath/sqlMode contract. Procedures share the
 * same identity attributes; Slice B wires the Replace path for them.
 */
data class ProcedureDefinition(
    val description: String? = null,
    val parameters: List<ParameterDefinition> = emptyList(),
    val language: String? = null,
    val body: String? = null,
    val dependencies: DependencyInfo? = null,
    val sourceDialect: String? = null,
    val security: RoutineSecurity? = null,
    val definer: String? = null,
    val searchPath: List<String>? = null,
    val sqlMode: String? = null,
)
