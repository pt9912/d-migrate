package dev.dmigrate.cli.commands

import dev.dmigrate.mcp.registry.PhaseCWiring
import dev.dmigrate.mcp.server.McpLimitsConfig
import dev.dmigrate.server.adapter.audit.logging.LoggingAuditSink
import dev.dmigrate.server.application.quota.DefaultQuotaService
import dev.dmigrate.server.ports.memory.InMemoryArtifactContentStore
import dev.dmigrate.server.ports.memory.InMemoryArtifactStore
import dev.dmigrate.server.ports.memory.InMemoryJobStore
import dev.dmigrate.server.ports.memory.InMemoryQuotaStore
import dev.dmigrate.server.ports.memory.InMemorySchemaStore
import dev.dmigrate.server.ports.memory.InMemoryUploadSegmentStore
import dev.dmigrate.server.ports.memory.InMemoryUploadSessionStore
import java.time.Clock

/**
 * Builds a [PhaseCWiring] backed entirely by in-memory ports + the
 * `dev.dmigrate.audit` log appender.
 *
 * **Test/dev only.** Per `ImpPlan-0.9.6-C.md` §6.21 this helper is no
 * longer the production anchor for `mcp serve` — production CLI
 * wiring goes through [McpCliPhaseCWiring.phaseCWiring], which puts
 * upload segments and artefact content on disk under the resolved
 * state dir. Use this helper for Phase-C handler unit tests and for
 * embedded smoke tests where byte content does not need to survive
 * the process.
 *
 * Quota enforcement defaults to "no limit" (`Long.MAX_VALUE` for every
 * dimension) — same shape the Phase-C handler tests use. Tests that
 * want real quota policing wire a [DefaultQuotaService] with the
 * desired `limitFor` lambda before reaching this helper.
 */
internal fun developmentPhaseCWiring(
    limits: McpLimitsConfig = McpLimitsConfig(),
    clock: Clock = Clock.systemUTC(),
): PhaseCWiring {
    val quotaStore = InMemoryQuotaStore()
    return PhaseCWiring(
        uploadSessionStore = InMemoryUploadSessionStore(),
        uploadSegmentStore = InMemoryUploadSegmentStore(),
        artifactStore = InMemoryArtifactStore(),
        artifactContentStore = InMemoryArtifactContentStore(),
        schemaStore = InMemorySchemaStore(),
        jobStore = InMemoryJobStore(),
        quotaService = DefaultQuotaService(quotaStore) { Long.MAX_VALUE },
        limits = limits,
        clock = clock,
        auditSink = LoggingAuditSink(),
    )
}
