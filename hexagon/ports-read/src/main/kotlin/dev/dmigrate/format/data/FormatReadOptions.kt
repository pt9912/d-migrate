package dev.dmigrate.format.data

import java.nio.charset.Charset

/**
 * Read-oriented options for [DataChunkReaderFactory] and format readers.
 *
 * Carries only fields relevant to parsing input files (encoding, CSV
 * specifics). Writer/import-oriented fields (trigger mode, truncate,
 * on-conflict, etc.) live in [dev.dmigrate.driver.data.ImportOptions].
 */
data class FormatReadOptions(
    /**
     * Input encoding. `null` = auto-detect via BOM sniffing (UTF-8 / UTF-16).
     * For non-UTF encodings (ISO-8859-1, Windows-1252, ...) the user must
     * set this explicitly.
     */
    val encoding: Charset? = null,

    /**
     * CSV: when `true`, the input has no header row — values are matched
     * positionally to target columns. Default `false`.
     */
    val csvNoHeader: Boolean = false,

    /**
     * CSV: sentinel string interpreted as SQL NULL. Default is empty string,
     * symmetric to the export default.
     */
    val csvNullString: String = "",
)
