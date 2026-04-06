package dev.dmigrate.format.data.csv

import com.univocity.parsers.csv.CsvWriter
import com.univocity.parsers.csv.CsvWriterSettings
import dev.dmigrate.core.data.ColumnDescriptor
import dev.dmigrate.core.data.DataChunk
import dev.dmigrate.format.data.DataChunkWriter
import dev.dmigrate.format.data.ExportOptions
import dev.dmigrate.format.data.SerializedValue
import dev.dmigrate.format.data.ValueSerializer
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets

/**
 * CSV-Format-Writer mit uniVocity-parsers ([CsvWriter]).
 *
 * Plan §3.5 / §6.4.1 / §6.5 / §6.6 / §6.17 / §11.5:
 * - Header-Zeile steuerbar via [ExportOptions.csvHeader] (Default: an)
 * - BOM-Bytes vor dem ersten geschriebenen Byte wenn [ExportOptions.csvBom]
 * - NULL via [ExportOptions.csvNullString] (Default leerer String)
 * - Empty-Table:
 *   - mit Header: nur die Header-Zeile, terminiert mit Linebreak
 *   - ohne Header: leere Datei (oder nur BOM-Bytes wenn `--csv-bom`)
 * - Encoding: konfigurierbar via [ExportOptions.encoding] (Default UTF-8)
 *
 * **Performance**: uniVocity-parsers gilt als die schnellste JVM-Bibliothek
 * für CSV-Schreiboperationen (~2 Mio rows/s).
 */
class CsvChunkWriter(
    private val output: OutputStream,
    private val options: ExportOptions = ExportOptions(),
    private val warningSink: ((ValueSerializer.W202) -> Unit)? = null,
) : DataChunkWriter {

    private val serializer = ValueSerializer(warningSink)
    private var csvWriter: CsvWriter? = null
    private var beginCalled: Boolean = false
    private var closed: Boolean = false
    private var columnCount: Int = 0

    override fun begin(table: String, columns: List<ColumnDescriptor>) {
        check(!beginCalled) { "begin() called twice on the same CsvChunkWriter" }
        beginCalled = true
        columnCount = columns.size

        // BOM-Bytes vor allem anderen schreiben (falls gewünscht).
        // uniVocity hat keine eingebaute BOM-Option — wir machen das selbst,
        // damit wir die Hoheit über den ersten Byte-Output behalten (siehe
        // Plan §6.6 / §10 Risikotabelle).
        if (options.csvBom) {
            writeBomBytes()
        }

        val streamWriter = OutputStreamWriter(output, options.encoding)
        val settings = CsvWriterSettings().apply {
            format.delimiter = options.csvDelimiter
            format.quote = options.csvQuote
            format.quoteEscape = options.csvQuote   // RFC 4180-konform: "" als Escape
            // uniVocity normalisiert lineSeparator beim Schreiben — wir lassen den Default ('\n').
            nullValue = options.csvNullString
            emptyValue = options.csvNullString
        }
        csvWriter = CsvWriter(streamWriter, settings)

        if (options.csvHeader) {
            csvWriter!!.writeHeaders(*columns.map { it.name }.toTypedArray())
        }
    }

    override fun write(chunk: DataChunk) {
        if (chunk.rows.isEmpty()) return
        val w = checkNotNull(csvWriter) { "write() called before begin()" }
        for (row in chunk.rows) {
            val rendered = arrayOfNulls<String>(columnCount)
            for (i in 0 until columnCount) {
                rendered[i] = renderValue(serializer.serialize(chunk.table, "col$i", row[i]))
            }
            w.writeRow(rendered)
        }
    }

    override fun end() {
        // CsvWriter.flush() schreibt den internen Buffer in den Stream
        csvWriter?.flush()
    }

    override fun close() {
        if (closed) return
        closed = true
        try {
            csvWriter?.close()  // schließt internen StreamWriter → OutputStream
        } catch (_: Throwable) {}
        // Falls begin() nie aufgerufen wurde, ist csvWriter null → wir müssen
        // den OutputStream selbst schließen, damit Resources freigegeben werden.
        if (csvWriter == null) {
            try { output.close() } catch (_: Throwable) {}
        }
    }

    private fun writeBomBytes() {
        // Standard-BOMs für die häufigsten Encodings.
        val bom: ByteArray = when (options.encoding) {
            StandardCharsets.UTF_8 -> byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
            StandardCharsets.UTF_16BE -> byteArrayOf(0xFE.toByte(), 0xFF.toByte())
            StandardCharsets.UTF_16LE -> byteArrayOf(0xFF.toByte(), 0xFE.toByte())
            else -> ByteArray(0)
        }
        if (bom.isNotEmpty()) {
            output.write(bom)
        }
    }

    private fun renderValue(value: SerializedValue): String? = when (value) {
        SerializedValue.Null -> null  // uniVocity schreibt das als nullValue (csvNullString)
        is SerializedValue.Bool -> value.value.toString()
        is SerializedValue.Integer -> value.value.toString()
        is SerializedValue.FloatingPoint -> value.value.toString()
        is SerializedValue.PreciseNumber -> value.value
        is SerializedValue.Text -> value.value
    }
}
