package dev.dmigrate.format.parquet

import dev.dmigrate.format.data.ChunkSchema
import dev.dmigrate.format.data.DataChunkWriter
import dev.dmigrate.format.data.DataChunkWriterFactory
import dev.dmigrate.format.data.DataExportFormat
import dev.dmigrate.format.data.ExportOptions
import dev.dmigrate.format.data.ValueSerializationWarning
import java.io.OutputStream

/**
 * `DataChunkWriterFactory`-Variante, die ausschliesslich
 * `DataExportFormat.PARQUET` bedient (AP10 §4.2 / AP12 §5.2).
 * Symmetrisch `public` zu [dev.dmigrate.format.data.DefaultDataChunkWriterFactory];
 * das CLI-Wiring uebergibt eine Instanz an den
 * `CompositeDataChunkWriterFactory`-Adapter.
 *
 * - [warningSink] ist forward-compat; ParquetChunkWriter emittiert heute
 *   keine [ValueSerializationWarning].
 * - [extraMetaDataProvider] reicht den `d-migrate.manifest`-Footer-KV-
 *   Provider an [ParquetChunkWriter] durch. Single-File-Exports verdrahten
 *   `ParquetSingleFileManifestWriter(...).provider`, Bundle-Exports
 *   lassen den Default — die Output-Mode-Auswahl trifft das CLI-Wiring
 *   gemaess `docs/adr/0005-writerfactorybuilder-output-mode-invariant.md`.
 */
class ParquetChunkWriterFactory(
    private val warningSink: ((ValueSerializationWarning) -> Unit)? = null,
    private val extraMetaDataProvider: (ChunkSchema) -> Map<String, String> = { emptyMap() },
    /** LN-005 (R2): Parquet-Row-Group-Größe in Bytes; Default via [ParquetChunkWriter]. */
    private val rowGroupBytes: Long = ParquetChunkWriter.DEFAULT_ROW_GROUP_BYTES,
) : DataChunkWriterFactory {

    override fun create(
        format: DataExportFormat,
        output: OutputStream,
        options: ExportOptions,
    ): DataChunkWriter {
        require(format == DataExportFormat.PARQUET) {
            "ParquetChunkWriterFactory does not support format=$format"
        }
        return ParquetChunkWriter(
            output = output,
            extraMetaDataProvider = extraMetaDataProvider,
            warningSink = warningSink,
            rowGroupBytes = rowGroupBytes,
        )
    }
}
