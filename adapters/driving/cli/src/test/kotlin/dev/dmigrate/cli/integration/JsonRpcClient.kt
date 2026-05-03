package dev.dmigrate.cli.integration

import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.util.concurrent.atomic.AtomicLong

/**
 * AP 6.24: minimal JSON-RPC 2.0 helpers shared by the stdio + HTTP
 * harnesses. The server's lsp4j-based [dev.dmigrate.mcp.transport.stdio.NdjsonMessageProducer]
 * accepts standard JSON-RPC objects; the client side serialises with
 * Gson and parses responses back into [JsonElement] so the harness
 * surface stays type-light (transport-neutral asserts can normalise
 * JSON nodes directly).
 */
internal class JsonRpcClient {

    private val ids = AtomicLong(0)
    private val gson = GsonBuilder().disableHtmlEscaping().serializeNulls().create()

    fun nextId(): Long = ids.incrementAndGet()

    fun request(method: String, params: JsonElement?, id: Long = nextId()): String {
        val payload = JsonObject().apply {
            addProperty("jsonrpc", "2.0")
            addProperty("id", id)
            addProperty("method", method)
            if (params != null) add("params", params)
        }
        return gson.toJson(payload)
    }

    fun notification(method: String, params: JsonElement? = null): String {
        val payload = JsonObject().apply {
            addProperty("jsonrpc", "2.0")
            addProperty("method", method)
            if (params != null) add("params", params)
        }
        return gson.toJson(payload)
    }

    fun parseResponse(raw: String): JsonRpcResponse {
        val obj = JsonParser.parseString(raw).asJsonObject
        return JsonRpcResponse(
            id = obj.get("id")?.takeUnless { it.isJsonNull }?.asLong,
            result = obj.get("result")?.takeUnless { it.isJsonNull },
            error = obj.get("error")?.takeUnless { it.isJsonNull }?.asJsonObject,
        )
    }

    fun toJson(any: Any?): JsonElement = gson.toJsonTree(any)
}

internal data class JsonRpcResponse(
    val id: Long?,
    val result: JsonElement?,
    val error: JsonObject?,
) {
    fun resultOrThrow(): JsonElement {
        if (error != null) {
            error("JSON-RPC error: ${error.get("code")?.asInt} ${error.get("message")?.asString}")
        }
        return result ?: error("JSON-RPC response has neither result nor error")
    }

    fun <T> resultAs(gson: com.google.gson.Gson, clazz: Class<T>): T =
        gson.fromJson(resultOrThrow(), clazz)
}
