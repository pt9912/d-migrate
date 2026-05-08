package dev.dmigrate.cli.integration

import dev.dmigrate.mcp.protocol.ToolMetadata
import dev.dmigrate.mcp.server.McpServerConfig

/**
 * AP 6.24 E2: canonical Phase-C tool matrix per
 * `ImpPlan-0.9.6-C.md` §3.1 (In Scope) + the Phase-C registry built
 * by [dev.dmigrate.mcp.registry.PhaseCRegistries.defaultToolRegistry].
 *
 * The integration suite checks `tools/list` against this set on both
 * transports — drift in either direction (a Phase-C tool missing
 * from a transport, or a transport advertising a non-Phase-C tool
 * via the Phase-C registry) fails the suite with a per-transport
 * diagnostic.
 *
 * Aliases: §3.1 ships no Phase-C-tool aliases — the spec uses the
 * canonical tool names directly. If a future AP introduces an alias
 * (e.g. `schema_validate` → `validate_schema`), add it to
 * [PHASE_C_TOOL_ALIASES] so the drift report keeps reading false
 * negatives out.
 */
internal object PhaseCToolMatrix {

    /**
     * The canonical 9 Phase-C tools from §3.1. `tools/list` MAY
     * advertise more (Phase-B registers the 0.9.6 universe so
     * Protected Resource Metadata stays consistent — see
     * `McpServerConfig.buildDefaultScopeMapping`), but every entry
     * here MUST appear on both transports.
     */
    val PHASE_C_TOOLS: Set<String> = setOf(
        "capabilities_list",
        "schema_validate",
        "schema_generate",
        "schema_compare",
        "artifact_chunk_get",
        "artifact_upload_init",
        "artifact_upload",
        "artifact_upload_abort",
        "job_status_get",
    )

    val PHASE_C_TOOL_ALIASES: Map<String, Set<String>> = emptyMap()

    /**
     * Canonical Phase-B+C tool universe — the set every transport's
     * `tools/list` MUST be a subset of. Built from
     * [McpServerConfig.DEFAULT_SCOPE_MAPPING]'s keys minus the
     * MCP protocol method names (which are dispatched separately and
     * never advertised as tools, per `PhaseBRegistries.PROTOCOL_METHODS`).
     *
     * This is the deterministic source the drift assertion checks
     * against — passing the union of stdio + http advertised names
     * would only catch a transport-asymmetric divergence, missing a
     * common-extra-tool regression where both transports gain the
     * same surplus tool.
     */
    val EXPECTED_TOOL_SUPERSET: Set<String> = run {
        val protocolMethods = setOf(
            "tools/list",
            "tools/call",
            "resources/list",
            "resources/templates/list",
            "resources/read",
            "connections/list",
        )
        McpServerConfig.DEFAULT_SCOPE_MAPPING.keys - protocolMethods
    }

    /**
     * @return per-transport drift report:
     * - `missing`: Phase-C tools NOT advertised by the transport
     * - `unexpected`: tools advertised by the transport that are
     *   neither in [PHASE_C_TOOLS] nor in the Phase-B universe
     *   (`expectedSuperset` lets the caller pass the Phase-B
     *   scope-mapping keys so non-Phase-C-but-registered tools
     *   don't show up as drift)
     */
    fun drift(
        advertised: Set<String>,
        expectedSuperset: Set<String> = advertised,
    ): Drift = Drift(
        missing = (PHASE_C_TOOLS - advertised).toSortedSet(),
        unexpected = (advertised - expectedSuperset).toSortedSet(),
    )

    fun toolNames(metadata: List<ToolMetadata>): Set<String> = metadata.map { it.name }.toSet()

    data class Drift(
        val missing: Set<String>,
        val unexpected: Set<String>,
    ) {
        fun isEmpty(): Boolean = missing.isEmpty() && unexpected.isEmpty()

        fun render(transportName: String): String = buildString {
            appendLine("$transportName tools/list drift:")
            appendLine("  missing Phase-C tools: ${missing.ifEmpty { "(none)" }}")
            appendLine("  unexpected non-Phase-C tools: ${unexpected.ifEmpty { "(none)" }}")
        }
    }
}
