package dev.dmigrate.mcp.auth

/**
 * Method-level scope authorization per `ImpPlan-0.9.6-B.md` §12.9 +
 * §12.14.
 *
 * Both transports (HTTP route and `McpServiceImpl` for stdio) look
 * up the required scope set via the configured [scopeMapping]
 * (typically `McpServerConfig.scopeMapping`); unknown methods
 * fail-closed to `dmigrate:admin`. Initialize and
 * `notifications/initialized` are scope-free and handled by the
 * caller's special-case (not via this checker).
 */
object ScopeChecker {

    private const val FAIL_CLOSED_FALLBACK_SCOPE = "dmigrate:admin"

    /**
     * Methods that bypass the scope check entirely: without these,
     * a fresh client could not bootstrap the session that would
     * eventually make its tool calls scope-valid (§12.14).
     */
    val SCOPE_FREE_METHODS: Set<String> = setOf(
        "initialize",
        "notifications/initialized",
    )

    fun isScopeFree(method: String): Boolean = method in SCOPE_FREE_METHODS

    fun requiredScopes(method: String, scopeMapping: Map<String, Set<String>>): Set<String> =
        scopeMapping[method] ?: setOf(FAIL_CLOSED_FALLBACK_SCOPE)

    /** True when [granted] is a superset of [required]. */
    fun isSatisfied(granted: Set<String>, required: Set<String>): Boolean =
        granted.containsAll(required)
}
