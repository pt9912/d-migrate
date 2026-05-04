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
import dev.dmigrate.server.core.connection.ConnectionReference
import dev.dmigrate.server.core.connection.ConnectionSensitivity
import dev.dmigrate.server.ports.DiffIndexEntry
import dev.dmigrate.server.ports.ProfileIndexEntry
import dev.dmigrate.server.ports.SchemaIndexEntry
import dev.dmigrate.server.ports.memory.InMemoryArtifactStore
import dev.dmigrate.server.ports.memory.InMemoryAuditSink
import dev.dmigrate.server.ports.memory.InMemoryConnectionReferenceStore
import dev.dmigrate.server.ports.memory.InMemoryDiffStore
import dev.dmigrate.server.ports.memory.InMemoryJobStore
import dev.dmigrate.server.ports.memory.InMemoryProfileStore
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
     * Default tenant for the legacy [INTEGRATION_PRINCIPAL] constant.
     * Per-transport-run scenarios MUST use [freshTransportPrincipal]
     * — see the KDoc there for why.
     */
    val INTEGRATION_TENANT: TenantId = TenantId("default")

    /**
     * Legacy shared principal — kept for non-scenario unit tests that
     * have no isolation requirement (e.g. `McpHarnessSmokeTest`'s
     * cross-transport drift assertion). Scenario tests that pre-stage
     * tenant-scoped state and assert audit-event correlation MUST use
     * [freshTransportPrincipal] instead so AP 6.24's "eigene
     * Tenant/Principal je Transportlauf" requirement holds end-to-end.
     */
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
     * AP 6.24 §6.24 final-review: per-transport-run principal so a
     * stdio + HTTP pair invoked from the same `withFreshTransports`
     * helper see DIFFERENT tenants/principals server-side. Each
     * harness instance gets its own, with a unique 8-char suffix
     * embedded in `tenantId` / `principalId` / `auditSubject` so
     * audit-event correlation tests can pin per-transport identity
     * without cross-pollution.
     *
     * `isAdmin = true` keeps `ScopeChecker.isSatisfied`'s admin-bypass
     * working (the same loophole AuthMode.DISABLED relies on for
     * loopback tooling) so handler-level scope checks pass without
     * requiring the principal to enumerate every Phase-C scope.
     */
    fun freshTransportPrincipal(transport: String): PrincipalContext {
        val suffix = java.util.UUID.randomUUID().toString().take(SUFFIX_LEN)
        val tenantId = TenantId("it-$transport-$suffix")
        val principalId = PrincipalId("it-$transport-$suffix")
        return PrincipalContext(
            principalId = principalId,
            homeTenantId = tenantId,
            effectiveTenantId = tenantId,
            allowedTenantIds = setOf(tenantId),
            scopes = setOf("dmigrate:admin"),
            isAdmin = true,
            auditSubject = "it-$transport-$suffix",
            authSource = AuthSource.ANONYMOUS,
            expiresAt = Instant.MAX,
        )
    }

    private const val SUFFIX_LEN: Int = 8

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
            // AP D11: replace the default Empty-stores with seedable
            // InMemory variants so the discovery scenario suite can
            // round-trip every list-tool family + connection-resource.
            profileStore = InMemoryProfileStore(),
            diffStore = InMemoryDiffStore(),
            connectionStore = InMemoryConnectionReferenceStore(),
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

    /**
     * AP D11: stages a [ProfileIndexEntry] directly so the
     * `profile_list` / `resources/list` scenarios get deterministic
     * discovery records without driving the (Phase-D-future) profile
     * upload flow end-to-end.
     */
    fun stageProfile(
        wiring: PhaseCWiring,
        principal: PrincipalContext,
        profileId: String,
        artifactRef: String? = null,
        clock: Clock = Clock.systemUTC(),
    ): String {
        val tenantId = principal.effectiveTenantId
        val now = clock.instant()
        wiring.profileStore.save(
            ProfileIndexEntry(
                profileId = profileId,
                tenantId = tenantId,
                resourceUri = ServerResourceUri(tenantId, ResourceKind.PROFILES, profileId),
                artifactRef = artifactRef ?: "art-$profileId",
                displayName = "integration profile $profileId",
                createdAt = now,
                expiresAt = now.plusSeconds(SECONDS_PER_HOUR.toLong()),
            ),
        )
        return profileId
    }

    /**
     * AP D11: stages a [DiffIndexEntry] directly so the `diff_list`
     * scenario sees a populated store. Phase-D start tools will write
     * diffs in a future milestone; the integration suite seeds them
     * by hand for now.
     */
    fun stageDiff(
        wiring: PhaseCWiring,
        principal: PrincipalContext,
        diffId: String,
        clock: Clock = Clock.systemUTC(),
    ): String {
        val tenantId = principal.effectiveTenantId
        val now = clock.instant()
        wiring.diffStore.save(
            DiffIndexEntry(
                diffId = diffId,
                tenantId = tenantId,
                resourceUri = ServerResourceUri(tenantId, ResourceKind.DIFFS, diffId),
                artifactRef = "art-$diffId",
                displayName = "integration diff $diffId",
                sourceRef = "schema-source-$diffId",
                targetRef = "schema-target-$diffId",
                createdAt = now,
                expiresAt = now.plusSeconds(SECONDS_PER_HOUR.toLong()),
            ),
        )
        return diffId
    }

    /**
     * AP D11: stages a [ConnectionReference] directly so the
     * `connection_list` (well, `resources/list` / `resources/read`)
     * scenarios see a populated connection-reference store. Plan-D
     * §10.10 secret-free guarantee: this helper accepts a plain
     * `credentialRef` string but never expands it.
     */
    @Suppress("LongParameterList")
    fun stageConnection(
        wiring: PhaseCWiring,
        principal: PrincipalContext,
        connectionId: String,
        displayName: String = "integration connection $connectionId",
        dialectId: String = "postgresql",
        sensitivity: ConnectionSensitivity = ConnectionSensitivity.NON_PRODUCTION,
        credentialRef: String? = "env:INTEGRATION_PASS",
    ): String {
        val tenantId = principal.effectiveTenantId
        wiring.connectionStore.save(
            ConnectionReference(
                connectionId = connectionId,
                tenantId = tenantId,
                displayName = displayName,
                dialectId = dialectId,
                sensitivity = sensitivity,
                resourceUri = ServerResourceUri(tenantId, ResourceKind.CONNECTIONS, connectionId),
                credentialRef = credentialRef,
            ),
        )
        return connectionId
    }

    private const val SECONDS_PER_HOUR: Int = 3600
    private const val SHA256_HEX_LEN: Int = 64
}
