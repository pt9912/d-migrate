package dev.dmigrate.core.identity

import dev.dmigrate.core.model.ParameterDefinition

/**
 * Canonical, lossless key codec for routine and trigger identity in the
 * neutral schema model.
 *
 * Routines use `name(direction:type,direction:type,...)` as their
 * canonical key so that overloaded routines (same name, different
 * parameter signatures) can coexist without collision.
 *
 * Triggers use `table::name` as their canonical key so that identically
 * named triggers on different tables can coexist.
 *
 * All name components are percent-encoded for the reserved separators
 * `%`, `(`, `)`, `,`, `:` before assembly, making the string
 * representation lossless and round-trippable.
 */
object ObjectKeyCodec {

    private val ENCODE_REGEX = Regex("[%(),:]")

    /** Percent-encode reserved separators in a single name component. */
    fun encode(component: String): String =
        ENCODE_REGEX.replace(component) { "%" + it.value[0].code.toString(16).uppercase().padStart(2, '0') }

    /** Decode a percent-encoded component back to its original form. */
    fun decode(encoded: String): String =
        Regex("%([0-9A-Fa-f]{2})").replace(encoded) {
            it.groupValues[1].toInt(16).toChar().toString()
        }

    /**
     * Build the canonical key for a function or procedure.
     *
     * Format: `name(direction:type,direction:type,...)`
     *
     * An empty parameter list produces `name()`.
     */
    fun routineKey(name: String, params: List<ParameterDefinition>): String {
        val encodedName = encode(name)
        val paramParts = params.joinToString(",") { p ->
            "${encode(p.direction.name.lowercase())}:${encode(p.type)}"
        }
        return "$encodedName($paramParts)"
    }

    /**
     * Parse a canonical routine key back into name and parameter pairs.
     *
     * @return pair of (decoded name, list of (direction, type) pairs)
     */
    fun parseRoutineKey(key: String): Pair<String, List<Pair<String, String>>> {
        val openParen = key.indexOf('(')
        require(openParen >= 0 && key.endsWith(')')) { "Invalid routine key: $key" }

        val name = decode(key.substring(0, openParen))
        val paramStr = key.substring(openParen + 1, key.length - 1)

        if (paramStr.isEmpty()) return name to emptyList()

        val params = paramStr.split(',').map { part ->
            val colonIdx = part.indexOf(':')
            require(colonIdx >= 0) { "Invalid parameter part: $part" }
            decode(part.substring(0, colonIdx)) to decode(part.substring(colonIdx + 1))
        }
        return name to params
    }

    /**
     * Build the canonical key for a trigger.
     *
     * Format: `table::name`
     */
    fun triggerKey(table: String, name: String): String =
        "${encode(table)}::${encode(name)}"

    /**
     * Parse a canonical trigger key back into (table, name).
     */
    fun parseTriggerKey(key: String): Pair<String, String> {
        val sep = key.indexOf("::")
        require(sep >= 0) { "Invalid trigger key: $key" }
        return decode(key.substring(0, sep)) to decode(key.substring(sep + 2))
    }
}
