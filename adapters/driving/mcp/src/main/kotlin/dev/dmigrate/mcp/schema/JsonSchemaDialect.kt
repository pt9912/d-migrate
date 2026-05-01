package dev.dmigrate.mcp.schema

/**
 * Pinned JSON-Schema dialect per `ImpPlan-0.9.6-B.md` §5.6 + §6.10.
 *
 * MCP advertises tool input/output schemas using JSON Schema. d-migrate
 * pins **Draft 2020-12** as the only acceptable dialect — uniform
 * across stdio and HTTP, no transport-specific variants.
 *
 * If a Phase B tool schema sets `$schema`, it MUST be exactly
 * [SCHEMA_URI]. If `$schema` is absent, MCP's tool-schema contract
 * still treats the document as 2020-12.
 *
 * Draft-07-only keywords (`definitions`, top-level `id` without `$`,
 * etc.) are forbidden — see [DRAFT_07_FORBIDDEN_KEYWORDS].
 */
object JsonSchemaDialect {

    const val SCHEMA_URI: String = "https://json-schema.org/draft/2020-12/schema"

    const val SCHEMA_KEYWORD: String = "\$schema"

    /**
     * JSON-Schema keywords that exist in Draft-07 but NOT in 2020-12,
     * or whose meaning changed enough that mixing them is a contract
     * violation. The Phase-B golden test rejects any tool schema that
     * uses one of these at any nesting level.
     *
     * - `definitions`: replaced by `$defs` in 2020-12.
     * - `id` (without leading `$`): renamed to `$id` in Draft-06 and
     *   forbidden as a non-`$`-prefixed identifier in 2020-12.
     * - `dependencies`: split into `dependentSchemas` and
     *   `dependentRequired` in 2019-09 / 2020-12.
     */
    val DRAFT_07_FORBIDDEN_KEYWORDS: Set<String> = setOf(
        "definitions",
        "id",
        "dependencies",
    )
}
