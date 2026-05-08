package dev.dmigrate.cli.commands

import dev.dmigrate.mcp.registry.McpRuntimeWiring
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
 * Builds a [McpRuntimeWiring] backed entirely by in-memory ports + the
 * `dev.dmigrate.audit` log appender.
 *
 * **Test/dev only.** Per LF-012 / LN-038 this helper is no
 * longer the production anchor for `mcp serve` — production CLI
 * wiring goes through [McpCliRuntimeWiring.runtimeWiring], which puts
 * upload segments and artefact content on disk under the resolved
 * state dir. Use this helper for LF-012 / LN-038 handler unit tests and for
 * embedded smoke tests where byte content does not need to survive
 * the process.
 *
 * Quota enforcement defaults to "no limit" (`Long.MAX_VALUE` for every
 * dimension) — same shape the LF-012 / LN-038 handler tests use. Tests that
 * want real quota policing wire a [DefaultQuotaService] with the
 * desired `limitFor` lambda before reaching this helper.
 */
internal fun developmentMcpRuntimeWiring(
    limits: McpLimitsConfig = McpLimitsConfig(),
    clock: Clock = Clock.systemUTC(),
): McpRuntimeWiring {
    val quotaStore = InMemoryQuotaStore()
    return McpRuntimeWiring(
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
