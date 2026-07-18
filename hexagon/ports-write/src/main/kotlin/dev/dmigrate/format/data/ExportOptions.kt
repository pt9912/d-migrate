package dev.dmigrate.format.data

import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

/**
 * Konfigurations-Optionen für die [DataChunkWriter]-Familie. Wird vom CLI
 * aus den `--csv-*` / `--encoding` / `--null-string` Flags konstruiert
 * und an die [DataChunkWriterFactory] weitergegeben.
 *
 * LF-009 / LF-013.
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
    /**
     * CSV: ob ein BOM am Dateianfang geschrieben wird.
     *
     * LF-009 / LF-013:
     * Das geschriebene BOM passt zum ausgewaehlten [encoding] — also
     * `EF BB BF` fuer UTF-8, `FE FF` fuer UTF-16 BE und `FF FE` fuer
     * UTF-16 LE. Fuer andere Encodings (z.B. ISO-8859-1, Windows-1252)
     * ist das Flag ein No-op, weil ISO-8859 kein definiertes BOM hat.
     */
    val csvBom: Boolean = false,
    /** CSV: NULL-Repräsentation (Default: leerer String). */
    val csvNullString: String = "",
    /**
     * CSV: Formel-Injection-Guard (CWE-1236). Ein Text-Zellwert aus einer
     * untrusted Quell-DB, der mit `=`/`+`/`-`/`@`/Tab/CR beginnt, wird von
     * Excel/LibreOffice als **Formel** interpretiert (RFC-4180-Quoting verhindert
     * das nicht). Default **`false`** = treuer Dump (wie `pg_dump`); der Writer
     * meldet betroffene Spalten per W203, verändert den Wert aber nicht. Auf
     * `true` (opt-in, projekt-deklarierbar) präfixt der Writer solche Zellen mit
     * `'` (spreadsheet-safe) — das **verändert** den exportierten Wert, der
     * Round-Trip ist dann nicht mehr byte-identisch.
     */
    val csvFormulaGuard: Boolean = false,
)
