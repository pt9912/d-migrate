package dev.dmigrate.format.data.csv

import com.univocity.parsers.csv.CsvParser
import com.univocity.parsers.csv.CsvParserSettings
import com.univocity.parsers.common.TextParsingException
import dev.dmigrate.core.data.ColumnDescriptor
import dev.dmigrate.core.data.DataChunk
import dev.dmigrate.core.data.ImportSchemaMismatchException
import dev.dmigrate.format.data.DataChunkReader
import dev.dmigrate.format.data.EncodingDetector
import dev.dmigrate.format.data.FormatReadOptions
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.Charset

/**
 * CSV-Format-Reader mit uniVocity-parsers ([CsvParser]).
 *
 * Plan §3.5.1 / §6.2:
 * - uniVocity `CsvParser` mit `beginParsing`/`parseNext` — nativ chunk-fähig
 * - Header-Zeile steuerbar via [FormatReadOptions.csvNoHeader]
 * - NULL-Sentinel via [FormatReadOptions.csvNullString] (Default: leerer String)
 * - Alle Werte bleiben String — Typ-Konvertierung erfolgt erst im
 *   `ValueDeserializer` anhand des JDBC-Typ-Hints (§3.5.2)
 * - Keine stille Row-Normalisierung: Feldanzahlfehler bleiben Formatfehler
 *
 * **Encoding**: Anders als JSON/YAML (deren Libraries intern UTF-8 erwarten)
 * arbeitet uniVocity über einen [java.io.Reader]. Das Charset aus
 * [EncodingDetector] wird direkt an [InputStreamReader] durchgereicht —
 * kein Transcoding-Zwischenschritt nötig.
 */
class CsvChunkReader(
    rawInput: InputStream,
    private val table: String,
    private val chunkSize: Int,
    private val options: FormatReadOptions = FormatReadOptions(),
) : DataChunkReader {

    private data class ParsedCsvRow(
        val values: Array<String?>,
        val actualFieldCount: Int,
    )

    private val resolvedInput: InputStream
    private val parser: CsvParser
    private val headerNames: List<String>?
    private val columnCount: Int
    private val delimiter: Char
    private val quote: Char
    private var pendingFirstRow: Array<Any?>? = null
    private var chunkIndex: Long = 0
    private var exhausted: Boolean
    private var closed: Boolean = false

    init {
        // 1. Encoding (§6.9)
        val (charset, stream) = resolveEncoding(rawInput, options.encoding)
        resolvedInput = stream

        // 2. Reader mit erkanntem Charset — kein Transcoding nötig
        val reader = InputStreamReader(resolvedInput, charset).buffered()

        // 3. uniVocity CsvParser
        val settings = CsvParserSettings().apply {
            setHeaderExtractionEnabled(!options.csvNoHeader)
            // M-R9: keine impliziten Parser-Heuristiken oder Feld-Reorderings.
            // Der Reader arbeitet auf der rohen Feldanzahl jeder Row und lehnt
            // Breitenabweichungen explizit ab, statt sie still zu normalisieren.
            setAutoConfigurationEnabled(false)
            setColumnReorderingEnabled(false)
        }
        delimiter = settings.format.delimiter
        quote = settings.format.quote
        parser = CsvParser(settings)
        parser.beginParsing(reader)

        // 4. Header / erste Datenzeile lesen
        // Erste Zeile lesen — bei Header-Extraktion liest uniVocity den
        // Header automatisch und liefert die erste Datenzeile bei parseNext().
        val firstRow = parseNextRow()

        if (options.csvNoHeader) {
            // Kein Header → headerColumns() = null (per Vertrag)
            if (firstRow == null) {
                headerNames = null
                columnCount = 0
                exhausted = true
            } else {
                headerNames = null
                columnCount = firstRow.actualFieldCount
                pendingFirstRow = convertHeaderlessRow(firstRow)
                exhausted = false
            }
        } else {
            // Header von uniVocity extrahiert (via context nach erstem parseNext)
            val headers = parser.context?.parsedHeaders()
            if (headers == null) {
                // Leere Datei (kein Header, keine Daten)
                headerNames = null
                columnCount = 0
                exhausted = true
            } else {
                headerNames = headers.toList()
                columnCount = headers.size
                if (firstRow == null) {
                    // Nur Header, keine Daten
                    exhausted = true
                } else {
                    pendingFirstRow = convertHeaderedRow(firstRow)
                    exhausted = false
                }
            }
        }
    }

    // ── DataChunkReader ─────────────────────────────────────────────

    override fun nextChunk(): DataChunk? {
        check(!closed) { "CsvChunkReader is already closed" }
        if (exhausted) return null

        val columns = buildColumns()
        val rows = mutableListOf<Array<Any?>>()

        // Pending first row aus dem Init
        pendingFirstRow?.let {
            rows.add(it)
            pendingFirstRow = null
        }

        // Weitere Rows aus dem Parser sammeln
        while (rows.size < chunkSize) {
            val raw = parseNextRow() ?: break
            rows.add(
                if (headerNames != null) {
                    convertHeaderedRow(raw)
                } else {
                    convertHeaderlessRow(raw)
                }
            )
        }

        if (rows.isEmpty()) {
            exhausted = true
            return null
        }

        // Prüfe ob der Parser erschöpft ist
        // (wird beim nächsten nextChunk-Aufruf via parseNext() == null erkannt)

        return DataChunk(table, columns, rows, chunkIndex++)
    }

    override fun headerColumns(): List<String>? = headerNames

    override fun close() {
        if (closed) return
        closed = true
        try {
            parser.stopParsing()
        } catch (_: Throwable) {
            // Idempotent cleanup
        }
        try {
            resolvedInput.close()
        } catch (_: Throwable) {
            // Idempotent cleanup
        }
    }

    // ── Interne Helfer ──────────────────────────────────────────────

    /** Liest die nächste Parser-Row und normalisiert Parserfehler fachlich. */
    private fun parseNextRow(): ParsedCsvRow? = try {
        val parsed = parser.parseNext() ?: return null
        val parsedContent = parser.context?.currentParsedContent()
        val actualFieldCount = parsedContent?.let(::detectActualFieldCount) ?: parsed.size
        ParsedCsvRow(parsed, actualFieldCount)
    } catch (e: TextParsingException) {
        throw invalidCsv("invalid CSV input", e)
    }

    /**
     * Konvertiert eine headered uniVocity-Rohzeile (`String?[]`) in ein
     * `Array<Any?>` und erzwingt M-R9.
     */
    private fun convertHeaderedRow(raw: ParsedCsvRow): Array<Any?> {
        if (raw.actualFieldCount != columnCount) {
            throw ImportSchemaMismatchException(
                "Table '$table': headered CSV row has ${raw.actualFieldCount} columns, header defined $columnCount",
            )
        }
        return raw.values.map(::normalizeValue).toTypedArray()
    }

    /**
     * Konvertiert eine headerlose uniVocity-Rohzeile (`String?[]`) in ein
     * `Array<Any?>`. Die erste Row definiert die rohe file-breite; spätere
     * Rows dürfen davon nicht abweichen, damit keine Breitenfehler verdeckt
     * werden, bevor der Importer gegen das Zielschema prüft.
     */
    private fun convertHeaderlessRow(raw: ParsedCsvRow): Array<Any?> {
        if (columnCount != 0 && raw.actualFieldCount != columnCount) {
            throw ImportSchemaMismatchException(
                "Table '$table': headerless CSV row has ${raw.actualFieldCount} columns, first row defined $columnCount",
            )
        }
        return raw.values.take(raw.actualFieldCount).map(::normalizeValue).toTypedArray()
    }

    private fun normalizeValue(value: String?): Any? {
        val csvNull = options.csvNullString
        return if (value == null || value == csvNull) null else value
    }

    /** Baut die Spalten-Deskriptoren für den DataChunk. */
    private fun buildColumns(): List<ColumnDescriptor> {
        return if (headerNames != null) {
            headerNames.map { ColumnDescriptor(it, nullable = true) }
        } else {
            emptyList()
        }
    }

    private fun invalidCsv(message: String, cause: Throwable): ImportSchemaMismatchException =
        ImportSchemaMismatchException("Table '$table': $message", cause)

    private fun detectActualFieldCount(parsedContent: String): Int {
        if (parsedContent.isEmpty()) return 1

        var fields = 1
        var inQuotes = false
        var index = 0
        while (index < parsedContent.length) {
            when (val ch = parsedContent[index]) {
                quote -> {
                    if (inQuotes && index + 1 < parsedContent.length && parsedContent[index + 1] == quote) {
                        index++
                    } else {
                        inQuotes = !inQuotes
                    }
                }
                delimiter -> if (!inQuotes) {
                    fields++
                }
            }
            index++
        }
        return fields
    }

    companion object {
        private fun resolveEncoding(
            raw: InputStream,
            explicit: Charset?,
        ): EncodingDetector.Detected {
            return if (explicit == null) {
                EncodingDetector.detectOrFallback(raw)
            } else {
                EncodingDetector.Detected(explicit, EncodingDetector.wrapWithExplicit(raw, explicit))
            }
        }
    }
}
