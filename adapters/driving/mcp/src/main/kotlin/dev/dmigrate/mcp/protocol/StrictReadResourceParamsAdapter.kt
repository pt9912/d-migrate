package dev.dmigrate.mcp.protocol

import com.google.gson.TypeAdapter
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter

/**
 * Plan-D §5.3 + §10.7 strict deserialiser for [ReadResourceParams].
 *
 * Phase-D rejects any `resources/read` request that carries a field
 * other than `uri`: chunking, range-reads and similar extensions
 * MUST NOT ride in on `resources/read` because their natural place
 * is `artifact_chunk_get` (cursor-bound) or a chunk-URI follow-up.
 *
 * Why a custom adapter instead of validating in the dispatcher: the
 * @JsonRequest binding lsp4j performs only sees the typed
 * [ReadResourceParams]. By the time the dispatcher runs, Gson has
 * already silently dropped every unknown field, so a check there
 * would be a no-op. Capturing the first unexpected field name into
 * [ReadResourceParams.unknownParameter] lets the dispatcher render
 * a typed `VALIDATION_ERROR` with `error.data.dmigrateCode` instead
 * of the raw `-32700 ParseError` lsp4j would emit if we threw from
 * inside Gson.
 */
internal class StrictReadResourceParamsAdapter : TypeAdapter<ReadResourceParams>() {

    override fun write(out: JsonWriter, value: ReadResourceParams?) {
        if (value == null) {
            out.nullValue()
            return
        }
        out.beginObject()
        if (value.uri != null) {
            out.name(FIELD_URI).value(value.uri)
        }
        out.endObject()
    }

    override fun read(reader: JsonReader): ReadResourceParams {
        if (reader.peek() == JsonToken.NULL) {
            reader.nextNull()
            return ReadResourceParams()
        }
        var uri: String? = null
        var firstUnknown: String? = null
        reader.beginObject()
        while (reader.hasNext()) {
            val name = reader.nextName()
            when (name) {
                FIELD_URI -> {
                    if (reader.peek() == JsonToken.NULL) {
                        reader.nextNull()
                    } else {
                        uri = reader.nextString()
                    }
                }
                else -> {
                    // Capture the first offender; keep reading so
                    // Gson's parser stays balanced (skipValue
                    // consumes the value of the current field
                    // including nested objects/arrays).
                    if (firstUnknown == null) {
                        firstUnknown = name
                    }
                    reader.skipValue()
                }
            }
        }
        reader.endObject()
        return ReadResourceParams(uri = uri, unknownParameter = firstUnknown)
    }

    companion object {
        private const val FIELD_URI: String = "uri"
    }
}
