package dev.dmigrate.format.data.json

import com.dslplatform.json.DslJson
import com.dslplatform.json.JsonWriter
import dev.dmigrate.core.data.ColumnDescriptor
import dev.dmigrate.core.data.DataChunk
import dev.dmigrate.format.data.DataChunkWriter
import dev.dmigrate.format.data.ExportOptions
import dev.dmigrate.format.data.SerializedValue
import dev.dmigrate.format.data.ValueSerializer
import java.io.OutputStream

/**
 * JSON-Format-Writer mit DSL-JSON (low-level [JsonWriter]).
 *
 * Plan §3.5 / §6.4.1 / §6.5 / §6.17 / §11.5:
 * - Array-of-Objects-Container: `[\n  {...},\n  {...}\n]`
 * - Empty-Table: `[]`
 * - NULL: `null`
 * - BigDecimal/BigInteger: als JSON-String (Präzisionsschutz, kein
 *   double-roundtrip)
 * - NaN/Infinity: als JSON-String mit W202-Warnung
 *
 * **Performance**: Wir verwenden DSL-JSON's [JsonWriter] direkt — keine
 * Reflection, keine `@CompiledJson`-Annotations auf unseren Datenklassen.
 * Pro Chunk wird die `JsonWriter`-buffer in den Output-Stream geflusht.
 */
class JsonChunkWriter(
    output: OutputStream,
    private val options: ExportOptions = ExportOptions(),
    private val warningSink: ((ValueSerializer.Warning) -> Unit)? = null,
) : DataChunkWriter {

    /**
     * F25: DSL-JSON schreibt rohe UTF-8-Bytes. Wenn der User ein anderes
     * Encoding verlangt (`--encoding utf-16`, `iso-8859-1` etc.), wrappen
     * wir den Ziel-Stream in einen [CharsetReencodingOutputStream], der
     * UTF-8-Bytes aus DSL-JSON in das Ziel-Encoding re-encodet.
     *
     * Für UTF-8 (Default) bleibt der Pfad direkt — kein Overhead.
     */
    private val output: OutputStream = if (options.encoding == Charsets.UTF_8) {
        output
    } else {
        CharsetReencodingOutputStream(output, options.encoding)
    }

    private val dslJson = DslJson<Any>()
    private val writer: JsonWriter = dslJson.newWriter()
    private val serializer = ValueSerializer(warningSink)

    private var columns: List<ColumnDescriptor> = emptyList()
    private var firstObject: Boolean = true
    private var closed: Boolean = false
    private var beginCalled: Boolean = false

    override fun begin(table: String, columns: List<ColumnDescriptor>) {
        check(!beginCalled) { "begin() called twice on the same JsonChunkWriter" }
        beginCalled = true
        this.columns = columns
        // Öffnender Bracket
        writer.writeByte('['.code.toByte())
    }

    override fun write(chunk: DataChunk) {
        if (chunk.rows.isEmpty()) return
        for (row in chunk.rows) {
            if (firstObject) {
                firstObject = false
                writer.writeByte('\n'.code.toByte())
                writer.writeAscii("  ")
            } else {
                writer.writeByte(','.code.toByte())
                writer.writeByte('\n'.code.toByte())
                writer.writeAscii("  ")
            }
            writeRow(chunk.table, row)
            // periodischer Flush, um Buffer-Wachstum zu begrenzen
            flushBuffer()
        }
    }

    override fun end() {
        if (!firstObject) {
            // Wir hatten Rows → schließender Newline vor `]`
            writer.writeByte('\n'.code.toByte())
        }
        writer.writeByte(']'.code.toByte())
        writer.writeByte('\n'.code.toByte())
        flushBuffer()
        output.flush()
    }

    override fun close() {
        if (closed) return
        closed = true
        // Wenn begin()/end() noch nicht gerufen wurde, schreiben wir nichts —
        // siehe DataChunkWriter-Vertrag F24. Der Output-Stream wird trotzdem
        // geschlossen.
        try { output.close() } catch (_: Throwable) {}
    }

    private fun writeRow(table: String, row: Array<Any?>) {
        writer.writeByte('{'.code.toByte())
        for (i in columns.indices) {
            if (i > 0) writer.writeByte(','.code.toByte())
            writer.writeString(columns[i].name)
            writer.writeByte(':'.code.toByte())
            writer.writeByte(' '.code.toByte())
            writeValue(serializer.serialize(table, columns[i].name, row[i]))
        }
        writer.writeByte('}'.code.toByte())
    }

    private fun writeValue(value: SerializedValue) {
        when (value) {
            SerializedValue.Null -> writer.writeNull()
            is SerializedValue.Bool -> if (value.value) writer.writeAscii("true") else writer.writeAscii("false")
            is SerializedValue.Integer -> writer.writeAscii(value.value.toString())
            is SerializedValue.FloatingPoint -> {
                if (value.value.isNaN() || value.value.isInfinite()) {
                    // NaN/Inf sind nicht gültige JSON-Numbers — als String escapen
                    writer.writeString(value.value.toString())
                } else {
                    writer.writeAscii(value.value.toString())
                }
            }
            // Präzisionsschutz: BigDecimal/BigInteger als JSON-String
            is SerializedValue.PreciseNumber -> writer.writeString(value.value)
            is SerializedValue.Text -> writer.writeString(value.value)
        }
    }

    private fun flushBuffer() {
        // DSL-JSON akkumuliert in einem internen byte[]; wir flushen periodisch
        // in den Output-Stream und resetten den Writer.
        writer.toStream(output)
        writer.reset()
    }
}
