package dev.dmigrate.cli.integration

import dev.dmigrate.mcp.registry.PhaseCWiring
import dev.dmigrate.mcp.server.McpLimitsConfig
import dev.dmigrate.server.adapter.storage.file.FileBackedArtifactContentStore
import dev.dmigrate.server.adapter.storage.file.FileBackedUploadSegmentStore
import dev.dmigrate.server.adapter.storage.file.FileSpoolAssembledUploadPayloadFactory
import dev.dmigrate.server.application.quota.DefaultQuotaService
import dev.dmigrate.server.core.principal.AuthSource
import dev.dmigrate.server.core.principal.PrincipalContext
import dev.dmigrate.server.core.principal.PrincipalId
import dev.dmigrate.server.core.principal.TenantId
import dev.dmigrate.server.ports.memory.InMemoryArtifactStore
import dev.dmigrate.server.ports.memory.InMemoryAuditSink
import dev.dmigrate.server.ports.memory.InMemoryJobStore
import dev.dmigrate.server.ports.memory.InMemoryQuotaStore
import dev.dmigrate.server.ports.memory.InMemorySchemaStore
import dev.dmigrate.server.ports.memory.InMemoryUploadSessionStore
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

}
