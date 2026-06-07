package dev.dmigrate.format.parquet

import dev.dmigrate.core.data.DataChunk
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.format.data.ChunkColumnSchema
import dev.dmigrate.format.data.ChunkSchema
import dev.dmigrate.format.data.DataExportFormat
import dev.dmigrate.format.data.ExportOptions
import dev.dmigrate.format.data.SchemaOrigin
import dev.dmigrate.format.parquet.manifest.ParquetSingleFileManifestReader
import dev.dmigrate.format.parquet.manifest.ParquetSingleFileManifestWriter
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.Path as HadoopPath
import org.apache.parquet.hadoop.ParquetFileReader
import org.apache.parquet.hadoop.util.HadoopInputFile
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * S7-0 / Review-Finding (Plan-Review-v3 Finding 2): die
 * [ParquetChunkWriterFactory] reicht den `extraMetaDataProvider`-
 * Konstruktor-Parameter verlustfrei an den [ParquetChunkWriter] durch.
 * Wir verifizieren das per echtem Footer-Round-Trip, weil der Provider
 * im Writer privat ist und nicht inspiziert werden kann.
 *
 * Bundle-Pfad-Invariante (S4 §2.2): wenn die Factory ohne Provider
 * konstruiert wurde, traegt die geschriebene Datei den
 * `d-migrate.manifest`-Schluessel NICHT.
 */
class ParquetChunkWriterFactoryFooterKvTest : FunSpec({

    val schema = ChunkSchema(
        table = "public.users",
        origin = SchemaOrigin.JDBC_METADATA,
        columns = listOf(
            ChunkColumnSchema("id", false, NeutralType.BigInteger),
            ChunkColumnSchema("name", true, NeutralType.Text(maxLength = 100)),
        ),
    )
    val fixedClock = Clock.fixed(Instant.parse("2026-06-08T12:00:00Z"), ZoneOffset.UTC)

    fun writeOneChunk(file: java.nio.file.Path, factory: ParquetChunkWriterFactory) {
        Files.newOutputStream(file).use { out ->
            factory.create(DataExportFormat.PARQUET, out, ExportOptions()).use { writer ->
                writer.begin(schema.table, schema)
                writer.write(
                    DataChunk(
                        table = schema.table,
                        columns = emptyList(),
                        rows = listOf(arrayOf<Any?>(1L, "alice")),
                        chunkIndex = 0L,
                    )
                )
                writer.end()
            }
        }
    }

    fun footerKeyValueMetadata(file: java.nio.file.Path): Map<String, String> {
        val hadoopFile = HadoopInputFile.fromPath(HadoopPath(file.toUri()), Configuration(false))
        return ParquetFileReader.open(hadoopFile).use { reader ->
            reader.fileMetaData.keyValueMetaData ?: emptyMap()
        }
    }

    test("Single-File-Modus: Factory mit Provider schreibt d-migrate.manifest in den Footer-KV") {
        val tmp = Files.createTempFile("pcwf-singlefile-", ".parquet")
        Files.deleteIfExists(tmp)
        try {
            val factory = ParquetChunkWriterFactory(
                extraMetaDataProvider = ParquetSingleFileManifestWriter(
                    producerVersion = "0.9.8",
                    clock = fixedClock,
                ).provider,
            )

            writeOneChunk(tmp, factory)

            val kv = footerKeyValueMetadata(tmp)
            kv.keys shouldBe kv.keys.also {
                // explizit pruefen, dass der Schluessel da ist
                require(ParquetSingleFileManifestWriter.FOOTER_KEY in it) {
                    "Expected ${ParquetSingleFileManifestWriter.FOOTER_KEY} in $it"
                }
            }
            val resolvedSchema = ParquetSingleFileManifestReader().readSchema(kv)
            resolvedSchema!!.table shouldBe "public.users"
            resolvedSchema.columns.map { it.name } shouldBe listOf("id", "name")
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    test("Bundle-Modus: Factory ohne Provider schreibt KEINEN d-migrate.manifest-Eintrag (S4 §2.2-Invariant)") {
        val tmp = Files.createTempFile("pcwf-bundle-", ".parquet")
        Files.deleteIfExists(tmp)
        try {
            val factory = ParquetChunkWriterFactory()

            writeOneChunk(tmp, factory)

            val kv = footerKeyValueMetadata(tmp)
            kv.keys shouldNotContain ParquetSingleFileManifestWriter.FOOTER_KEY
        } finally {
            Files.deleteIfExists(tmp)
        }
    }
})
