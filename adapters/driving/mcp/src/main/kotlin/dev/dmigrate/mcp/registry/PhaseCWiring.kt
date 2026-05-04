package dev.dmigrate.mcp.registry

import dev.dmigrate.core.validation.SchemaValidator
import dev.dmigrate.mcp.schema.DefaultSchemaStagingFinalizer
import dev.dmigrate.mcp.schema.SchemaContentLoader
import dev.dmigrate.mcp.schema.SchemaSourceResolver
import dev.dmigrate.mcp.schema.SchemaStagingFinalizer
import dev.dmigrate.mcp.server.McpLimitsConfig
import dev.dmigrate.server.application.quota.QuotaService
import dev.dmigrate.server.core.upload.AssembledUploadPayloadFactory
import dev.dmigrate.mcp.resources.EmptyDiffStore
import dev.dmigrate.mcp.resources.EmptyProfileStore
import dev.dmigrate.server.ports.ArtifactContentStore
import dev.dmigrate.server.ports.ArtifactStore
import dev.dmigrate.server.ports.AuditSink
import dev.dmigrate.server.ports.DiffStore
import dev.dmigrate.server.ports.JobStore
import dev.dmigrate.server.ports.ProfileStore
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
    /**
     * AP 6.20: optional audit sink. When supplied, every `tools/call`
     * dispatched through `McpServiceImpl` records a single
     * [dev.dmigrate.server.core.audit.AuditEvent] (success and
     * failure paths alike). Production wiring usually plugs in
     * `LoggingAuditSink`; tests can leave this null to opt out, or
     * supply `InMemoryAuditSink` to assert event shape.
     */
    val auditSink: AuditSink? = null,
    /**
     * AP 6.22: factory that allocates a streaming spool for the
     * `ArtifactUploadHandler` finalisation path. Production CLI
     * wires the file-spool variant under the AP-6.21 state dir;
     * tests/dev default to `AssembledUploadPayloadFactory.inMemory()`.
     */
    val assembledUploadPayloadFactory: AssembledUploadPayloadFactory = AssembledUploadPayloadFactory.inMemory(),

    /**
     * AP D6: store backing `profile_list`. No Phase-C tool emits
     * profile records yet (Phase-D start tools will), so the
     * default is the no-op [EmptyProfileStore]. Integration tests
     * inject an `InMemoryProfileStore` so staged profiles
     * round-trip through the discovery handler.
     */
    val profileStore: ProfileStore = EmptyProfileStore,

    /**
     * AP D6: store backing `diff_list`. Same default-empty
     * rationale as [profileStore] — `schema_compare` produces
     * `diffArtifactRef` but no DiffIndexEntry yet; Phase-D start
     * tools will.
     */
    val diffStore: DiffStore = EmptyDiffStore,
)
