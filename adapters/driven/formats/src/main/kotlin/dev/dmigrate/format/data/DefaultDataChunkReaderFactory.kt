package dev.dmigrate.format.data

import dev.dmigrate.format.data.csv.CsvChunkReader
import dev.dmigrate.format.data.json.JsonChunkReader
import dev.dmigrate.format.data.yaml.YamlChunkReader
import java.io.InputStream

/**
 * LF-010 / LF-013: Default-Implementierung der [DataChunkReaderFactory]
 * mit JSON-, YAML- und CSV-Readern.
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
            // S3 Contract-Branch (Parquet Cut A): dauerhafte Domain-
            // Aussage, kein Stopgap. Parquet liest seekbar ueber
            // SeekableDataChunkReaderFactory in
            // adapters:driven:formats-parquet — der DefaultData…Factory
            // bleibt Hadoop-/Parquet-frei (AP12 §5.2). Der CLI-Pfad
            // (S6) reicht Parquet an die separate Factory durch; ein
            // hier ankommender PARQUET-Aufruf signalisiert ein
            // Wiring-Fehler.
            DataExportFormat.PARQUET -> error(
                "DefaultDataChunkReaderFactory does not support Parquet; " +
                    "Parquet reads go through StreamingImporter's " +
                    "seekableReaderFactory (ParquetSeekableDataChunkReaderFactory)"
            )
        }
    }
}
