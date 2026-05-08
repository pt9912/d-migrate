package dev.dmigrate.mcp.registry

import dev.dmigrate.core.validation.SchemaValidator
import dev.dmigrate.mcp.cursor.CursorKey
import dev.dmigrate.mcp.cursor.CursorKeyring
import dev.dmigrate.mcp.schema.DefaultSchemaStagingFinalizer
import dev.dmigrate.mcp.schema.SchemaContentLoader
import dev.dmigrate.mcp.schema.SchemaSourceResolver
import dev.dmigrate.mcp.schema.SchemaStagingFinalizer
import dev.dmigrate.mcp.server.McpLimitsConfig
import dev.dmigrate.mcp.upload.DefaultJobInputFinalizer
import dev.dmigrate.mcp.upload.JobInputFinalizer
import dev.dmigrate.server.application.fingerprint.DefaultPayloadFingerprintService
import dev.dmigrate.server.application.fingerprint.PayloadFingerprintService
import dev.dmigrate.server.application.policy.ConfiguredPolicyService
import dev.dmigrate.server.application.policy.PolicyEffect
import dev.dmigrate.server.application.policy.PolicyService
import dev.dmigrate.server.application.quota.QuotaService
import dev.dmigrate.server.application.upload.AbortApprovalFingerprint
import dev.dmigrate.server.application.upload.UploadInitApprovalFingerprint
import dev.dmigrate.server.application.upload.UploadInitOrchestrator
import dev.dmigrate.server.core.upload.AssembledUploadPayloadFactory
import dev.dmigrate.mcp.resources.EmptyConnectionStore
import dev.dmigrate.mcp.resources.EmptyDiffStore
import dev.dmigrate.mcp.resources.EmptyProfileStore
import dev.dmigrate.server.ports.AbortOutcomeStore
import dev.dmigrate.server.ports.ArtifactContentStore
import dev.dmigrate.server.ports.ArtifactStore
import dev.dmigrate.server.ports.AuditSink
import dev.dmigrate.server.ports.ConnectionReferenceStore
import dev.dmigrate.server.ports.DiffStore
import dev.dmigrate.server.ports.JobStore
import dev.dmigrate.server.ports.ProfileStore
import dev.dmigrate.server.ports.SchemaStore
import dev.dmigrate.server.ports.SyncEffectIdempotencyStore
import dev.dmigrate.server.ports.UploadInitClaimStore
import dev.dmigrate.server.ports.UploadSegmentStore
import dev.dmigrate.server.ports.UploadSessionStore
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.util.UUID

/**
 * LF-012 / LN-038 wiring bundle per LF-012 / LN-027 / LN-028 / LN-038. Holds every
 * fachliche Abhaengigkeit the LF-012 / LN-038 handlers need so the bootstrap
 * can build the registry in one shot rather than threading 10+
 * stores through `startStdio` / `startHttp`.
 *
 * The bundle is meant to be assembled once at server start by the
 * caller (CLI, embed-test, future driver wiring), then passed
 * verbatim into [McpRuntimeRegistries.defaultToolRegistry]. Individual
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
data class McpRuntimeWiring(
    val uploadSessionStore: UploadSessionStore,
    val uploadSegmentStore: UploadSegmentStore,
    val artifactStore: ArtifactStore,
    val artifactContentStore: ArtifactContentStore,
    val schemaStore: SchemaStore,
    val jobStore: JobStore,
    val quotaService: QuotaService,
    val limits: McpLimitsConfig,
    val clock: Clock,
    val operationTimeout: Duration = Duration.ofMinutes(5),
    val finalizer: SchemaStagingFinalizer = DefaultSchemaStagingFinalizer(
        artifactStore = artifactStore,
        artifactContentStore = artifactContentStore,
        schemaStore = schemaStore,
        validator = SchemaValidator(),
        clock = clock,
    ),
    /**
     * LF-010 / LF-013 / LN-009 / LN-011 § 8.5 (F.5 3/3): Finaliser fuer policy-pflichtige
     * `uploadIntent=job_input`-Sessions. Materialisiert die
     * Bytes via [artifactContentStore] und registriert den
     * [ArtifactRecord] (kind=session.artifactKind,
     * contentType=session.mimeType). Default = [DefaultJobInputFinalizer]
     * mit den Bestands-Stores; Tests duerfen einen Stub injizieren.
     */
    val jobInputFinalizer: JobInputFinalizer = DefaultJobInputFinalizer(
        artifactStore = artifactStore,
        artifactContentStore = artifactContentStore,
        clock = clock,
    ),
    /**
     * LF-012 / LN-027 / LN-028 / LN-038: optional audit sink. When supplied, every `tools/call`
     * dispatched through `McpServiceImpl` records a single
     * [dev.dmigrate.server.core.audit.AuditEvent] (success and
     * failure paths alike). Production wiring usually plugs in
     * `LoggingAuditSink`; tests can leave this null to opt out, or
     * supply `InMemoryAuditSink` to assert event shape.
     */
    val auditSink: AuditSink? = null,
    /**
     * LF-010 / LF-013 / LN-009 / LN-011: factory that allocates a streaming spool for the
     * `ArtifactUploadHandler` finalisation path. Production CLI
     * wires the file-spool variant under the LF-012 / LN-027 / LN-028 / LN-038 state dir;
     * tests/dev default to `AssembledUploadPayloadFactory.inMemory()`.
     */
    val assembledUploadPayloadFactory: AssembledUploadPayloadFactory = AssembledUploadPayloadFactory.inMemory(),

    /**
     * LF-012 / LN-038: store backing `profile_list`. No LF-012 / LN-038 tool emits
     * profile records yet (LF-012 / LN-038 start tools will), so the
     * default is the no-op [EmptyProfileStore]. Integration tests
     * inject an `InMemoryProfileStore` so staged profiles
     * round-trip through the discovery handler.
     */
    val profileStore: ProfileStore = EmptyProfileStore,

    /**
     * LF-012 / LN-038: store backing `diff_list`. Same default-empty
     * rationale as [profileStore] — `schema_compare` produces
     * `diffArtifactRef` but no DiffIndexEntry yet; LF-012 / LN-038 start
     * tools will.
     */
    val diffStore: DiffStore = EmptyDiffStore,

    /**
     * LF-012 / LN-038: secret-free connection-reference store. The MCP
     * bootstrap typically wires
     * `LoaderBackedConnectionReferenceStore(YamlConnectionReferenceLoader(...))`
     * so `resources/list`, `resources/read` and the discovery
     * tools see the deployment's connection refs without ever
     * materialising the resolved JDBC URL or the expanded secret.
     * The default stays empty for tests / CLI-only deployments
     * that don't carry a LF-012 / LN-038 YAML.
     */
    val connectionStore: ConnectionReferenceStore = EmptyConnectionStore,

    /**
     * LF-012 / LN-038 + LF-012 / LN-038 review: HMAC keyring backing every
     * LF-012 / LN-038 MCP cursor (`resources/list`, `*_list` discovery
     * tools, chunk follow-ups).
     *
     * Default is the **deterministic dev keyring** ([DEV_DEFAULT])
     * so tests and single-process dev runs stay reproducible —
     * a cursor minted in one test method round-trips into the
     * next without surprise verification failures, and LF-012 / LN-038
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
    val policyService: PolicyService = ConfiguredPolicyService(
        rules = emptyList(),
        defaultEffect = PolicyEffect.Challenge(
            requiredScopes = setOf("dmigrate:artifact:upload"),
            reasons = listOf("policy:no-rule"),
        ),
    ),
    val payloadFingerprintService: PayloadFingerprintService = DefaultPayloadFingerprintService(),
    val syncEffectStore: SyncEffectIdempotencyStore = InProcessSyncEffectIdempotencyStore(),
    val uploadInitClaimStore: UploadInitClaimStore = InProcessUploadInitClaimStore(),
    val abortOutcomeStore: AbortOutcomeStore = InProcessAbortOutcomeStore(),
    val uploadInitApprovalFingerprint: UploadInitApprovalFingerprint =
        UploadInitApprovalFingerprint(payloadFingerprintService),
    val abortApprovalFingerprint: AbortApprovalFingerprint =
        AbortApprovalFingerprint(payloadFingerprintService),
    val uploadInitOrchestrator: UploadInitOrchestrator = UploadInitOrchestrator(
        syncEffectStore = syncEffectStore,
        claimStore = uploadInitClaimStore,
        sessionStore = uploadSessionStore,
        policyService = policyService,
        approvalFingerprintService = uploadInitApprovalFingerprint,
        quotaService = quotaService,
    ),
) {
    companion object {

        /**
         * LF-012 / LN-038 dev/test keyring: a fixed `kid`/secret pair
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
