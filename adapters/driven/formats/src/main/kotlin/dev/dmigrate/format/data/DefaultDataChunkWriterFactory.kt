package dev.dmigrate.format.data

import dev.dmigrate.format.data.csv.CsvChunkWriter
import dev.dmigrate.format.data.json.JsonChunkWriter
import dev.dmigrate.format.data.yaml.YamlChunkWriter
import java.io.OutputStream

/**
 * Default-Implementierung der [DataChunkWriterFactory] mit den drei
 * Phase-D-Writern. Wird vom CLI in Phase E zentral instanziiert und an
 * den [dev.dmigrate.streaming.StreamingExporter] übergeben.
 *
 * Optional kann ein [warningSink] übergeben werden, der pro
 * [ValueSerializer.Warning] aufgerufen wird (für Export-Reports).
 */
class DefaultDataChunkWriterFactory(
    private val warningSink: ((ValueSerializer.Warning) -> Unit)? = null,
) : DataChunkWriterFactory {

    override fun create(
        format: DataExportFormat,
        output: OutputStream,
        options: ExportOptions,
    ): DataChunkWriter = when (format) {
        DataExportFormat.JSON -> JsonChunkWriter(output, options, warningSink)
        DataExportFormat.YAML -> YamlChunkWriter(output, options, warningSink)
        DataExportFormat.CSV -> CsvChunkWriter(output, options, warningSink)
    }
}
