package dev.dmigrate.format.data.csv

import com.univocity.parsers.csv.CsvWriter
import com.univocity.parsers.csv.CsvWriterSettings
import dev.dmigrate.core.data.DataChunk
import dev.dmigrate.format.data.ChunkSchema
import dev.dmigrate.format.data.DataChunkWriter
import dev.dmigrate.format.data.ExportOptions
import dev.dmigrate.format.data.SerializedValue
import dev.dmigrate.format.data.ValueSerializer
import dev.dmigrate.format.data.ValueSerializationWarning
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets

/**
 * CSV-Format-Writer mit uniVocity-parsers ([CsvWriter]).
 *
 * LF-009 / LF-013:
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
    private val warningSink: ((ValueSerializationWarning) -> Unit)? = null,
) : DataChunkWriter {

    private val serializer = ValueSerializer(warningSink)
    private var csvWriter: CsvWriter? = null
    private var beginCalled: Boolean = false
    private var closed: Boolean = false
    private var columnNames: List<String> = emptyList()

    /**
     * Wir tracken pro `(table, column)`-Tupel, ob wir bereits eine
     * W201-Warnung für eine Sequence in dieser Spalte gemeldet haben —
     * LF-009 / LF-013: CSV unterstützt Arrays nicht und produziert eine
     * W201 + null. Die Warnung wird hier (nicht im ValueSerializer) erzeugt,
     * weil ValueSerializer formatübergreifend ist und nicht weiß, dass
     * der Output gerade CSV ist.
     */
    private val sequenceWarnedColumns = HashSet<String>()

    /** W203: pro `(table, column)` einmal melden, dass Text-Zellen formel-injektions-anfällig sind. */
    private val formulaWarnedColumns = HashSet<String>()

    override fun begin(table: String, schema: ChunkSchema) {
        check(!beginCalled) { "begin() called twice on the same CsvChunkWriter" }
        beginCalled = true
        // AP2 §6.3: CSV liest aus schema.columns nur die Spaltennamen.
        columnNames = schema.columns.map { it.name }

        // BOM-Bytes vor allem anderen schreiben (falls gewünscht).
        // uniVocity hat keine eingebaute BOM-Option — wir machen das selbst,
        // damit wir die Hoheit über den ersten Byte-Output behalten.
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
            csvWriter!!.writeHeaders(*columnNames.toTypedArray())
        }
    }

    override fun write(chunk: DataChunk) {
        if (chunk.rows.isEmpty()) return
        val w = checkNotNull(csvWriter) { "write() called before begin()" }
        val n = columnNames.size
        for (row in chunk.rows) {
            val rendered = arrayOfNulls<String>(n)
            for (i in 0 until n) {
                // F27: echte Spaltennamen an den Serializer übergeben, damit
                // W202-Warnings korrekt attribuiert sind.
                val serialized = serializer.serialize(chunk.table, columnNames[i], row[i])
                // F29: CSV kann java.sql.Array nicht darstellen → W201 + null.
                // Die Warnung wird hier (nicht im ValueSerializer) erzeugt, weil
                // sie format-spezifisch ist (JSON/YAML können Arrays darstellen).
                if (serialized is SerializedValue.Sequence) {
                    val key = "${chunk.table}|${columnNames[i]}"
                    if (sequenceWarnedColumns.add(key)) {
                        warningSink?.invoke(
                            ValueSerializationWarning(
                                code = "W201",
                                table = chunk.table,
                                column = columnNames[i],
                                javaClass = "java.sql.Array",
                                message = "java.sql.Array cannot be represented in CSV; column rendered as null",
                            )
                        )
                    }
                }
                rendered[i] = renderCell(serialized, chunk.table, columnNames[i])
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
        // LF-009 / LF-013:
        // `--csv-bom` schreibt das BOM, das zum ausgewaehlten `--encoding`
        // passt. Fuer Encodings ohne definiertes BOM (ISO-8859-1,
        // Windows-1252, ...) ist das Flag ein No-op — das BOM-Konzept
        // existiert dort nicht.
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

    /**
     * Formel-Injection-Schutz (CWE-1236, Audit-Follow-up #6). Nur **Text**-Zellen
     * tragen den Vektor — Zahlen/Bool sind typisiert. Ein Text-Wert, der mit einem
     * Formel-Zeichen (`=`/`+`/`-`/`@`/Tab/CR) beginnt, wird von Tabellenkalkulationen
     * als Formel ausgewertet (RFC-4180-Quoting verhindert das nicht).
     * - `csvFormulaGuard=false` (Default, treu): Wert unverändert, Spalte einmal per
     *   W203 gemeldet.
     * - `csvFormulaGuard=true` (opt-in, spreadsheet-safe): Zelle mit `'` präfixt —
     *   verändert den Wert; ebenfalls per W203 gemeldet (nicht stumm).
     */
    private fun renderCell(value: SerializedValue, table: String, column: String): String? {
        if (value !is SerializedValue.Text || !isFormulaProne(value.value)) return renderValue(value)
        warnFormula(table, column, guarded = options.csvFormulaGuard)
        return if (options.csvFormulaGuard) FORMULA_GUARD_PREFIX + value.value else value.value
    }

    private fun isFormulaProne(s: String): Boolean = s.isNotEmpty() && s[0] in FORMULA_CHARS

    private fun warnFormula(table: String, column: String, guarded: Boolean) {
        if (!formulaWarnedColumns.add("$table|$column")) return
        warningSink?.invoke(
            ValueSerializationWarning(
                code = "W203",
                table = table,
                column = column,
                javaClass = "java.lang.String",
                message = if (guarded) {
                    "column has spreadsheet-formula-prone text values (leading =/+/-/@/tab/CR); " +
                        "prefixed with ' for spreadsheet safety — the exported value differs from the source " +
                        "(round-trip is not byte-identical)"
                } else {
                    "column has spreadsheet-formula-prone text values (leading =/+/-/@/tab/CR) written verbatim; " +
                        "a spreadsheet may execute them on open — set export.csv.formula_guard (or " +
                        "--csv-formula-guard) for a spreadsheet-safe export"
                },
            )
        )
    }

    private fun renderValue(value: SerializedValue): String? = when (value) {
        SerializedValue.Null -> null  // uniVocity schreibt das als nullValue (csvNullString)
        is SerializedValue.Bool -> value.value.toString()
        is SerializedValue.Integer -> value.value.toString()
        is SerializedValue.FloatingPoint -> value.value.toString()
        is SerializedValue.PreciseInteger -> value.value.toString()
        is SerializedValue.PreciseDecimal -> value.value
        is SerializedValue.Text -> value.value
        // F29: java.sql.Array kann CSV nicht darstellen → null. Die zugehörige
        // W201-Warnung wird in [write] vor dem Render emittiert.
        is SerializedValue.Sequence -> null
    }

    private companion object {
        // Zeichen, mit denen ein Zellwert eine Tabellenkalkulations-Formel starten kann.
        private const val FORMULA_CHARS = "=+-@\t\r"
        private const val FORMULA_GUARD_PREFIX = "'"
    }
}
