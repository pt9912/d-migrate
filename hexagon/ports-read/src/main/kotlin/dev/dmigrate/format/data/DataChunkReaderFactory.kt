package dev.dmigrate.format.data

import java.io.InputStream

/**
 * Creates [DataChunkReader] instances per table from a [DataExportFormat],
 * [InputStream], and [FormatReadOptions].
 */
interface DataChunkReaderFactory {

    /**
     * Returns a new [DataChunkReader] for the given format that reads from
     * the provided [InputStream]. The reader owns the stream's lifetime
     * via [DataChunkReader.close].
     *
     * @param format Input format (JSON / YAML / CSV).
     * @param input Raw data input stream. The reader wraps it in an
     *   encoding-detecting pushback stream when `options.encoding == null`.
     * @param table Table name for debug/error messages.
     * @param chunkSize Number of rows per `nextChunk()` call. Must be > 0.
     * @param options Read-oriented options (encoding, CSV header/null-string).
     */
    fun create(
        format: DataExportFormat,
        input: InputStream,
        table: String,
        chunkSize: Int,
        options: FormatReadOptions = FormatReadOptions(),
    ): DataChunkReader
}
