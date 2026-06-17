package dev.dmigrate.driver.data

/**
 * Encodes a JDBC [java.sql.Array] value (e.g. PostgreSQL `text[]`) as a JSON
 * array string such as `["NEW","DELETED"]`.
 *
 * Used on the cross-dialect transfer path when a source array column maps to a
 * target JSON column (the tool maps `array` → `JSON` for MySQL): binding the raw
 * `java.sql.Array` would make the JDBC driver fall back to Java serialisation
 * (NotSerializableException), so the value is materialised as a JSON string the
 * JSON column accepts directly.
 */
object JdbcArrayJsonEncoder {

    /** Encode a JDBC array's elements as a JSON array string. */
    fun encode(array: java.sql.Array): String {
        val elements = array.array as? Array<*> ?: return "[]"
        return elements.joinToString(prefix = "[", postfix = "]", separator = ",") { encodeElement(it) }
    }

    private fun encodeElement(value: Any?): String = when (value) {
        null -> "null"
        is Boolean -> value.toString()
        is Number -> value.toString()
        else -> "\"${escape(value.toString())}\""
    }

    private fun escape(raw: String): String = buildString {
        for (ch in raw) {
            when (ch) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (ch < ' ') append("\\u").append(ch.code.toString(16).padStart(4, '0')) else append(ch)
            }
        }
    }
}
