package dev.dmigrate.core.model

/**
 * E.1 Routine-Migration Slice A: routine identity for functions
 * comprises the [body] (hash-compared via [RoutineBodyNormalizer]
 * downstream) PLUS the signature/security/definer/search-path
 * attributes below. Any difference in those attributes triggers a
 * `ReplaceFunction` even when the body hashes are equal — they are
 * part of the routine's externally observable contract.
 *
 * All new attributes are nullable so existing schemas that don't
 * declare them keep their behaviour: a missing attribute means
 * "dialect default applies", not "any value matches".
 */
data class FunctionDefinition(
    val description: String? = null,
    val parameters: List<ParameterDefinition> = emptyList(),
    val returns: ReturnType? = null,
    val language: String? = null,
    val deterministic: Boolean? = null,
    val body: String? = null,
    val dependencies: DependencyInfo? = null,
    val sourceDialect: String? = null,
    /**
     * Routine execution privilege: `INVOKER` (runs as the calling
     * role; PostgreSQL default for functions) vs `DEFINER` (runs as
     * the routine's owner). Part of the identity because a
     * silent flip between the two would change effective privilege
     * scope.
     */
    val security: RoutineSecurity? = null,
    /**
     * For `SECURITY = DEFINER` routines, the role that owns the
     * routine. Null means the dialect-default owner applies.
     */
    val definer: String? = null,
    /**
     * Ordered `SET search_path TO ...` entries for PostgreSQL.
     * Pinning the search-path is the standard safety mitigation for
     * SECURITY DEFINER functions — operators encode it explicitly so
     * dependency-rewriting works on a known schema list.
     */
    val searchPath: List<String>? = null,
    /**
     * MySQL `sql_mode` snapshot at routine creation time. Stored as
     * the canonical comma-joined string; a diff in this string
     * triggers ReplaceFunction even when the body is identical.
     */
    val sqlMode: String? = null,
)

/**
 * Execution-privilege selector for stored functions and procedures.
 */
enum class RoutineSecurity {
    INVOKER,
    DEFINER,
}

data class ReturnType(
    val type: String,
    val precision: Int? = null,
    val scale: Int? = null
)
