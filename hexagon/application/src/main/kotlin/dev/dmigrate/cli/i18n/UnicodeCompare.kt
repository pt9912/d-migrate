package dev.dmigrate.cli.i18n

/**
 * Unicode-aware comparison utility using normalization.
 *
 * Compares strings after normalizing both to the configured mode.
 * Use for metadata comparisons where accented characters or combining
 * marks should compare as equal to their precomposed forms.
 *
 * NOT for data payloads — see master plan §4.4.
 */
object UnicodeCompare {

    /**
     * Returns true if both strings are equal after Unicode normalization.
     */
    fun equals(a: String, b: String, mode: UnicodeNormalizationMode): Boolean =
        UnicodeNormalizer.normalize(a, mode) == UnicodeNormalizer.normalize(b, mode)
}
