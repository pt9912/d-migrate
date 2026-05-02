package dev.dmigrate.mcp.registry

import dev.dmigrate.core.validation.SchemaValidator
import dev.dmigrate.mcp.schema.DefaultSchemaStagingFinalizer
import dev.dmigrate.mcp.schema.SchemaContentLoader
import dev.dmigrate.mcp.schema.SchemaSourceResolver
import dev.dmigrate.mcp.schema.SchemaStagingFinalizer
import dev.dmigrate.mcp.server.McpLimitsConfig
import dev.dmigrate.server.application.quota.QuotaService
import dev.dmigrate.server.ports.ArtifactContentStore
import dev.dmigrate.server.ports.ArtifactStore
import dev.dmigrate.server.ports.JobStore
import dev.dmigrate.server.ports.SchemaStore
import dev.dmigrate.server.ports.UploadSegmentStore
import dev.dmigrate.server.ports.UploadSessionStore
import java.time.Clock

/**
 * Phase-C wiring bundle per `ImpPlan-0.9.6-C.md` §6.14. Holds every
 * fachliche Abhaengigkeit the Phase-C handlers need so the bootstrap
 * can build the registry in one shot rather than threading 10+
 * stores through `startStdio` / `startHttp`.
 *
 * The bundle is meant to be assembled once at server start by the
 * caller (CLI, embed-test, future driver wiring), then passed
 * verbatim into [PhaseCRegistries.defaultToolRegistry]. Individual
 * fields stay accessible so a deployment can swap, e.g., a
 * file-backed `ArtifactContentStore` for the in-memory default
 * without rebuilding every handler.
 *
 * `finalizer` defaults to [DefaultSchemaStagingFinalizer] but tests
 * can inject a stub to short-circuit the parse/validate path.
 *
 * Note: `SchemaGenerateHandler` looks up `DdlGenerator` instances
 * via the static `DatabaseDriverRegistry` (the same lookup the CLI
 * uses); the wiring does not own that lookup. Production callers
 * are expected to have run `RuntimeBootstrap.initialize()` (which
 * loads drivers via `ServiceLoader`) before constructing the
 * bundle.
 */
data class PhaseCWiring(
    val uploadSessionStore: UploadSessionStore,
    val uploadSegmentStore: UploadSegmentStore,
    val artifactStore: ArtifactStore,
    val artifactContentStore: ArtifactContentStore,
    val schemaStore: SchemaStore,
    val jobStore: JobStore,
    val quotaService: QuotaService,
    val limits: McpLimitsConfig,
    val clock: Clock,
    val finalizer: SchemaStagingFinalizer = DefaultSchemaStagingFinalizer(
        artifactStore = artifactStore,
        artifactContentStore = artifactContentStore,
        schemaStore = schemaStore,
        validator = SchemaValidator(),
        clock = clock,
    ),
)
