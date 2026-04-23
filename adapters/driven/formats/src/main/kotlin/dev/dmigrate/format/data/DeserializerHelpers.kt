package dev.dmigrate.format.data

import dev.dmigrate.core.data.ImportSchemaMismatchException
import java.math.BigDecimal

internal object DeserializerHelpers {

    /** Detects MySQL `BIT(N>1)` type names. `BIT(1)` is treated as BOOLEAN. */
    fun isMultiBit(sqlTypeName: String): Boolean {
        if (!sqlTypeName.startsWith("BIT")) return false
        val start = sqlTypeName.indexOf('(')
        val end = sqlTypeName.indexOf(')')
        if (start < 0 || end < 0 || end < start) return false
        val inner = sqlTypeName.substring(start + 1, end).trim()
        return inner.toIntOrNull()?.let { it > 1 } ?: false
    }

    /**
     * Heuristic: does the timestamp string carry an offset or zone?
     * Looks for `Z`, `+hh:mm` or `-hh:mm` after the `T` separator.
     */
    fun hasOffsetOrZone(s: String): Boolean {
        val tIdx = s.indexOf('T')
        if (tIdx < 0) return false
        val tail = s.substring(tIdx + 1)
        if (tail.endsWith('Z')) return true
        return tail.any { it == '+' } || (tail.indexOf('-') >= 0 && tail.length > 6)
    }

    /** Builds a type-mismatch exception with table/column/jdbcType context. */
    fun typeMismatch(
        table: String,
        columnName: String,
        hint: JdbcTypeHint,
        value: Any?,
        underlyingMessage: String?,
        cause: Throwable?,
    ): ImportSchemaMismatchException {
        val hintText = underlyingMessage?.let { " ($it)" } ?: ""
        return ImportSchemaMismatchException(
            "table '$table' column '$columnName' (jdbcType=${hint.jdbcType}) " +
                "rejected value '$value'$hintText",
            cause = cause,
        )
    }

    fun simpleJsonString(value: Any?): String = buildString {
        append(jsonToken(value))
    }

    fun looksDecimalToken(text: String): Boolean =
        text.any { it == '.' || it == 'e' || it == 'E' }

    fun parseExactLong(hint: JdbcTypeHint, columnName: String, text: String): Long {
        val numeric = try {
            BigDecimal(text)
        } catch (e: NumberFormatException) {
            throw ImportSchemaMismatchException(
                "column '$columnName' expects NUMERIC/DECIMAL, got '$text'"
            )
        }
        val canonical = numeric.stripTrailingZeros()
        if (canonical.scale() > 0) {
            throw ImportSchemaMismatchException(
                "column '$columnName' expects integer-shaped NUMERIC/DECIMAL, got decimal '$text'"
            )
        }
        val longValue = try {
            canonical.longValueExact()
        } catch (_: ArithmeticException) {
            throw ImportSchemaMismatchException(
                "column '$columnName' expects integer-shaped NUMERIC/DECIMAL within 64-bit range, got '$text'"
            )
        }
        return checkedLong(hint, columnName, longValue)
    }

    fun checkedLong(hint: JdbcTypeHint, columnName: String, value: Long): Long {
        hint.precision?.let { precision ->
            val digits = value.toString().removePrefix("-").length
            if (digits > precision) {
                throw ImportSchemaMismatchException(
                    "column '$columnName' expects NUMERIC/DECIMAL precision <= $precision, got $value"
                )
            }
        }
        return value
    }

    private fun jsonToken(value: Any?): String = when (value) {
        null -> "null"
        is Boolean -> value.toString()
        is Number -> value.toString()
        is String -> "\"" + escapeJson(value) + "\""
        is Map<*, *> -> value.entries.joinToString(
            separator = ",",
            prefix = "{",
            postfix = "}",
        ) { "\"${escapeJson(it.key.toString())}\":${jsonToken(it.value)}" }
        is List<*> -> value.joinToString(separator = ",", prefix = "[", postfix = "]") { jsonToken(it) }
        is Array<*> -> value.joinToString(separator = ",", prefix = "[", postfix = "]") { jsonToken(it) }
        else -> "\"${escapeJson(value.toString())}\""
    }

    private fun escapeJson(s: String): String = buildString(s.length) {
        for (c in s) when (c) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            '\b' -> append("\\b")
            '\u000C' -> append("\\f")
            else -> if (c.code < 0x20) append("\\u%04x".format(c.code)) else append(c)
        }
    }
}
