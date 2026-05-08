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

    /**
     * True when the principal may invoke a method requiring [required].
     *
     * - [isAdmin] short-circuits the check: a principal carrying
     *   `dmigrate:admin` (and therefore `isAdmin = true` per
     *   [ClaimsMapper]) is unrestricted across all method-level scope
     *   gates. This matches both the §12.14 wording ("admin scope is
     *   highest privilege") and the route-level
     *   `AuthMode.DISABLED → ANONYMOUS_PRINCIPAL` flow, where the
     *   principal carries `isAdmin = true` but does not enumerate every
     *   method-specific scope. Without the bypass the service-layer
     *   re-check would silently veto every read tool that the route
     *   already greenlit, yielding inconsistent decisions across the
     *   two enforcement layers.
     * - When [isAdmin] is `false`, the original superset check applies.
     */
    fun isSatisfied(granted: Set<String>, required: Set<String>, isAdmin: Boolean = false): Boolean =
        isAdmin || granted.containsAll(required)
}
