package dev.dmigrate.format.parquet

import dev.dmigrate.core.data.DataChunk
import dev.dmigrate.format.data.ChunkSchema
import dev.dmigrate.format.data.DataChunkWriter
import org.apache.hadoop.conf.Configuration
import org.apache.parquet.example.data.simple.SimpleGroupFactory
import org.apache.parquet.hadoop.ParquetWriter
import org.apache.parquet.hadoop.example.ExampleParquetWriter
import org.apache.parquet.hadoop.example.GroupWriteSupport
import org.apache.parquet.hadoop.metadata.CompressionCodecName
import java.io.OutputStream

/**
 * Produktiver [DataChunkWriter] fuer Parquet (S3 Cut A).
 *
 * Folgt der AP3-Spike-Linie (parquet-java 1.17.1 + GZIP-Codec
 * ohne SNAPPY/ZSTD-Native-Libs,
 * `docs/planning/done/parquet-libraries.md` §5/§7) und erweitert
 * den Spike um:
 *
 * - ChunkSchema-getriebenes Schema (AP2 §6.1) — der Writer ruft
 *   in [begin] [ChunkSchemaToParquetMessageType.convert] und
 *   uebergibt das resultierende `MessageType` an
 *   `ExampleParquetWriter.Builder.withType`.
 * - `OutputStream`-basierten Pfad ohne Hadoop-`Path` ueber
 *   [OutputStreamOutputFile] (AP10 §3.4 / AP12 §5.2). Stdout-/
 *   Stream-Targets sind damit grundsaetzlich offen; CLI-Wiring
 *   (S6) entscheidet, was als CLI-Default zulaessig ist.
 * - Streaming per Chunk: jeder [write]-Aufruf schreibt seine
 *   Rows als einzelne Parquet-`Group`-Records direkt; Row-Group-
 *   Akkumulation uebernimmt `ParquetWriter` selbst.
 *
 * `begin` darf nur einmal aufgerufen werden (per
 * [DataChunkWriter]-Vertrag). `close` ist idempotent und
 * schliesst den unterliegenden Stream **nicht** —
 * Stream-Lifetime gehoert dem Aufrufer (CLI/StreamingExporter).
 */
class ParquetChunkWriter(
    private val output: OutputStream,
    /**
     * S4 Cut A (AP11 §6.1): optionaler Hook, der beim
     * [begin]-Aufruf die `extraMetaData`-Map fuer den Parquet-
     * Footer liefert. Single-File-Pfade verdrahten hier den
     * `d-migrate.manifest`-Schluessel ueber
     * [dev.dmigrate.format.parquet.manifest.ParquetSingleFileManifestWriter];
     * Bundle-Pfade lassen den Default (`{ emptyMap() }`)
     * stehen, weil ihr Manifest extern in `manifest.yaml`
     * lebt.
     */
    private val extraMetaDataProvider: (ChunkSchema) -> Map<String, String> = { emptyMap() },
) : DataChunkWriter {

    private var beginCalled: Boolean = false
    private var closed: Boolean = false
    private var schema: ChunkSchema? = null
    private var writer: ParquetWriter<org.apache.parquet.example.data.Group>? = null
    private var groupFactory: SimpleGroupFactory? = null

    override fun begin(table: String, schema: ChunkSchema) {
        check(!beginCalled) { "begin() called twice on the same ParquetChunkWriter" }
        beginCalled = true
        this.schema = schema
        val messageType = ChunkSchemaToParquetMessageType.convert(schema)
        val configuration = Configuration(false)
        GroupWriteSupport.setSchema(messageType, configuration)
        this.groupFactory = SimpleGroupFactory(messageType)
        val extraMetaData = extraMetaDataProvider(schema)
        val builder = ExampleParquetWriter.builder(OutputStreamOutputFile(output))
            .withConf(configuration)
            .withCompressionCodec(CompressionCodecName.GZIP)
            .withType(messageType)
        if (extraMetaData.isNotEmpty()) {
            builder.withExtraMetaData(extraMetaData)
        }
        this.writer = builder.build()
    }

    override fun write(chunk: DataChunk) {
        check(beginCalled) { "write() called before begin()" }
        if (chunk.rows.isEmpty()) return
        val activeWriter = writer ?: error("ParquetChunkWriter has no active writer")
        val factory = groupFactory ?: error("ParquetChunkWriter has no group factory")
        val activeSchema = schema ?: error("ParquetChunkWriter has no schema")
        val columns = activeSchema.columns
        for (row in chunk.rows) {
            val group = factory.newGroup()
            for ((index, columnSchema) in columns.withIndex()) {
                val value = row.getOrNull(index)
                ParquetGroupValueWriter.writeColumn(
                    group = group,
                    columnName = columnSchema.name,
                    neutralType = columnSchema.neutralType,
                    value = value,
                )
            }
            activeWriter.write(group)
        }
    }

    override fun end() {
        // ParquetWriter selbst schreibt den Footer beim close.
        // end() bleibt fuer die DataChunkWriter-Lifecycle-Spiegelung
        // ein No-Op, weil JSON/YAML/CSV hier ihren Container-End
        // emittieren — Parquet hat keinen aequivalenten Bracket.
    }

    override fun close() {
        if (closed) return
        closed = true
        try {
            writer?.close()
        } finally {
            writer = null
            groupFactory = null
            schema = null
        }
    }
}
