package dev.dmigrate.cli.i18n

import com.ibm.icu.text.Normalizer2

/**
 * Pure utility for Unicode normalization.
 *
 * Supports all four Unicode normalization forms via ICU4J.
 * No global state, no JVM-default-locale dependency.
 *
 * This is a comparison/metadata utility — it does NOT silently normalize
 * export/import/transfer payloads (see master plan §4.4).
 */
object UnicodeNormalizer {

    fun normalize(input: CharSequence, mode: UnicodeNormalizationMode): String {
        val normalizer = when (mode) {
            UnicodeNormalizationMode.NFC -> Normalizer2.getNFCInstance()
            UnicodeNormalizationMode.NFD -> Normalizer2.getNFDInstance()
            UnicodeNormalizationMode.NFKC -> Normalizer2.getNFKCInstance()
            UnicodeNormalizationMode.NFKD -> Normalizer2.getNFKDInstance()
        }
        return normalizer.normalize(input)
    }

    fun isNormalized(input: CharSequence, mode: UnicodeNormalizationMode): Boolean {
        val normalizer = when (mode) {
            UnicodeNormalizationMode.NFC -> Normalizer2.getNFCInstance()
            UnicodeNormalizationMode.NFD -> Normalizer2.getNFDInstance()
            UnicodeNormalizationMode.NFKC -> Normalizer2.getNFKCInstance()
            UnicodeNormalizationMode.NFKD -> Normalizer2.getNFKDInstance()
        }
        return normalizer.isNormalized(input)
    }
}
