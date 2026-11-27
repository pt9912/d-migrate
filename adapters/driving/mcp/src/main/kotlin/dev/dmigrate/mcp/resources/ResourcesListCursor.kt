package dev.dmigrate.mcp.resources

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonSyntaxException
import dev.dmigrate.server.core.resource.ResourceKind
import java.util.Base64

/**
 * Opaque cursor for `resources/list` per LF-012 / LN-027 / LN-028 / LN-038
 * §12.17. The cursor encodes `(kind, innerToken)` so the server can
 * resume:
 * - `kind` — the resource family currently being walked.
 * - `innerToken` — the underlying store's `PageRequest.pageToken` for
 *   that family, or `null` to start a fresh page within the family.
 *
 * Wire form: `Base64.URL_SAFE.encode(JSON({kind, innerToken}))`. MCP
 * treats the cursor as opaque — clients MUST NOT introspect it.
 *
 * A malformed cursor is reported back to the caller as an
 * `IllegalArgumentException`; the route translates that into JSON-RPC
 * `-32602` (Invalid params, §12.8).
 */
internal data class ResourcesListCursor(
    val kind: ResourceKind,
    val innerToken: String?,
) {
    fun encode(): String {
        // Feld fuer Feld statt ueber Reflexion (Issue #88, siehe McpCursorPayloadJson).
        val body = JsonObject()
        body.addProperty("kind", kind.name)
        innerToken?.let { body.addProperty("innerToken", it) }
        val json = GSON.toJson(body)
        return ENCODER.encodeToString(json.toByteArray(Charsets.UTF_8))
    }

    companion object {

        private val GSON = Gson()
        private val DECODER = Base64.getUrlDecoder()
        private val ENCODER = Base64.getUrlEncoder().withoutPadding()

        /**
         * Resource kinds that `resources/list` actually walks. Defined
         * here so the cursor decoder can reject forged cursors that
         * point at non-listable kinds (e.g. `UPLOAD_SESSIONS` is in
         * [ResourceKind] but isn't a Discovery surface — §12.17). The
         * handler imports this same set as its walk order.
         */
        val LISTABLE_KINDS: List<ResourceKind> = listOf(
            ResourceKind.JOBS,
            ResourceKind.ARTIFACTS,
            ResourceKind.SCHEMAS,
            ResourceKind.PROFILES,
            ResourceKind.DIFFS,
            ResourceKind.CONNECTIONS,
        )

        private val LISTABLE_KIND_SET: Set<ResourceKind> = LISTABLE_KINDS.toSet()

        /**
         * Parses [encoded]. Throws [IllegalArgumentException] when the
         * input is not valid Base64-URL, the inner JSON is malformed,
         * the `kind` field doesn't match a known [ResourceKind], or
         * the kind is not in [LISTABLE_KINDS]. `null` returns `null`
         * (treated as "start of listing").
         */
        fun decode(encoded: String?): ResourcesListCursor? {
            if (encoded == null) return null
            val bytes = try {
                DECODER.decode(encoded)
            } catch (e: IllegalArgumentException) {
                throw IllegalArgumentException("invalid cursor encoding: ${e.message}", e)
            }
            val body = parseBody(String(bytes, Charsets.UTF_8))
            val kindName = body.text("kind") ?: throw IllegalArgumentException("cursor missing 'kind'")
            val kind = runCatching { ResourceKind.valueOf(kindName) }
                .getOrElse { throw IllegalArgumentException("cursor 'kind=$kindName' is not a known ResourceKind") }
            require(kind in LISTABLE_KIND_SET) {
                "cursor 'kind=$kindName' is not listable via resources/list"
            }
            return ResourcesListCursor(kind = kind, innerToken = body.text("innerToken"))
        }

        private fun parseBody(json: String): JsonObject {
            val element = try {
                JsonParser.parseString(json)
            } catch (e: JsonSyntaxException) {
                throw IllegalArgumentException("invalid cursor body: ${e.message}", e)
            }
            require(element.isJsonObject) { "invalid cursor body: not an object" }
            return element.asJsonObject
        }

        private fun JsonObject.text(name: String): String? {
            val value = get(name)?.takeUnless { it.isJsonNull } ?: return null
            require(value.isJsonPrimitive) { "invalid cursor body: '$name' is not a string" }
            return value.asString
        }
    }
}
