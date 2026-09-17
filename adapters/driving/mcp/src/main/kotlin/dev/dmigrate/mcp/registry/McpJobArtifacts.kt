package dev.dmigrate.mcp.registry

import com.google.gson.GsonBuilder
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.driver.SchemaReadReportInput
import dev.dmigrate.format.SchemaFileResolver
import dev.dmigrate.format.report.ProfileReportWriter
import dev.dmigrate.format.report.ReverseReportWriter
import dev.dmigrate.profiling.model.DatabaseProfile
import dev.dmigrate.server.application.job.JobArtifactPublisher
import dev.dmigrate.server.core.artifact.ArtifactKind
import dev.dmigrate.server.core.artifact.ArtifactRecord
import dev.dmigrate.server.core.artifact.ManagedArtifact
import dev.dmigrate.server.core.job.JobRecord
import dev.dmigrate.server.core.job.JobVisibility
import dev.dmigrate.server.core.resource.ResourceKind
import dev.dmigrate.server.core.resource.ServerResourceUri
import dev.dmigrate.server.ports.ArtifactContentStore
import dev.dmigrate.server.ports.ArtifactStore
import dev.dmigrate.server.ports.DiffIndexEntry
import dev.dmigrate.server.ports.DiffStore
import dev.dmigrate.server.ports.ProfileIndexEntry
import dev.dmigrate.server.ports.ProfileStore
import dev.dmigrate.server.ports.SchemaIndexEntry
import dev.dmigrate.server.ports.SchemaStore
import dev.dmigrate.server.ports.WriteArtifactOutcome
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Die Artefakte der drei MCP-Lese-Jobs — je Job ein **typisierter**
 * [JobArtifactPublisher]: der Reverse veroeffentlicht eine
 * [SchemaDefinition], das Profiling ein [DatabaseProfile], der Vergleich ein
 * [SchemaCompareOutcome]. Welche Nutzlast welche Art ergibt, entscheidet der
 * Compiler, nicht eine Typpruefung zur Laufzeit.
 *
 * Jedes Artefakt wird geschrieben, im [ArtifactStore] registriert und in
 * seinem Index eingetragen: Schema → `schemas`, Profil → `profiles`,
 * Vergleich (Art [ArtifactKind.COMPARE]) → `diffs`. Der Reverse-Report einer
 * gelesenen Verbindung ([readReports]) hat keinen Index.
 */
internal class McpJobArtifacts(
    private val artifactStore: ArtifactStore,
    private val artifactContentStore: ArtifactContentStore,
    private val schemaStore: SchemaStore,
    private val profileStore: ProfileStore,
    private val diffStore: DiffStore,
    private val clock: Clock,
    private val ttl: Duration = Duration.ofHours(24),
) {

    private val gson = GsonBuilder().disableHtmlEscaping().create()
    private val reportWriter = ReverseReportWriter()

    /** `schema_reverse_start`: das Schema als YAML, Index `schemas`. */
    fun schemas(): JobArtifactPublisher<SchemaDefinition> = JobArtifactPublisher { job, schema ->
        val bytes = ByteArrayOutputStream()
            .also { SchemaFileResolver.codecForFormat("yaml").write(it, schema) }
            .toByteArray()
        publish(job, Rendered(ArtifactKind.SCHEMA, "schema-${safeId()}.yaml", "application/x-yaml", bytes)) { stored ->
            schemaStore.save(
                SchemaIndexEntry(
                    schemaId = stored.indexId("sch"),
                    tenantId = job.tenantId,
                    resourceUri = ServerResourceUri(job.tenantId, ResourceKind.SCHEMAS, stored.indexId("sch")),
                    artifactRef = stored.artifactId,
                    displayName = "Schema from ${job.managedJob.operation}",
                    createdAt = stored.at,
                    expiresAt = stored.at.plus(ttl),
                    jobRef = job.resourceUri.render(),
                    format = "yaml",
                    origin = job.managedJob.operation,
                    sizeBytes = bytes.size.toLong(),
                    hash = stored.sha256,
                ),
            )
        }
    }

    /** `data_profile_start`: der Report als JSON, Index `profiles`. */
    fun profiles(): JobArtifactPublisher<DatabaseProfile> = JobArtifactPublisher { job, profile ->
        val bytes = ProfileReportWriter().renderJson(profile).toByteArray(Charsets.UTF_8)
        publish(job, Rendered(ArtifactKind.PROFILE, "profile-${safeId()}.json", "application/json", bytes)) { stored ->
            profileStore.save(
                ProfileIndexEntry(
                    profileId = stored.indexId("prof"),
                    tenantId = job.tenantId,
                    resourceUri = ServerResourceUri(job.tenantId, ResourceKind.PROFILES, stored.indexId("prof")),
                    artifactRef = stored.artifactId,
                    displayName = "Profile from ${job.managedJob.operation}",
                    createdAt = stored.at,
                    expiresAt = stored.at.plus(ttl),
                    jobRef = job.resourceUri.render(),
                ),
            )
        }
    }

    /**
     * `schema_compare_start`: das Ergebnis in der Form des Compare-Artefakts
     * ([SchemaCompareOutcome.artifact], `spec/mcp-server.md`), Art
     * [ArtifactKind.COMPARE], Index `diffs` mit beiden Verweisen.
     */
    fun comparisons(sourceRef: String, targetRef: String): JobArtifactPublisher<SchemaCompareOutcome> =
        JobArtifactPublisher { job, outcome ->
            val bytes = gson.toJson(outcome.artifact()).toByteArray(Charsets.UTF_8)
            publish(job, Rendered(ArtifactKind.COMPARE, "compare-${safeId()}.json", "application/json", bytes)) { stored ->
                diffStore.save(
                    DiffIndexEntry(
                        diffId = stored.indexId("diff"),
                        tenantId = job.tenantId,
                        resourceUri = ServerResourceUri(job.tenantId, ResourceKind.DIFFS, stored.indexId("diff")),
                        artifactRef = stored.artifactId,
                        sourceRef = sourceRef,
                        targetRef = targetRef,
                        displayName = "Diff from ${job.managedJob.operation}",
                        createdAt = stored.at,
                        expiresAt = stored.at.plus(ttl),
                        jobRef = job.resourceUri.render(),
                        statusSummary = "DIFF_PUBLISHED",
                    ),
                )
            }
        }

    /**
     * Der Reverse-Report einer aus einer Verbindung gelesenen Seite
     * (`schema_reverse_start`, `schema_compare_start` mit Verbindungen): die
     * Notes und uebersprungenen Objekte des Readers in derselben Form wie der
     * Reverse-Report von `schema reverse` (`spec/mcp-server.md`), Art
     * [ArtifactKind.OTHER], ohne Index.
     */
    fun readReports(): JobArtifactPublisher<SchemaReadReportInput> = JobArtifactPublisher { job, report ->
        val bytes = reportWriter.render(report).toByteArray(Charsets.UTF_8)
        publish(job, Rendered(ArtifactKind.OTHER, "reverse-report-${safeId()}.yaml", "application/x-yaml", bytes)) { }
    }

    /** Schreibt die Bytes, registriert das Artefakt und traegt es in seinen Index ein. */
    private fun publish(job: JobRecord, rendered: Rendered, index: (Stored) -> Unit): String {
        val artifactId = "art-${UUID.randomUUID().toString().replace("-", "").take(16)}"
        val bytes = rendered.bytes
        val sha256 = when (val write = artifactContentStore.write(artifactId, ByteArrayInputStream(bytes), bytes.size.toLong())) {
            is WriteArtifactOutcome.Stored -> write.sha256
            is WriteArtifactOutcome.AlreadyExists -> write.existingSha256
            is WriteArtifactOutcome.SizeMismatch ->
                error("artifact size mismatch while publishing job '${job.managedJob.jobId}'")
            is WriteArtifactOutcome.Conflict ->
                error("artifact id conflict while publishing job '${job.managedJob.jobId}'")
        }
        val now = clock.instant()
        val artifactUri = ServerResourceUri(job.tenantId, ResourceKind.ARTIFACTS, artifactId)
        artifactStore.save(
            ArtifactRecord(
                managedArtifact = ManagedArtifact(
                    artifactId = artifactId,
                    filename = rendered.filename,
                    contentType = rendered.contentType,
                    sizeBytes = bytes.size.toLong(),
                    sha256 = sha256,
                    createdAt = now,
                    expiresAt = now.plus(ttl),
                ),
                kind = rendered.kind,
                tenantId = job.tenantId,
                ownerPrincipalId = job.ownerPrincipalId,
                visibility = JobVisibility.OWNER,
                resourceUri = artifactUri,
                jobRef = job.resourceUri.render(),
            ),
        )
        index(Stored(artifactId, sha256, now))
        return artifactUri.render()
    }

    private fun safeId(): String = UUID.randomUUID().toString().replace("-", "").take(8)

    private class Rendered(
        val kind: ArtifactKind,
        val filename: String,
        val contentType: String,
        val bytes: ByteArray,
    )

    private class Stored(val artifactId: String, val sha256: String, val at: Instant) {
        /** Die Index-Kennung zum Artefakt: dasselbe Suffix, eigenes Praefix. */
        fun indexId(prefix: String): String = "$prefix-${artifactId.removePrefix("art-")}"
    }
}
