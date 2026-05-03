package dev.dmigrate.cli.commands

import dev.dmigrate.mcp.registry.PhaseCWiring
import dev.dmigrate.mcp.server.McpLimitsConfig
import dev.dmigrate.server.adapter.audit.logging.LoggingAuditSink
import dev.dmigrate.server.adapter.storage.file.FileBackedArtifactContentStore
import dev.dmigrate.server.adapter.storage.file.FileBackedUploadSegmentStore
import dev.dmigrate.server.adapter.storage.file.FileSpoolAssembledUploadPayloadFactory
import dev.dmigrate.server.application.quota.DefaultQuotaService
import dev.dmigrate.server.ports.memory.InMemoryArtifactStore
import dev.dmigrate.server.ports.memory.InMemoryJobStore
import dev.dmigrate.server.ports.memory.InMemoryQuotaStore
import dev.dmigrate.server.ports.memory.InMemorySchemaStore
import dev.dmigrate.server.ports.memory.InMemoryUploadSessionStore
import java.nio.file.Path
import java.time.Clock

/**
 * Production CLI wiring for `mcp serve` per `ImpPlan-0.9.6-C.md`
 * §6.21 + §6.22.
 *
 * Byte-Stores are file-backed under [stateDir]:
 * - `FileBackedUploadSegmentStore(stateDir)` lays out segments under
 *   `<stateDir>/segments/<uploadSessionId>/...`.
 * - `FileBackedArtifactContentStore(stateDir)` lays out artefacts
 *   under `<stateDir>/artifacts/<sha256-prefix>/<artifactId>.bin`
 *   plus sidecar.
 * - `FileSpoolAssembledUploadPayloadFactory(stateDir)` keeps the
 *   AP-6.22 streaming-finalisation spool off-heap under
 *   `<stateDir>/assembly/<uploadSessionId>/<uuid>.bin`. The default
 *   `AssembledUploadPayloadFactory.inMemory()` from `PhaseCWiring`
 *   would defeat the AP-6.22 heap guarantee — production CLI MUST
 *   inject the file-spool factory here.
 *
 * Both byte adapters create their own canonical sub-directories — no
 * extra speaking sub-folder is interposed so the on-disk layout stays
 * stable at exactly `<stateDir>/segments/...`, `<stateDir>/artifacts/...`
 * and `<stateDir>/assembly/...`.
 *
 * Metadata stores remain ephemeral (`InMemoryUploadSessionStore`,
 * `InMemoryArtifactStore`, `InMemorySchemaStore`, `InMemoryJobStore`,
 * `InMemoryQuotaStore`). After a restart, byte files in an
 * operator-supplied state dir without matching metadata are
 * unreferenceable via MCP and only diagnostic/forensic; the startup
 * orphan sweep configured in `McpCommands` bounds them.
 *
 * Quota enforcement defaults to "no limit" (`Long.MAX_VALUE` per
 * dimension) until a quota policy adapter lands.
 *
 * State-dir resolution, fail-fast validation, single-writer locking,
 * orphan sweeping, stderr start-state line and shutdown/cleanup are
 * the responsibility of `McpCommands` — this helper only constructs
 * the wiring from a state dir that the caller has already validated
 * and locked.
 */
internal object McpCliPhaseCWiring {
    fun phaseCWiring(
        stateDir: Path,
        limits: McpLimitsConfig = McpLimitsConfig(),
        clock: Clock = Clock.systemUTC(),
    ): PhaseCWiring {
        val quotaStore = InMemoryQuotaStore()
        return PhaseCWiring(
            uploadSessionStore = InMemoryUploadSessionStore(),
            uploadSegmentStore = FileBackedUploadSegmentStore(stateDir),
            artifactStore = InMemoryArtifactStore(),
            artifactContentStore = FileBackedArtifactContentStore(stateDir),
            schemaStore = InMemorySchemaStore(),
            jobStore = InMemoryJobStore(),
            quotaService = DefaultQuotaService(quotaStore) { Long.MAX_VALUE },
            limits = limits,
            clock = clock,
            auditSink = LoggingAuditSink(),
            assembledUploadPayloadFactory = FileSpoolAssembledUploadPayloadFactory(stateDir),
        )
    }
}
