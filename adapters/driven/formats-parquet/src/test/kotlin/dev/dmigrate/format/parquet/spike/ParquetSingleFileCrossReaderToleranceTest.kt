package dev.dmigrate.format.parquet.spike

import dev.dmigrate.core.data.DataChunk
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.format.data.ChunkColumnSchema
import dev.dmigrate.format.data.ChunkSchema
import dev.dmigrate.format.data.SchemaOrigin
import dev.dmigrate.format.parquet.ParquetChunkWriter
import dev.dmigrate.format.parquet.manifest.ParquetSingleFileManifestWriter
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.Path as HadoopPath
import org.apache.parquet.arrow.schema.SchemaConverter
import org.apache.parquet.hadoop.ParquetFileReader
import org.apache.parquet.hadoop.util.HadoopInputFile
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * S9b Single-File-Test-Familie **4 (DuckDB-/Arrow-Single-File-KV-Toleranz)**:
 * eine produktiv geschriebene Single-File-Parquet-Datei trägt **bewusst**
 * einen `d-migrate.manifest`-Footer-KV (AP11 — die Datei ist selbst-
 * beschreibend, kein separates `manifest.yaml`). Belegt den Kontrast zur
 * Bundle-KV-Toleranz (S9a.4, dort **kein** Footer-KV): unabhängige Reader
 * (DuckDB `read_parquet`, Arrow `SchemaConverter`) müssen die Datei **trotz**
 * des custom Footer-KV normal lesen — ein Parquet-Footer-KV ist optionaler
 * Standard-Metadaten-Slot, kein Bruch für Fremd-Werkzeuge.
 *
 * DuckDB/Arrow sind `testImplementation`-only Inspektionswerkzeuge
 * (`parquet-libraries.md` §3.4/§3.5).
 */
class ParquetSingleFileCrossReaderToleranceTest : FunSpec({

    fun writeSingleFileWithFooterKv(path: Path) {
        val schema = ChunkSchema(
            table = "public.users",
            origin = SchemaOrigin.JDBC_METADATA,
            columns = listOf(ChunkColumnSchema("id", false, NeutralType.BigInteger)),
        )
        val provider = ParquetSingleFileManifestWriter(
            producerVersion = "0.9.8",
            clock = Clock.fixed(Instant.parse("2026-06-06T11:00:00Z"), ZoneOffset.UTC),
        ).provider
        Files.newOutputStream(path).use { out ->
            ParquetChunkWriter(out, extraMetaDataProvider = provider).use { w ->
                w.begin("public.users", schema)
                w.write(DataChunk(table = "public.users", columns = emptyList(), rows = listOf(arrayOf<Any?>(7L)), chunkIndex = 0L))
                w.end()
            }
        }
    }

    fun footerKeyValues(file: Path): Map<String, String> {
        val inputFile = HadoopInputFile.fromPath(HadoopPath(file.toUri()), Configuration(false))
        return ParquetFileReader.open(inputFile).use { it.fileMetaData.keyValueMetaData ?: emptyMap() }
    }

    test("Single-File trägt d-migrate-Footer-KV (Kontrast zum Bundle)") {
        val file = Files.createTempFile("s9b4-kv-", ".parquet")
        Files.deleteIfExists(file)
        try {
            writeSingleFileWithFooterKv(file)
            footerKeyValues(file).containsKey("d-migrate.manifest") shouldBe true
        } finally {
            Files.deleteIfExists(file)
        }
    }

    test("DuckDB read_parquet liest die Single-File trotz d-migrate-Footer-KV") {
        val file = Files.createTempFile("s9b4-duckdb-", ".parquet")
        Files.deleteIfExists(file)
        try {
            writeSingleFileWithFooterKv(file)
            DriverManager.getConnection("jdbc:duckdb:").use { conn ->
                conn.prepareStatement("SELECT id FROM read_parquet(?)").use { stmt ->
                    stmt.setString(1, file.toAbsolutePath().toString())
                    stmt.executeQuery().use { rs ->
                        rs.next() shouldBe true
                        rs.getLong("id") shouldBe 7L
                    }
                }
            }
        } finally {
            Files.deleteIfExists(file)
        }
    }

    test("Arrow SchemaConverter inspiziert die Single-File trotz d-migrate-Footer-KV") {
        val file = Files.createTempFile("s9b4-arrow-", ".parquet")
        Files.deleteIfExists(file)
        try {
            writeSingleFileWithFooterKv(file)
            val inputFile = HadoopInputFile.fromPath(HadoopPath(file.toUri()), Configuration(false))
            val parquetSchema = ParquetFileReader.open(inputFile).use { it.fileMetaData.schema }
            val arrowSchema = SchemaConverter().fromParquet(parquetSchema).arrowSchema
            arrowSchema.fields.map { it.name } shouldBe listOf("id")
        } finally {
            Files.deleteIfExists(file)
        }
    }
})
