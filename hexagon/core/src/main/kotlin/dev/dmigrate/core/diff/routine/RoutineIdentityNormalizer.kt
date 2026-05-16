package dev.dmigrate.core.diff.routine

import java.util.Locale

/**
 * Canonicalises routine identity attributes that are not part of the
 * routine body. The comparator and migration fingerprint both use this
 * helper so semantically equivalent authoring forms do not create
 * spurious routine replacements.
 */
object RoutineIdentityNormalizer {

    fun normalizePostgresSearchPath(searchPath: List<String>?): List<String>? {
        if (searchPath.isNullOrEmpty()) return null
        val seen = linkedSetOf<String>()
        for (segment in searchPath) {
            val normalized = normalizeSearchPathSegment(segment) ?: continue
            seen += normalized
        }
        return seen.toList().takeIf { it.isNotEmpty() }
    }

    fun normalizeMysqlSqlMode(sqlMode: String?): String? {
        if (sqlMode.isNullOrBlank()) return null
        return sqlMode.split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { it.uppercase(Locale.ROOT) }
            .distinct()
            .sorted()
            .joinToString(",")
            .takeIf { it.isNotEmpty() }
    }

    private fun normalizeSearchPathSegment(segment: String): String? {
        val trimmed = segment.trim()
        if (trimmed.isEmpty()) return null
        val unquoted = unquoteIdentifier(trimmed)
        if (unquoted.equals("\$user", ignoreCase = true)) return "\$user"
        return if (isQuotedIdentifier(trimmed)) {
            unquoted
        } else {
            unquoted.lowercase(Locale.ROOT)
        }
    }

    private fun isQuotedIdentifier(value: String): Boolean =
        value.length >= 2 && value.first() == '"' && value.last() == '"'

    private fun unquoteIdentifier(value: String): String =
        if (isQuotedIdentifier(value)) {
            value.substring(1, value.length - 1).replace("\"\"", "\"")
        } else {
            value
        }
}
