package dev.dmigrate.text

/**
 * Unicode text service port.
 *
 * Encapsulates the Unicode-specific operations the application needs
 * — normalization and grapheme-cluster counting — without exposing
 * any concrete implementation library (ICU4J, ICU C, JDK `java.text`,
 * …) to the application or domain layers.
 *
 * Concrete implementations live in driven adapters; the production
 * default is `IcuUnicodeTextService` from the `adapters:driven:text-icu`
 * module. Application tests can substitute a fake implementation.
 *
 * The port is intentionally narrow: only the operations that the
 * application actually calls today are exposed. A generic Unicode
 * facade is **not** the goal — adding helpers like
 * `normalizedEquals(left, right, mode)` should be done as small
 * application-/test-side helpers built from [normalize], not by
 * widening this contract.
 */
interface UnicodeTextService {

    /**
     * Returns [input] normalized to the requested [mode].
     *
     * @throws IllegalArgumentException if the implementation cannot
     *   normalize the given input under the requested mode (a defect,
     *   not user input — production implementations must accept all
     *   well-formed Unicode strings).
     */
    fun normalize(input: CharSequence, mode: UnicodeNormalizationMode): String

    /**
     * Returns true when [input] is already normalized under the
     * requested [mode].
     */
    fun isNormalized(input: CharSequence, mode: UnicodeNormalizationMode): Boolean

    /**
     * Returns the number of grapheme clusters (user-perceived
     * characters) in [input].
     *
     * Unlike [String.length] (UTF-16 code units) or
     * [String.codePointCount] (Unicode code points), this counts what
     * users perceive as individual characters — combining accents,
     * emoji ZWJ sequences, regional-indicator flags and similar
     * multi-codepoint clusters all count as one.
     *
     * Use only where semantic character length matters (user-facing
     * limits, diagnostic messages, column-width estimates). Technical
     * string operations (parsers, buffers, serializers) should keep
     * using [String.length].
     */
    fun graphemeCount(input: CharSequence): Int
}
