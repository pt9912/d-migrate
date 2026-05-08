package dev.dmigrate.text

import java.text.BreakIterator
import java.text.Normalizer
import java.util.Locale

/**
 * JDK-only [UnicodeTextService] for application-level tests.
 *
 * Backed by `java.text.Normalizer` and `java.text.BreakIterator` so that
 * `hexagon:application`-side tests do not need to depend on the ICU4J
 * driven adapter. Behaviour matches ICU4J for the cases the application
 * exercises (NFC/NFD/NFKC/NFKD, ASCII grapheme counting, basic
 * combining-mark sequences).
 *
 * **Not** suitable for ICU-specific behaviour tests (ZWJ emoji
 * sequences, regional-indicator flags, complex script clustering); those
 * live in `adapters:driven:text-icu` next to the real implementation.
 */
class FakeUnicodeTextService : UnicodeTextService {

    override fun normalize(input: CharSequence, mode: UnicodeNormalizationMode): String =
        Normalizer.normalize(input, jdkForm(mode))

    override fun isNormalized(input: CharSequence, mode: UnicodeNormalizationMode): Boolean =
        Normalizer.isNormalized(input, jdkForm(mode))

    override fun graphemeCount(input: CharSequence): Int {
        if (input.isEmpty()) return 0
        val iterator = BreakIterator.getCharacterInstance(Locale.ROOT)
        iterator.setText(input.toString())
        var count = 0
        while (iterator.next() != BreakIterator.DONE) count++
        return count
    }

    private fun jdkForm(mode: UnicodeNormalizationMode): Normalizer.Form = when (mode) {
        UnicodeNormalizationMode.NFC -> Normalizer.Form.NFC
        UnicodeNormalizationMode.NFD -> Normalizer.Form.NFD
        UnicodeNormalizationMode.NFKC -> Normalizer.Form.NFKC
        UnicodeNormalizationMode.NFKD -> Normalizer.Form.NFKD
    }
}
