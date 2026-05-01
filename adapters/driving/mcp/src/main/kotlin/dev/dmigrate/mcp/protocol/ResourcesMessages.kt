package dev.dmigrate.mcp.protocol

/**
 * MCP `resources/list` request shape per the 2025-11-25 specification +
 * `ImpPlan-0.9.6-B.md` §6.9. `cursor` is the opaque continuation token
 * from a previous response; `null`/absent means "start from the
 * beginning". Phase B encodes the cursor as Base64-URL-safe JSON of
 * `{kind, innerToken}` (see `ResourcesListCursor`); clients treat it
 * as opaque.
 */
data class ResourcesListParams(
    val cursor: String? = null,
)

/**
 * MCP `resources/list` response. `nextCursor == null` means "no more
 * pages". `resources` may be empty even when `nextCursor != null` if
 * principal-filtering excluded every record on the underlying store
 * page — clients SHOULD continue paging until `nextCursor == null`.
 */
data class ResourcesListResult(
    val resources: List<Resource>,
    val nextCursor: String? = null,
)

/**
 * MCP `Resource` entry. `mimeType` is required by clients that
 * dispatch `resources/read` based on it; Phase B advertises
 * `application/json` for every projection.
 */
data class Resource(
    val uri: String,
    val name: String,
    val mimeType: String,
    val description: String? = null,
)

/**
 * MCP `resources/templates/list` request shape. Phase B returns a
 * static list of 7 templates and ignores the cursor.
 */
data class ResourcesTemplatesListParams(
    val cursor: String? = null,
)

data class ResourcesTemplatesListResult(
    val resourceTemplates: List<ResourceTemplate>,
    val nextCursor: String? = null,
)

/**
 * MCP resource template per `ImpPlan-0.9.6-B.md` §5.5 + §6.9. The
 * `uriTemplate` follows MCP-RFC-6570 — `{tenantId}` and friends are
 * placeholders the client substitutes before invoking
 * `resources/read`.
 */
data class ResourceTemplate(
    val uriTemplate: String,
    val name: String,
    val mimeType: String,
    val description: String? = null,
)
