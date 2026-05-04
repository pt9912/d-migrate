package dev.dmigrate.mcp.registry

import dev.dmigrate.server.application.audit.SecretScrubber
import dev.dmigrate.server.core.artifact.ArtifactRecord
import dev.dmigrate.server.core.job.JobRecord
import dev.dmigrate.server.core.job.JobVisibility
import dev.dmigrate.server.ports.DiffIndexEntry
import dev.dmigrate.server.ports.ProfileIndexEntry
import dev.dmigrate.server.ports.SchemaIndexEntry

/**
 * Phase-D §6.4 + §10.5 list-item projections per discovery tool.
 *
 * Each projector takes a record from the underlying store and
 * returns the JSON map the list-tool wire shape requires. Every
 * user-supplied free-text field flows through [SecretScrubber] so a
 * planted Bearer / JDBC URL / approval-token cannot ride into a
 * `*_list` response — same scrubbing surface
 * [dev.dmigrate.mcp.resources.ResourceContentProjector] applies to
 * `resources/read`.
 *
 * `ownerPrincipalId` is NEVER projected per §6.4 — it is treated as
 * potential PII. Visibility is surfaced as a neutralised
 * [JobVisibility] enum string under `visibilityClass`.
 */
internal object ListItemProjector {

    fun projectJobListItem(record: JobRecord): Map<String, Any?> = mapOf(
        "jobId" to record.managedJob.jobId,
        "tenantId" to record.tenantId.value,
        "status" to record.managedJob.status.name,
        "operation" to record.managedJob.operation,
        "resourceUri" to record.resourceUri.render(),
        "createdAt" to record.managedJob.createdAt.toString(),
        "updatedAt" to record.managedJob.updatedAt.toString(),
        "expiresAt" to record.managedJob.expiresAt.toString(),
        "visibilityClass" to record.visibility.toVisibilityClass(),
        // artifactUris uses the existing artefact resource-URI shape
        // — naked ids would not match the schema's pattern. Empty
        // list is allowed (job with no artefacts is legal).
        "artifactUris" to record.managedJob.artifacts.map {
            "dmigrate://tenants/${record.tenantId.value}/artifacts/$it"
        },
    )

    fun projectArtifactListItem(record: ArtifactRecord): Map<String, Any?> {
        val tenant = record.tenantId.value
        val id = record.managedArtifact.artifactId
        return mapOf(
            "artifactId" to id,
            "tenantId" to tenant,
            "artifactKind" to record.kind.name,
            "jobId" to record.jobRef,
            "filename" to SecretScrubber.scrub(record.managedArtifact.filename),
            "sizeBytes" to record.managedArtifact.sizeBytes,
            "contentType" to SecretScrubber.scrub(record.managedArtifact.contentType),
            "resourceUri" to record.resourceUri.render(),
            // Phase-D §6.4 chunk-template surfaces the per-artifact
            // chunk URI shape so clients can build the next-chunk
            // address without out-of-band knowledge of the path
            // grammar. AP D7's resources/read recognises this URI
            // pattern.
            "chunkTemplate" to "dmigrate://tenants/$tenant/artifacts/$id/chunks/{chunkId}",
            "createdAt" to record.managedArtifact.createdAt.toString(),
            "expiresAt" to record.managedArtifact.expiresAt.toString(),
            "visibilityClass" to record.visibility.toVisibilityClass(),
        )
    }

    fun projectSchemaListItem(entry: SchemaIndexEntry): Map<String, Any?> = mapOf(
        "schemaId" to entry.schemaId,
        "tenantId" to entry.tenantId.value,
        "displayName" to SecretScrubber.scrub(entry.displayName),
        "artifactRef" to entry.artifactRef,
        "resourceUri" to entry.resourceUri.render(),
        "jobId" to entry.jobRef,
        // Plan-D §6.4 mindestfelder: `format`, `origin`, `sizeBytes`,
        // optional `hash`. Wire-shape declares them required + nullable
        // because the producer may not record every field
        // (Phase-E start tools will fill them all).
        "format" to entry.format,
        "origin" to entry.origin,
        "sizeBytes" to entry.sizeBytes,
        "hash" to entry.hash,
        "createdAt" to entry.createdAt.toString(),
        "expiresAt" to entry.expiresAt.toString(),
    )

    fun projectProfileListItem(entry: ProfileIndexEntry): Map<String, Any?> = mapOf(
        "profileId" to entry.profileId,
        "tenantId" to entry.tenantId.value,
        "displayName" to SecretScrubber.scrub(entry.displayName),
        "artifactRef" to entry.artifactRef,
        "resourceUri" to entry.resourceUri.render(),
        "jobId" to entry.jobRef,
        // Plan-D §6.4: connection identity + profiling scope are
        // mindestfelder. SecretScrubber on `scope` because operator-
        // supplied free text could carry an embedded URL/token.
        "connectionRef" to entry.connectionRef,
        "scope" to entry.scope?.let { SecretScrubber.scrub(it) },
        "warningCount" to entry.warningCount,
        "createdAt" to entry.createdAt.toString(),
        "expiresAt" to entry.expiresAt.toString(),
    )

    fun projectDiffListItem(entry: DiffIndexEntry): Map<String, Any?> = mapOf(
        "diffId" to entry.diffId,
        "tenantId" to entry.tenantId.value,
        "displayName" to SecretScrubber.scrub(entry.displayName),
        "artifactRef" to entry.artifactRef,
        "resourceUri" to entry.resourceUri.render(),
        // Plan-D §6.4 wire-names: leftSchemaId / rightSchemaId
        // (the model field is `sourceRef` / `targetRef` for
        // backward-compatibility with the staging helpers).
        "leftSchemaId" to entry.sourceRef,
        "rightSchemaId" to entry.targetRef,
        "statusSummary" to entry.statusSummary,
        "jobId" to entry.jobRef,
        "createdAt" to entry.createdAt.toString(),
        "expiresAt" to entry.expiresAt.toString(),
    )

    /**
     * Maps the internal [JobVisibility] enum to the §6.4 wire
     * surface `OWN` / `TENANT_VISIBLE` / `ADMIN_VISIBLE`. The wire
     * names hide the internal state machine from clients and
     * stay stable across future visibility refactors.
     */
    private fun JobVisibility.toVisibilityClass(): String = when (this) {
        JobVisibility.OWNER -> "OWN"
        JobVisibility.TENANT -> "TENANT_VISIBLE"
        JobVisibility.ADMIN -> "ADMIN_VISIBLE"
    }
}
