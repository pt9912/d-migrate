package dev.dmigrate.cli.integration

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive

/**
 * AP 6.24: normalisation helpers for transport-neutral equality
 * assertions. Replace transport- / runtime-dependent values
 * (request ids, session ids, dynamic resource ids, absolute paths,
 * timestamps) with stable placeholders so a stdio response and an
 * HTTP response of the same business call compare equal at the
 * field level.
 *
 * Plan §6.24 explicitly preserves business fields (toolname,
 * error-code, isError, schemaRef-shape, resource-URI shape,
 * truncated, artifactRef, finding-code/severity/path, audit-outcome)
 * — the helpers below only mask transport noise.
 */
internal object TransportNormalisation {

    /**
     * Returns a deep copy of [node] with the supplied object-key
     * paths overwritten by `<masked>`. Use this for fields that
     * vary between transports for non-business reasons (e.g.
     * `executionMeta.requestId`, `MCP-Session-Id`-derived URIs).
     */
    fun maskFields(node: JsonElement, paths: Set<String>): JsonElement = when (node) {
        is JsonObject -> JsonObject().also { copy ->
            for ((key, value) in node.entrySet()) {
                if (key in paths) copy.addProperty(key, MASKED) else copy.add(key, maskFields(value, paths))
            }
        }
        else -> node.deepCopy()
    }

    /**
     * Strips the trailing `/{dynamicId}` from artefact / schema /
     * upload-session resource URIs so two responses that produced
     * different (but well-formed) ids still compare equal at the
     * shape layer.
     */
    fun normaliseResourceUri(raw: String): String =
        DYNAMIC_RESOURCE_URI.replace(raw) { match ->
            "${match.groupValues[1]}/<masked>"
        }

    /**
     * AP 6.24 E2: walks [node] recursively and replaces every string
     * value that matches a Phase-C resource-URI shape with its
     * normalised form (dynamic id → `<masked>`). Used for transport-
     * neutral equality of payloads that carry per-call dynamic ids in
     * `artifactRef`, `schemaRef`, `diffArtifactRef`, etc.
     *
     * Plan §6.24: the URI shape (tenant + kind + dynamic-id slot) is
     * a fachliches Feld and stays asserted; only the dynamic id is
     * masked.
     */
    fun normaliseResourceUris(node: JsonElement): JsonElement = when {
        node.isJsonObject -> JsonObject().also { copy ->
            for ((key, value) in node.asJsonObject.entrySet()) {
                copy.add(key, normaliseResourceUris(value))
            }
        }
        node.isJsonArray -> com.google.gson.JsonArray().also { copy ->
            for (element in node.asJsonArray) copy.add(normaliseResourceUris(element))
        }
        node.isJsonPrimitive && node.asJsonPrimitive.isString ->
            JsonPrimitive(normaliseResourceUri(node.asString))
        else -> node.deepCopy()
    }

    const val MASKED: String = "<masked>"

    private val DYNAMIC_RESOURCE_URI: Regex =
        Regex("""(dmigrate://tenants/[^/]+/(?:artifacts|schemas|upload_sessions|jobs))/[^/\s"]+""")
}
