package dev.dmigrate.format.parquet

import dev.dmigrate.format.data.ChunkSchema
import dev.dmigrate.format.data.DataChunkReader
import dev.dmigrate.format.data.DataExportFormat
import dev.dmigrate.format.data.FormatReadOptions
import dev.dmigrate.format.data.SeekableChunkSource
import dev.dmigrate.format.data.SeekableDataChunkReaderFactory

/**
 * Default-Implementierung des
 * [SeekableDataChunkReaderFactory]-Ports fuer Parquet
 * (AP10 §4.2 / S3 Cut A).
 *
 * Bedient ausschliesslich [DataExportFormat.PARQUET] und
 * akzeptiert heute genau eine [SeekableChunkSource]-Variante
 * ([SeekableChunkSource.Local]); kuenftige Adapter (Object-
 * Storage, gemounteter Cache) erweitern die Sealed-
 * Hierarchie im Port-Modul und brechen den `when` hier
 * bewusst (AP10 §3.2 Sweep-Punkt).
 *
 * `options.encoding` ist fuer Parquet ohne Bedeutung (binaer-
 * encoded); im CLI-Preflight (S6) wird ein gesetztes
 * `encoding` bei `--format parquet` abgelehnt.
 */
class ParquetSeekableDataChunkReaderFactory : SeekableDataChunkReaderFactory {

    override fun create(
        format: DataExportFormat,
        source: SeekableChunkSource,
        table: String,
        schema: ChunkSchema,
        chunkSize: Int,
        options: FormatReadOptions,
    ): DataChunkReader {
        require(format == DataExportFormat.PARQUET) {
            "ParquetSeekableDataChunkReaderFactory does not support format=$format"
        }
        require(chunkSize > 0) { "chunkSize must be > 0, got $chunkSize" }
        require(schema.table == table) {
            "ChunkSchema.table='${schema.table}' must match table parameter='$table'"
        }
        val path = when (source) {
            is SeekableChunkSource.Local -> source.path
        }
        return ParquetChunkReader(schema = schema, file = path, chunkSize = chunkSize)
    }
}
