package dev.dmigrate.mcp.server

/**
 * Numeric Phase-C limit set per `ImpPlan-0.9.6-C.md` §4.2.
 *
 * Single source of truth for every byte/findings cap the read-only
 * tools advertise via `capabilities_list` and (later, AP 6.13)
 * enforce on responses.
 */
data class McpLimitsConfig(
    val maxToolResponseBytes: Int = 65_536,
    val maxNonUploadToolRequestBytes: Int = 262_144,
    val maxInlineSchemaBytes: Int = 32_768,
    val maxUploadToolRequestBytes: Int = 6_291_456,
    val maxUploadSegmentBytes: Int = 4_194_304,
    val maxArtifactChunkBytes: Int = 32_768,
    val maxInlineFindings: Int = 200,
    val maxArtifactUploadBytes: Long = 209_715_200L,
)
