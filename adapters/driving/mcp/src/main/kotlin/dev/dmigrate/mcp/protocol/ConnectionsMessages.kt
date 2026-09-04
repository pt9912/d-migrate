package dev.dmigrate.mcp.protocol

/**
 * MCP `connections/list` request shape
 * (ImpPlan-1.2.0-mcp-policy-file-and-connections-list.md Slice B).
 * Scope `dmigrate:admin`.
 *
 * @property tenantId optional — addresses a tenant other than the
 *   caller's own [dev.dmigrate.server.core.principal.PrincipalContext.effectiveTenantId].
 *   Must be in [dev.dmigrate.server.core.principal.PrincipalContext.allowedTenantIds];
 *   otherwise the call fails with a tenant-scope error. Omitted =
 *   the caller's own tenant, same as every other `*_list` surface.
 * @property pageSize optional, default/cap per [dev.dmigrate.mcp.registry.ListToolHelpers].
 * @property cursor opaque, HMAC-sealed continuation token from a
 *   previous response.
 * @property checkLive opt-in (default `false`): when `true`, each
 *   returned connection gets a real, short-timeout reachability probe
 *   (AE-B1/AE-B2) instead of staying metadata-only.
 */
data class ConnectionsListParams(
    val tenantId: String? = null,
    val pageSize: Int? = null,
    val cursor: String? = null,
    val checkLive: Boolean = false,
)

/**
 * MCP `connections/list` response. `nextCursor == null` means "no
 * more pages".
 */
data class ConnectionsListResult(
    val connections: List<ConnectionListEntry>,
    val nextCursor: String? = null,
)

/**
 * One connection's projection. Deliberately minimal (AE-B6): no
 * `credentialRef`/`providerRef`/`allowedPrincipalIds`/`allowedScopes` —
 * those are internal config details, not needed for an admin listing.
 *
 * @property status `null` when the request had `checkLive=false`;
 *   otherwise one of `REACHABLE`/`UNREACHABLE`/`CREDENTIAL_ERROR`
 *   (AE-B3 — never a raw exception message, no host/port/network
 *   detail).
 */
data class ConnectionListEntry(
    val connectionId: String,
    val displayName: String,
    val dialectId: String,
    val sensitivity: String,
    val status: String? = null,
)
