package dev.dmigrate.mcp.registry

import dev.dmigrate.mcp.resources.PhaseBResourceTemplates
import dev.dmigrate.mcp.schema.PhaseBToolSchemas
import dev.dmigrate.mcp.server.McpServerConfig
import dev.dmigrate.server.core.error.ToolErrorCode

/**
 * Builds the Phase-B default tool/resource registries per
 * `ImpPlan-0.9.6-B.md` §3.1 + §6.8 + §12.11.
 *
 * Phase B registers EVERY 0.9.6 tool so `tools/list` advertises the
 * full contract; only `capabilities_list` is wired to a real handler
 * (per §12.11). All other tools dispatch through
 * [UnsupportedToolHandler], which raises
 * `UnsupportedToolOperationException` — translated to a tool result
 * with `isError=true` and a `ToolErrorEnvelope` (§12.8).
 *
 * The descriptor metadata (titles, descriptions, error-codes,
 * inline-limit hints) is stable and reviewed for the contract; AP
 * 6.10 will replace the placeholder JSON-Schemas with real 2020-12
 * definitions and add the golden-test gate.
 *
 * Tool universe: every entry in `McpServerConfig.scopeMapping` that is
 * not an MCP-protocol method (`tools/list`, `resources/list`,
 * `resources/templates/list`, `resources/read`, `connections/list`)
 * counts as a tool. The protocol methods are scope-checked via the
 * same `McpServerConfig.scopeMapping` but are not listed in
 * `tools/list`.
 */
object PhaseBRegistries {

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
        // §12.11: capabilities_list MUST be present in Phase B —
        // it's the only tool with a real handler. A scopeMapping that
        // omits it would silently produce a server with zero working
        // tools; fail fast at build time instead.
        check("capabilities_list" in scopeMapping) {
            "scopeMapping must register 'capabilities_list' (§12.11 — Phase B's only fachlicher Handler)"
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
     * Phase B resource registry. §4.7 + §5.5: stdio and HTTP MUST
     * read templates from the same registry instance; the registry is
     * the single source of truth so `resources/templates/list` and
     * any future `resources/list`-template-driven projection in Phase
     * C/D can't drift apart.
     *
     * Phase B registers the 7 templates from [PhaseBResourceTemplates]
     * (jobs / artifacts / artifact-chunks / schemas / profiles /
     * diffs / connections) and ZERO concrete resources — concrete
     * resources are projected on the fly by `ResourcesListHandler`
     * from the configured stores.
     */
    fun resourceRegistry(): ResourceRegistry {
        val builder = ResourceRegistry.builder()
        for (template in PhaseBResourceTemplates.ALL) {
            builder.registerTemplate(template)
        }
        return builder.build()
    }

    private fun describe(name: String, scopes: Set<String>): ToolDescriptor {
        val schemas = PhaseBToolSchemas.forTool(name) ?: error(
            "no schema registered for tool '$name' — PhaseBToolSchemas must cover every entry in scopeMapping",
        )
        return ToolDescriptor(
            name = name,
            title = TITLES[name] ?: name,
            description = DESCRIPTIONS[name]
                ?: "0.9.6 contract tool '$name' (Phase B: registered, not implemented).",
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
        "job_status_get" to "Get job status",
        "artifact_list" to "List artifacts",
        "artifact_chunk_get" to "Read an artifact chunk",
        "schema_reverse_start" to "Start schema reverse-engineering job",
        "schema_compare_start" to "Start schema comparison job",
        "data_profile_start" to "Start data profiling job",
        "data_export_start" to "Start data export job",
        "artifact_upload_init" to "Init artifact upload session",
        "artifact_upload" to "Upload an artifact segment (implicit finalisation)",
        "artifact_upload_abort" to "Abort artifact upload",
        "data_import_start" to "Start data import job",
        "data_transfer_start" to "Start data transfer job",
        "job_cancel" to "Cancel a running job",
        "procedure_transform_plan" to "Plan procedure transform (AI)",
        "procedure_transform_execute" to "Execute procedure transform (AI)",
        "testdata_plan" to "Plan test data generation (AI)",
        "testdata_execute" to "Execute test data generation (AI)",
    )

    private val DESCRIPTIONS: Map<String, String> = mapOf(
        "capabilities_list" to (
            "Returns the static d-migrate contract: protocol versions, the registered tools, " +
                "and the scope table. Stores- and driver-free per ImpPlan §12.11."
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
