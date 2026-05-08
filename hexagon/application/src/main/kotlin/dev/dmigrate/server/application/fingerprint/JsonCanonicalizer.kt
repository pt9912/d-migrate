package dev.dmigrate.server.application.fingerprint

import dev.dmigrate.cli.i18n.UnicodeNormalizationMode
import dev.dmigrate.cli.i18n.UnicodeNormalizer

/**
 * RFC 8785 (JCS) compliant canonicalization for the [JsonValue] AST.
 * Phase A restriction: numbers are integer-only ([JsonValue.Num.value]
 * is `Long`); floats and big-integers cannot enter this canonicalizer
 * because they have no representation in the AST.
 *
 * Unicode normalization goes through [UnicodeNormalizer] so that the
 * planned `UnicodeTextService` port migration (see
 * `docs/refactoring-icu4j.md`) only needs to swap a single seam — this
 * file does not import `com.ibm.icu.*` directly.
 */
internal object JsonCanonicalizer {

    fun canonicalize(value: JsonValue): String {
        val sb = StringBuilder()
        write(value, sb)
        return sb.toString()
    }

    private fun write(value: JsonValue, out: StringBuilder) {
        when (value) {
            is JsonValue.Null -> out.append("null")
            is JsonValue.Bool -> out.append(if (value.value) "true" else "false")
            is JsonValue.Num -> out.append(value.value.toString())
            is JsonValue.Str -> writeString(value.value, out)
            is JsonValue.Arr -> writeArray(value.items, out)
            is JsonValue.Obj -> writeObject(value.fields, out)
        }
    }

    private fun writeArray(items: List<JsonValue>, out: StringBuilder) {
        out.append('[')
        items.forEachIndexed { index, item ->
            if (index > 0) out.append(',')
            write(item, out)
        }
        out.append(']')
    }

    private fun writeObject(fields: Map<String, JsonValue>, out: StringBuilder) {
        // String.compareTo is contract-defined to compare UTF-16 code units,
        // matching JCS §3.2.3.
        val normalized = fields.entries
            .map { (key, value) -> nfc(key) to value }
            .sortedBy { it.first }
        out.append('{')
        normalized.forEachIndexed { index, (key, value) ->
            if (index > 0) out.append(',')
            writeString(key, out)
            out.append(':')
            write(value, out)
        }
        out.append('}')
    }

    private fun writeString(value: String, out: StringBuilder) {
        val normalized = nfc(value)
        out.append('"')
        for (i in normalized.indices) {
            val ch = normalized[i]
            when {
                ch == '"' -> out.append("\\\"")
                ch == '\\' -> out.append("\\\\")
                ch.code < 0x20 -> out.append(escapeControl(ch))
                else -> out.append(ch)
            }
        }
        out.append('"')
    }

    private fun nfc(value: String): String =
        UnicodeNormalizer.normalize(value, UnicodeNormalizationMode.NFC)

    private fun escapeControl(ch: Char): String =
        "\\u" + ch.code.toString(HEX_RADIX).padStart(UNICODE_ESCAPE_DIGITS, '0')

    private const val HEX_RADIX = 16
    private const val UNICODE_ESCAPE_DIGITS = 4
}
