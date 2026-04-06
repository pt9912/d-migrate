package dev.dmigrate.format.data

import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

/**
 * Konfigurations-Optionen für die [DataChunkWriter]-Familie. Wird vom CLI
 * aus den `--csv-*` / `--encoding` / `--null-string` Flags konstruiert
 * und an die [DataChunkWriterFactory] weitergegeben.
 *
 * Plan §3.5 / §6.5 / §6.6 / §6.17.
 */
data class ExportOptions(
    /** Output-Encoding für alle Formate. Default UTF-8. */
    val encoding: Charset = StandardCharsets.UTF_8,
    /** CSV: ob die Header-Zeile geschrieben wird. Default `true` (siehe §6.17 + F9). */
    val csvHeader: Boolean = true,
    /** CSV: Spalten-Trennzeichen. */
    val csvDelimiter: Char = ',',
    /** CSV: Quoting-Zeichen. */
    val csvQuote: Char = '"',
    /** CSV: ob ein BOM am Dateianfang geschrieben wird. */
    val csvBom: Boolean = false,
    /** CSV: NULL-Repräsentation (Default: leerer String). */
    val csvNullString: String = "",
)
