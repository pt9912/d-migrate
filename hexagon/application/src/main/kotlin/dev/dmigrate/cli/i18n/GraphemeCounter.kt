package dev.dmigrate.cli.i18n

import com.ibm.icu.text.BreakIterator

/**
 * Counts grapheme clusters (user-perceived characters) in a string.
 *
 * Unlike [String.length] (UTF-16 code units) or [String.codePointCount]
 * (Unicode code points), this counts what users would see as individual
 * characters — correctly handling combining accents, emoji with ZWJ
 * sequences, and other multi-codepoint clusters.
 *
 * Use only where semantic character length matters (user-facing limits,
 * diagnostic messages, column width estimates). Technical string operations
 * (parsers, buffers, serializers) should continue using [String.length].
 */
object GraphemeCounter {

    fun count(input: CharSequence): Int {
        if (input.isEmpty()) return 0
        val iterator = BreakIterator.getCharacterInstance()
        iterator.setText(input.toString())
        var count = 0
        while (iterator.next() != BreakIterator.DONE) count++
        return count
    }
}
