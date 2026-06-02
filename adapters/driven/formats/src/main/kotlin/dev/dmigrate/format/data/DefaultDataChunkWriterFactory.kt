package dev.dmigrate.format.data

import dev.dmigrate.format.data.csv.CsvChunkWriter
import dev.dmigrate.format.data.json.JsonChunkWriter
import dev.dmigrate.format.data.yaml.YamlChunkWriter
import java.io.OutputStream

/**
 * LF-009 / LF-013: Default-Implementierung der [DataChunkWriterFactory]
 * mit JSON-, YAML- und CSV-Writern.
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
