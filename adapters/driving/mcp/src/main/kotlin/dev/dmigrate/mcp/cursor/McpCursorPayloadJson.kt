package dev.dmigrate.mcp.cursor

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonSyntaxException
import dev.dmigrate.server.core.principal.TenantId
import java.time.Instant

/**
 * JSON-Form von [McpCursorPayload], Feld fuer Feld statt ueber Reflexion: Im Native-Image lieferte Gson fuer die
 * nicht registrierte Klasse `{}`, ohne Fehler, und jeder Cursor war unbrauchbar (Issue #88). Reihenfolge,
 * `null`-Felder und die Form von [Instant] (`seconds`, `nanos`) sind Teil des Formats.
 */
internal object McpCursorPayloadJson {

    private val gson: Gson = GsonBuilder().disableHtmlEscaping().serializeNulls().create()

    fun write(payload: McpCursorPayload): String {
        val filters = JsonObject()
        payload.filters.forEach { (key, value) -> filters.addProperty(key, value) }
        val json = JsonObject().apply {
            addProperty("cursorType", payload.cursorType)
            addProperty("version", payload.version)
            addProperty("kid", payload.kid)
            addProperty("tenantId", payload.tenantId.value)
            addProperty("family", payload.family)
            add("filters", filters)
            addProperty("pageSize", payload.pageSize)
            addProperty("sort", payload.sort)
            addProperty("resumeToken", payload.resumeToken)
            add("issuedAt", instant(payload.issuedAt))
            add("expiresAt", instant(payload.expiresAt))
        }
        return gson.toJson(json)
    }

    /** `null`, wenn [json] kein Cursor-Payload ist: kein Objekt, ein Pflichtfeld fehlt oder hat die falsche Art. */
    fun read(json: String): McpCursorPayload? =
        try {
            fromObject(JsonParser.parseString(json).asJsonObject)
        } catch (_: JsonSyntaxException) {
            null
        } catch (_: IllegalStateException) {
            null
        } catch (_: NumberFormatException) {
            null
        } catch (_: UnsupportedOperationException) {
            null
        }

    private fun fromObject(json: JsonObject): McpCursorPayload = McpCursorPayload(
        cursorType = json.text("cursorType"),
        version = json.required("version").asInt,
        kid = json.text("kid"),
        tenantId = TenantId(json.text("tenantId")),
        family = json.text("family"),
        filters = json.get("filters")?.takeUnless { it.isJsonNull }?.asJsonObject
            ?.entrySet()?.associate { it.key to it.value.asString }
            .orEmpty(),
        pageSize = json.required("pageSize").asInt,
        sort = json.optionalText("sort"),
        resumeToken = json.optionalText("resumeToken"),
        issuedAt = json.instant("issuedAt"),
        expiresAt = json.instant("expiresAt"),
    )

    private fun instant(value: Instant): JsonObject = JsonObject().apply {
        addProperty("seconds", value.epochSecond)
        addProperty("nanos", value.nano)
    }

    private fun JsonObject.required(name: String): JsonElement =
        get(name)?.takeUnless { it.isJsonNull } ?: error("cursor field '$name' missing")

    private fun JsonObject.text(name: String): String = required(name).asString

    private fun JsonObject.optionalText(name: String): String? = get(name)?.takeUnless { it.isJsonNull }?.asString

    private fun JsonObject.instant(name: String): Instant = required(name).asJsonObject.let {
        Instant.ofEpochSecond(it.required("seconds").asLong, it.required("nanos").asLong)
    }
}
