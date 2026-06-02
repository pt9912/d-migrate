package dev.dmigrate.mcp.server

/**
 * Numeric LF-012 / LN-038 limit set per LF-012 / LN-027 / LN-028 / LN-038
 *
 * Single source of truth for every byte/findings cap the read-only
 * tools advertise via `capabilities_list` and (later, LF-012 / LN-027 / LN-028 / LN-038)
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
    /**
     * LF-012 / LN-038: hard upper bound on the serialised
     * `resources/read` response (content array + metadata). Keeps the
     * `resources/read` envelope under the same 64 KiB ceiling
     * `tools/call` enforces.
     */
    val maxResourceReadResponseBytes: Int = 65_536,
    /**
     * LF-012 / LN-038: per-content inline UTF-8 body cap. A
     * resolver may inline a text/JSON body only when its UTF-8 byte
     * count is `<=` this limit AND the resulting envelope fits under
     * [maxResourceReadResponseBytes]. Larger bodies surface as an
     * `artifactRef` / `nextChunkUri` referral.
     */
    val maxInlineResourceContentBytes: Int = 49_152,
)
