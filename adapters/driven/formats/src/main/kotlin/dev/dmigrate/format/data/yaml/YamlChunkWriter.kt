package dev.dmigrate.format.data.yaml

import dev.dmigrate.core.data.ColumnDescriptor
import dev.dmigrate.core.data.DataChunk
import dev.dmigrate.format.data.DataChunkWriter
import dev.dmigrate.format.data.ExportOptions
import dev.dmigrate.format.data.SerializedValue
import dev.dmigrate.format.data.ValueSerializer
import org.snakeyaml.engine.v2.api.Dump
import org.snakeyaml.engine.v2.api.DumpSettings
import org.snakeyaml.engine.v2.common.FlowStyle
import org.snakeyaml.engine.v2.common.NonPrintableStyle
import java.io.OutputStream
import java.io.OutputStreamWriter

/**
 * YAML-Format-Writer mit SnakeYAML Engine ([Dump] + [StreamDataWriter]).
 *
 * Plan §3.5 / §6.4.1 / §6.5 / §6.17 / §11.5:
 * - Sequence-of-Maps: `- col1: value\n  col2: value\n- col1: ...`
 * - Empty-Table: `[]\n` (flow-style empty sequence)
 * - NULL: `~` (kanonisch)
 * - BigDecimal/BigInteger: als YAML-String (Präzisionsschutz)
 *
 * **Performance**: SnakeYAML Engine ist die Java-Referenz-Implementierung
 * und schlägt Jacksons YAML-Modul deutlich. Wir nutzen [Dump.dumpAll]
 * pro Chunk-Iterator, sodass keine vollständige Document-Tree im Speicher
 * aufgebaut wird.
 */
class YamlChunkWriter(
    private val output: OutputStream,
    private val options: ExportOptions = ExportOptions(),
    private val warningSink: ((ValueSerializer.Warning) -> Unit)? = null,
) : DataChunkWriter {

    private val streamWriter = OutputStreamWriter(output, options.encoding)
    private val dump: Dump = run {
        val settings = DumpSettings.builder()
            .setDefaultFlowStyle(FlowStyle.BLOCK)
            .setNonPrintableStyle(NonPrintableStyle.ESCAPE)
            .build()
        Dump(settings)
    }
    private val serializer = ValueSerializer(warningSink)

    private var columns: List<ColumnDescriptor> = emptyList()
    private var rowsWritten: Long = 0L
    private var beginCalled: Boolean = false
    private var closed: Boolean = false

    override fun begin(table: String, columns: List<ColumnDescriptor>) {
        check(!beginCalled) { "begin() called twice on the same YamlChunkWriter" }
        beginCalled = true
        this.columns = columns
    }

    override fun write(chunk: DataChunk) {
        if (chunk.rows.isEmpty()) return
        // Wir bauen eine List<Map<String, Any?>> für diesen Chunk und dumpen
        // sie über Dump.dumpAll(iterator) — das schreibt jede Map als
        // separates Sequence-Element ohne den ganzen Tree im Speicher zu halten.
        val mapList = ArrayList<Map<String, Any?>>(chunk.rows.size)
        for (row in chunk.rows) {
            val map = LinkedHashMap<String, Any?>(columns.size)
            for (i in columns.indices) {
                val serialized = serializer.serialize(chunk.table, columns[i].name, row[i])
                map[columns[i].name] = toYamlValue(serialized)
            }
            mapList += map
        }
        // Wir rendern die Maps als einzelne YAML-Dokumente und konvertieren das
        // dann zu einem Sequence-of-Maps-Stream (manuell, weil dumpAll mehrere
        // top-level Documents schreibt, kein Sequence).
        for (map in mapList) {
            // Erste Zeile mit "- ", folgende mit "  "
            val rendered = dump.dumpToString(map).trimEnd()
            renderAsSequenceItem(rendered)
            rowsWritten += 1
        }
        streamWriter.flush()
    }

    override fun end() {
        if (rowsWritten == 0L) {
            // Empty-Table → flow-style leeres Array
            streamWriter.write("[]\n")
        }
        streamWriter.flush()
    }

    override fun close() {
        if (closed) return
        closed = true
        try { streamWriter.close() } catch (_: Throwable) {}
        // OutputStreamWriter.close() schließt auch den darunterliegenden
        // OutputStream — wir müssen ihn nicht separat schließen.
    }

    /**
     * Rendert eine YAML-Map als Sequence-Item:
     * ```
     * - col1: value
     *   col2: value
     * ```
     */
    private fun renderAsSequenceItem(yamlMap: String) {
        val lines = yamlMap.lines()
        for ((i, line) in lines.withIndex()) {
            if (line.isEmpty() && i == lines.lastIndex) continue
            if (i == 0) {
                streamWriter.write("- ")
                streamWriter.write(line)
            } else {
                streamWriter.write("\n  ")
                streamWriter.write(line)
            }
        }
        streamWriter.write("\n")
    }

    private fun toYamlValue(value: SerializedValue): Any? = when (value) {
        SerializedValue.Null -> null
        is SerializedValue.Bool -> value.value
        is SerializedValue.Integer -> value.value
        is SerializedValue.FloatingPoint -> {
            if (value.value.isNaN() || value.value.isInfinite()) value.value.toString() else value.value
        }
        // F30: BigInteger → YAML-Number (das raw BigInteger; SnakeYAML rendert
        // es als unquoted Number)
        is SerializedValue.PreciseInteger -> value.value
        // BigDecimal → YAML-String (Präzisionsschutz)
        is SerializedValue.PreciseDecimal -> value.value
        is SerializedValue.Text -> value.value
        // F29: java.sql.Array → YAML-Sequence (rekursiv)
        is SerializedValue.Sequence -> value.elements.map { toYamlValue(it) }
    }
}

