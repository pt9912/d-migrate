package dev.dmigrate.cli.integration

import dev.dmigrate.mcp.registry.PhaseCWiring
import dev.dmigrate.mcp.server.McpLimitsConfig
import dev.dmigrate.server.adapter.storage.file.FileBackedArtifactContentStore
import dev.dmigrate.server.adapter.storage.file.FileBackedUploadSegmentStore
import dev.dmigrate.server.adapter.storage.file.FileSpoolAssembledUploadPayloadFactory
import dev.dmigrate.server.application.quota.DefaultQuotaService
import dev.dmigrate.server.core.artifact.ArtifactKind
import dev.dmigrate.server.core.artifact.ArtifactRecord
import dev.dmigrate.server.core.artifact.ManagedArtifact
import dev.dmigrate.server.core.job.JobRecord
import dev.dmigrate.server.core.job.JobStatus
import dev.dmigrate.server.core.job.JobVisibility
import dev.dmigrate.server.core.job.ManagedJob
import dev.dmigrate.server.core.principal.AuthSource
import dev.dmigrate.server.core.principal.PrincipalContext
import dev.dmigrate.server.core.principal.PrincipalId
import dev.dmigrate.server.core.principal.TenantId
import dev.dmigrate.server.core.resource.ResourceKind
import dev.dmigrate.server.core.resource.ServerResourceUri
import dev.dmigrate.server.ports.SchemaIndexEntry
import dev.dmigrate.server.ports.memory.InMemoryArtifactStore
import dev.dmigrate.server.ports.memory.InMemoryAuditSink
import dev.dmigrate.server.ports.memory.InMemoryJobStore
import dev.dmigrate.server.ports.memory.InMemoryQuotaStore
import dev.dmigrate.server.ports.memory.InMemorySchemaStore
import dev.dmigrate.server.ports.memory.InMemoryUploadSessionStore
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant

/**
 * AP 6.24: per-transport-run isolation primitives. Every harness
 * instance carries its own state dir, tenant, principal and audit
 * sink so a stdio + http run inside the same scenario does NOT
 * cross-pollinate persisted state, audit events or principals.
 *
 * The wiring is the AP-6.21 file-backed Phase-C wiring:
 * - [FileBackedUploadSegmentStore] under `<stateDir>/segments/...`
 * - [FileBackedArtifactContentStore] under `<stateDir>/artifacts/<shard>/...`
 * - [FileSpoolAssembledUploadPayloadFactory] under `<stateDir>/assembly/...`
 * - audit sink is an [InMemoryAuditSink] (per-transport, fresh) so
 *   the AP-6.24 "exactly one audit event per dispatched tools/call"
 *   assertion is unambiguous.
 *
 * Plan §6.24 explicitly forbids HTTP from inheriting the file-backed
 * wiring from the stdio CLI path — both transports build their own
 * `PhaseCWiring` here, sharing no in-memory state.
 */
internal object IntegrationFixtures {

    fun freshStateDir(prefix: String = "dmigrate-it-"): Path =
        Files.createTempDirectory(prefix)

    /**
     * AP 6.24: shared principal for both transports. Mirrors
     * [DisabledAuthValidator.ANONYMOUS_PRINCIPAL] (the principal HTTP
     * `AuthMode.DISABLED` synthesises) exactly — `isAdmin = true`
     * triggers [ScopeChecker.isSatisfied]'s admin-bypass so both the
     * route- and service-layer scope checks agree even though the
     * principal only enumerates `dmigrate:admin`. Keeping the
     * fixtures aligned ensures stdio + HTTP transport-neutral asserts
     * do not need per-transport principal differences.
     */
    val INTEGRATION_TENANT: TenantId = TenantId("default")

    val INTEGRATION_PRINCIPAL: PrincipalContext = PrincipalContext(
        principalId = PrincipalId("anonymous"),
        homeTenantId = INTEGRATION_TENANT,
        effectiveTenantId = INTEGRATION_TENANT,
        allowedTenantIds = setOf(INTEGRATION_TENANT),
        scopes = setOf("dmigrate:admin"),
        isAdmin = true,
        auditSubject = "anonymous",
        authSource = AuthSource.ANONYMOUS,
        expiresAt = Instant.MAX,
    )

    /**
     * Builds a Phase-C wiring identical in shape to the production
     * `McpCliPhaseCWiring.phaseCWiring(stateDir)` but with an
     * [InMemoryAuditSink] swapped in so the integration test can
     * assert audit-event counts. The `auditSink` reference is also
     * exposed so the spec can read events back per-transport.
     */
    data class IntegrationWiring(
        val wiring: PhaseCWiring,
        val auditSink: InMemoryAuditSink,
        val stateDir: Path,
    )

    fun integrationWiring(
        stateDir: Path,
        clock: Clock = Clock.systemUTC(),
        limits: McpLimitsConfig = McpLimitsConfig(),
    ): IntegrationWiring {
        val auditSink = InMemoryAuditSink()
        val quotaStore = InMemoryQuotaStore()
        val wiring = PhaseCWiring(
            uploadSessionStore = InMemoryUploadSessionStore(),
            uploadSegmentStore = FileBackedUploadSegmentStore(stateDir),
            artifactStore = InMemoryArtifactStore(),
            artifactContentStore = FileBackedArtifactContentStore(stateDir),
            schemaStore = InMemorySchemaStore(),
            jobStore = InMemoryJobStore(),
            quotaService = DefaultQuotaService(quotaStore) { Long.MAX_VALUE },
            limits = limits,
            clock = clock,
            // LoggingAuditSink would log to slf4j only; the integration
            // suite needs the in-memory sink to assert event counts.
            auditSink = auditSink,
            assembledUploadPayloadFactory = FileSpoolAssembledUploadPayloadFactory(stateDir),
        )
        return IntegrationWiring(wiring, auditSink, stateDir)
    }

    /**
     * AP 6.24 E3: stages a schema-staging-readonly artefact + the
     * matching `SchemaIndexEntry` directly via the in-memory stores
     * exposed by [PhaseCWiring]. Returns the wire-shape `schemaRef`
     * (`dmigrate://tenants/<tenant>/schemas/<schemaId>`) the test can
     * pass to `schema_compare` / `schema_generate`.
     *
     * The end-to-end upload flow lives in E4; E3 only needs both
     * transports' stores pre-populated with parsable schemas.
     */
    fun stageSchema(
        wiring: PhaseCWiring,
        principal: PrincipalContext,
        schemaId: String,
        json: String,
        clock: Clock = Clock.systemUTC(),
    ): String {
        val tenantId = principal.effectiveTenantId
        val artifactId = "art-$schemaId"
        val bytes = json.toByteArray(Charsets.UTF_8)
        val now = clock.instant()
        val expires = now.plusSeconds(SECONDS_PER_HOUR.toLong())
        val managed = ManagedArtifact(
            artifactId = artifactId,
            filename = "$schemaId.json",
            contentType = "application/json",
            sizeBytes = bytes.size.toLong(),
            sha256 = "0".repeat(SHA256_HEX_LEN),
            createdAt = now,
            expiresAt = expires,
        )
        val record = ArtifactRecord(
            managedArtifact = managed,
            kind = ArtifactKind.SCHEMA,
            tenantId = tenantId,
            ownerPrincipalId = principal.principalId,
            visibility = JobVisibility.TENANT,
            resourceUri = ServerResourceUri(tenantId, ResourceKind.ARTIFACTS, artifactId),
        )
        wiring.artifactStore.save(record)
        wiring.artifactContentStore.write(artifactId, ByteArrayInputStream(bytes), bytes.size.toLong())
        val schemaUri = ServerResourceUri(tenantId, ResourceKind.SCHEMAS, schemaId)
        wiring.schemaStore.save(
            SchemaIndexEntry(
                schemaId = schemaId,
                tenantId = tenantId,
                resourceUri = schemaUri,
                artifactRef = artifactId,
                displayName = "integration test schema $schemaId",
                createdAt = now,
                expiresAt = expires,
            ),
        )
        return schemaUri.render()
    }

    /**
     * AP 6.24 E5: stages an artefact (record + content) directly via
     * the in-memory stores so the chunk-read scenarios don't need to
     * drive the upload flow end-to-end again. Returns the artifactId
     * the test passes back to `artifact_chunk_get`.
     */
    fun stageArtifact(
        wiring: PhaseCWiring,
        principal: PrincipalContext,
        artifactId: String,
        content: ByteArray,
        contentType: String = "application/json",
        filename: String = "$artifactId.bin",
        kind: ArtifactKind = ArtifactKind.OTHER,
        clock: Clock = Clock.systemUTC(),
    ): String {
        val tenantId = principal.effectiveTenantId
        val now = clock.instant()
        val expires = now.plusSeconds(SECONDS_PER_HOUR.toLong())
        val managed = ManagedArtifact(
            artifactId = artifactId,
            filename = filename,
            contentType = contentType,
            sizeBytes = content.size.toLong(),
            sha256 = "0".repeat(SHA256_HEX_LEN),
            createdAt = now,
            expiresAt = expires,
        )
        val record = ArtifactRecord(
            managedArtifact = managed,
            kind = kind,
            tenantId = tenantId,
            ownerPrincipalId = principal.principalId,
            visibility = JobVisibility.TENANT,
            resourceUri = ServerResourceUri(tenantId, ResourceKind.ARTIFACTS, artifactId),
        )
        wiring.artifactStore.save(record)
        wiring.artifactContentStore.write(artifactId, ByteArrayInputStream(content), content.size.toLong())
        return artifactId
    }

    /**
     * AP 6.24 E5: stages a [JobRecord] directly so the
     * `job_status_get` scenarios get a deterministic terminal job
     * with a known artefact list (the artefact-backfill projection
     * needs an entry to lift onto a `ServerResourceUri`).
     */
    @Suppress("LongParameterList")
    fun stageJob(
        wiring: PhaseCWiring,
        principal: PrincipalContext,
        jobId: String,
        operation: String = "schema_validate",
        status: JobStatus = JobStatus.SUCCEEDED,
        artifacts: List<String> = emptyList(),
        clock: Clock = Clock.systemUTC(),
    ): String {
        val tenantId = principal.effectiveTenantId
        val now = clock.instant()
        val managed = ManagedJob(
            jobId = jobId,
            operation = operation,
            status = status,
            createdAt = now,
            updatedAt = now,
            expiresAt = now.plusSeconds(SECONDS_PER_HOUR.toLong()),
            createdBy = principal.principalId.value,
            artifacts = artifacts,
        )
        val record = JobRecord(
            managedJob = managed,
            tenantId = tenantId,
            ownerPrincipalId = principal.principalId,
            visibility = JobVisibility.TENANT,
            resourceUri = ServerResourceUri(tenantId, ResourceKind.JOBS, jobId),
        )
        wiring.jobStore.save(record)
        return jobId
    }

    private const val SECONDS_PER_HOUR: Int = 3600
    private const val SHA256_HEX_LEN: Int = 64
}
