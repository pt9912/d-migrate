package dev.dmigrate.streaming

import dev.dmigrate.format.data.ChunkSchema
import dev.dmigrate.format.data.DataChunkReader
import dev.dmigrate.format.data.DataExportFormat
import dev.dmigrate.format.data.FormatReadOptions
import dev.dmigrate.format.data.SeekableChunkSource
import dev.dmigrate.format.data.SeekableDataChunkReaderFactory

/**
 * Production-Fallback fuer Aufrufer, die [StreamingImporter] konstruieren
 * muessen, aber den seekable Pfad bewusst nicht unterstuetzen (heute:
 * MCP-Import, der noch keine Parquet-Unterstuetzung exponiert; Tests, die
 * keinen Seekable-Pfad durchlaufen).
 *
 * Wirft beim Aufruf von [create] einen [UnsupportedOperationException] mit
 * dem konfigurierten [reason]. Solange der [StreamingImporter] den
 * `is ResolvedTableInput.Seekable -> error(...)`-Stopgap aus S5a/S5b
 * beibehaelt (S7-Arbeit), wird [create] in den heutigen Pfaden nicht
 * gerufen.
 */
class UnsupportedSeekableDataChunkReaderFactory(
    private val reason: String,
) : SeekableDataChunkReaderFactory {

    override fun create(
        format: DataExportFormat,
        source: SeekableChunkSource,
        table: String,
        schema: ChunkSchema,
        chunkSize: Int,
        options: FormatReadOptions,
    ): DataChunkReader {
        throw UnsupportedOperationException(
            "SeekableDataChunkReaderFactory.create invoked but not supported: $reason"
        )
    }
}
