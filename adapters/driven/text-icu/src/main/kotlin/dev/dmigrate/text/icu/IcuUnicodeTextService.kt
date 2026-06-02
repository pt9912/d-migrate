package dev.dmigrate.text.icu

import com.ibm.icu.text.BreakIterator
import com.ibm.icu.text.Normalizer2
import dev.dmigrate.text.UnicodeNormalizationMode
import dev.dmigrate.text.UnicodeTextService

/**
 * ICU4J-backed [UnicodeTextService].
 *
 * Uses [Normalizer2] for normalization (NFC/NFD/NFKC/NFKD) and
 * [BreakIterator] for grapheme-cluster counting. The ICU4J runtime is
 * bundled in this driven module so the application layer stays
 * library-agnostic.
 */
class IcuUnicodeTextService : UnicodeTextService {

    override fun normalize(input: CharSequence, mode: UnicodeNormalizationMode): String =
        normalizerFor(mode).normalize(input)

    override fun isNormalized(input: CharSequence, mode: UnicodeNormalizationMode): Boolean =
        normalizerFor(mode).isNormalized(input)

    override fun graphemeCount(input: CharSequence): Int {
        if (input.isEmpty()) return 0
        val iterator = BreakIterator.getCharacterInstance()
        iterator.setText(input.toString())
        var count = 0
        while (iterator.next() != BreakIterator.DONE) count++
        return count
    }

    private fun normalizerFor(mode: UnicodeNormalizationMode): Normalizer2 = when (mode) {
        UnicodeNormalizationMode.NFC -> Normalizer2.getNFCInstance()
        UnicodeNormalizationMode.NFD -> Normalizer2.getNFDInstance()
        UnicodeNormalizationMode.NFKC -> Normalizer2.getNFKCInstance()
        UnicodeNormalizationMode.NFKD -> Normalizer2.getNFKDInstance()
    }
}
