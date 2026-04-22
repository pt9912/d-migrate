package dev.dmigrate.core.identity

/**
 * Canonical encoding for reverse-generated schema provenance.
 *
 * Reverse-generated schemas use a reserved prefix in
 * [dev.dmigrate.core.model.SchemaDefinition.name] and a fixed
 * placeholder for [dev.dmigrate.core.model.SchemaDefinition.version]
 * so that they are machine-identifiable after YAML/JSON serialization
 * without needing a sidecar file.
 *
 * Format: `__dmigrate_reverse__:<dialect>:<key>=<value>[;<key>=<value>...]`
 *
 * Component values are percent-encoded for structural separators
 * (`;`, `=`, `:`, `%`) via RFC 3986-style encoding.
 */
object ReverseScopeCodec {

    const val PREFIX = "__dmigrate_reverse__:"
    const val REVERSE_VERSION = "0.0.0-reverse"

    private val ENCODE_REGEX = Regex("[;=:%]")

    /** Percent-encode structural separators in a component value. */
    fun encodeComponent(value: String): String =
        ENCODE_REGEX.replace(value) { "%" + it.value[0].code.toString(16).uppercase().padStart(2, '0') }

    /** Decode a percent-encoded component value.
     * @throws IllegalArgumentException if the string contains invalid percent sequences */
    fun decodeComponent(encoded: String): String {
        // Validate: every % must be followed by exactly two hex digits
        val invalidPercent = Regex("%(?![0-9A-Fa-f]{2})").find(encoded)
        require(invalidPercent == null) {
            "Invalid percent-encoding at position ${invalidPercent!!.range.first} in '$encoded'"
        }
        return Regex("%([0-9A-Fa-f]{2})").replace(encoded) {
            it.groupValues[1].toInt(16).toChar().toString()
        }
    }

    /** Check if a string contains only valid percent-encoding sequences. */
    fun hasValidEncoding(value: String): Boolean =
        !Regex("%(?![0-9A-Fa-f]{2})").containsMatchIn(value)

    /**
     * Build canonical reverse name for PostgreSQL.
     * Format: `__dmigrate_reverse__:postgresql:database=<db>;schema=<schema>`
     */
    fun postgresName(database: String, schema: String): String =
        "${PREFIX}postgresql:database=${encodeComponent(database)};schema=${encodeComponent(schema)}"

    /**
     * Build canonical reverse name for MySQL.
     * Format: `__dmigrate_reverse__:mysql:database=<db>`
     */
    fun mysqlName(database: String): String =
        "${PREFIX}mysql:database=${encodeComponent(database)}"

    /**
     * Build canonical reverse name for SQLite.
     * Format: `__dmigrate_reverse__:sqlite:schema=<schema>`
     */
    fun sqliteName(schema: String): String =
        "${PREFIX}sqlite:schema=${encodeComponent(schema)}"

    /**
     * Check whether a schema was reverse-generated.
     * Both [name] and [version] must match the canonical marker set.
     */
    fun isReverseGenerated(name: String, version: String): Boolean =
        version == REVERSE_VERSION &&
            name.startsWith(PREFIX) &&
            parseScopeOrNull(name) != null

    /**
     * Parse a reverse-generated name into its components.
     *
     * @return map with keys `dialect` and dialect-specific keys
     *         (e.g. `database`, `schema`)
     * @throws IllegalArgumentException if the name is not a valid reverse scope
     */
    fun parseScope(name: String): Map<String, String> =
        parseScopeOrNull(name)
            ?: throw IllegalArgumentException("Invalid reverse scope: $name")

    private fun parseScopeOrNull(name: String): Map<String, String>? {
        if (!name.startsWith(PREFIX)) return null
        val afterPrefix = name.substring(PREFIX.length)

        // dialect:key=value[;key=value...]
        val dialectEnd = afterPrefix.indexOf(':')
        if (dialectEnd < 0) return null
        val dialect = afterPrefix.substring(0, dialectEnd)
        if (dialect.isEmpty()) return null

        val kvPart = afterPrefix.substring(dialectEnd + 1)
        if (kvPart.isEmpty()) return null

        val result = mutableMapOf("dialect" to dialect)
        for (pair in kvPart.split(';')) {
            val eqIdx = pair.indexOf('=')
            if (eqIdx < 0) return null
            val key = pair.substring(0, eqIdx)
            val rawValue = pair.substring(eqIdx + 1)
            if (key.isEmpty()) return null
            // Reject invalid percent-encoding sequences
            if (!hasValidEncoding(rawValue)) return null
            val value = try { decodeComponent(rawValue) } catch (_: IllegalArgumentException) { return null }
            result[key] = value
        }
        return result
    }
}
