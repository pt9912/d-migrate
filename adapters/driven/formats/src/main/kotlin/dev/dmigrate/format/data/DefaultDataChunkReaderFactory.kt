package dev.dmigrate.format.data

import dev.dmigrate.format.data.csv.CsvChunkReader
import dev.dmigrate.format.data.json.JsonChunkReader
import dev.dmigrate.format.data.yaml.YamlChunkReader
import java.io.InputStream

/**
 * Default-Implementierung der [DataChunkReaderFactory] mit den drei
 * Phase-B-Readern. Wird vom CLI in Phase E zentral instanziiert und
 * an den [dev.dmigrate.streaming.StreamingImporter] übergeben.
 */
class DefaultDataChunkReaderFactory : DataChunkReaderFactory {

    override fun create(
        format: DataExportFormat,
        input: InputStream,
        table: String,
        chunkSize: Int,
        options: FormatReadOptions,
    ): DataChunkReader {
        require(chunkSize > 0) { "chunkSize must be > 0, got $chunkSize" }
        return when (format) {
            DataExportFormat.JSON -> JsonChunkReader(input, table, chunkSize, options)
            DataExportFormat.YAML -> YamlChunkReader(input, table, chunkSize, options)
            DataExportFormat.CSV  -> CsvChunkReader(input, table, chunkSize, options)
        }
    }
}
