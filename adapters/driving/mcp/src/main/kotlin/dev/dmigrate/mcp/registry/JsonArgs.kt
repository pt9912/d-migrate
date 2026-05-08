package dev.dmigrate.mcp.registry

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.google.gson.JsonSyntaxException
import dev.dmigrate.server.application.error.ValidationErrorException
import dev.dmigrate.server.application.error.ValidationViolation

/**
 * Tiny Gson-tree helpers shared by Phase-C tool handlers
 * (`schema_validate` AP 6.4, `schema_generate` AP 6.5,
 * `schema_compare` AP 6.6, `job_status_get` AP 6.12).
 *
 * Centralising here avoids each handler reinventing the
 * `JsonNull`-as-absent rule and the cast-to-`JsonObject` defence.
 * Argument-shape errors uniformly surface as
 * `VALIDATION_ERROR(arguments, ...)` rather than `ClassCastException`s
 * leaking through the dispatch path.
 */
internal object JsonArgs {

    /**
     * Coerces a raw `tools/call arguments` value into a [JsonObject].
     * `null` and `JsonNull` are treated as the empty object so handlers
     * can read optional fields without an extra null-guard. Anything
     * else (string, number, array, primitive) raises a structured
     * `VALIDATION_ERROR` on the `arguments` field.
     */
    fun requireObject(raw: JsonElement?): JsonObject = when {
        raw == null || raw.isJsonNull -> JsonObject()
        raw is JsonObject -> raw
        else -> throw ValidationErrorException(
            listOf(ValidationViolation("arguments", "must be a JSON object")),
        )
    }

    /**
     * Returns a [JsonObject]'s string field or `null` if the field is
     * absent, JSON null, or not a string. Non-string values fail
     * silently here — handlers that want to reject them call
     * `requireString(...)` instead.
     */
    fun JsonObject.optString(field: String): String? {
        val raw = get(field)?.takeUnless { it.isJsonNull } ?: return null
        return try {
            raw.asString
        } catch (_: UnsupportedOperationException) {
            null
        } catch (_: JsonSyntaxException) {
            null
        }
    }

    /**
     * Returns a [JsonObject]'s nested-object field or `null` if absent
     * or JSON null. Non-object values raise a structured error on the
     * named field — keeps the typed fields' parser contract honest
     * (e.g. `schema` MUST be an object).
     */
    fun JsonObject.optObject(field: String): JsonElement? =
        get(field)?.takeUnless { it.isJsonNull }

    /**
     * Validates that an optional enum-shaped field is one of [allowed]
     * if present. Returns the value (or `null` when absent); throws
     * `VALIDATION_ERROR` on the named field if the value is outside
     * the allow-set so callers don't need to repeat the contains
     * check at each call site.
     */
    fun JsonObject.optEnum(field: String, allowed: Set<String>): String? {
        val value = optString(field) ?: return null
        if (value !in allowed) {
            throw ValidationErrorException(
                listOf(ValidationViolation(field, "must be one of ${allowed.sorted()}")),
            )
        }
        return value
    }

    /**
     * Required-string accessor: returns the field's value or throws a
     * structured `VALIDATION_ERROR(field, "is required")`. Non-string
     * values (object, array, number, boolean) raise the same error so
     * the wire contract isn't silently widened.
     */
    fun JsonObject.requireString(field: String): String =
        optString(field) ?: throw missing(field)

    /**
     * Required-integer accessor with a `>= min` constraint. Used by
     * Phase-C handlers that take typed numeric arguments (e.g.
     * segmentIndex, expectedSizeBytes) — see `ArtifactUploadHandler`
     * and `ArtifactUploadInitHandler`.
     */
    fun JsonObject.requireInt(field: String, min: Int = Int.MIN_VALUE): Int {
        val primitive = numericPrimitive(field)
        val value = try {
            primitive.asInt
        } catch (_: NumberFormatException) {
            throw mustBeInteger(field)
        }
        if (value < min) {
            throw ValidationErrorException(
                listOf(ValidationViolation(field, "must be >= $min")),
            )
        }
        return value
    }

    /** Required-long accessor with a `>= min` constraint. */
    fun JsonObject.requireLong(field: String, min: Long = Long.MIN_VALUE): Long {
        val primitive = numericPrimitive(field)
        val value = try {
            primitive.asLong
        } catch (_: NumberFormatException) {
            throw mustBeInteger(field)
        }
        if (value < min) {
            throw ValidationErrorException(
                listOf(ValidationViolation(field, "must be >= $min")),
            )
        }
        return value
    }

    /** Required-boolean accessor; numeric/string values raise `VALIDATION_ERROR`. */
    fun JsonObject.requireBool(field: String): Boolean {
        val element = get(field) ?: throw missing(field)
        val primitive = element as? JsonPrimitive
        if (primitive == null || !primitive.isBoolean) {
            throw ValidationErrorException(
                listOf(ValidationViolation(field, "must be a boolean")),
            )
        }
        return primitive.asBoolean
    }

    private fun JsonObject.numericPrimitive(field: String): JsonPrimitive {
        val element = get(field) ?: throw missing(field)
        val primitive = element as? JsonPrimitive
        if (primitive == null || !primitive.isNumber) throw mustBeInteger(field)
        return primitive
    }

    private fun missing(field: String): ValidationErrorException =
        ValidationErrorException(listOf(ValidationViolation(field, "is required")))

    private fun mustBeInteger(field: String): ValidationErrorException =
        ValidationErrorException(listOf(ValidationViolation(field, "must be an integer")))
}
