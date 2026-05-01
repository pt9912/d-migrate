package dev.dmigrate.mcp.schema

/**
 * Verifies tool schemas don't accept secret-shaped properties per
 * `ImpPlan-0.9.6-B.md` §5.6 + §6.10 ("Schemas, die rohe JDBC-Secrets
 * als Tool-Payload erlauben" sind verboten).
 *
 * Rationale: even though Phase B doesn't dispatch most tools, a tool
 * that admits `password` / `apiKey` / `jdbcUrl` as input — even by
 * accident — turns the MCP transport into a secret-funnelling channel.
 * AP 6.10 closes that hole at the contract layer: the registry refuses
 * to publish a schema that names any forbidden property.
 *
 * The guard walks the schema tree (objects, arrays, nested
 * `properties`, `items`, `oneOf` / `anyOf` / `allOf` branches, `$defs`)
 * and reports the path of every offending property name.
 */
internal object SchemaSecretGuard {

    /**
     * Property names that MUST NOT appear in a Phase-B tool input or
     * output schema. The list is intentionally explicit (not a
     * fuzzy regex) so future additions go through review. Comparison
     * is case-insensitive — `Password`, `PASSWORD`, `password` all
     * map to the same forbidden name.
     */
    val FORBIDDEN_PROPERTIES: Set<String> = setOf(
        "password",
        "passwd",
        "secret",
        "secrets",
        "token",
        "apikey",
        "api_key",
        "credentialref",
        "credentialsref",
        "providerref",
        "jdbcurl",
        "connectionstring",
        "connection_string",
        "privatekey",
        "private_key",
    )

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
            if (checkKeyName && keyName.lowercase() in FORBIDDEN_PROPERTIES) {
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
