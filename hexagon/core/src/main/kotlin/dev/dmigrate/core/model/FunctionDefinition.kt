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
    /**
     * F3 (docs/planning/in-progress/sample-db-roundtrip-findings.md):
     * PostgreSQL function volatility — `IMMUTABLE` / `STABLE` / `VOLATILE`.
     * Part of the externally observable contract (an IMMUTABLE function may
     * be inlined/cached); silently dropping it on round-trip degrades it to
     * the `VOLATILE` default. Null means "dialect default applies".
     * `deterministic` (MySQL) stays a separate, coarser boolean.
     */
    val volatility: FunctionVolatility? = null,
    /**
     * F3: PostgreSQL `STRICT` (a.k.a. `RETURNS NULL ON NULL INPUT`) — the
     * function returns NULL whenever any argument is NULL. A real behavioural
     * attribute; null/false means the `CALLED ON NULL INPUT` default applies.
     */
    val strict: Boolean? = null,
)

/**
 * Execution-privilege selector for stored functions and procedures.
 */
enum class RoutineSecurity {
    INVOKER,
    DEFINER,
}

/**
 * PostgreSQL function volatility category (`pg_proc.provolatile`): the
 * planner's purity contract. `IMMUTABLE` (same args → same result, no DB
 * reads), `STABLE` (consistent within one statement), `VOLATILE` (default;
 * may have side effects / differ per call).
 */
enum class FunctionVolatility {
    IMMUTABLE,
    STABLE,
    VOLATILE,
}

data class ReturnType(
    val type: String,
    val precision: Int? = null,
    val scale: Int? = null
)
