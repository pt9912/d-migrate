package dev.dmigrate.mcp.registry

/**
 * MCP resource projection per `ImpPlan-0.9.6-B.md` §5.5 + §6.9. Phase
 * B's [ResourceRegistry] does not persist live `ResourceDescriptor`
 * instances — those follow in AP 6.9 from store projections. This
 * class is the type contract Phase C/D handlers will populate.
 *
 * @param uri canonical MCP URI (e.g.
 *  `dmigrate://tenants/{tenantId}/jobs/{jobId}`). `tenantId` in the
 *  URI is addressing, NOT authorisation (§5.5).
 * @param name human-readable label.
 * @param mimeType MIME type for `resources/read` payload negotiation.
 * @param description optional description; ends up in `resources/list`.
 * @param requiredScopes scope set the principal must hold for
 *  `resources/read` to succeed. Read-list is `dmigrate:read` (§12.9).
 */
data class ResourceDescriptor(
    val uri: String,
    val name: String,
    val mimeType: String,
    val description: String? = null,
    val requiredScopes: Set<String> = emptySet(),
)

/**
 * MCP resource template per §5.5 + §6.9 — supplies the URI shape with
 * `{placeholder}` slots so clients can construct concrete resource
 * URIs without out-of-band knowledge.
 *
 * @param uriTemplate MCP-RFC-6570 template (e.g.
 *  `dmigrate://tenants/{tenantId}/jobs/{jobId}`).
 * @param name human-readable label.
 * @param mimeType MIME type the templated resource will return.
 * @param description optional description.
 * @param requiredScopes scope set required for `resources/read` on a
 *  concrete URI matching this template.
 */
data class ResourceTemplateDescriptor(
    val uriTemplate: String,
    val name: String,
    val mimeType: String,
    val description: String? = null,
    val requiredScopes: Set<String> = emptySet(),
)
