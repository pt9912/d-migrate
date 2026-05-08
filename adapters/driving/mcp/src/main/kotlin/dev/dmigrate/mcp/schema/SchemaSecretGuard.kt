package dev.dmigrate.mcp.schema

/**
 * Verifies tool schemas don't accept secret-shaped properties per
 * LF-012 / LN-027 / LN-028 / LN-038§6.10 ("Schemas, die rohe JDBC-Secrets
 * als Tool-Payload erlauben" sind verboten).
 *
 * Rationale: even though LF-012 / LN-038 doesn't dispatch most tools, a tool
 * that admits `password` / `apiKey` / `jdbcUrl` as input — even by
 * accident — turns the MCP transport into a secret-funnelling channel.
 * LF-012 / LN-027 / LN-028 / LN-038 closes that hole at the contract layer: the registry refuses
 * to publish a schema that names any forbidden property.
 *
 * The guard walks the schema tree (objects, arrays, nested
 * `properties`, `items`, `oneOf` / `anyOf` / `allOf` branches, `$defs`)
 * and reports the path of every offending property name.
 */
internal object SchemaSecretGuard {

    /**
     * Substring tokens that MUST NOT appear in any LF-012 / LN-038 tool input
     * / output schema property name AFTER normalisation (lowercase +
     * stripping of `_`, `-`, `.`). LF-012 / LN-027 / LN-028 / LN-038 widens LF-012 / LN-027 / LN-028 / LN-038's exact-
     * lowercase match to a normalised substring match so variants
     * like `dbPassword`, `credentialToken`, `api_token`, `JDBC.Url`
     * are blocked without an explicit allowlist entry per spelling.
     *
     * The list is intentionally explicit (not a fuzzy regex) so
     * future additions go through review.
     */
    val FORBIDDEN_TOKENS: Set<String> = setOf(
        "password",
        "passwd",
        "secret",
        "token",
        "credential",
        "connectionstring",
        "jdbcurl",
        "apikey",
        "privatekey",
    )

    /**
     * Forbidden names that are not safe to substring-match (because
     * the substring would itself be too generic). Match is on the
     * fully normalised name only.
     */
    val FORBIDDEN_EXACT: Set<String> = setOf(
        "providerref",
    )

    /**
     * LF-012 / LN-027 / LN-028 / LN-038 review W1 escape hatch: normalised property names that
     * would match a [FORBIDDEN_TOKENS] substring but are documented-
     * legitimate (e.g. `tokenizer`, `tokenBucketRate`). Empty today
     * because no LF-012 / LN-038/C schema collides; entries MUST go through
     * code review and carry a comment that justifies the override.
     * The list lets future tools opt out of a false-positive without
     * weakening the substring match for everyone else.
     */
    val ALLOWED_OVERRIDES: Set<String> = setOf(
        // LF-012 / LN-011 / LN-017 / LN-027 §7.4: `approvalToken` ist ein client-praesentiertes
        // Approval-Token im Approved-Retry-Flow. Es wandert nie in
        // Store/Audit — der Server fingerprint't es per
        // ApprovalTokenFingerprint.compute(...) und verwirft das Klartext-
        // Token sofort danach. Der Substring `token` matcht generisch,
        // aber dieses Feld ist ausdruecklich Teil des MCP-Protokolls
        // (LF-012 / LN-011 / LN-017 / LN-027 / §7.6 input).
        "approvaltoken",
    )

    /**
     * LF-012 / LN-027 / LN-028 / LN-038: backwards-compat alias. Old call sites or tests that
     * inspect the forbidden list see the normalised substrings; the
     * exact-only entries are folded in. Use [isForbidden] for
     * decisions — it applies the same normalisation as the walker.
     */
    @Deprecated(
        "Use isForbidden(name); FORBIDDEN_PROPERTIES no longer enumerates every variant.",
        ReplaceWith("isForbidden(name)"),
    )
    val FORBIDDEN_PROPERTIES: Set<String> = FORBIDDEN_TOKENS + FORBIDDEN_EXACT

    /**
     * LF-012 / LN-027 / LN-028 / LN-038 normalisation: lowercase + drop `_`, `-`, `.` so
     * `db_password`, `dbPassword`, `Db.Password`, `DB-PASSWORD` all
     * collapse to `dbpassword`. The walker compares the normalised
     * form against [FORBIDDEN_TOKENS] (substring) and
     * [FORBIDDEN_EXACT] (full match).
     */
    fun normalize(name: String): String =
        name.lowercase().replace(NORMALISATION_REGEX, "")

    /**
     * True if [name] (after normalisation) hits a forbidden token /
     * exact entry, unless explicitly listed in [ALLOWED_OVERRIDES].
     */
    fun isForbidden(name: String): Boolean {
        val normalised = normalize(name)
        if (normalised in ALLOWED_OVERRIDES) return false
        if (normalised in FORBIDDEN_EXACT) return true
        return FORBIDDEN_TOKENS.any { it in normalised }
    }

    private val NORMALISATION_REGEX: Regex = Regex("[_\\-.]")

    /**
     * Walks [schema] and returns a list of paths (e.g.
     * `properties.connection.password`) where a forbidden property
     * name appears. Empty list means the schema is clean.
     */
    fun findSecretLeaks(schema: Map<String, Any?>): List<String> {
        val leaks = mutableListOf<String>()
        walk(schema, path = "", leaks = leaks)
        return leaks
    }

    private fun walk(node: Any?, path: String, leaks: MutableList<String>) {
        when (node) {
            is Map<*, *> -> walkMap(node, path, leaks)
            is List<*> -> node.forEachIndexed { idx, element ->
                walk(element, "$path[$idx]", leaks)
            }
            else -> Unit
        }
    }

    private fun walkMap(
        node: Map<*, *>,
        path: String,
        leaks: MutableList<String>,
    ) {
        // `properties`: keys ARE payload field names; the strings we
        // actually want to check against the forbidden-name list.
        // Values are nested schemas to recurse into.
        walkPropertyKeyedMap(node["properties"], path, "properties", checkKeyName = true, leaks)

        // `patternProperties`, `$defs`, `definitions`: keys are
        // schema regex / def names (NOT payload names — definitions
        // can have any name without leaking secrets), but values are
        // nested schemas we still need to walk.
        walkPropertyKeyedMap(node["patternProperties"], path, "patternProperties", checkKeyName = false, leaks)
        walkPropertyKeyedMap(node["\$defs"], path, "\$defs", checkKeyName = false, leaks)
        walkPropertyKeyedMap(node["definitions"], path, "definitions", checkKeyName = false, leaks)

        // Single-schema and list-of-schemas keywords. Their values are
        // schemas (or lists of schemas) — neither shape carries a
        // payload-name key, so we just recurse straight into them.
        for (kw in SCHEMA_VALUE_KEYWORDS) {
            val sub = node[kw] ?: continue
            walk(sub, if (path.isEmpty()) kw else "$path.$kw", leaks)
        }
    }

    private fun walkPropertyKeyedMap(
        node: Any?,
        parentPath: String,
        keyword: String,
        checkKeyName: Boolean,
        leaks: MutableList<String>,
    ) {
        if (node !is Map<*, *>) return
        for ((rawKey, child) in node) {
            val keyName = rawKey as? String ?: continue
            val childPath = if (parentPath.isEmpty()) "$keyword.$keyName" else "$parentPath.$keyword.$keyName"
            if (checkKeyName && isForbidden(keyName)) {
                leaks += childPath
            }
            walk(child, childPath, leaks)
        }
    }

    private val SCHEMA_VALUE_KEYWORDS: List<String> = listOf(
        "items",
        "additionalProperties",
        "oneOf",
        "anyOf",
        "allOf",
        "not",
        "if",
        "then",
        "else",
        "prefixItems",
        "contains",
        "unevaluatedItems",
        "unevaluatedProperties",
        "propertyNames",
    )
}
