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
 * Builds a [PhaseCWiring] backed by in-memory ports + the
 * `dev.dmigrate.audit` log appender. Used by `mcp serve` so every
 * Phase-C tool from `ImpPlan-0.9.6-C.md` §3.1 dispatches to its real
 * handler instead of the Phase-B `UnsupportedToolHandler` fallback.
 *
 * **State is ephemeral.** Sessions, segments, schemas, and artefacts
 * live in process memory and disappear on restart. This is the
 * intended shape for local development, smoke tests, and
 * integration-test bootstraps; production deployments must replace
 * these stores with durable adapters (file-backed for content,
 * SQL/object-store for metadata) once they land.
 *
 * Quota enforcement defaults to "no limit" (`Long.MAX_VALUE` for every
 * dimension) — same shape the Phase-C handler tests use. Operators
 * who want real quota policing wire a [DefaultQuotaService] with the
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
