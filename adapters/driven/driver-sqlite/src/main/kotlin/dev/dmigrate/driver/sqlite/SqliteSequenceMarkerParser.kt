package dev.dmigrate.driver.sqlite

/**
 * 0.9.7 SQLite-Sequence Phase D: parser for the canonical
 * `/* d-migrate:sqlite-sequence-v1 object=… sequence=… table=…
 * column=… */` marker comments emitted by [SqliteSequenceEmulationTemplates]
 * inside the `_bi` / `_ai` trigger bodies.
 *
 * Plan §6.1 lines 1691–1701:
 * - the marker may sit anywhere inside a `/* ... */` block in the
 *   trigger body (not only the first comment, not only inside
 *   `BEGIN...END`);
 * - key-value parsing is whitespace-tolerant;
 * - values are percent-decoded per the encoding rule from
 *   `SqliteSequenceEmulationTemplates.markerValue`.
 */
internal object SqliteSequenceMarkerParser {

    /** What [SqliteSequenceNaming.FORMAT_VERSION] adds to the marker. */
    private const val MARKER_TOKEN = "d-migrate:sqlite-sequence-v1"

    /** Allowed `object=` values per Plan §3.3. */
    private const val OBJ_BEFORE_INSERT = "sequence-trigger"
    private const val OBJ_AFTER_INSERT = "sequence-trigger-post"

    data class Marker(
        val objectType: ObjectType,
        val sequenceName: String,
        val tableName: String,
        val columnName: String,
    )

    enum class ObjectType { BEFORE_INSERT, AFTER_INSERT }

    /**
     * Walks every `/* ... */` block in [triggerSql] and returns the
     * first that matches the canonical marker shape. Returns `null`
     * when no canonical marker is present.
     */
    fun parse(triggerSql: String): Marker? {
        val blocks = blockCommentsOf(triggerSql)
        for (block in blocks) {
            if (MARKER_TOKEN !in block) continue
            val marker = parseBlock(block) ?: continue
            return marker
        }
        return null
    }

    private fun parseBlock(block: String): Marker? {
        // Collapse all whitespace into single spaces so the key=value
        // matcher tolerates line breaks and indentation.
        val flat = block.replace(Regex("\\s+"), " ").trim()
        val pairs = mutableMapOf<String, String>()
        // Match `key=value` where value is everything up to the next
        // whitespace-delimited key. Plan §3.3 percent-encodes
        // anything that would break the structure, so a raw `=` or
        // whitespace inside a value cannot occur for canonical
        // markers.
        val regex = Regex("([a-zA-Z_][a-zA-Z0-9_]*)=([^\\s]+)")
        for (match in regex.findAll(flat)) {
            pairs[match.groupValues[1]] = match.groupValues[2]
        }
        val obj = pairs["object"] ?: return null
        val type = when (obj) {
            OBJ_BEFORE_INSERT -> ObjectType.BEFORE_INSERT
            OBJ_AFTER_INSERT -> ObjectType.AFTER_INSERT
            else -> return null
        }
        val sequence = pairs["sequence"]?.let(::decodeMarkerValue) ?: return null
        val table = pairs["table"]?.let(::decodeMarkerValue) ?: return null
        val column = pairs["column"]?.let(::decodeMarkerValue) ?: return null
        return Marker(type, sequence, table, column)
    }

    /**
     * Inverse of [SqliteSequenceEmulationTemplates.markerValue].
     * `%XX` percent-escapes decode back to the original UTF-8 byte
     * sequence. Unknown / malformed escapes leave the input as-is.
     */
    private fun decodeMarkerValue(encoded: String): String {
        if ('%' !in encoded) return encoded
        val bytes = ByteArray(encoded.length)
        var b = 0
        var i = 0
        while (i < encoded.length) {
            val ch = encoded[i]
            if (ch == '%' && i + 2 < encoded.length) {
                val hex = encoded.substring(i + 1, i + 3)
                val byte = hex.toIntOrNull(16)
                if (byte != null) {
                    bytes[b++] = byte.toByte()
                    i += 3
                    continue
                }
            }
            bytes[b++] = ch.code.toByte()
            i++
        }
        return String(bytes, 0, b, Charsets.UTF_8)
    }

    /**
     * Extracts every `/* ... */` block, including nested ones (SQLite
     * treats nested comments as plain text but the comment grammar
     * here matches the SQL spec — single-level). Single-line `--`
     * comments are ignored because Plan §3.3 places the marker only
     * inside `/* ... */` blocks.
     */
    private fun blockCommentsOf(sql: String): List<String> {
        val out = mutableListOf<String>()
        var i = 0
        while (i < sql.length - 1) {
            if (sql[i] == '/' && sql[i + 1] == '*') {
                val end = sql.indexOf("*/", i + 2)
                if (end < 0) break
                out += sql.substring(i + 2, end)
                i = end + 2
            } else {
                i++
            }
        }
        return out
    }
}

/**
 * 0.9.7 SQLite-Sequence Phase D: token-based body checker for
 * `_bi` / `_ai` trigger sekundär-/integritäts-matching.
 *
 * Plan §6.1 lines 1716–1726 require:
 * - `_bi` body must contain `dmg_sequences` as a SQL identifier
 *   token (any of the four SQLite quoting forms, optionally schema-
 *   qualified);
 * - `_ai` body must contain both the host-table identifier and
 *   `ROWID` as tokens.
 *
 * The check follows the same SQL-text normalisation as the rollback
 * dependency scan (Plan §5.2 lines 1462–1490): strip string
 * literals and comments first, then scan token boundaries.
 */
internal object SqliteIdentifierTokenScanner {

    /**
     * Returns `true` if [identifier] appears as a SQL identifier
     * token in [sql] (case-insensitive). Recognised quoting forms:
     * unquoted, `"…"`, `[…]`, `` `…` ``. Optional schema qualifier
     * (e.g. `main.dmg_sequences`) is tolerated.
     */
    fun containsIdentifier(sql: String, identifier: String): Boolean {
        val cleaned = stripLiteralsAndComments(sql).lowercase()
        val target = identifier.lowercase()
        // Exact-match all four quoting forms.
        val quotedForms = listOf(
            "\"${target.replace("\"", "\"\"")}\"",
            "`${target.replace("`", "``")}`",
            "[$target]",
        )
        if (quotedForms.any { it in cleaned }) return true
        // Schema-qualified quoted forms — only the trailing segment
        // needs to match exactly; the leading schema may be quoted
        // in any form too. Approximate with a regex over the four
        // quoting variants.
        val schemaQualifiedRegex = Regex(
            "([a-z_][a-z0-9_]*|\"[^\"]*\"|`[^`]*`|\\[[^]]*])" +
                "\\." +
                "(${Regex.escape(target)}|" +
                "\"${Regex.escape(target)}\"|" +
                "`${Regex.escape(target)}`|" +
                "\\[${Regex.escape(target)}])",
        )
        if (schemaQualifiedRegex.containsMatchIn(cleaned)) return true
        // Bare identifier at a token boundary (no quoting).
        val bareRegex = Regex(
            "(?<![a-z0-9_\"`\\[])${Regex.escape(target)}(?![a-z0-9_\"`\\]])",
        )
        return bareRegex.containsMatchIn(cleaned)
    }

    /**
     * Removes single-quoted string literals and SQL comments before
     * the token scan so `dmg_sequences` mentioned inside a literal
     * or comment is not counted as a real identifier reference.
     */
    private fun stripLiteralsAndComments(sql: String): String {
        val out = StringBuilder(sql.length)
        var i = 0
        while (i < sql.length) {
            i = when {
                sql[i] == '\'' -> skipStringLiteral(sql, i)
                isLineCommentStart(sql, i) -> skipLineComment(sql, i)
                isBlockCommentStart(sql, i) -> skipBlockComment(sql, i)
                else -> {
                    out.append(sql[i])
                    i + 1
                }
            }
        }
        return out.toString()
    }

    private fun isLineCommentStart(sql: String, i: Int): Boolean =
        i + 1 < sql.length && sql[i] == '-' && sql[i + 1] == '-'

    private fun isBlockCommentStart(sql: String, i: Int): Boolean =
        i + 1 < sql.length && sql[i] == '/' && sql[i + 1] == '*'

    private fun skipStringLiteral(sql: String, start: Int): Int {
        var j = start + 1
        while (j < sql.length) {
            if (sql[j] != '\'') {
                j++
                continue
            }
            // Double-`'` escape — stay inside the literal.
            if (j + 1 < sql.length && sql[j + 1] == '\'') {
                j += 2
                continue
            }
            return j + 1
        }
        return j
    }

    private fun skipLineComment(sql: String, start: Int): Int {
        val eol = sql.indexOf('\n', start)
        return if (eol < 0) sql.length else eol + 1
    }

    private fun skipBlockComment(sql: String, start: Int): Int {
        val end = sql.indexOf("*/", start + 2)
        return if (end < 0) sql.length else end + 2
    }
}
