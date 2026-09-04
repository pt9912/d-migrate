package dev.dmigrate.mcp.registry

import dev.dmigrate.mcp.resources.McpResourceTemplates
import dev.dmigrate.mcp.schema.McpToolSchemas
import dev.dmigrate.mcp.server.McpServerConfig
import dev.dmigrate.server.core.error.ToolErrorCode

/**
 * LF-012 / LN-027 / LN-028 / LN-038: builds the default MCP
 * tool/resource registries.
 *
 * The contract registry registers every advertised tool so
 * `tools/list` exposes the full contract; only `capabilities_list`
 * is wired to a real handler in this layer. All other tools dispatch through
 * [UnsupportedToolHandler], which raises
 * `UnsupportedToolOperationException` — translated to a tool result
 * with `isError=true` and a `ToolErrorEnvelope` (§12.8).
 *
 * The descriptor metadata (titles, descriptions, error-codes,
 * inline-limit hints) is stable and reviewed for the contract.
 *
 * Tool universe: every entry in `McpServerConfig.scopeMapping` that is
 * not an MCP-protocol method (`tools/list`, `resources/list`,
 * `resources/templates/list`, `resources/read`, `connections/list`)
 * counts as a tool. The protocol methods are scope-checked via the
 * same `McpServerConfig.scopeMapping` but are not listed in
 * `tools/list`.
 */
object McpContractRegistries {

    /**
     * §12.16 verbindlich: MCP-protocol method names that must NOT be
     * projected as tools. The default scope mapping only contains a
     * subset of these (`tools/call` and `resources/templates/list`
     * have no scope entry today), but custom scope-mappings can add
     * any of them and the filter still has to drop them — otherwise
     * a custom-scoped `tools/call` would land in `tools/list` and a
     * client could try to dispatch `tools/call` on itself.
     */
    internal val PROTOCOL_METHODS: Set<String> = setOf(
        "tools/list",
        "tools/call",
        "resources/list",
        "resources/templates/list",
        "resources/read",
        "connections/list",
        // LF-017 / LF-024 / LN-030 / LN-031: MCP-Prompt-Methoden sind ebenfalls
        // Protokoll-Slots, NICHT Tools. Sie laufen ueber den
        // [PromptsHandler] in [McpServiceImpl], nicht ueber die
        // Tool-Registry.
        "prompts/list",
        "prompts/get",
    )

    /**
     * Builds the default tool registry. Two-phase: (1) build all
     * descriptors, (2) build handlers — `capabilities_list` reads the
     * descriptor list, every other tool dispatches to
     * [UnsupportedToolHandler]. The descriptor list is the source of
     * truth for `tools/list`; the registry just associates each
     * descriptor with its handler.
     */
    fun toolRegistry(
        scopeMapping: Map<String, Set<String>> = McpServerConfig.DEFAULT_SCOPE_MAPPING,
    ): ToolRegistry {
        // LF-012 / LN-038: capabilities_list MUST be present —
        // it's the only tool with a real handler. A scopeMapping that
        // omits it would silently produce a server with zero working
        // tools; fail fast at build time instead.
        check("capabilities_list" in scopeMapping) {
            "scopeMapping must register 'capabilities_list' (required MCP contract handler)"
        }
        val descriptors = scopeMapping
            .filterKeys { it !in PROTOCOL_METHODS }
            .map { (name, scopes) -> describe(name, scopes) }
        val capabilitiesHandler = CapabilitiesListReadOnlyHandler(descriptors, scopeMapping)
        val builder = ToolRegistry.builder()
        for (descriptor in descriptors) {
            val handler: ToolHandler = if (descriptor.name == "capabilities_list") {
                capabilitiesHandler
            } else {
                UnsupportedToolHandler(descriptor.title)
            }
            builder.register(descriptor, handler)
        }
        return builder.build()
    }

    /**
     * LF-012 / LN-038: stdio and HTTP MUST
     * read templates from the same registry instance; the registry is
     * the single source of truth so `resources/templates/list` and
     * any future `resources/list`-template-driven projection can't drift apart.
 *
     * Registers the 7 templates from [McpResourceTemplates]
     * (jobs / artifacts / artifact-chunks / schemas / profiles /
     * diffs / connections) and ZERO concrete resources — concrete
     * resources are projected on the fly by `ResourcesListHandler`
     * from the configured stores.
     */
    fun resourceRegistry(): ResourceRegistry {
        val builder = ResourceRegistry.builder()
        for (template in McpResourceTemplates.ALL) {
            builder.registerTemplate(template)
        }
        return builder.build()
    }

    private fun describe(name: String, scopes: Set<String>): ToolDescriptor {
        val schemas = McpToolSchemas.forTool(name) ?: error(
            "no schema registered for tool '$name' — McpToolSchemas must cover every entry in scopeMapping",
        )
        return ToolDescriptor(
            name = name,
            title = TITLES[name] ?: name,
            description = DESCRIPTIONS[name]
                ?: "MCP contract tool '$name' — see spec/mcp-server.md for details.",
            requiredScopes = scopes,
            inputSchema = schemas.inputSchema,
            outputSchema = schemas.outputSchema,
            inlineLimits = INLINE_LIMITS[name],
            resourceFallbackHint = FALLBACK_HINTS[name],
            errorCodes = ERROR_CODES[name] ?: setOf(ToolErrorCode.UNSUPPORTED_TOOL_OPERATION),
        )
    }

    private val TITLES: Map<String, String> = mapOf(
        "capabilities_list" to "Capabilities (server contract)",
        "schema_validate" to "Validate schema document",
        "schema_compare" to "Compare two registered schemas (schemaRef vs schemaRef)",
        "schema_generate" to "Generate DDL from a neutral schema",
        "schema_list" to "List schema artifacts",
        "profile_list" to "List data profiles",
        "diff_list" to "List schema diffs",
        "job_list" to "List jobs",
        "job_status_get" to "Get the status and progress of a job",
        "artifact_list" to "List artifacts",
        "artifact_chunk_get" to "Read one chunk of an artifact (text or base64)",
        "schema_reverse_start" to "Start schema reverse-engineering job",
        "schema_compare_start" to "Start schema comparison job",
        "data_profile_start" to "Start data profiling job",
        "artifact_upload_init" to "Open a read-only schema-staging upload session",
        "artifact_upload" to "Upload one segment of an active staging session",
        "artifact_upload_abort" to "Abort one's own active staging session",
        "data_import_start" to "Start data import job",
        "data_transfer_start" to "Start data transfer job",
        "job_cancel" to "Cancel a running job",
        "procedure_transform_plan" to (
            "Analyse and plan a stored-procedure transformation against a target dialect. " +
                "Approval-driven, AI-backed; produces an immutable plan artifact (no executable target code)."
            ),
        "procedure_transform_execute" to (
            "Execute a previously approved procedure-transform plan and produce the target " +
                "artifact. Approval-driven; the plan reference is the only source of truth — " +
                "no source refs in this payload."
            ),
        "testdata_plan" to (
            "Plan synthetic test data from a schema, optional profiling summary and structured " +
                "rules. Approval-driven; produces an immutable plan artifact, not actual database writes."
            ),
        "testdata_execute" to "Execute test data generation (AI) — not part of 0.9.6 scope",
    )

    private val DESCRIPTIONS: Map<String, String> = mapOf(
        "capabilities_list" to (
            "Returns the static d-migrate contract: protocol versions, the registered tools, " +
                "and the scope table. Stores- and driver-free per LF-012 / LN-038."
            ),
    )

    private val INLINE_LIMITS: Map<String, String> = mapOf(
        "artifact_chunk_get" to "max 1 MiB per chunk; iterate via successive `chunkId` values",
        "schema_compare" to "max 1 MiB inline; larger diffs land as artifact",
    )

    private val FALLBACK_HINTS: Map<String, String> = mapOf(
        "artifact_chunk_get" to (
            "use `dmigrate://tenants/{tenantId}/artifacts/{artifactId}/chunks/{chunkId}` " +
                "template for streaming"
            ),
        "schema_list" to (
            "use `dmigrate://tenants/{tenantId}/schemas/{schemaId}` template for full schema reads"
            ),
    )

    private val ERROR_CODES: Map<String, Set<ToolErrorCode>> = mapOf(
        "capabilities_list" to emptySet(),
    )
}
