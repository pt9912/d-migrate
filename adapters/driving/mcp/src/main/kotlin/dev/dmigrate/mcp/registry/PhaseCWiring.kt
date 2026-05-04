package dev.dmigrate.mcp.registry

import dev.dmigrate.core.validation.SchemaValidator
import dev.dmigrate.mcp.cursor.CursorKey
import dev.dmigrate.mcp.cursor.CursorKeyring
import dev.dmigrate.mcp.schema.DefaultSchemaStagingFinalizer
import dev.dmigrate.mcp.schema.SchemaContentLoader
import dev.dmigrate.mcp.schema.SchemaSourceResolver
import dev.dmigrate.mcp.schema.SchemaStagingFinalizer
import dev.dmigrate.mcp.server.McpLimitsConfig
import dev.dmigrate.server.application.quota.QuotaService
import dev.dmigrate.server.core.upload.AssembledUploadPayloadFactory
import dev.dmigrate.mcp.resources.EmptyConnectionStore
import dev.dmigrate.mcp.resources.EmptyDiffStore
import dev.dmigrate.mcp.resources.EmptyProfileStore
import dev.dmigrate.server.ports.ArtifactContentStore
import dev.dmigrate.server.ports.ArtifactStore
import dev.dmigrate.server.ports.AuditSink
import dev.dmigrate.server.ports.ConnectionReferenceStore
import dev.dmigrate.server.ports.DiffStore
import dev.dmigrate.server.ports.JobStore
import dev.dmigrate.server.ports.ProfileStore
import dev.dmigrate.server.ports.SchemaStore
import dev.dmigrate.server.ports.UploadSegmentStore
import dev.dmigrate.server.ports.UploadSessionStore
import java.security.SecureRandom
import java.time.Clock
import java.util.UUID

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

    /**
     * AP D10: secret-free connection-reference store. The MCP
     * bootstrap typically wires
     * `LoaderBackedConnectionReferenceStore(YamlConnectionReferenceLoader(...))`
     * so `resources/list`, `resources/read` and the discovery
     * tools see the deployment's connection refs without ever
     * materialising the resolved JDBC URL or the expanded secret.
     * The default stays empty for tests / CLI-only deployments
     * that don't carry a Phase-D YAML.
     */
    val connectionStore: ConnectionReferenceStore = EmptyConnectionStore,

    /**
     * AP D8 + Plan-D §10.3 review: HMAC keyring backing every
     * Phase-D MCP cursor (`resources/list`, `*_list` discovery
     * tools, chunk follow-ups).
     *
     * Default is the **deterministic dev keyring** ([DEV_DEFAULT])
     * so tests and single-process dev runs stay reproducible —
     * a cursor minted in one test method round-trips into the
     * next without surprise verification failures, and Phase-D
     * integration suites do not depend on random key material.
     *
     * Production / multi-instance / blue-green deployments MUST
     * override with [randomCursorKeyring] (single-instance,
     * fresh-random-per-start) or a deterministic keyring loaded
     * from a shared secret store. The CLI's
     * `--cursor-keyring-file` flag wires the production keyring;
     * production wiring without an override is a misconfig.
     */
    val cursorKeyring: CursorKeyring = DEV_DEFAULT,
) {
    companion object {

        /**
         * Plan-D §10.3 dev/test keyring: a fixed `kid`/secret pair
         * so dev workflows + tests get reproducible cursor wire
         * shapes. Bytes are an obvious "do-not-use-in-production"
         * marker (`0x00..0x1F`) — production wiring MUST replace
         * this via [randomCursorKeyring] or a config-loaded keyring.
         */
        val DEV_DEFAULT: CursorKeyring = CursorKeyring(
            signing = CursorKey(
                kid = "dev-default",
                secret = ByteArray(32) { it.toByte() },
            ),
        )

        /**
         * Generates a random per-process keyring for single-instance
         * deployments that don't carry an external keyring file.
         * Cursors stay valid for the duration of one server process;
         * a restart invalidates outstanding cursors (clients
         * re-paginate). NOT suitable for multi-instance deployments.
         */
        fun randomCursorKeyring(): CursorKeyring {
            val secret = ByteArray(32).also { SecureRandom().nextBytes(it) }
            return CursorKeyring(
                signing = CursorKey(
                    kid = "auto-${UUID.randomUUID()}",
                    secret = secret,
                ),
            )
        }
    }
}
