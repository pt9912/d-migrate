package dev.dmigrate.mcp.registry

import dev.dmigrate.core.validation.SchemaValidator
import dev.dmigrate.mcp.cursor.McpCursorCodec
import dev.dmigrate.mcp.schema.SchemaContentLoader
import dev.dmigrate.mcp.schema.SchemaSourceResolver
import dev.dmigrate.mcp.server.McpServerConfig
import dev.dmigrate.server.application.audit.AuditScope

/**
 * Phase-C wiring entry point per `ImpPlan-0.9.6-C.md` §6.1.
 *
 * Override semantics worth pinning down:
 * - keys MUST be tool names already registered by [PhaseBRegistries] —
 *   an unknown name would otherwise become a silent `tools/call -32601`
 *   at runtime instead of failing fast at boot.
 * - keys MUST NOT be MCP-protocol method names (`tools/list`,
 *   `resources/read`, ...). Those are dispatched by the protocol layer,
 *   not the tool registry, so an override would silently no-op.
 * - non-overridden tools keep dispatching to [UnsupportedToolHandler]
 *   so the `UNSUPPORTED_TOOL_OPERATION` envelope is preserved while
 *   later phases land their handlers incrementally.
 *
 * Both transports MUST share the SAME registry instance — §6.1
 * acceptance ("Aufrufe nutzen denselben Handler unabhaengig vom
 * Transport"). Build once, pass into both
 * `McpServerBootstrap.startStdio` and `startHttp`.
 */
object PhaseCRegistries {

    fun toolRegistry(
        scopeMapping: Map<String, Set<String>> = McpServerConfig.DEFAULT_SCOPE_MAPPING,
        handlerOverrides: Map<String, ToolHandler> = emptyMap(),
    ): ToolRegistry {
        if (handlerOverrides.isEmpty()) return PhaseBRegistries.toolRegistry(scopeMapping)

        val protocolHits = handlerOverrides.keys.intersect(PhaseBRegistries.PROTOCOL_METHODS)
        check(protocolHits.isEmpty()) {
            "handlerOverrides target MCP-protocol methods ${protocolHits.sorted()} — " +
                "those are dispatched by the protocol layer, not the tool registry"
        }

        val base = PhaseBRegistries.toolRegistry(scopeMapping)
        val unknown = handlerOverrides.keys.filter { base.find(it) == null }
        check(unknown.isEmpty()) {
            "handlerOverrides target unregistered tools: ${unknown.sorted()} " +
                "(register them via scopeMapping + PhaseBToolSchemas first)"
        }

        val builder = ToolRegistry.builder()
        for (descriptor in base.all()) {
            val handler = handlerOverrides[descriptor.name] ?: base.findHandler(descriptor.name)!!
            builder.register(descriptor, handler)
        }
        return builder.build()
    }

    /**
     * AP 6.14: builds the production-ready Phase-C registry by
     * instantiating every fachlichen Handler from §3.1 and routing
     * them through [toolRegistry]. The bootstrap calls this once at
     * server start so `tools/call` dispatches into the real handlers
     * instead of the Phase-B `UnsupportedToolHandler` fallback.
     *
     * Stays a separate entry point from [toolRegistry] so existing
     * Phase-B tests (which call `toolRegistry()` with no overrides)
     * keep their incremental-fallback semantics — backwards-
     * compatible per AP 6.14 acceptance.
     */
    fun defaultToolRegistry(
        wiring: PhaseCWiring,
        scopeMapping: Map<String, Set<String>> = McpServerConfig.DEFAULT_SCOPE_MAPPING,
    ): ToolRegistry {
        // Capabilities first: it surfaces the registered tools list,
        // so it must be built from the SAME descriptors the
        // PhaseB-registry hands back. We let PhaseBRegistries seed
        // the descriptor universe and then override every handler
        // via this layer's contract.
        val resolver = SchemaSourceResolver(wiring.schemaStore, wiring.limits)
        val contentLoader = SchemaContentLoader(
            wiring.artifactStore,
            wiring.artifactContentStore,
            wiring.limits,
        )
        val artifactSink = ArtifactSink(
            wiring.artifactStore,
            wiring.artifactContentStore,
            wiring.clock,
        )
        val descriptors = PhaseBRegistries.toolRegistry(scopeMapping).all()
        val capabilitiesHandler = CapabilitiesListReadOnlyHandler(
            tools = descriptors,
            scopeMapping = scopeMapping,
            limits = wiring.limits,
        )
        // AP D8: one cursor codec instance, shared across the five
        // discovery list-tools. SealedListToolCursor stamps a
        // per-tool `cursorType` into the binding, so the shared
        // codec still emits cursors that can't be replayed across
        // tools.
        val listToolCursor = SealedListToolCursor(
            codec = McpCursorCodec(keyring = wiring.cursorKeyring, clock = wiring.clock),
            clock = wiring.clock,
        )
        val overrides: Map<String, ToolHandler> = mapOf(
            "capabilities_list" to capabilitiesHandler,
            "schema_validate" to SchemaValidateHandler(
                resolver = resolver,
                contentLoader = contentLoader,
                validator = SchemaValidator(),
                limits = wiring.limits,
                artifactSink = artifactSink,
            ),
            "schema_generate" to SchemaGenerateHandler(
                resolver = resolver,
                contentLoader = contentLoader,
                artifactSink = artifactSink,
                limits = wiring.limits,
            ),
            "schema_compare" to SchemaCompareHandler(
                resolver = resolver,
                contentLoader = contentLoader,
                comparator = dev.dmigrate.core.diff.SchemaComparator(),
                artifactSink = artifactSink,
                limits = wiring.limits,
            ),
            "artifact_chunk_get" to ArtifactChunkGetHandler(
                artifactStore = wiring.artifactStore,
                contentStore = wiring.artifactContentStore,
                limits = wiring.limits,
                // AP D9: wire the HMAC-sealed `nextChunkCursor` so
                // the chunk-walk produces both `nextChunkUri` and
                // `nextChunkCursor` (Tool-pfad). The shared codec is
                // safe — SealedChunkCursor stamps cursorType into
                // the binding so the cursor can't be replayed
                // across tools.
                cursorCodec = SealedChunkCursor(
                    codec = McpCursorCodec(keyring = wiring.cursorKeyring, clock = wiring.clock),
                    clock = wiring.clock,
                ),
            ),
            "artifact_upload_init" to ArtifactUploadInitHandler(
                sessionStore = wiring.uploadSessionStore,
                quotaService = wiring.quotaService,
                limits = wiring.limits,
                options = ArtifactUploadInitHandler.Options(clock = wiring.clock),
            ),
            "artifact_upload" to ArtifactUploadHandler(
                sessionStore = wiring.uploadSessionStore,
                segmentStore = wiring.uploadSegmentStore,
                quotaService = wiring.quotaService,
                limits = wiring.limits,
                options = ArtifactUploadHandler.Options(
                    clock = wiring.clock,
                    finalizer = wiring.finalizer,
                    // AP 6.22: thread the file-spool factory from the
                    // wiring DTO into the handler — the handler default is
                    // the in-memory variant, which would defeat the
                    // streaming heap guarantee in production.
                    payloadFactory = wiring.assembledUploadPayloadFactory,
                ),
            ),
            "artifact_upload_abort" to ArtifactUploadAbortHandler(
                sessionStore = wiring.uploadSessionStore,
                segmentStore = wiring.uploadSegmentStore,
                quotaService = wiring.quotaService,
                clock = wiring.clock,
            ),
            "job_status_get" to JobStatusGetHandler(jobStore = wiring.jobStore),
            // AP D6 + AP D8: discovery list tools with HMAC-sealed
            // cursors. The shared SealedListToolCursor wraps every
            // per-tool resumeToken so a cursor minted for tool A /
            // tenant X / filters F cannot be replayed against tool B
            // or filters F'.
            "job_list" to JobListHandler(wiring.jobStore, listToolCursor),
            "artifact_list" to ArtifactListHandler(wiring.artifactStore, listToolCursor),
            "schema_list" to SchemaListHandler(wiring.schemaStore, listToolCursor),
            "profile_list" to ProfileListHandler(wiring.profileStore, listToolCursor),
            "diff_list" to DiffListHandler(wiring.diffStore, listToolCursor),
        )
        // Filter to the scope-mapping universe so a deployment that
        // narrows the tool set (e.g. read-only vs full Phase-C)
        // doesn't trip the "handlerOverrides target unregistered
        // tools" guard in `toolRegistry`.
        val filtered = overrides.filterKeys { it in scopeMapping.keys }
        return toolRegistry(scopeMapping, filtered)
    }

    /**
     * AP 6.19: the dispatch-time request/response byte enforcer
     * matching the [defaultToolRegistry]. The bootstrap wires this
     * into [dev.dmigrate.mcp.protocol.McpServiceImpl] so every
     * `tools/call` is bracketed by the same byte caps the
     * [defaultToolRegistry] handlers rely on for their inline /
     * artefact split. Phase-B-only deployments can leave the
     * enforcer null — `dispatch` then behaves as before.
     */
    fun defaultResponseLimitEnforcer(wiring: PhaseCWiring): ResponseLimitEnforcer =
        ResponseLimitEnforcer(
            limits = wiring.limits,
            artifactSink = ArtifactSink(
                wiring.artifactStore,
                wiring.artifactContentStore,
                wiring.clock,
            ),
        )

    /**
     * Bundles the per-call dispatch components a single `tools/call`
     * needs — the registry that finds the handler, the byte-limit
     * enforcer that brackets it, and (AP 6.20) the audit scope that
     * records one event per call. The bootstrap takes one of these
     * instead of three correlated parameters with cross-defaults so
     * the `phaseCWiring?.let { ... }`-fallback expression lives in
     * one place. Phase-B-only callers (no `PhaseCWiring`) get a
     * registry with no enforcer and no audit — the same shape
     * `dispatch` already handles.
     */
    data class McpServiceComponents(
        val toolRegistry: ToolRegistry,
        val responseLimitEnforcer: ResponseLimitEnforcer?,
        val auditScope: AuditScope?,
        /**
         * AP D7: capabilities document for `resources/read
         * dmigrate://capabilities`. Built from the same descriptors +
         * scope mapping + limits the [CapabilitiesListReadOnlyHandler]
         * uses so the resource and the tool stay in lock-step.
         * Empty map means the URI is wired but the document is
         * unconfigured — `resources/read` falls back to
         * `RESOURCE_NOT_FOUND` for that case.
         */
        val capabilitiesProvider: () -> Map<String, Any?> = { emptyMap() },
        /**
         * AP D8: HMAC cursor codec backing `resources/list` and the
         * `*_list` discovery tools. Null in Phase-B-only deployments
         * (no PhaseCWiring) — the dispatcher then falls back to the
         * legacy unsigned Base64 cursor for resources/list and to
         * `nextCursor=null` for the discovery tools (AP D6 no-op).
         */
        val cursorCodec: McpCursorCodec? = null,
    )

    fun defaultComponents(
        phaseCWiring: PhaseCWiring?,
        scopeMapping: Map<String, Set<String>> = McpServerConfig.DEFAULT_SCOPE_MAPPING,
    ): McpServiceComponents = if (phaseCWiring != null) {
        val toolRegistry = defaultToolRegistry(phaseCWiring, scopeMapping)
        val capabilitiesHandler = CapabilitiesListReadOnlyHandler(
            tools = toolRegistry.all(),
            scopeMapping = scopeMapping,
            limits = phaseCWiring.limits,
        )
        McpServiceComponents(
            toolRegistry = toolRegistry,
            responseLimitEnforcer = defaultResponseLimitEnforcer(phaseCWiring),
            auditScope = phaseCWiring.auditSink?.let { AuditScope(it, phaseCWiring.clock) },
            capabilitiesProvider = capabilitiesHandler::staticPayload,
            cursorCodec = McpCursorCodec(
                keyring = phaseCWiring.cursorKeyring,
                clock = phaseCWiring.clock,
            ),
        )
    } else {
        McpServiceComponents(
            toolRegistry = PhaseBRegistries.toolRegistry(scopeMapping),
            responseLimitEnforcer = null,
            auditScope = null,
        )
    }
}
