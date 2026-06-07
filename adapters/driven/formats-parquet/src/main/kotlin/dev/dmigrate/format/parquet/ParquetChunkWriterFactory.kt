package dev.dmigrate.format.parquet

import dev.dmigrate.format.data.DataChunkWriter
import dev.dmigrate.format.data.DataChunkWriterFactory
import dev.dmigrate.format.data.DataExportFormat
import dev.dmigrate.format.data.ExportOptions
import dev.dmigrate.format.data.ValueSerializer
import java.io.OutputStream

/**
 * `DataChunkWriterFactory`-Variante, die ausschliesslich
 * `DataExportFormat.PARQUET` bedient (AP10 §4.2 / AP12 §5.2).
 *
 * Bewusst `public`, parallel zur Sichtbarkeit von
 * [dev.dmigrate.format.data.DefaultDataChunkWriterFactory]: das
 * CLI-Wiring (S6) instanziiert die Factory direkt und uebergibt
 * sie an den `CompositeDataChunkWriterFactory`-Adapter. Ein
 * `internal`-Sichtbarkeitsmodell wuerde einen zusaetzlichen
 * Provider erzwingen, ohne semantischen Gewinn.
 *
 * Review-Finding G1: `warningSink` ist symmetrisch zu
 * [dev.dmigrate.format.data.DefaultDataChunkWriterFactory] verfuegbar,
 * auch wenn ParquetChunkWriter heute (S6) keine
 * [ValueSerializer.Warning] emittiert. Damit ist der Sink-Pfad fuer
 * eine spaetere Parquet-Type-Coercion-Warnung schon vorbereitet —
 * das CLI-Composite leitet Default- und Parquet-Warnings dann ohne
 * weitere API-Aenderung in dieselbe `collectWarnings`-Liste.
 */
class ParquetChunkWriterFactory(
    @Suppress("UnusedPrivateMember")
    private val warningSink: ((ValueSerializer.Warning) -> Unit)? = null,
) : DataChunkWriterFactory {

    override fun create(
        format: DataExportFormat,
        output: OutputStream,
        options: ExportOptions,
    ): DataChunkWriter {
        require(format == DataExportFormat.PARQUET) {
            "ParquetChunkWriterFactory does not support format=$format"
        }
        return ParquetChunkWriter(output)
    }
}
