package dev.dmigrate.driver.mssql

/**
 * Isolates the SELECT query out of a `sys.sql_modules` view definition
 * (`CREATE VIEW [s].[v] (cols) WITH ... AS SELECT ...` → `SELECT ...`).
 *
 * The scanner walks the definition once and looks for the first
 * top-level `AS` keyword after `VIEW` — outside strings, brackets,
 * parentheses and comments, so column aliases inside a column list or a
 * `CAST(... AS int)` never match.
 */
internal object MssqlViewDefinitionScanner {

    fun queryOf(definition: String): String? {
        var index = 0
        var depth = 0
        var seenView = false
        while (index < definition.length) {
            val ch = definition[index]
            when {
                ch == '-' && definition.startsWith("--", index) -> {
                    index = definition.indexOf('\n', index).let { if (it < 0) definition.length else it }
                }
                ch == '/' && definition.startsWith("/*", index) -> {
                    index = definition.indexOf("*/", index).let { if (it < 0) definition.length else it + 2 }
                }
                ch == '\'' -> index = skipQuoted(definition, index, '\'')
                ch == '[' -> index = skipBracketed(definition, index)
                ch == '"' -> index = skipQuoted(definition, index, '"')
                ch == '(' -> { depth++; index++ }
                ch == ')' -> { depth--; index++ }
                ch.isLetter() -> {
                    val start = index
                    while (index < definition.length && (definition[index].isLetterOrDigit() || definition[index] == '_')) {
                        index++
                    }
                    val word = definition.substring(start, index)
                    when {
                        word.equals("VIEW", ignoreCase = true) -> seenView = true
                        seenView && depth == 0 && word.equals("AS", ignoreCase = true) ->
                            return definition.substring(index).trim().ifEmpty { null }
                    }
                }
                else -> index++
            }
        }
        return null
    }

    private fun skipQuoted(definition: String, from: Int, quote: Char): Int {
        var index = from + 1
        while (index < definition.length) {
            if (definition[index] == quote) {
                if (index + 1 < definition.length && definition[index + 1] == quote) {
                    index += 2
                    continue
                }
                return index + 1
            }
            index++
        }
        return index
    }

    private fun skipBracketed(definition: String, from: Int): Int {
        var index = from + 1
        while (index < definition.length) {
            if (definition[index] == ']') {
                if (index + 1 < definition.length && definition[index + 1] == ']') {
                    index += 2
                    continue
                }
                return index + 1
            }
            index++
        }
        return index
    }
}
