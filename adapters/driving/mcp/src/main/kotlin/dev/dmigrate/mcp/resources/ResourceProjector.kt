package dev.dmigrate.mcp.resources

import dev.dmigrate.mcp.protocol.Resource
import dev.dmigrate.mcp.registry.ResourceTemplateDescriptor
import dev.dmigrate.server.core.artifact.ArtifactRecord
import dev.dmigrate.server.core.connection.ConnectionReference
import dev.dmigrate.server.core.job.JobRecord
import dev.dmigrate.server.ports.DiffIndexEntry
import dev.dmigrate.server.ports.ProfileIndexEntry
import dev.dmigrate.server.ports.SchemaIndexEntry

/**
 * Pure projections from store records to MCP `resources/list` wire
 * shape per `ImpPlan-0.9.6-B.md` §5.5 + §6.9.
 *
 * Connection references are projected WITHOUT secrets (no
 * `credentialRef`, `providerRef`, JDBC URL — only `connectionId`,
 * `displayName`, `dialectId`, `sensitivity`). §6.9 acceptance:
 * "Connection-Refs ohne Secrets projizieren".
 *
 * Every MCP resource carries `mimeType=application/json` because
 * `resources/read` in Phase C/D will return the underlying record as
 * JSON. The mime is uniform across kinds — no transport-specific
 * variation.
 */
internal object ResourceProjector {

    private const val JSON_MIME = "application/json"

    fun project(job: JobRecord): Resource = Resource(
        uri = job.resourceUri.render(),
        name = job.managedJob.jobId,
        mimeType = JSON_MIME,
        description = "Job '${job.managedJob.operation}' (status=${job.managedJob.status.name})",
    )

    fun project(artifact: ArtifactRecord): Resource = Resource(
        uri = artifact.resourceUri.render(),
        name = artifact.managedArtifact.filename,
        mimeType = JSON_MIME,
        description = "Artifact (${artifact.kind.name}, ${artifact.managedArtifact.sizeBytes} bytes)",
    )

    fun project(schema: SchemaIndexEntry): Resource = Resource(
        uri = schema.resourceUri.render(),
        name = schema.displayName,
        mimeType = JSON_MIME,
        description = "Schema (id=${schema.schemaId})",
    )

    fun project(profile: ProfileIndexEntry): Resource = Resource(
        uri = profile.resourceUri.render(),
        name = profile.displayName,
        mimeType = JSON_MIME,
        description = "Profile (id=${profile.profileId})",
    )

    fun project(diff: DiffIndexEntry): Resource = Resource(
        uri = diff.resourceUri.render(),
        name = diff.displayName,
        mimeType = JSON_MIME,
        description = "Diff (id=${diff.diffId})",
    )

    /**
     * Connection projection — strictly secret-free. Includes
     * `dialectId` and `sensitivity` because clients use them to pick
     * non-production targets / surface appropriate UI badges; never
     * the actual JDBC URL or credential reference.
     */
    fun project(connection: ConnectionReference): Resource = Resource(
        uri = connection.resourceUri.render(),
        name = connection.displayName,
        mimeType = JSON_MIME,
        description = "Connection (dialect=${connection.dialectId}, sensitivity=${connection.sensitivity.name})",
    )
}

/**
 * Static templates per `ImpPlan-0.9.6-B.md` §5.5 + §6.9. Phase B
 * publishes one template per resource family plus the chunk template
 * (acceptance: "Templates enthalten Chunk-URIs"). The list is ordered
 * deterministically — important for golden-test stability and for
 * client UIs that surface templates in registration order.
 *
 * The list is `ResourceTemplateDescriptor` (registry-shape, with
 * `requiredScopes`) rather than the `ResourceTemplate` wire shape, so
 * `PhaseBRegistries.resourceRegistry()` can register them as the
 * single source of truth for `resources/templates/list`. The wire
 * projection happens in `McpServiceImpl.toWireTemplate(...)`.
 */
internal object PhaseBResourceTemplates {

    private const val JSON_MIME = "application/json"
    private val READ_SCOPE: Set<String> = setOf("dmigrate:read")

    val ALL: List<ResourceTemplateDescriptor> = listOf(
        ResourceTemplateDescriptor(
            uriTemplate = "dmigrate://tenants/{tenantId}/jobs/{jobId}",
            name = "Job",
            mimeType = JSON_MIME,
            description = "Job metadata, status and progress",
            requiredScopes = READ_SCOPE,
        ),
        ResourceTemplateDescriptor(
            uriTemplate = "dmigrate://tenants/{tenantId}/artifacts/{artifactId}",
            name = "Artifact",
            mimeType = JSON_MIME,
            description = "Artifact metadata and inline content (small payloads)",
            requiredScopes = READ_SCOPE,
        ),
        ResourceTemplateDescriptor(
            uriTemplate = "dmigrate://tenants/{tenantId}/artifacts/{artifactId}/chunks/{chunkId}",
            name = "Artifact chunk",
            mimeType = JSON_MIME,
            description = "Streamable chunk for large artifacts; iterate via successive chunkId values",
            requiredScopes = READ_SCOPE,
        ),
        ResourceTemplateDescriptor(
            uriTemplate = "dmigrate://tenants/{tenantId}/schemas/{schemaId}",
            name = "Schema",
            mimeType = JSON_MIME,
            description = "Schema metadata and content",
            requiredScopes = READ_SCOPE,
        ),
        ResourceTemplateDescriptor(
            uriTemplate = "dmigrate://tenants/{tenantId}/profiles/{profileId}",
            name = "Profile",
            mimeType = JSON_MIME,
            description = "Data profile metadata and content",
            requiredScopes = READ_SCOPE,
        ),
        ResourceTemplateDescriptor(
            uriTemplate = "dmigrate://tenants/{tenantId}/diffs/{diffId}",
            name = "Diff",
            mimeType = JSON_MIME,
            description = "Schema diff metadata and content",
            requiredScopes = READ_SCOPE,
        ),
        ResourceTemplateDescriptor(
            uriTemplate = "dmigrate://tenants/{tenantId}/connections/{connectionId}",
            name = "Connection reference",
            mimeType = JSON_MIME,
            description = "Connection metadata; never carries credentials or JDBC URLs",
            requiredScopes = READ_SCOPE,
        ),
    )
}
