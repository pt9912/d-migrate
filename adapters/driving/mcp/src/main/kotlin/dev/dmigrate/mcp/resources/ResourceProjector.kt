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
 * Phase B `resources/read` content projections per §5.5 + §6.9.
 *
 * Where [ResourceProjector] yields the small `Resource` envelope used
 * by `resources/list`, this projector yields the JSON map that
 * becomes the `text` body of `resources/read`. The two share one
 * security responsibility — secrets MUST NOT cross the projection
 * boundary; the connection projection in particular drops
 * `credentialRef`, `providerRef` and any JDBC URL.
 *
 * The shape is intentionally minimal: identifiers, status/timestamps,
 * the canonical URI plus a few descriptive fields per kind. Phase D
 * may add typed payload sections (e.g. inline schema bodies) by
 * appending to the same map; clients MUST tolerate unknown fields.
 *
 * Returned maps may contain nullable values; the caller serialises
 * them with Gson which renders `null` literally — that's intentional
 * so clients can distinguish "field absent" (older server) from
 * "field present, no value" (e.g. no `jobRef` for an ad-hoc upload).
 */
internal object ResourceContentProjector {

    fun projectContent(job: JobRecord): Map<String, Any?> = mapOf(
        "uri" to job.resourceUri.render(),
        "tenantId" to job.tenantId.value,
        "jobId" to job.managedJob.jobId,
        "operation" to job.managedJob.operation,
        "status" to job.managedJob.status.name,
        "visibility" to job.visibility.name,
        "createdAt" to job.managedJob.createdAt.toString(),
        "updatedAt" to job.managedJob.updatedAt.toString(),
        "expiresAt" to job.managedJob.expiresAt.toString(),
        "createdBy" to job.managedJob.createdBy,
        "artifacts" to job.managedJob.artifacts,
        // Operators reading a FAILED or in-flight job through
        // resources/read need the failure cause and the latest
        // progress phase — same fields tools/job_status_get exposes,
        // so the two surfaces stay coherent.
        "error" to job.managedJob.error?.let {
            mapOf("code" to it.code, "message" to it.message, "exitCode" to it.exitCode)
        },
        "progress" to job.managedJob.progress?.let {
            mapOf("phase" to it.phase, "numericValues" to it.numericValues)
        },
    )

    fun projectContent(artifact: ArtifactRecord): Map<String, Any?> = mapOf(
        "uri" to artifact.resourceUri.render(),
        "tenantId" to artifact.tenantId.value,
        "artifactId" to artifact.managedArtifact.artifactId,
        "filename" to artifact.managedArtifact.filename,
        "kind" to artifact.kind.name,
        "contentType" to artifact.managedArtifact.contentType,
        "sizeBytes" to artifact.managedArtifact.sizeBytes,
        "sha256" to artifact.managedArtifact.sha256,
        "visibility" to artifact.visibility.name,
        "createdAt" to artifact.managedArtifact.createdAt.toString(),
        "expiresAt" to artifact.managedArtifact.expiresAt.toString(),
        "jobRef" to artifact.jobRef,
    )

    fun projectContent(schema: SchemaIndexEntry): Map<String, Any?> = mapOf(
        "uri" to schema.resourceUri.render(),
        "tenantId" to schema.tenantId.value,
        "schemaId" to schema.schemaId,
        "displayName" to schema.displayName,
        "artifactRef" to schema.artifactRef,
        "createdAt" to schema.createdAt.toString(),
        "expiresAt" to schema.expiresAt.toString(),
        "jobRef" to schema.jobRef,
        "labels" to schema.labels,
    )

    fun projectContent(profile: ProfileIndexEntry): Map<String, Any?> = mapOf(
        "uri" to profile.resourceUri.render(),
        "tenantId" to profile.tenantId.value,
        "profileId" to profile.profileId,
        "displayName" to profile.displayName,
        "artifactRef" to profile.artifactRef,
        "createdAt" to profile.createdAt.toString(),
        "expiresAt" to profile.expiresAt.toString(),
        "jobRef" to profile.jobRef,
        "labels" to profile.labels,
    )

    fun projectContent(diff: DiffIndexEntry): Map<String, Any?> = mapOf(
        "uri" to diff.resourceUri.render(),
        "tenantId" to diff.tenantId.value,
        "diffId" to diff.diffId,
        "displayName" to diff.displayName,
        "artifactRef" to diff.artifactRef,
        "sourceRef" to diff.sourceRef,
        "targetRef" to diff.targetRef,
        "createdAt" to diff.createdAt.toString(),
        "expiresAt" to diff.expiresAt.toString(),
        "jobRef" to diff.jobRef,
        "labels" to diff.labels,
    )

    /**
     * Connection content — same secret-free contract as
     * [ResourceProjector.project]. `credentialRef`, `providerRef`,
     * `allowedPrincipalIds` and `allowedScopes` are deliberately
     * dropped: they are authorization metadata, not connection
     * surface that callers of `resources/read` need.
     */
    fun projectContent(connection: ConnectionReference): Map<String, Any?> = mapOf(
        "uri" to connection.resourceUri.render(),
        "tenantId" to connection.tenantId.value,
        "connectionId" to connection.connectionId,
        "displayName" to connection.displayName,
        "dialectId" to connection.dialectId,
        "sensitivity" to connection.sensitivity.name,
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
