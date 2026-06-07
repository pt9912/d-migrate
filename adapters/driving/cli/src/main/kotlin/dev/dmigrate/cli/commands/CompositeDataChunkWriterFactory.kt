package dev.dmigrate.cli.commands

import dev.dmigrate.format.data.DataChunkWriter
import dev.dmigrate.format.data.DataChunkWriterFactory
import dev.dmigrate.format.data.DataExportFormat
import dev.dmigrate.format.data.ExportOptions
import java.io.OutputStream

/**
 * Parquet Cut A S6 (AP12 §5.2): CLI-Composite, der pro
 * [DataExportFormat] zwischen der parquet-freien
 * `DefaultDataChunkWriterFactory` und der
 * `ParquetChunkWriterFactory` entscheidet. Damit bleibt
 * `adapters:driven:formats` (und der gesamte
 * Streaming-Layer) Hadoop-/Parquet-frei; nur das
 * CLI-Modul, das ohnehin beide Adapter konsumiert, kennt
 * das Routing.
 *
 * Der Composite ist die einzige Stelle, an der Parquet-
 * und Default-Writer im gleichen `when (format)` zusammen
 * leben. Ein direkter PARQUET-Aufruf an den Default oder
 * ein nicht-PARQUET-Aufruf an die Parquet-Factory bleibt
 * Wiring-Fehler (jeweils via `error(...)`/`require(...)`).
 */
class CompositeDataChunkWriterFactory(
    private val defaultFactory: DataChunkWriterFactory,
    private val parquetFactory: DataChunkWriterFactory,
) : DataChunkWriterFactory {

    override fun create(
        format: DataExportFormat,
        output: OutputStream,
        options: ExportOptions,
    ): DataChunkWriter = when (format) {
        DataExportFormat.PARQUET -> parquetFactory.create(format, output, options)
        DataExportFormat.JSON,
        DataExportFormat.YAML,
        DataExportFormat.CSV -> defaultFactory.create(format, output, options)
    }
}
