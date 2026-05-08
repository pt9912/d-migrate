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

/**
 * MCP `resources/read` request shape per the 2025-11-25 specification
 * + `ImpPlan-0.9.6-B.md` §5.5 + §6.9 + `ImpPlan-0.9.6-D.md` §5.3 + §10.7.
 *
 * `uri` MUST be a fully-resolved `dmigrate://...` URI. Plan-D §5.3
 * pins the request shape: `uri` is the ONLY accepted field —
 * `cursor`, `range`, `chunkId` and any other extension key are
 * rejected as `-32602 InvalidParams` with
 * `error.data.dmigrateCode=VALIDATION_ERROR`. The strict-shape
 * enforcement runs through [StrictReadResourceParamsAdapter];
 * carrying [unknownParameter] as a typed marker lets the
 * dispatcher build the error envelope with the same JSON-RPC code
 * + dmigrateCode pair as the rest of the resource-error chain
 * instead of the raw Gson exception lsp4j would otherwise wrap as
 * `-32700 ParseError`.
 *
 * A missing `uri` field is also `-32602` with
 * `dmigrateCode=VALIDATION_ERROR`.
 */
@com.google.gson.annotations.JsonAdapter(StrictReadResourceParamsAdapter::class)
data class ReadResourceParams(
    val uri: String? = null,
    /**
     * Plan-D §5.3: name of the first unexpected parameter the
     * client carried. Non-null means the request is malformed and
     * the dispatcher MUST reject it before the URI even parses.
     */
    val unknownParameter: String? = null,
)

/**
 * MCP `resources/read` response. `contents` is a list because the
 * spec allows multi-part resources (e.g. metadata + binary blob);
 * Phase B always returns exactly one JSON projection.
 */
data class ReadResourceResult(
    val contents: List<ResourceContents>,
)

/**
 * One content slice of a `resources/read` response. Exactly one of
 * `text` (UTF-8 string) or `blob` (Base64-encoded bytes) MUST be
 * populated. Phase B always returns `text` with
 * `mimeType=application/json`.
 */
data class ResourceContents(
    val uri: String,
    val mimeType: String,
    val text: String? = null,
    val blob: String? = null,
)
